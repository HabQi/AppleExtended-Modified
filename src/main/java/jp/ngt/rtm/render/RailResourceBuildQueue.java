/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Staged client-side construction for static rail resources.
 */
package jp.ngt.rtm.render;

import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.GLObject;
import jp.ngt.rtm.rail.RenderLargeRail;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Captures on the render thread, builds on workers, and uploads one VBO per frame. */
@SideOnly(Side.CLIENT)
public final class RailResourceBuildQueue {
    public static final RailResourceBuildQueue INSTANCE = new RailResourceBuildQueue();

    private static final int MAX_PENDING = 256;
    private static final int MAX_WORKER_PENDING = 64;
    private static final long MAX_COMPLETED_BYTES = 64L * 1024L * 1024L;
    private static final long MAIN_THREAD_BUDGET_NANOS = 2_000_000L;
    private static final long INITIAL_RETRY_DELAY_NANOS = 500_000_000L;
    private static final long MAX_RETRY_DELAY_NANOS = 5_000_000_000L;

    private final Queue<Task> tasks = new PriorityQueue<>();
    private final Set<TaskKey> pending = new HashSet<>();
    private final Map<TaskKey, SlotRecord> slots = new HashMap<>();
    private final Map<TaskKey, Long> retryAfter = new HashMap<>();
    private final Map<TaskKey, Integer> failureCounts = new HashMap<>();
    private final Queue<BuildResult> completed = new PriorityQueue<>();
    private final AtomicLong completedBytes = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final ThreadPoolExecutor workers;
    private long sequence;

    private RailResourceBuildQueue() {
        AtomicInteger threadId = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "RTM-RailMesh-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_WORKER_PENDING), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void enqueueRail(TileEntityLargeRailCore rail, RailPartsRendererBase renderer, int slot) {
        if (!this.canQueue(rail) || renderer == null || slot < 0 || renderer.isResourceReady(rail, slot)) {
            return;
        }
        TaskKey key = new TaskKey(rail, Kind.RAIL, slot);
        long generation = rail.getRailRenderGeneration();
        SlotRecord record = this.slots.get(key);
        boolean fallback = record != null && record.generation == generation
                && record.renderer == renderer && record.state == SlotState.FALLBACK;
        if (record != null && record.generation == generation && record.renderer == renderer
                && record.state != SlotState.FALLBACK && record.state != SlotState.EMPTY) {
            return;
        }
        rail.beginRailRenderBuild(generation);
        boolean queued = this.enqueue(new Task(Kind.RAIL, rail, renderer, renderer.getModelObject(),
                rail.getWorld(), slot, generation, fallback, !renderer.hasValidResource(rail, slot),
                this.distanceSq(rail), ++this.sequence));
        if (queued && !fallback) {
            this.slots.put(key, new SlotRecord(generation, renderer, SlotState.SNAPSHOT));
        }
    }

    public synchronized void enqueueBlock(TileEntityLargeRailCore rail) {
        if (!this.canQueue(rail)) {
            return;
        }
        this.enqueue(new Task(Kind.BLOCK, rail, null, null, rail.getWorld(), -1, 0L, true,
                !GLHelper.isValid(rail.railBlocks), this.distanceSq(rail), ++this.sequence));
    }

    private boolean enqueue(Task task) {
        TaskKey key = task.key();
        Long retryTime = this.retryAfter.get(key);
        if ((retryTime != null && retryTime > System.nanoTime()) || !this.pending.add(key)) {
            return false;
        }
        if (this.tasks.size() >= MAX_PENDING) {
            this.pending.remove(key);
            return false;
        }
        this.tasks.offer(task);
        return true;
    }

    /** Processes one completed upload and captures at most one new resource. */
    public void processFrame() {
        long frameWorkStartedAt = System.nanoTime();
        boolean uploaded = this.uploadCompleted();
        if (System.nanoTime() - frameWorkStartedAt >= MAIN_THREAD_BUDGET_NANOS) {
            return;
        }

        Task task;
        synchronized (this) {
            Task next = this.tasks.peek();
            if (uploaded && next != null && (next.kind == Kind.BLOCK || next.forceLegacy)) {
                return;
            }
            task = this.tasks.poll();
            if (task != null) {
                this.pending.remove(task.key());
            }
        }
        if (task == null) {
            return;
        }
        if (!this.isCurrent(task)) {
            this.discardTask(task, true);
            return;
        }
        if (task.kind == Kind.BLOCK || task.forceLegacy) {
            this.buildLegacy(task);
            return;
        }
        RailBuildProfiler.FallbackReason fallbackReason = this.getAsyncFallbackReason(task);
        if (fallbackReason != null) {
            if (uploaded) {
                synchronized (this) {
                    this.enqueue(task);
                }
                return;
            }
            RailBuildProfiler.recordAsyncFallback(fallbackReason);
            synchronized (this) {
                this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.FALLBACK));
            }
            this.buildLegacy(task.asFallback(task.sequence));
            return;
        }
        if (this.workers.getQueue().remainingCapacity() == 0) {
            synchronized (this) {
                this.enqueue(task.withSequence(++this.sequence));
            }
            return;
        }
        if (System.nanoTime() - frameWorkStartedAt >= MAIN_THREAD_BUDGET_NANOS) {
            synchronized (this) {
                this.enqueue(task);
            }
            return;
        }

        long snapshotStartedAt = System.nanoTime();
        RailMeshSnapshot snapshot;
        try {
            snapshot = task.renderer.captureMeshSnapshot(task.rail, task.slot);
        } catch (Throwable throwable) {
            RailBuildProfiler.recordAsyncFallback(RailBuildProfiler.FallbackReason.SNAPSHOT_FAILURE);
            NGTLog.debug("[RailBuildQueue] snapshot failed at %s: %s", task.rail.getPos(), throwable.toString());
            this.markFallbackAndRequeue(task);
            return;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_SNAPSHOT,
                    System.nanoTime() - snapshotStartedAt);
        }
        if (snapshot == null) {
            this.scheduleRetry(task);
            return;
        }

        TaskKey key = task.key();
        synchronized (this) {
            this.slots.put(key, new SlotRecord(task.generation, task.renderer, SlotState.BUILDING));
        }
        long capturedEpoch = this.epoch.get();
        try {
            this.workers.execute(new WorkerJob(task, snapshot, capturedEpoch));
        } catch (RejectedExecutionException rejected) {
            synchronized (this) {
                this.slots.put(key, new SlotRecord(task.generation, task.renderer, SlotState.SNAPSHOT));
                this.enqueue(task.withSequence(++this.sequence));
            }
        }
    }

    private void buildOnWorker(Task task, RailMeshSnapshot snapshot, long capturedEpoch) {
        long startedAt = System.nanoTime();
        RailMeshData mesh = null;
        Throwable failure = null;
        try {
            mesh = snapshot.build();
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_WORKER_MESH,
                    System.nanoTime() - startedAt);
        }
        this.publishCompleted(task, mesh, failure, capturedEpoch);
    }

    private synchronized void publishCompleted(Task task, RailMeshData mesh,
                                               Throwable failure, long capturedEpoch) {
        SlotRecord record = this.slots.get(task.key());
        if (capturedEpoch != this.epoch.get() || record == null
                || record.generation != task.generation || record.renderer != task.renderer
                || record.state != SlotState.BUILDING) {
            RailBuildProfiler.recordStaleDiscard();
            return;
        }
        while (mesh != null && this.completedBytes.get() + mesh.byteSize > MAX_COMPLETED_BYTES) {
            BuildResult worst = this.findWorstCompletedMesh();
            if (mesh.byteSize > MAX_COMPLETED_BYTES || worst == null
                    || task.compareTo(worst.task) >= 0) {
                failure = new IllegalStateException("Completed rail mesh budget exceeded");
                mesh = null;
                break;
            }
            this.completed.remove(worst);
            this.completedBytes.addAndGet(-worst.mesh.byteSize);
            this.completed.offer(worst.withFailure(new IllegalStateException(
                    "Completed rail mesh displaced by a higher-priority task")));
        }
        if (mesh != null) {
            long bytes = this.completedBytes.addAndGet(mesh.byteSize);
            RailBuildProfiler.recordCompletedQueueBytes(bytes);
            this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.READY_CPU));
        }
        this.completed.offer(new BuildResult(task, mesh, failure, capturedEpoch));
    }

    private BuildResult findWorstCompletedMesh() {
        BuildResult worst = null;
        for (BuildResult result : this.completed) {
            if (result.mesh != null && (worst == null || result.compareTo(worst) > 0)) {
                worst = result;
            }
        }
        return worst;
    }

    private boolean uploadCompleted() {
        BuildResult result;
        synchronized (this) {
            result = this.completed.poll();
            if (result != null && result.mesh != null) {
                this.completedBytes.addAndGet(-result.mesh.byteSize);
            }
        }
        if (result == null) {
            return false;
        }
        Task task = result.task;
        if (result.epoch != this.epoch.get() || !this.isCurrent(task)) {
            RailBuildProfiler.recordStaleDiscard();
            this.discardTask(task, true);
            return false;
        }
        if (result.failure != null || result.mesh == null) {
            RailBuildProfiler.recordAsyncFallback(RailBuildProfiler.FallbackReason.WORKER_FAILURE);
            NGTLog.debug("[RailBuildQueue] worker build failed at %s: %s", task.rail.getPos(),
                    result.failure == null ? "empty mesh" : result.failure.toString());
            this.markFallbackAndRequeue(task);
            return false;
        }

        synchronized (this) {
            this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.READY_CPU));
        }
        long uploadStartedAt = System.nanoTime();
        RailVertexBuffer newBuffer;
        try {
            newBuffer = RailVertexBuffer.upload(result.mesh, task.generation,
                    task.modelIdentity, task.slot);
        } catch (Throwable throwable) {
            RailBuildProfiler.recordAsyncFallback(RailBuildProfiler.FallbackReason.UPLOAD_FAILURE);
            NGTLog.debug("[RailBuildQueue] VBO upload failed at %s: %s", task.rail.getPos(), throwable.toString());
            this.markFallbackAndRequeue(task);
            return true;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_GPU_UPLOAD,
                    System.nanoTime() - uploadStartedAt);
        }
        if (!this.isCurrent(task)) {
            GLHelper.deleteGLList(newBuffer);
            RailBuildProfiler.recordStaleDiscard();
            this.discardTask(task, true);
            return true;
        }

        this.ensureSlotArray(task.rail);
        GLObject oldResource = task.rail.glLists[task.slot];
        task.rail.glLists[task.slot] = newBuffer;
        GLHelper.deleteGLList(oldResource);
        task.rail.initializeBrightnessBaseline();
        synchronized (this) {
            this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.READY_GPU));
            this.clearFailure(task.key());
        }
        this.updateRailReadyFlag(task.rail, task.generation);
        RailBuildProfiler.recordTask(true, true, System.nanoTime() - uploadStartedAt);
        return true;
    }

    private void buildLegacy(Task task) {
        long startedAt = System.nanoTime();
        boolean success = false;
        try {
            if (task.kind == Kind.BLOCK) {
                success = RenderLargeRail.BLOCK_RENDERER.buildResource(task.rail);
            } else if (task.renderer.isCurrentRenderer(task.rail, task.slot)) {
                success = task.renderer.buildResource(task.rail, task.slot);
            }
        } catch (Throwable throwable) {
            NGTLog.debug("[RailBuildQueue] legacy build failed at %s: %s", task.rail.getPos(), throwable.toString());
        } finally {
            RailBuildProfiler.recordTask(task.kind == Kind.RAIL, success, System.nanoTime() - startedAt);
        }
        synchronized (this) {
            if (success) {
                this.clearFailure(task.key());
                if (task.kind == Kind.RAIL) {
                    this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.READY_GPU));
                }
            } else {
                this.recordFailure(task.key());
                if (task.kind == Kind.RAIL) {
                    this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.FALLBACK));
                }
            }
        }
        if (success && task.kind == Kind.RAIL) {
            this.updateRailReadyFlag(task.rail, task.generation);
        }
    }

    private RailBuildProfiler.FallbackReason getAsyncFallbackReason(Task task) {
        try {
            if (!task.renderer.supportsAsyncStaticMesh()) {
                return RailBuildProfiler.FallbackReason.RENDERER_UNSUPPORTED;
            }
        } catch (Throwable ignored) {
            return RailBuildProfiler.FallbackReason.RENDERER_UNSUPPORTED;
        }
        try {
            return OpenGlHelper.useVbo() ? null : RailBuildProfiler.FallbackReason.VBO_UNAVAILABLE;
        } catch (Throwable ignored) {
            return RailBuildProfiler.FallbackReason.VBO_UNAVAILABLE;
        }
    }

    private boolean isCurrent(Task task) {
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.world;
        if (!this.canQueue(task.rail) || world == null || task.rail.getWorld() != world) {
            return false;
        }
        return task.rail.getWorld() == task.worldIdentity
                && (task.kind != Kind.RAIL || task.rail.getRailRenderGeneration() == task.generation
                && task.renderer != null && task.renderer.isCurrentRenderer(task.rail, task.slot)
                && task.renderer.getModelObject() == task.modelIdentity);
    }

    private void updateRailReadyFlag(TileEntityLargeRailCore rail, long generation) {
        if (rail.getRailRenderGeneration() != generation) {
            return;
        }
        int expected = rail.subRails.size() + 1;
        if (rail.glLists == null || rail.glLists.length != expected) {
            rail.shouldRerenderRail = true;
            return;
        }
        for (int slot = 0; slot < expected; ++slot) {
            SlotRecord record;
            synchronized (this) {
                record = this.slots.get(new TaskKey(rail, Kind.RAIL, slot));
            }
            if (record == null || record.generation != generation || record.state != SlotState.READY_GPU
                    || !GLHelper.isValid(rail.glLists[slot])) {
                rail.shouldRerenderRail = true;
                return;
            }
        }
        rail.finishRailRenderBuild(generation);
    }

    private void ensureSlotArray(TileEntityLargeRailCore rail) {
        int expected = rail.subRails.size() + 1;
        if (rail.glLists == null) {
            rail.glLists = new GLObject[expected];
        } else if (rail.glLists.length != expected) {
            GLObject[] old = rail.glLists;
            rail.glLists = new GLObject[expected];
            int copy = Math.min(old.length, expected);
            System.arraycopy(old, 0, rail.glLists, 0, copy);
            for (int i = copy; i < old.length; ++i) {
                GLHelper.deleteGLList(old[i]);
            }
        }
    }

    private void markFallbackAndRequeue(Task task) {
        synchronized (this) {
            this.slots.put(task.key(), new SlotRecord(task.generation, task.renderer, SlotState.FALLBACK));
            this.enqueue(task.asFallback(++this.sequence));
        }
    }

    private void scheduleRetry(Task task) {
        synchronized (this) {
            this.recordFailure(task.key());
            this.slots.remove(task.key());
        }
    }

    private void discardTask(Task task, boolean clearSlot) {
        synchronized (this) {
            if (clearSlot) {
                SlotRecord record = this.slots.get(task.key());
                if (record != null && record.generation == task.generation
                        && record.renderer == task.renderer) {
                    this.slots.remove(task.key());
                }
            }
        }
    }

    private void recordFailure(TaskKey key) {
        int failures = this.failureCounts.getOrDefault(key, 0) + 1;
        this.failureCounts.put(key, failures);
        this.retryAfter.put(key, System.nanoTime() + retryDelayNanos(failures));
    }

    private void clearFailure(TaskKey key) {
        this.retryAfter.remove(key);
        this.failureCounts.remove(key);
    }

    public synchronized void clear() {
        this.epoch.incrementAndGet();
        this.tasks.clear();
        this.pending.clear();
        this.slots.clear();
        this.retryAfter.clear();
        this.failureCounts.clear();
        this.workers.getQueue().clear();
        BuildResult result;
        while ((result = this.completed.poll()) != null) {
            if (result.mesh != null) {
                this.completedBytes.addAndGet(-result.mesh.byteSize);
            }
        }
        this.completedBytes.set(0L);
        RailBuildProfiler.reset();
    }

    public synchronized int getPendingCount() {
        return this.tasks.size() + this.workers.getQueue().size() + this.workers.getActiveCount()
                + this.completed.size();
    }

    public synchronized long getCompletedBytes() {
        return Math.max(0L, this.completedBytes.get());
    }

    public synchronized void cancel(TileEntityLargeRailCore rail) {
        if (rail == null) {
            return;
        }
        Iterator<Task> taskIterator = this.tasks.iterator();
        while (taskIterator.hasNext()) {
            Task task = taskIterator.next();
            if (task.rail == rail) {
                this.pending.remove(task.key());
                taskIterator.remove();
            }
        }
        this.slots.keySet().removeIf(key -> key.rail == rail);
        this.retryAfter.keySet().removeIf(key -> key.rail == rail);
        this.failureCounts.keySet().removeIf(key -> key.rail == rail);
        this.workers.getQueue().removeIf(runnable -> runnable instanceof WorkerJob
                && ((WorkerJob) runnable).task.rail == rail);
        Iterator<BuildResult> resultIterator = this.completed.iterator();
        while (resultIterator.hasNext()) {
            BuildResult result = resultIterator.next();
            if (result.task.rail == rail) {
                resultIterator.remove();
                if (result.mesh != null) {
                    this.completedBytes.addAndGet(-result.mesh.byteSize);
                }
            }
        }
    }

    private double distanceSq(TileEntityLargeRailCore rail) {
        Entity viewer = Minecraft.getMinecraft().getRenderViewEntity();
        return viewer == null ? 0.0D : viewer.getDistanceSq(rail.getPos());
    }

    private boolean canQueue(TileEntityLargeRailCore rail) {
        return rail != null && !rail.isInvalid() && rail.isLoaded()
                && rail.areAllRailEndpointChunksReadyToRender();
    }

    private static long retryDelayNanos(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), 3);
        return Math.min(INITIAL_RETRY_DELAY_NANOS << shift, MAX_RETRY_DELAY_NANOS);
    }

    private enum Kind { BLOCK, RAIL }

    private enum SlotState { EMPTY, SNAPSHOT, BUILDING, READY_CPU, READY_GPU, FALLBACK }

    private static final class SlotRecord {
        private final long generation;
        private final RailPartsRendererBase renderer;
        private final SlotState state;

        private SlotRecord(long generation, RailPartsRendererBase renderer, SlotState state) {
            this.generation = generation;
            this.renderer = renderer;
            this.state = state;
        }
    }

    private final class WorkerJob implements Runnable {
        private final Task task;
        private final RailMeshSnapshot snapshot;
        private final long epoch;

        private WorkerJob(Task task, RailMeshSnapshot snapshot, long epoch) {
            this.task = task;
            this.snapshot = snapshot;
            this.epoch = epoch;
        }

        @Override
        public void run() {
            RailResourceBuildQueue.this.buildOnWorker(this.task, this.snapshot, this.epoch);
        }
    }

    private static final class Task implements Comparable<Task> {
        private final Kind kind;
        private final TileEntityLargeRailCore rail;
        private final RailPartsRendererBase renderer;
        private final Object modelIdentity;
        private final World worldIdentity;
        private final int slot;
        private final long generation;
        private final boolean forceLegacy;
        private final boolean firstLoad;
        private final double distanceSq;
        private final long sequence;

        private Task(Kind kind, TileEntityLargeRailCore rail, RailPartsRendererBase renderer,
                     Object modelIdentity, World worldIdentity, int slot,
                     long generation, boolean forceLegacy, boolean firstLoad, double distanceSq, long sequence) {
            this.kind = kind;
            this.rail = rail;
            this.renderer = renderer;
            this.modelIdentity = modelIdentity;
            this.worldIdentity = worldIdentity;
            this.slot = slot;
            this.generation = generation;
            this.forceLegacy = forceLegacy;
            this.firstLoad = firstLoad;
            this.distanceSq = distanceSq;
            this.sequence = sequence;
        }

        private TaskKey key() { return new TaskKey(this.rail, this.kind, this.slot); }

        private Task asFallback(long newSequence) {
            return new Task(this.kind, this.rail, this.renderer, this.modelIdentity, this.worldIdentity,
                    this.slot, this.generation,
                    true, this.firstLoad, this.distanceSq, newSequence);
        }

        private Task withSequence(long newSequence) {
            return new Task(this.kind, this.rail, this.renderer, this.modelIdentity, this.worldIdentity,
                    this.slot, this.generation,
                    this.forceLegacy, this.firstLoad, this.distanceSq, newSequence);
        }

        @Override
        public int compareTo(Task other) {
            if (this.firstLoad != other.firstLoad) {
                return this.firstLoad ? -1 : 1;
            }
            int distance = Double.compare(this.distanceSq, other.distanceSq);
            return distance != 0 ? distance : Long.compare(this.sequence, other.sequence);
        }
    }

    private static final class BuildResult implements Comparable<BuildResult> {
        private final Task task;
        private final RailMeshData mesh;
        private final Throwable failure;
        private final long epoch;

        private BuildResult(Task task, RailMeshData mesh, Throwable failure, long epoch) {
            this.task = task;
            this.mesh = mesh;
            this.failure = failure;
            this.epoch = epoch;
        }

        private BuildResult withFailure(Throwable newFailure) {
            return new BuildResult(this.task, null, newFailure, this.epoch);
        }

        @Override
        public int compareTo(BuildResult other) {
            return this.task.compareTo(other.task);
        }
    }

    private static final class TaskKey {
        private final TileEntityLargeRailCore rail;
        private final Kind kind;
        private final int slot;

        private TaskKey(TileEntityLargeRailCore rail, Kind kind, int slot) {
            this.rail = rail;
            this.kind = kind;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof TaskKey)) {
                return false;
            }
            TaskKey other = (TaskKey) object;
            return this.rail == other.rail && this.kind == other.kind && this.slot == other.slot;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(this.rail);
            result = 31 * result + this.kind.hashCode();
            return 31 * result + this.slot;
        }
    }
}
