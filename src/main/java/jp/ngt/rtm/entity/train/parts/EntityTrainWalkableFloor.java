/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended - train interior walking carrier.
 * Licensed under LGPL v3; see the project license.
 */
package jp.ngt.rtm.entity.train.parts;

import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.entity.train.EntityTrainBase;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import jp.ngt.rtm.item.ItemTicket;
import jp.ngt.rtm.modelpack.cfg.TrainConfig;
import jp.ngt.rtm.network.PacketTrainWalkCorrection;
import jp.apple.train.PassengerPoseRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.MoverType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketSetPassengers;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Hidden passenger carrier for players walking inside a train. */
public class EntityTrainWalkableFloor extends EntityVehiclePart {
    private static final DataParameter<Float> CENTER_X = EntityDataManager.createKey(EntityTrainWalkableFloor.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> CENTER_Y = EntityDataManager.createKey(EntityTrainWalkableFloor.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> CENTER_Z = EntityDataManager.createKey(EntityTrainWalkableFloor.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> LENGTH = EntityDataManager.createKey(EntityTrainWalkableFloor.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> WIDTH = EntityDataManager.createKey(EntityTrainWalkableFloor.class, DataSerializers.FLOAT);

    private static final double EDGE_MARGIN = 0.3D;
    private static final double MAX_TRANSFER_GATEWAY_DISTANCE_SQ = 16.0D;
    private static final double MIN_TRANSFER_INSET = 0.05D;
    private static final int TRANSFER_COOLDOWN_TICKS = 3;
    private static final int PENDING_SNAPSHOT_TICKS = 40;
    private final Map<UUID, Vec3> passengerPositions = new HashMap<>();
    private final Map<UUID, Vec3> previousPassengerPositions = new HashMap<>();
    private final Map<UUID, Vec3> correctionErrors = new HashMap<>();
    private final Map<UUID, TrainWalkLogic.InputAccumulator> inputAccumulators = new HashMap<>();
    private final Map<UUID, Long> acknowledgedInputSequences = new HashMap<>();
    private final Map<UUID, Long> receivedSnapshotSequences = new HashMap<>();
    private final Map<UUID, Vec3> lastBroadcastPositions = new HashMap<>();
    private final Map<UUID, Long> lastBroadcastAcknowledgements = new HashMap<>();
    private final Map<UUID, TrainWalkLogic.PredictionHistory> predictionHistories = new HashMap<>();
    private final Map<UUID, PendingDismount> pendingDismounts = new HashMap<>();
    private final Map<UUID, Long> transferCooldowns = new HashMap<>();
    private final Map<UUID, PendingSnapshot> pendingSnapshots = new HashMap<>();
    private final Map<UUID, Boolean> diagnosticMovingPlayers = new HashMap<>();
    private final Map<UUID, Float> passengerWalkAnimationAmounts = new HashMap<>();
    private final Map<UUID, Float> passengerWalkAnimationPhases = new HashMap<>();
    private boolean destroying;
    private boolean floorTrackerInstalled;
    private int syncTick;
    private long snapshotSequence;

    public EntityTrainWalkableFloor(World world) {
        super(world);
        this.preventEntitySpawning = true;
        this.setSize(0.1F, 0.1F);
    }

    public EntityTrainWalkableFloor(World world, EntityVehicleBase parent, float[] center, float length, float width) {
        this(world);
        this.setVehicle(parent);
        this.setPartPos(center[0], center[1], center[2]);
        this.getDataManager().set(CENTER_X, center[0]);
        this.getDataManager().set(CENTER_Y, center[1]);
        this.getDataManager().set(CENTER_Z, center[2]);
        this.getDataManager().set(LENGTH, length);
        this.getDataManager().set(WIDTH, width);
        this.updatePartPos(parent);
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.getDataManager().register(CENTER_X, 0.0F);
        this.getDataManager().register(CENTER_Y, 0.0F);
        this.getDataManager().register(CENTER_Z, 0.0F);
        this.getDataManager().register(LENGTH, 0.0F);
        this.getDataManager().register(WIDTH, 0.0F);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        this.getDataManager().set(CENTER_X, nbt.getFloat("walkCenterX"));
        this.getDataManager().set(CENTER_Y, nbt.getFloat("walkCenterY"));
        this.getDataManager().set(CENTER_Z, nbt.getFloat("walkCenterZ"));
        this.getDataManager().set(LENGTH, nbt.getFloat("walkLength"));
        this.getDataManager().set(WIDTH, nbt.getFloat("walkWidth"));
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setFloat("walkCenterX", this.getCenterX());
        nbt.setFloat("walkCenterY", this.getCenterY());
        nbt.setFloat("walkCenterZ", this.getCenterZ());
        nbt.setFloat("walkLength", this.getLength());
        nbt.setFloat("walkWidth", this.getWidth());
        super.writeEntityToNBT(nbt);
    }

    @Override
    public void onLoadVehicle() {
        EntityVehicleBase vehicle = this.getVehicle();
        if (vehicle != null) {
            vehicle.setWalkableFloor(this);
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (this.isDead) {
            return;
        }
        if (!this.world.isRemote && !this.floorTrackerInstalled) {
            this.floorTrackerInstalled = FloorTrackerEntry.ensureTracking(this);
        }
        EntityVehicleBase vehicle = this.getVehicle();
        if (vehicle != null) {
            // The client resolves the parent from the synced entity id after spawn.
            if (!vehicle.setWalkableFloor(this)) {
                return;
            }
            if (this.world.isRemote) {
                this.processPendingSnapshots();
                this.applyPendingCorrections();
            } else {
                this.processPendingDismounts(vehicle);
                this.processPendingInputs();
                if (++this.syncTick >= 2) {
                    this.syncTick = 0;
                    this.broadcastPassengerPositions();
                }
            }
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldRiderSit() {
        return PassengerPoseRenderState.shouldRiderSit(this);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox() {
        return null;
    }

    @Override
    protected boolean canFitPassenger(Entity passenger) {
        return passenger instanceof EntityPlayer && !passenger.isRiding();
    }

    @Override
    public void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (passenger instanceof EntityPlayer) {
            UUID uuid = passenger.getUniqueID();
            PendingSnapshot pendingSnapshot = this.pendingSnapshots.remove(uuid);
            this.clearPassengerState(uuid);
            this.pendingDismounts.remove(uuid);
            EntityVehicleBase vehicle = this.getVehicle();
            Vec3 local = vehicle != null
                    ? this.clamp(this.worldToTrainLocal(vehicle, passenger))
                    : this.centerPosition();
            this.passengerPositions.put(uuid, local);
            this.previousPassengerPositions.put(uuid, local);
            if (this.world.isRemote && pendingSnapshot != null) {
                this.setPassengerLocalPosition(uuid,
                        pendingSnapshot.local.getX(), pendingSnapshot.local.getY(), pendingSnapshot.local.getZ(),
                        pendingSnapshot.acknowledgedInputSequence, pendingSnapshot.snapshotSequence,
                        pendingSnapshot.localPlayer);
            }
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        UUID uuid = passenger.getUniqueID();
        Vec3 local = this.passengerPositions.get(uuid);
        if (local == null && this.getVehicle() != null) {
            local = this.clamp(this.worldToTrainLocal(this.getVehicle(), passenger));
        }
        super.removePassenger(passenger);
        if (!this.world.isRemote && !this.destroying && local != null) {
            this.pendingDismounts.put(uuid, new PendingDismount(
                    local, this.world.getTotalWorldTime() + 1L));
        }
        this.clearPassengerState(uuid);
        this.pendingSnapshots.remove(uuid);
    }

    @Override
    public void onDismount(Entity rider) {
        // EntityVehiclePart tracks only its first passenger. Per-player
        // dismount placement is handled from removePassenger instead.
    }

    @Override
    public void setDead() {
        if (this.destroying) {
            return;
        }
        this.destroying = true;
        EntityVehicleBase vehicle = this.getVehicle();
        for (Entity passenger : new java.util.ArrayList<>(this.getPassengers())) {
            Vec3 local = this.passengerPositions.get(passenger.getUniqueID());
            if (local == null && vehicle != null) {
                local = this.clamp(this.worldToTrainLocal(vehicle, passenger));
            }
            passenger.dismountRidingEntity();
            if (!this.world.isRemote && vehicle != null && local != null) {
                this.placeDismountedPassenger(passenger, vehicle, local);
            }
        }
        this.passengerPositions.clear();
        this.previousPassengerPositions.clear();
        this.correctionErrors.clear();
        this.inputAccumulators.clear();
        this.acknowledgedInputSequences.clear();
        this.receivedSnapshotSequences.clear();
        this.lastBroadcastPositions.clear();
        this.lastBroadcastAcknowledgements.clear();
        this.predictionHistories.clear();
        this.pendingDismounts.clear();
        this.transferCooldowns.clear();
        this.pendingSnapshots.clear();
        this.diagnosticMovingPlayers.clear();
        this.passengerWalkAnimationAmounts.clear();
        this.passengerWalkAnimationPhases.clear();
        super.setDead();
    }

    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (hand == EnumHand.MAIN_HAND && ItemTicket.isBoardingTicket(player.getHeldItem(hand))
                && !player.isSneaking() && !player.isRiding()) {
            if (!this.world.isRemote) {
                player.startRiding(this);
            }
            return true;
        }
        return false;
    }

    @Override
    public void updatePassenger(Entity passenger) {
        // Vanilla calls this once from Entity.updateRidden(). Keeping this as
        // the sole movement owner matches IR's offset-based passenger update.
        this.applyPassengerMovement(passenger);
    }

    /** Client sync after the parent train has consumed its latest pose. */
    @SideOnly(Side.CLIENT)
    public void synchronizeClientPassengerPositions() {
        EntityVehicleBase vehicle = this.getVehicle();
        if (!(vehicle instanceof EntityTrainBase)) {
            return;
        }
        for (Entity passenger : new java.util.ArrayList<>(this.getPassengers())) {
            Vec3 local = this.passengerPositions.get(passenger.getUniqueID());
            if (local != null) {
                Vec3 previousLocal = this.previousPassengerPositions.get(passenger.getUniqueID());
                if (previousLocal == null) {
                    previousLocal = local;
                }
                this.updatePassengerWalkAnimation(passenger, previousLocal, local);
                Vec3 previousWorld = vehicle.localToWorld(previousLocal,
                        vehicle.lastTickPosX, vehicle.lastTickPosY, vehicle.lastTickPosZ,
                        vehicle.prevRotationYaw, vehicle.prevRotationPitch, vehicle.prevRotationRoll);
                Vec3 world = vehicle.localToWorld(local);
                passenger.lastTickPosX = previousWorld.getX();
                passenger.lastTickPosY = previousWorld.getY();
                passenger.lastTickPosZ = previousWorld.getZ();
                passenger.prevPosX = previousWorld.getX();
                passenger.prevPosY = previousWorld.getY();
                passenger.prevPosZ = previousWorld.getZ();
                passenger.setPosition(world.getX(), world.getY(), world.getZ());
                if (passenger instanceof EntityOtherPlayerMP) {
                    passenger.setPositionAndRotationDirect(world.getX(), world.getY(), world.getZ(),
                            passenger.rotationYaw, passenger.rotationPitch, 0, false);
                }
                vehicle.updatePassengerView(passenger);
            }
        }
    }

    /** Drives player limb animation from movement relative to the train only. */
    @SideOnly(Side.CLIENT)
    private void updatePassengerWalkAnimation(Entity passenger, Vec3 previousLocal, Vec3 local) {
        if (!(passenger instanceof EntityLivingBase)) {
            return;
        }

        UUID uuid = passenger.getUniqueID();
        float previousAmount = this.passengerWalkAnimationAmounts.getOrDefault(uuid, 0.0F);
        float targetAmount = TrainWalkLogic.walkAnimationTarget(
                local.getX() - previousLocal.getX(), local.getZ() - previousLocal.getZ());
        float amount = TrainWalkLogic.smoothWalkAnimationAmount(previousAmount, targetAmount);
        float phase = this.passengerWalkAnimationPhases.getOrDefault(uuid, 0.0F) + amount;

        EntityLivingBase living = (EntityLivingBase) passenger;
        living.prevLimbSwingAmount = previousAmount;
        living.limbSwingAmount = amount;
        living.limbSwing = phase;
        this.passengerWalkAnimationAmounts.put(uuid, amount);
        this.passengerWalkAnimationPhases.put(uuid, phase);
    }

    /** Captures the local interpolation start before client input and corrections run. */
    @SideOnly(Side.CLIENT)
    public void captureClientPassengerInterpolationState() {
        this.previousPassengerPositions.clear();
        this.previousPassengerPositions.putAll(this.passengerPositions);
    }

    /** Returns the player's visual local position for the current render frame. */
    @SideOnly(Side.CLIENT)
    public Vec3 getInterpolatedPassengerLocalPosition(Entity passenger, float partialTick) {
        Vec3 current = this.passengerPositions.get(passenger.getUniqueID());
        if (current == null) {
            return null;
        }
        Vec3 previous = this.previousPassengerPositions.get(passenger.getUniqueID());
        if (previous == null) {
            return current;
        }
        return previous.add(
                (current.getX() - previous.getX()) * partialTick,
                (current.getY() - previous.getY()) * partialTick,
                (current.getZ() - previous.getZ()) * partialTick);
    }

    /** Applies one combined train displacement and passenger movement delta. */
    private void applyPassengerMovement(Entity passenger) {
        if (!this.isPassenger(passenger)) {
            return;
        }

        EntityVehicleBase vehicle = this.getVehicle();
        if (!(vehicle instanceof EntityTrainBase)) {
            return;
        }

        UUID uuid = passenger.getUniqueID();
        Vec3 local = this.passengerPositions.get(uuid);
        if (local == null) {
            local = this.worldToTrainLocal(vehicle, passenger);
            this.passengerPositions.put(uuid, clamp(local));
            local = this.passengerPositions.get(uuid);
        }

        Vec3 world = this.trainLocalToWorld(vehicle, local);
        double oldX = passenger.posX;
        double oldY = passenger.posY;
        double oldZ = passenger.posZ;
        double dx = world.getX() - oldX;
        double dy = world.getY() - oldY;
        double dz = world.getZ() - oldZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        if (distanceSq > 1.0E-8D) {
            if (distanceSq > 64.0D) {
                // A large discontinuity is a genuine entity desync. Keep this
                // as the only normal-path hard placement fallback.
                passenger.setPosition(world.getX(), world.getY(), world.getZ());
            } else {
                // Entity.move updates the player's interpolation and collision
                // state in one operation. Directly assigning posX/Y/Z here
                // makes the renderer see a sequence of teleports and is the
                // source of the visible one-tick stepping.
                passenger.move(MoverType.SELF, dx, dy, dz);
            }
        }

        passenger.motionX = passenger.posX - oldX;
        passenger.motionY = passenger.posY - oldY;
        passenger.motionZ = passenger.posZ - oldZ;
        // The carrier represents a solid interior floor but deliberately has
        // no collision box. Keep the rider grounded so vanilla gravity does
        // not pull it down between two passenger updates and create a visible
        // up/down oscillation.
        passenger.onGround = true;
        passenger.fallDistance = 0.0F;
        passenger.motionY = 0.0D;

        // Do not reinterpret the carrier's own collision/rounding residue as
        // passenger movement. Only a meaningful discrepancy while the player
        // is actively walking is allowed to alter the saved local position.
        Vec3 actualLocal = this.clamp(this.worldToTrainLocal(vehicle, passenger));
        Vec3 collisionError = actualLocal.sub(local);
        boolean walking = Boolean.TRUE.equals(this.diagnosticMovingPlayers.get(uuid));
        if (walking && collisionError.length() > 0.01D) {
            this.passengerPositions.put(uuid, actualLocal);
        }
        this.diagnosticMovingPlayers.remove(uuid);

        if (!this.world.isRemote) {
            float yawDelta = MathHelper.wrapDegrees(vehicle.rotationYaw - vehicle.prevRotationYaw);
            float pitchDelta = MathHelper.wrapDegrees(vehicle.rotationPitch - vehicle.prevRotationPitch);
            passenger.rotationYaw -= yawDelta;
            passenger.rotationPitch -= pitchDelta;
            if (passenger instanceof EntityLivingBase) {
                ((EntityLivingBase) passenger).renderYawOffset -= yawDelta;
            }
        }
    }

    public void handleInput(EntityPlayer player, float localMoveX, float localMoveZ, long sequence) {
        if (this.world.isRemote) {
            NGTLog.debug("[TrainWalkDiag] side=S event=INPUT_DROP tick=%d player=%s floor=%d "
                            + "seq=%d reason=remote-world",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(), sequence);
            return;
        }
        if (!this.isPassenger(player)) {
            Entity riding = player.getRidingEntity();
            NGTLog.debug("[TrainWalkDiag] side=S event=INPUT_DROP tick=%d player=%s floor=%d "
                            + "seq=%d reason=not-passenger riding=%d",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(), sequence,
                    riding == null ? -1 : riding.getEntityId());
            return;
        }
        if (!(this.getVehicle() instanceof EntityTrainBase)) {
            EntityVehicleBase vehicle = this.getVehicle();
            NGTLog.debug("[TrainWalkDiag] side=S event=INPUT_DROP tick=%d player=%s floor=%d "
                            + "seq=%d reason=invalid-train train=%d",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(), sequence,
                    vehicle == null ? -1 : vehicle.getEntityId());
            return;
        }
        if (!TrainWalkLogic.isFinite(localMoveX) || !TrainWalkLogic.isFinite(localMoveZ) || sequence < 0L) {
            NGTLog.debug("[TrainWalkDiag] side=S event=INPUT_DROP tick=%d player=%s floor=%d "
                            + "seq=%d reason=invalid-values input=(%s,%s)",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(), sequence,
                    Float.toString(localMoveX), Float.toString(localMoveZ));
            return;
        }
        TrainWalkLogic.InputAccumulator accumulator = this.inputAccumulators.computeIfAbsent(
                player.getUniqueID(), ignored -> new TrainWalkLogic.InputAccumulator());
        if (!accumulator.offer(localMoveX, localMoveZ, sequence)) {
            NGTLog.debug("[TrainWalkDiag] side=S event=INPUT_DROP tick=%d player=%s floor=%d "
                            + "seq=%d reason=stale-or-sequence-jump",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(), sequence);
        }
    }

    /** Converts camera-relative movement into a normalized train-local X/Z vector. */
    public float[] getLocalMovementInput(EntityPlayer player, float strafe, float forward) {
        if (!TrainWalkLogic.isFinite(strafe) || !TrainWalkLogic.isFinite(forward)) {
            NGTLog.debug("[TrainWalkDiag] side=C event=INPUT_INVALID tick=%d player=%s floor=%d "
                            + "raw=(%s,%s)",
                    this.world.getTotalWorldTime(), player.getName(), this.getEntityId(),
                    Float.toString(strafe), Float.toString(forward));
            return new float[]{0.0F, 0.0F};
        }
        double[] normalizedInput = TrainWalkLogic.normalize(strafe, forward);
        double rad = Math.toRadians(player.rotationYaw);
        double worldX = -Math.sin(rad) * normalizedInput[1] + Math.cos(rad) * normalizedInput[0];
        double worldZ = Math.cos(rad) * normalizedInput[1] + Math.sin(rad) * normalizedInput[0];
        EntityVehicleBase vehicle = this.getVehicle();
        if (vehicle == null) {
            return new float[]{0.0F, 0.0F};
        }
        double yawRad = Math.toRadians(-vehicle.rotationYaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);
        double localX = worldX * yawCos + worldZ * yawSin;
        double localZ = worldZ * yawCos - worldX * yawSin;
        double[] normalizedLocal = TrainWalkLogic.normalize(localX, localZ);
        return new float[]{(float) normalizedLocal[0], (float) normalizedLocal[1]};
    }

    /** Client-side prediction uses the same movement and clamp logic as the server. */
    public void applyClientInput(EntityPlayer player, float localMoveX, float localMoveZ, long sequence) {
        // The riding link is the authoritative client-side relation. During
        // entity spawn synchronization the carrier's local passenger list can
        // lag one tick behind player.getRidingEntity(); requiring both made
        // prediction silently stop and left only stepped server snapshots.
        if (this.world.isRemote && player.getRidingEntity() == this) {
            double[] normalized = TrainWalkLogic.normalize(localMoveX, localMoveZ);
            float moveX = (float) normalized[0];
            float moveZ = (float) normalized[1];
            UUID uuid = player.getUniqueID();
            TrainWalkLogic.PredictionHistory history = this.predictionHistories.computeIfAbsent(
                    uuid, ignored -> new TrainWalkLogic.PredictionHistory());
            history.record(moveX, moveZ, sequence);
            this.applyLocalInput(player, moveX, moveZ, sequence);
        }
    }

    /** Applies a snapshot now or retains it until the vanilla passenger relation catches up. */
    public void queuePassengerLocalPosition(UUID uuid, double x, double y, double z,
                                             long acknowledgedInputSequence, long snapshotSequence,
                                             boolean localPlayer) {
        EntityPlayer player = this.world.getPlayerEntityByUUID(uuid);
        if (player != null && player.getRidingEntity() == this) {
            this.setPassengerLocalPosition(uuid, x, y, z, acknowledgedInputSequence, snapshotSequence, localPlayer);
            return;
        }

        PendingSnapshot previous = this.pendingSnapshots.get(uuid);
        if (previous == null || snapshotSequence > previous.snapshotSequence) {
            this.pendingSnapshots.put(uuid, new PendingSnapshot(
                    new Vec3(x, y, z), acknowledgedInputSequence, snapshotSequence, localPlayer,
                    this.world.getTotalWorldTime() + PENDING_SNAPSHOT_TICKS));
            NGTLog.debug("[TrainWalkDiag] side=C event=SNAPSHOT_QUEUE tick=%d player=%s floor=%d "
                            + "snapshot=%d ack=%d riding=%d",
                    this.world.getTotalWorldTime(), this.getDiagnosticPlayerName(uuid), this.getEntityId(),
                    snapshotSequence, acknowledgedInputSequence,
                    player == null || player.getRidingEntity() == null
                            ? -1 : player.getRidingEntity().getEntityId());
        }
    }

    public void setPassengerLocalPosition(UUID uuid, double x, double y, double z,
                                          long acknowledgedInputSequence, long snapshotSequence,
                                          boolean localPlayer) {
        Long previousSnapshot = this.receivedSnapshotSequences.get(uuid);
        if (!TrainWalkLogic.isNewerSequence(previousSnapshot, snapshotSequence)) {
            NGTLog.debug("[TrainWalkDiag] side=%s event=SNAPSHOT_DROP tick=%d player=%s floor=%d "
                            + "snapshot=%d previousSnapshot=%s ack=%d localPlayer=%s",
                    this.getDiagnosticSide(), this.world.getTotalWorldTime(),
                    this.getDiagnosticPlayerName(uuid), this.getEntityId(), snapshotSequence,
                    previousSnapshot == null ? "null" : previousSnapshot.toString(),
                    acknowledgedInputSequence, localPlayer);
            return;
        }
        this.receivedSnapshotSequences.put(uuid, snapshotSequence);

        Vec3 authoritative = clamp(new Vec3(x, y, z));
        Vec3 target = authoritative;
        if (this.world.isRemote && localPlayer) {
            TrainWalkLogic.PredictionHistory history = this.predictionHistories.computeIfAbsent(
                    uuid, ignored -> new TrainWalkLogic.PredictionHistory());
            TrainWalkLogic.ReplayedPosition replayed = history.reconcile(
                    acknowledgedInputSequence, authoritative.getX(), authoritative.getZ(),
                    this.getMinX(), this.getMaxX(), this.getMinZ(), this.getMaxZ());
            target = new Vec3(replayed.x, this.getCenterY(), replayed.z);
        }
        Vec3 current = this.passengerPositions.get(uuid);
        if (current == null) {
            this.passengerPositions.put(uuid, target);
            this.previousPassengerPositions.put(uuid, target);
            this.correctionErrors.remove(uuid);
        } else {
            this.correctionErrors.put(uuid, target.sub(current));
        }
    }

    private void applyPendingCorrections() {
        for (UUID uuid : new HashSet<>(this.correctionErrors.keySet())) {
            Vec3 error = this.correctionErrors.get(uuid);
            Vec3 current = this.passengerPositions.get(uuid);
            if (current == null || error == null) {
                this.correctionErrors.remove(uuid);
                continue;
            }

            double blend = TrainWalkLogic.correctionBlend(error.length());
            if (blend <= 0.0D) {
                this.correctionErrors.remove(uuid);
                continue;
            }
            Vec3 step = error.multi(blend);
            Vec3 next = this.clamp(current.add(step));
            this.passengerPositions.put(uuid, next);
            if (blend >= 1.0D) {
                this.correctionErrors.remove(uuid);
            } else {
                this.correctionErrors.put(uuid, error.sub(step));
            }
        }
    }

    private void processPendingSnapshots() {
        long worldTick = this.world.getTotalWorldTime();
        for (UUID uuid : new HashSet<>(this.pendingSnapshots.keySet())) {
            PendingSnapshot pending = this.pendingSnapshots.get(uuid);
            if (pending == null) {
                continue;
            }
            EntityPlayer player = this.world.getPlayerEntityByUUID(uuid);
            if (player != null && player.getRidingEntity() == this) {
                this.pendingSnapshots.remove(uuid);
                this.setPassengerLocalPosition(uuid,
                        pending.local.getX(), pending.local.getY(), pending.local.getZ(),
                        pending.acknowledgedInputSequence, pending.snapshotSequence, pending.localPlayer);
            } else if (pending.expiresAtTick <= worldTick) {
                this.pendingSnapshots.remove(uuid);
                NGTLog.debug("[TrainWalkDiag] side=C event=SNAPSHOT_DROP tick=%d player=%s floor=%d "
                                + "snapshot=%d ack=%d reason=relation-timeout",
                        worldTick, this.getDiagnosticPlayerName(uuid), this.getEntityId(),
                        pending.snapshotSequence, pending.acknowledgedInputSequence);
            }
        }
    }

    private void broadcastPassengerPositions() {
        for (Entity passenger : new java.util.ArrayList<>(this.getPassengers())) {
            UUID uuid = passenger.getUniqueID();
            Vec3 local = this.passengerPositions.get(uuid);
            if (local != null) {
                long acknowledged = this.acknowledgedInputSequences.getOrDefault(uuid, 0L);
                Vec3 lastPosition = this.lastBroadcastPositions.get(uuid);
                Long lastAcknowledged = this.lastBroadcastAcknowledgements.get(uuid);
                double positionDelta = lastPosition == null
                        ? Double.POSITIVE_INFINITY : local.sub(lastPosition).length();
                if (!TrainWalkLogic.shouldBroadcastSnapshot(positionDelta, lastAcknowledged, acknowledged)) {
                    continue;
                }
                long snapshot = ++this.snapshotSequence;
                this.broadcastCorrection(uuid, local, acknowledged, snapshot);
                this.lastBroadcastPositions.put(uuid, local);
                this.lastBroadcastAcknowledgements.put(uuid, acknowledged);
            }
        }
    }

    /** Sends a complete snapshot to one newly-added observer without changing broadcast state. */
    public void sendInitialPassengerSnapshots(EntityPlayerMP observer) {
        if (this.world.isRemote || observer == null || observer.world != this.world) {
            return;
        }
        for (Entity passenger : new java.util.ArrayList<>(this.getPassengers())) {
            Vec3 local = this.passengerPositions.get(passenger.getUniqueID());
            if (local == null) {
                continue;
            }
            long acknowledged = this.acknowledgedInputSequences.getOrDefault(passenger.getUniqueID(), 0L);
            RTMCore.NETWORK_WRAPPER.sendTo(new PacketTrainWalkCorrection(
                    passenger.getUniqueID(), this.getEntityId(), local, acknowledged, ++this.snapshotSequence), observer);
        }
    }

    private void broadcastCorrection(UUID uuid, Vec3 local, long acknowledgedInputSequence, long snapshotSequence) {
        if (this.world.isRemote || !(this.world instanceof WorldServer)) {
            return;
        }
        PacketTrainWalkCorrection packet = new PacketTrainWalkCorrection(
                uuid, this.getEntityId(), local, acknowledgedInputSequence, snapshotSequence);
        java.util.Set<EntityPlayerMP> watchers = new HashSet<>();
        for (EntityPlayer watcher : ((WorldServer) this.world).getEntityTracker().getTrackingPlayers(this)) {
            if (watcher instanceof EntityPlayerMP) {
                watchers.add((EntityPlayerMP) watcher);
            }
        }
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof EntityPlayerMP) {
                watchers.add((EntityPlayerMP) passenger);
            }
        }
        for (EntityPlayerMP watcher : watchers) {
            RTMCore.NETWORK_WRAPPER.sendTo(packet, watcher);
        }
    }

    private boolean applyLocalInput(EntityPlayer player, float localMoveX, float localMoveZ, long sequence) {
        Vec3 local = this.passengerPositions.get(player.getUniqueID());
        if (local == null) {
            local = this.worldToTrainLocal(this.getVehicle(), player);
        }
        Vec3 candidate = local.add(
                localMoveX * TrainWalkLogic.WALK_SPEED, 0.0D,
                localMoveZ * TrainWalkLogic.WALK_SPEED);
        candidate = new Vec3(
                MathHelper.clamp(candidate.getX(), this.getMinX(), this.getMaxX()),
                this.getCenterY(), candidate.getZ());

        if (!this.world.isRemote && (candidate.getZ() < this.getMinZ() || candidate.getZ() > this.getMaxZ())
                && this.tryTransferPassenger(player, candidate, sequence)) {
            return true;
        }

        Vec3 next = clamp(candidate);
        this.passengerPositions.put(player.getUniqueID(), next);
        this.diagnosticMovingPlayers.put(player.getUniqueID(),
                Math.abs(localMoveX) > 1.0E-5F || Math.abs(localMoveZ) > 1.0E-5F);
        return false;
    }

    private boolean tryTransferPassenger(EntityPlayer player, Vec3 candidate, long sequence) {
        UUID uuid = player.getUniqueID();
        long worldTick = this.world.getTotalWorldTime();
        Long cooldown = this.transferCooldowns.get(uuid);
        if (cooldown != null) {
            if (cooldown > worldTick) {
                return false;
            }
            this.transferCooldowns.remove(uuid);
        }

        EntityVehicleBase vehicle = this.getVehicle();
        if (!(vehicle instanceof EntityTrainBase)) {
            return this.rejectTransfer(player, -1, "invalid-source-train");
        }
        EntityTrainBase sourceTrain = (EntityTrainBase) vehicle;
        TrainConfig.WalkableConfig sourceConfig = this.getTransferConfig(sourceTrain);
        if (sourceConfig == null) {
            return this.rejectTransfer(player, -1, "source-config-unavailable");
        }
        double sourceBoundary = candidate.getZ() > this.getMaxZ() ? this.getMaxZ() : this.getMinZ();
        int sourceEnd = this.getConnectionEndForBoundary(sourceTrain, this, sourceBoundary);
        if (!this.isTransferEndEnabled(sourceConfig, sourceEnd)) {
            return this.rejectTransfer(player, sourceEnd, "source-end-disabled");
        }
        if (!this.isWithinGangway(this, sourceConfig, candidate.getX())) {
            return this.rejectTransfer(player, sourceEnd, "outside-source-gangway");
        }

        EntityTrainBase targetTrain = sourceTrain.getConnectedTrain(sourceEnd);
        if (targetTrain == null || targetTrain == sourceTrain
                || targetTrain.isDead || targetTrain.world != this.world) {
            return this.rejectTransfer(player, sourceEnd, "target-train-unavailable");
        }
        int targetEnd = this.findConnectedEnd(targetTrain, sourceTrain);
        if (targetEnd < 0 || targetTrain.getConnectedTrain(targetEnd) != sourceTrain) {
            return this.rejectTransfer(player, sourceEnd, "connection-not-reciprocal");
        }

        EntityTrainWalkableFloor destination = targetTrain.getWalkableFloor();
        TrainConfig.WalkableConfig targetConfig = this.getTransferConfig(targetTrain);
        if (destination == null || destination == this
                || destination.isDead || destination.world != this.world) {
            return this.rejectTransfer(player, sourceEnd, "target-floor-unavailable");
        }
        if (!this.isTransferEndEnabled(targetConfig, targetEnd)) {
            return this.rejectTransfer(player, sourceEnd, "target-end-disabled");
        }

        double targetBoundary = this.getBoundaryForConnectionEnd(targetTrain, destination, targetEnd);
        Vec3 sourceGateway = sourceTrain.localToWorld(new Vec3(
                this.getCenterX(), this.getCenterY(), sourceBoundary));
        Vec3 targetGateway = targetTrain.localToWorld(new Vec3(
                destination.getCenterX(), destination.getCenterY(), targetBoundary));
        Vec3 gatewayDelta = sourceGateway.sub(targetGateway);
        double gatewayDistanceSq = gatewayDelta.getX() * gatewayDelta.getX()
                + gatewayDelta.getY() * gatewayDelta.getY()
                + gatewayDelta.getZ() * gatewayDelta.getZ();
        if (gatewayDistanceSq > MAX_TRANSFER_GATEWAY_DISTANCE_SQ) {
            return this.rejectTransfer(player, sourceEnd, "gateway-too-far");
        }

        Vec3 crossingWorld = sourceTrain.localToWorld(new Vec3(
                candidate.getX(), this.getCenterY(), sourceBoundary));
        Vec3 targetAtGateway = this.worldToTrainLocal(targetTrain, crossingWorld);
        if (!this.isWithinGangway(destination, targetConfig, targetAtGateway.getX())) {
            return this.rejectTransfer(player, sourceEnd, "outside-target-gangway");
        }

        double overflow = Math.abs(candidate.getZ() - sourceBoundary);
        double inwardDirection = Math.abs(targetBoundary - destination.getMaxZ())
                <= Math.abs(targetBoundary - destination.getMinZ()) ? -1.0D : 1.0D;
        Vec3 targetLocal = destination.clamp(new Vec3(
                targetAtGateway.getX(), destination.getCenterY(),
                targetBoundary + inwardDirection * Math.max(MIN_TRANSFER_INSET, overflow)));

        boolean mounted = player.startRiding(destination, true);
        if (!mounted || player.getRidingEntity() != destination) {
            return this.rejectTransfer(player, sourceEnd, "mount-rejected");
        }

        this.cancelPendingDismount(uuid);
        destination.passengerPositions.put(uuid, targetLocal);
        destination.previousPassengerPositions.put(uuid, targetLocal);
        destination.correctionErrors.remove(uuid);
        destination.acknowledgedInputSequences.put(uuid, sequence);
        destination.transferCooldowns.put(uuid, worldTick + TRANSFER_COOLDOWN_TICKS);
        destination.diagnosticMovingPlayers.put(uuid, true);
        destination.applyPassengerMovement(player);
        this.syncPassengerTransfer(destination, player);

        long snapshot = ++destination.snapshotSequence;
        destination.broadcastCorrection(uuid, targetLocal, sequence, snapshot);
        destination.lastBroadcastPositions.put(uuid, targetLocal);
        destination.lastBroadcastAcknowledgements.put(uuid, sequence);
        return true;
    }

    private boolean rejectTransfer(EntityPlayer player, int sourceEnd, String reason) {
        return false;
    }

    private TrainConfig.WalkableConfig getTransferConfig(EntityTrainBase train) {
        if (train == null || train.getResourceState().getResourceSet() == null
                || train.getResourceState().getResourceSet().getConfig() == null) {
            return null;
        }
        return train.getResourceState().getResourceSet().getConfig().walkable;
    }

    private boolean isTransferEndEnabled(TrainConfig.WalkableConfig config, int end) {
        return config != null && config.allowCarriageTransfer && config.transferEnds != null
                && end >= 0 && end < config.transferEnds.length && config.transferEnds[end];
    }

    private boolean isWithinGangway(EntityTrainWalkableFloor floor,
                                     TrainConfig.WalkableConfig config, double localX) {
        if (config == null) {
            return false;
        }
        double usableWidth = Math.min(config.transferWidth, floor.getMaxX() - floor.getMinX());
        return usableWidth > 0.0D
                && Math.abs(localX - floor.getCenterX()) <= usableWidth * 0.5D;
    }

    private int getConnectionEndForBoundary(EntityTrainBase train,
                                             EntityTrainWalkableFloor floor, double boundary) {
        TrainConfig config = this.getTrainConfig(train);
        float[][] bogiePositions = config == null ? null : config.getBogiePos();
        if (bogiePositions == null || bogiePositions.length < 2
                || bogiePositions[0] == null || bogiePositions[0].length < 3
                || bogiePositions[1] == null || bogiePositions[1].length < 3) {
            return boundary >= floor.getCenterZ() ? 0 : 1;
        }
        return Math.abs(bogiePositions[0][2] - boundary) <= Math.abs(bogiePositions[1][2] - boundary)
                ? 0 : 1;
    }

    private double getBoundaryForConnectionEnd(EntityTrainBase train,
                                                EntityTrainWalkableFloor floor, int end) {
        TrainConfig config = this.getTrainConfig(train);
        float[][] bogiePositions = config == null ? null : config.getBogiePos();
        if (bogiePositions == null || end < 0 || end >= bogiePositions.length
                || bogiePositions[end] == null || bogiePositions[end].length < 3) {
            return end == 0 ? floor.getMaxZ() : floor.getMinZ();
        }
        return Math.abs(bogiePositions[end][2] - floor.getMaxZ())
                <= Math.abs(bogiePositions[end][2] - floor.getMinZ())
                ? floor.getMaxZ() : floor.getMinZ();
    }

    private int findConnectedEnd(EntityTrainBase train, EntityTrainBase expectedNeighbor) {
        for (int end = 0; end < 2; ++end) {
            if (train.getConnectedTrain(end) == expectedNeighbor) {
                return end;
            }
        }
        return -1;
    }

    private TrainConfig getTrainConfig(EntityTrainBase train) {
        if (train == null || train.getResourceState().getResourceSet() == null) {
            return null;
        }
        return train.getResourceState().getResourceSet().getConfig();
    }

    private void syncPassengerTransfer(EntityTrainWalkableFloor destination, EntityPlayer player) {
        if (!(this.world instanceof WorldServer)) {
            return;
        }
        WorldServer serverWorld = (WorldServer) this.world;
        java.util.Set<EntityPlayerMP> watchers = new HashSet<>();
        for (EntityPlayer watcher : serverWorld.getEntityTracker().getTrackingPlayers(this)) {
            if (watcher instanceof EntityPlayerMP) {
                watchers.add((EntityPlayerMP) watcher);
            }
        }
        for (EntityPlayer watcher : serverWorld.getEntityTracker().getTrackingPlayers(destination)) {
            if (watcher instanceof EntityPlayerMP) {
                watchers.add((EntityPlayerMP) watcher);
            }
        }
        if (player instanceof EntityPlayerMP) {
            watchers.add((EntityPlayerMP) player);
        }
        SPacketSetPassengers sourcePacket = new SPacketSetPassengers(this);
        SPacketSetPassengers destinationPacket = new SPacketSetPassengers(destination);
        for (EntityPlayerMP watcher : watchers) {
            watcher.connection.sendPacket(sourcePacket);
            watcher.connection.sendPacket(destinationPacket);
        }
    }

    private void processPendingInputs() {
        for (UUID uuid : new HashSet<>(this.inputAccumulators.keySet())) {
            TrainWalkLogic.InputAccumulator accumulator = this.inputAccumulators.get(uuid);
            if (accumulator == null || !accumulator.hasPending()) {
                continue;
            }
            TrainWalkLogic.PendingInput input = accumulator.drain();
            EntityPlayer player = this.world.getPlayerEntityByUUID(uuid);
            if (input == null || player == null || !this.isPassenger(player)) {
                if (player == null || !this.isPassenger(player)) {
                    this.clearPassengerState(uuid);
                }
                continue;
            }
            boolean transferred = this.applyLocalInput(player, input.moveX, input.moveZ, input.sequence);
            if (!transferred) {
                this.acknowledgedInputSequences.put(uuid, input.sequence);
            }
        }
    }

    private void processPendingDismounts(EntityVehicleBase vehicle) {
        long worldTick = this.world.getTotalWorldTime();
        for (UUID uuid : new HashSet<>(this.pendingDismounts.keySet())) {
            PendingDismount request = this.pendingDismounts.get(uuid);
            if (request == null || request.dueTick > worldTick) {
                continue;
            }
            this.pendingDismounts.remove(uuid);
            EntityPlayer player = this.world.getPlayerEntityByUUID(uuid);
            if (player != null && !player.isRiding()) {
                this.placeDismountedPassenger(player, vehicle, request.local);
            }
        }
    }

    private void clearPassengerState(UUID uuid) {
        this.passengerPositions.remove(uuid);
        this.previousPassengerPositions.remove(uuid);
        this.correctionErrors.remove(uuid);
        this.inputAccumulators.remove(uuid);
        this.acknowledgedInputSequences.remove(uuid);
        this.receivedSnapshotSequences.remove(uuid);
        this.lastBroadcastPositions.remove(uuid);
        this.lastBroadcastAcknowledgements.remove(uuid);
        this.diagnosticMovingPlayers.remove(uuid);
        this.transferCooldowns.remove(uuid);
        this.passengerWalkAnimationAmounts.remove(uuid);
        this.passengerWalkAnimationPhases.remove(uuid);
        TrainWalkLogic.PredictionHistory history = this.predictionHistories.remove(uuid);
        if (history != null) {
            history.clear();
        }
    }

    private Vec3 clamp(Vec3 local) {
        return new Vec3(
                MathHelper.clamp(local.getX(), this.getMinX(), this.getMaxX()),
                this.getCenterY(),
                MathHelper.clamp(local.getZ(), this.getMinZ(), this.getMaxZ()));
    }

    private String getDiagnosticSide() {
        return this.world.isRemote ? "C" : "S";
    }

    private String getDiagnosticPlayerName(UUID uuid) {
        EntityPlayer player = this.world.getPlayerEntityByUUID(uuid);
        return player == null ? uuid.toString() : player.getName();
    }

    private double getMinX() { return this.getCenterX() - Math.max(0.0D, this.getWidth() * 0.5D - EDGE_MARGIN); }
    private double getMaxX() { return this.getCenterX() + Math.max(0.0D, this.getWidth() * 0.5D - EDGE_MARGIN); }
    private double getMinZ() { return this.getCenterZ() - Math.max(0.0D, this.getLength() * 0.5D - EDGE_MARGIN); }
    private double getMaxZ() { return this.getCenterZ() + Math.max(0.0D, this.getLength() * 0.5D - EDGE_MARGIN); }

    private Vec3 centerPosition() {
        return new Vec3(this.getCenterX(), this.getCenterY(), this.getCenterZ());
    }

    private Vec3 trainLocalToWorld(EntityVehicleBase vehicle, Vec3 local) {
        return vehicle.localToWorld(local);
    }

    private Vec3 worldToTrainLocal(EntityVehicleBase vehicle, Entity entity) {
        return vehicle.worldToLocal(new Vec3(entity.posX, entity.posY, entity.posZ),
                vehicle.posX, vehicle.posY, vehicle.posZ,
                vehicle.rotationYaw, vehicle.rotationPitch, vehicle.rotationRoll);
    }

    private Vec3 worldToTrainLocal(EntityVehicleBase vehicle, Vec3 worldPosition) {
        return vehicle.worldToLocal(worldPosition,
                vehicle.posX, vehicle.posY, vehicle.posZ,
                vehicle.rotationYaw, vehicle.rotationPitch, vehicle.rotationRoll);
    }

    /** Places a dismounted rider just outside the nearest configured rectangle side. */
    private void placeDismountedPassenger(Entity passenger, EntityVehicleBase vehicle, Vec3 local) {
        Vec3 position = this.getSafeDismountPosition(passenger, vehicle, local);
        if (position != null) {
            passenger.setPositionAndUpdate(position.getX(), position.getY(), position.getZ());
        }
    }

    /** Returns a safe location outside this carrier without moving the passenger. */
    public Vec3 getSafeDismountPosition(Entity passenger) {
        EntityVehicleBase vehicle = this.getVehicle();
        if (vehicle == null) {
            return null;
        }
        Vec3 local = this.passengerPositions.get(passenger.getUniqueID());
        if (local == null) {
            local = this.clamp(this.worldToTrainLocal(vehicle, passenger));
        }
        return this.getSafeDismountPosition(passenger, vehicle, local);
    }

    /** Immediately dismounts a player and cancels the normal next-tick placement. */
    public Vec3 dismountImmediately(EntityPlayer player) {
        Vec3 position = this.getSafeDismountPosition(player);
        player.dismountRidingEntity();
        this.pendingDismounts.remove(player.getUniqueID());
        if (position != null) {
            player.setPositionAndUpdate(position.getX(), position.getY(), position.getZ());
        }
        return position;
    }

    public void cancelPendingDismount(UUID playerId) {
        this.pendingDismounts.remove(playerId);
    }

    private Vec3 getSafeDismountPosition(Entity passenger, EntityVehicleBase vehicle, Vec3 local) {
        double halfLength = Math.max(0.0D, this.getLength() * 0.5D - EDGE_MARGIN);
        double halfWidth = Math.max(0.0D, this.getWidth() * 0.5D - EDGE_MARGIN);
        double minX = this.getCenterX() - halfWidth;
        double maxX = this.getCenterX() + halfWidth;
        double minZ = this.getCenterZ() - halfLength;
        double maxZ = this.getCenterZ() + halfLength;
        double dMinX = Math.abs(local.getX() - minX);
        double dMaxX = Math.abs(maxX - local.getX());
        double dMinZ = Math.abs(local.getZ() - minZ);
        double dMaxZ = Math.abs(maxZ - local.getZ());

        double best = dMinX;
        int side = 0;
        if (dMaxX < best) { best = dMaxX; side = 1; }
        if (dMinZ < best) { best = dMinZ; side = 2; }
        if (dMaxZ < best) { side = 3; }

        Vec3[] candidates = new Vec3[4];
        candidates[0] = new Vec3(minX - 0.6D, local.getY(), MathHelper.clamp(local.getZ(), minZ, maxZ));
        candidates[1] = new Vec3(maxX + 0.6D, local.getY(), MathHelper.clamp(local.getZ(), minZ, maxZ));
        candidates[2] = new Vec3(MathHelper.clamp(local.getX(), minX, maxX), local.getY(), minZ - 0.6D);
        candidates[3] = new Vec3(MathHelper.clamp(local.getX(), minX, maxX), local.getY(), maxZ + 0.6D);

        for (int i = 0; i < candidates.length; ++i) {
            int index = (side + i) % candidates.length;
            Vec3 worldPosition = this.trainLocalToWorld(vehicle, candidates[index]);
            double dx = worldPosition.getX() - passenger.posX;
            double dy = worldPosition.getY() - passenger.posY;
            double dz = worldPosition.getZ() - passenger.posZ;
            AxisAlignedBB box = passenger.getEntityBoundingBox().offset(dx, dy, dz);
            if (this.world.getCollisionBoxes(passenger, box).isEmpty()) {
                return worldPosition;
            }
        }

        return vehicle.getSafeDismountPosition(passenger, this);
    }

    public float getCenterX() { return this.getDataManager().get(CENTER_X); }
    public float getCenterY() { return this.getDataManager().get(CENTER_Y); }
    public float getCenterZ() { return this.getDataManager().get(CENTER_Z); }
    public float getLength() { return this.getDataManager().get(LENGTH); }
    public float getWidth() { return this.getDataManager().get(WIDTH); }

    private static final class PendingDismount {
        final Vec3 local;
        final long dueTick;

        PendingDismount(Vec3 local, long dueTick) {
            this.local = local;
            this.dueTick = dueTick;
        }
    }

    private static final class PendingSnapshot {
        final Vec3 local;
        final long acknowledgedInputSequence;
        final long snapshotSequence;
        final boolean localPlayer;
        final long expiresAtTick;

        PendingSnapshot(Vec3 local, long acknowledgedInputSequence, long snapshotSequence,
                        boolean localPlayer, long expiresAtTick) {
            this.local = local;
            this.acknowledgedInputSequence = acknowledgedInputSequence;
            this.snapshotSequence = snapshotSequence;
            this.localPlayer = localPlayer;
            this.expiresAtTick = expiresAtTick;
        }
    }
}
