/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Lightweight measurements for client-side rail resource construction.
 */
package jp.ngt.rtm.render;

import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.renderer.RenderDiagnostics;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;

@SideOnly(Side.CLIENT)
public final class RailBuildProfiler {
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;
    private static final int SAMPLE_CAPACITY = 2048;

    private static final SampleWindow RAIL_TASKS = new SampleWindow(SAMPLE_CAPACITY);
    private static final SampleWindow BALLAST_TASKS = new SampleWindow(SAMPLE_CAPACITY);
    private static final SampleWindow FRAMES = new SampleWindow(SAMPLE_CAPACITY);
    private static final StageStat[] STAGES = new StageStat[Stage.values().length];

    private static long railSucceeded;
    private static long railFailed;
    private static long ballastSucceeded;
    private static long ballastFailed;
    private static long unloadedBallastChunks;
    private static long missingBallastTiles;
    private static long actualMissingBlocks;
    private static final long[] ASYNC_FALLBACKS = new long[FallbackReason.values().length];
    private static long staleDiscards;
    private static long completedBytesPeak;
    private static long frameStartedAt;
    private static long lastReportAt = System.nanoTime();

    static {
        for (Stage stage : Stage.values()) {
            STAGES[stage.ordinal()] = new StageStat();
        }
    }

    private RailBuildProfiler() {
    }

    public static synchronized void beginFrame() {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        frameStartedAt = System.nanoTime();
    }

    public static synchronized void endFrame() {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        if (frameStartedAt != 0L) {
            FRAMES.add(System.nanoTime() - frameStartedAt);
            frameStartedAt = 0L;
        }
    }

    public static synchronized void recordTask(boolean rail, boolean success, long nanos) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        if (rail) {
            RAIL_TASKS.add(nanos);
            if (success) {
                ++railSucceeded;
            } else {
                ++railFailed;
            }
        } else {
            BALLAST_TASKS.add(nanos);
            if (success) {
                ++ballastSucceeded;
            } else {
                ++ballastFailed;
            }
        }
    }

    public static synchronized void recordStage(Stage stage, long nanos) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        if (stage != null && nanos >= 0L) {
            STAGES[stage.ordinal()].add(nanos);
        }
    }

    public static synchronized void recordAsyncFallback(FallbackReason reason) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        FallbackReason actualReason = reason == null ? FallbackReason.UNKNOWN : reason;
        ++ASYNC_FALLBACKS[actualReason.ordinal()];
    }

    public static synchronized void recordStaleDiscard() {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        ++staleDiscards;
    }

    public static synchronized void recordCompletedQueueBytes(long bytes) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        completedBytesPeak = Math.max(completedBytesPeak, Math.max(0L, bytes));
    }

    public static synchronized void recordBallastDataIssue(BallastDataIssue issue) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        if (issue == BallastDataIssue.CHUNK_UNLOADED) {
            ++unloadedBallastChunks;
        } else if (issue == BallastDataIssue.TILE_ENTITY_MISSING) {
            ++missingBallastTiles;
        } else if (issue == BallastDataIssue.ACTUAL_BLOCK_MISSING) {
            ++actualMissingBlocks;
        }
    }

    public static synchronized void logIfDue(int pendingTasks, long completedBytes) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        recordCompletedQueueBytes(completedBytes);
        long now = System.nanoTime();
        if (now - lastReportAt < REPORT_INTERVAL_NANOS) {
            return;
        }
        lastReportAt = now;

        long asyncFallbacks = getAsyncFallbackTotal();
        boolean hasBuildActivity = railSucceeded + railFailed + ballastSucceeded + ballastFailed > 0L
                || asyncFallbacks > 0L || staleDiscards > 0L || pendingTasks > 0 || completedBytes > 0L;
        if (hasBuildActivity) {
            NGTLog.debug("[RailBuildStats] pending=%d tasks rail=%d(ok=%d fail=%d) ballast=%d(ok=%d fail=%d)",
                    pendingTasks, railSucceeded + railFailed, railSucceeded, railFailed,
                    ballastSucceeded + ballastFailed, ballastSucceeded, ballastFailed);
            NGTLog.debug("[RailBuildStats] async fallback=%d reasons rendererUnsupported=%d "
                            + "vboUnavailable=%d snapshotFailure=%d workerFailure=%d uploadFailure=%d unknown=%d "
                            + "staleDiscard=%d completed=%.2fMiB peak=%.2fMiB",
                    asyncFallbacks,
                    ASYNC_FALLBACKS[FallbackReason.RENDERER_UNSUPPORTED.ordinal()],
                    ASYNC_FALLBACKS[FallbackReason.VBO_UNAVAILABLE.ordinal()],
                    ASYNC_FALLBACKS[FallbackReason.SNAPSHOT_FAILURE.ordinal()],
                    ASYNC_FALLBACKS[FallbackReason.WORKER_FAILURE.ordinal()],
                    ASYNC_FALLBACKS[FallbackReason.UPLOAD_FAILURE.ordinal()],
                    ASYNC_FALLBACKS[FallbackReason.UNKNOWN.ordinal()], staleDiscards,
                    completedBytes / 1048576.0D, completedBytesPeak / 1048576.0D);
            if (unloadedBallastChunks + missingBallastTiles + actualMissingBlocks > 0L) {
                NGTLog.debug("[RailBuildStats] ballastData chunkDeferred=%d tileDeferred=%d placeholders=%d",
                        unloadedBallastChunks, missingBallastTiles, actualMissingBlocks);
            }
            logSamples("railTask", RAIL_TASKS);
            logSamples("ballastTask", BALLAST_TASKS);
            logSamples("renderFrame", FRAMES);
            logStages();
            logStageSamples(Stage.RAIL_SNAPSHOT);
            logStageSamples(Stage.RAIL_WORKER_MESH);
            logStageSamples(Stage.RAIL_GPU_UPLOAD);
        }
        resetWindow();
    }

    public static synchronized void reset() {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        resetWindow();
        frameStartedAt = 0L;
        lastReportAt = System.nanoTime();
    }

    private static void logSamples(String name, SampleWindow samples) {
        if (samples.size == 0) {
            return;
        }
        long[] sorted = samples.sortedCopy();
        NGTLog.debug("[RailBuildStats] %s n=%d avg=%.2fms p95=%.2fms p99=%.2fms max=%.2fms",
                name, samples.size, samples.averageMillis(), percentileMillis(sorted, 0.95D),
                percentileMillis(sorted, 0.99D), sorted[sorted.length - 1] / 1.0E6D);
    }

    private static void logStages() {
        StringBuilder builder = new StringBuilder();
        for (Stage stage : Stage.values()) {
            StageStat stat = STAGES[stage.ordinal()];
            if (stat.count == 0L) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(stage.label).append("=")
                    .append(stat.count).append("/")
                    .append(formatMillis(stat.totalNanos)).append("ms/")
                    .append(formatMillis(stat.maxNanos)).append("msMax");
        }
        if (builder.length() > 0) {
            NGTLog.debug("[RailBuildStats] stages count/total/max %s", builder.toString());
        }
    }

    private static void logStageSamples(Stage stage) {
        StageStat stat = STAGES[stage.ordinal()];
        if (stat.samples.size == 0) {
            return;
        }
        long[] sorted = stat.samples.sortedCopy();
        NGTLog.debug("[RailBuildStats] %s n=%d avg=%.2fms p95=%.2fms p99=%.2fms max=%.2fms",
                stage.label, stat.samples.size, stat.samples.averageMillis(),
                percentileMillis(sorted, 0.95D), percentileMillis(sorted, 0.99D),
                sorted[sorted.length - 1] / 1.0E6D);
    }

    private static long getAsyncFallbackTotal() {
        long total = 0L;
        for (long count : ASYNC_FALLBACKS) {
            total += count;
        }
        return total;
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1.0E6D);
    }

    private static double percentileMillis(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return sorted[index] / 1.0E6D;
    }

    private static void resetWindow() {
        RAIL_TASKS.clear();
        BALLAST_TASKS.clear();
        FRAMES.clear();
        for (StageStat stat : STAGES) {
            stat.clear();
        }
        railSucceeded = 0L;
        railFailed = 0L;
        ballastSucceeded = 0L;
        ballastFailed = 0L;
        unloadedBallastChunks = 0L;
        missingBallastTiles = 0L;
        actualMissingBlocks = 0L;
        Arrays.fill(ASYNC_FALLBACKS, 0L);
        staleDiscards = 0L;
        completedBytesPeak = 0L;
    }

    public enum BallastDataIssue {
        CHUNK_UNLOADED,
        TILE_ENTITY_MISSING,
        ACTUAL_BLOCK_MISSING
    }

    public enum FallbackReason {
        RENDERER_UNSUPPORTED,
        VBO_UNAVAILABLE,
        SNAPSHOT_FAILURE,
        WORKER_FAILURE,
        UPLOAD_FAILURE,
        UNKNOWN
    }

    public enum Stage {
        RAIL_MAP("railMap"),
        RAIL_GEOMETRY("railGeometry"),
        RAIL_LIGHTING("railLighting"),
        RAIL_MATRIX("railMatrix"),
        RAIL_COMPILE("railCompile"),
        RAIL_SNAPSHOT("railSnapshot"),
        RAIL_WORKER_MESH("workerMesh"),
        RAIL_GPU_UPLOAD("gpuUpload"),
        BALLAST_BLOCK_LIST("ballastBlockList"),
        BALLAST_VALIDATE("ballastValidate"),
        BALLAST_TESSELLATION("ballastTessellation"),
        BALLAST_COMPILE("ballastCompile"),
        GL_FINALIZE("glFinalize");

        private final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final class SampleWindow {
        private final long[] values;
        private int next;
        private int size;
        private long totalNanos;

        private SampleWindow(int capacity) {
            this.values = new long[capacity];
        }

        private void add(long nanos) {
            if (this.size == this.values.length) {
                this.totalNanos -= this.values[this.next];
            } else {
                ++this.size;
            }
            this.values[this.next] = nanos;
            this.totalNanos += nanos;
            this.next = (this.next + 1) % this.values.length;
        }

        private long[] sortedCopy() {
            long[] copy = Arrays.copyOf(this.values, this.size);
            Arrays.sort(copy);
            return copy;
        }

        private double averageMillis() {
            return this.size > 0 ? this.totalNanos / (double) this.size / 1.0E6D : 0.0D;
        }

        private void clear() {
            this.next = 0;
            this.size = 0;
            this.totalNanos = 0L;
        }
    }

    private static final class StageStat {
        private final SampleWindow samples = new SampleWindow(SAMPLE_CAPACITY);
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void add(long nanos) {
            this.samples.add(nanos);
            ++this.count;
            this.totalNanos += nanos;
            this.maxNanos = Math.max(this.maxNanos, nanos);
        }

        private void clear() {
            this.samples.clear();
            this.count = 0L;
            this.totalNanos = 0L;
            this.maxNanos = 0L;
        }
    }
}
