/*
 * AppleExtended
 *
 * Original code (c) 2020 anatawa12 and other contributors.
 * Modifications (c) 2026 Applepie.
 * Further modifications (2026): HabQi.
 *
 * This file is part of AppleExtended, which is a derivative work of fixRTM.
 * Both are licensed under the GNU Lesser General Public License version 3.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.entity.vehicle;

import jp.ngt.ngtlib.entity.EntityCustom;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.math.NGTMath;
import jp.ngt.ngtlib.math.PooledVec3;
import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.ngtlib.network.PacketNBT;
import jp.ngt.ngtlib.util.NGTUtil;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.entity.train.EntityTrainBase;
import jp.ngt.rtm.entity.train.parts.EntityFloor;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.entity.train.util.Formation;
import jp.ngt.rtm.entity.train.util.TrainState;
import jp.ngt.rtm.entity.train.util.TrainState.TrainStateType;
import jp.ngt.rtm.modelpack.IResourceSelector;
import jp.ngt.rtm.modelpack.ResourceType;
import jp.ngt.rtm.modelpack.ScriptExecuter;
import jp.ngt.rtm.modelpack.cfg.VehicleBaseConfig;
import jp.ngt.rtm.modelpack.modelset.ModelSetBase;
import jp.ngt.rtm.modelpack.modelset.ModelSetVehicleBase;
import jp.ngt.rtm.modelpack.state.ResourceState;
import jp.ngt.rtm.network.PacketNotice;
import jp.ngt.rtm.network.PacketSetTrainState;
import jp.ngt.rtm.network.PacketVehicleMovement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public abstract class EntityVehicleBase<T extends ModelSetVehicleBase> extends EntityCustom implements IResourceSelector {

    public static final int MAX_SEAT_ROTATION = 45;
    public static final int MAX_DOOR_MOVE = 60;
    public static final int MAX_PANTOGRAPH_MOVE = 40;
    public static final float TO_ANGULAR_VELOCITY = (float) (360.0D / Math.PI);
    private static final int RENDER_DELAY_TICKS = 6;
    private static final double DEFAULT_PER_TICK_ALPHA = 0.06D;

    protected int renderDelayTicks = RENDER_DELAY_TICKS;
    protected double perTickAlpha = DEFAULT_PER_TICK_ALPHA;

    private ResourceState<T> state = new ResourceState<>(this.getSubType(), this);
    private ScriptExecuter executer = new ScriptExecuter();

    protected List<EntityFloor> vehicleFloors = new ArrayList<>();
    private EntityTrainWalkableFloor walkableFloor;
    private boolean walkableFloorLoaded;
    protected final IUpdateVehicle soundUpdater;

    private boolean floorLoaded;
    /**
     * TrainTrackerEntryを生成したかどうか
     */
    private boolean tracked;
    private EntityLivingBase rider;

    public float rotationRoll;
    public float prevRotationRoll;

    @SideOnly(Side.CLIENT)
    public int seatRotation;
    @SideOnly(Side.CLIENT)
    public int rollsignAnimation;
    @SideOnly(Side.CLIENT)
    public int rollsignV;

    @SideOnly(Side.CLIENT)
    public int doorMoveL, doorMoveR;
    @SideOnly(Side.CLIENT)
    public int pantograph_F, pantograph_B;
    @SideOnly(Side.CLIENT)
    public float wheelRotationR, wheelRotationL;

    @SideOnly(Side.CLIENT)
    protected int vehiclePosRotationInc;
    @SideOnly(Side.CLIENT)
    protected double vehicleX, vehicleY, vehicleZ;
    @SideOnly(Side.CLIENT)
    protected float vehicleYaw, vehiclePitch, vehicleRoll;

    private static class Snapshot {
        final long tick;
        final double x, y, z;
        final float yaw, pitch, roll;

        Snapshot(long tick, double x, double y, double z, float yaw, float pitch, float roll) {
            this.tick = tick;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }
    }

    private final java.util.ArrayDeque<Snapshot> snapshotBuffer = new java.util.ArrayDeque<>();
    private double targetOffset = Double.NaN;
    private double smoothedOffset = Double.NaN;

    public EntityVehicleBase(World world) {
        super(world);
        this.ignoreFrustumCheck = true;
        if (!NGTUtil.isServer()) {
            setRenderDistanceWeight(16.0D);
        }
        this.preventEntitySpawning = true;
        this.soundUpdater = world != null ? RTMCore.proxy.getSoundUpdater(this) : null;

        for (TrainStateType type : TrainStateType.values()) {
            this.setVehicleState(type, type.min);
        }
    }


    @Override
    protected void entityInit() {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setTag("State", this.getResourceState().writeToNBT());
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        this.getResourceState().readFromNBT(nbt.getCompoundTag("State"));

        if (this.world != null && this.world.isRemote) {
            this.updateResourceState();
        }
    }


    @Override
    public boolean shouldRenderInPass(int pass) {
        return pass >= 0;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !this.isDead;
    }

    @Override
    protected boolean canTriggerWalking() {
        return false;
    }

    @Override
    public AxisAlignedBB getCollisionBox(Entity par1) {
		
	/*if(par1 instanceof EntityVehiclePart)
	{
		if(((EntityVehiclePart)par1).getVehicle() == this)
		{
			return null;
		}
	}
	else if(par1 instanceof EntityBogie)
	{
		if(((EntityBogie)par1).getTrain() == this)
		{
			return null;
		}
	}
	return par1.getEntityBoundingBox();*/
        return null;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox() {


        return null;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public void syncData() {
        PacketNBT.sendToClient(this);
    }

    @Override
    public void addTrackingPlayer(EntityPlayerMP player) {
        PacketNBT.sendToClient(this, player);
    }

    public void setFloor(EntityFloor par1) {
        if (!this.vehicleFloors.contains(par1)) {
            this.vehicleFloors.add(par1);
        }
    }

    /** Claims the single walkable carrier owned by this vehicle. */
    public boolean setWalkableFloor(EntityTrainWalkableFloor floor) {
        if (floor == null || floor.isDead || floor.world != this.world) {
            return false;
        }

        this.walkableFloorLoaded = true;
        EntityTrainWalkableFloor current = this.walkableFloor;
        if (current == null || current.isDead || current.world != this.world) {
            this.walkableFloor = floor;
            return true;
        }
        if (current == floor) {
            return true;
        }

        boolean currentOccupied = !current.getPassengers().isEmpty();
        boolean candidateOccupied = !floor.getPassengers().isEmpty();
        if (!this.world.isRemote && currentOccupied && candidateOccupied) {
            return true;
        }
        boolean replaceCurrent = candidateOccupied != currentOccupied
                ? candidateOccupied
                : floor.getEntityId() < current.getEntityId();
        if (replaceCurrent) {
            this.walkableFloor = floor;
        }

        if (!this.world.isRemote) {
            EntityTrainWalkableFloor duplicate = replaceCurrent ? current : floor;
            duplicate.setDead();
            return this.walkableFloor == floor;
        }
        return true;
    }

    public EntityTrainWalkableFloor getWalkableFloor() {
        if (this.walkableFloor == null && !this.world.isRemote && !this.walkableFloorLoaded) {
            this.setupWalkableFloor();
        }
        if (this.walkableFloor != null && this.walkableFloor.isDead) {
            this.walkableFloor = null;
            this.walkableFloorLoaded = false;
        }
        return this.walkableFloor;
    }

    /** Repairs stale references left by a rejected or legacy duplicate carrier. */
    public void repairWalkableFloorReference() {
        this.walkableFloor = null;
        this.walkableFloorLoaded = false;
        for (Entity entity : new ArrayList<>(this.world.loadedEntityList)) {
            if (entity instanceof EntityTrainWalkableFloor && !entity.isDead
                    && this.world.getEntityByID(entity.getEntityId()) == entity
                    && ((EntityTrainWalkableFloor) entity).getParentVehicle() == this) {
                this.setWalkableFloor((EntityTrainWalkableFloor) entity);
            }
        }
    }

    /**
     * Re-anchors ordinary passengers after all client entities have ticked.
     * Position and view use the completed vehicle pose; train-walking local
     * coordinates and prediction state stay untouched.
     */
    @SideOnly(Side.CLIENT)
    public void synchronizeClientPassengerPositions() {
        if (!this.world.isRemote || this.isDead) {
            return;
        }

        for (Entity passenger : new ArrayList<>(this.getPassengers())) {
            this.updatePassengerPosition(passenger);
            this.updatePassengerView(passenger, false);
        }

        for (EntityFloor floor : new ArrayList<>(this.vehicleFloors)) {
            if (floor != null && !floor.isDead && floor.getParentVehicle() == this) {
                floor.synchronizeClientPassengerPositions(this);
            }
        }

    }

    @Override
    public void setDead() {
        super.setDead();

        if (this.world.isRemote) {
            ;
        } else {
            for (EntityFloor floor : this.vehicleFloors) {
                if (floor != null) {
                    floor.setDead();
                }
            }

            if (this.walkableFloor != null) {
                this.walkableFloor.setDead();
                this.walkableFloor = null;
            }


            RTMCore.NETWORK_WRAPPER.sendToAllTracking(new PacketVehicleMovement(this, true), this);
        }
    }

    @Override
    public final void onUpdate() {
        this.onVehicleUpdate();
    }

    /**
     * Entity.onUpdate()を上書き
     */
    public double lastTickPosXPublic, lastTickPosYPublic, lastTickPosZPublic;

    protected void onVehicleUpdate() {
        super.onUpdate();

        this.prevRotationRoll = this.rotationRoll;

        if (this.world.isRemote) {
            if (this.ticksExisted == (60 + (this.getEntityId() % 20))) {
                String currentModel = this.getResourceState().getResourceName();
                String defaultModel = this.getSubType().defaultName;
                if (currentModel.equals(defaultModel)) {
                    RTMCore.NETWORK_WRAPPER.sendToServer(new PacketNotice(PacketNotice.Side_SERVER, "requestSync", this));
                }
            }
            if (this.soundUpdater != null) {
                this.soundUpdater.update();
            }

            this.updateAnimation();
            this.updatePosAndRotationClient();
        } else {
            if (!this.tracked) {
                this.tracked = VehicleTrackerEntry.trackingVehicle(this);
            }

            if (!this.floorLoaded) {
                this.setupFloors();
            }
            if (!this.walkableFloorLoaded) {
                this.setupWalkableFloor();
            }

            Entity passenger = this.getFirstPassenger();
            if (passenger != null) {
                if (this.rider == null && passenger instanceof EntityLivingBase) {
                    this.rider = (EntityLivingBase) passenger;
                }
            } else if (this.rider != null) {

                this.rider = null;
            }

            this.executer.execScript(this);

            this.updateBlockCollisionState();
            this.updateEntityCollisionState();

            this.updateFallState();
            this.updateMovement();
        }
    }

    /**
     * (Server Only)
     */
    protected void updateBlockCollisionState() {
    }

    /**
     * (Server Only)
     */
    protected void updateEntityCollisionState() {
    }

    protected void updateFallState() {
        this.motionY -= 0.05D;
    }

    protected void updateMovement() {
        this.applyPhysicalEffect();
        this.move(MoverType.SELF, this.motionX, this.motionY, this.motionZ);
    }

    protected void applyPhysicalEffect() {
        this.motionX *= 0.9900000095367432D;
        this.motionY *= 0.949999988079071D;
        this.motionZ *= 0.9900000095367432D;
    }

    @SideOnly(Side.CLIENT)
    protected void updatePosAndRotationClient() {
        if (this.snapshotBuffer.isEmpty() || Double.isNaN(this.smoothedOffset)) {
            return;
        }

        if (!Double.isNaN(this.targetOffset)) {
            this.smoothedOffset += (this.targetOffset - this.smoothedOffset) * this.perTickAlpha;
        }

        double renderTickD = (double) this.ticksExisted + this.smoothedOffset - this.renderDelayTicks;
        long renderTick = (long) Math.floor(renderTickD);

        Snapshot before = null;
        Snapshot after = null;
        for (Snapshot s : this.snapshotBuffer) {
            if (s.tick <= renderTick) {
                before = s;
            } else if (after == null) {
                after = s;
                break;
            }
        }

        if (before != null && after != null && after.tick > before.tick) {
            float t = (float) ((renderTickD - before.tick) / (double) (after.tick - before.tick));
            t = MathHelper.clamp(t, 0.0F, 1.0F);

            this.posX = before.x + (after.x - before.x) * t;
            this.posY = before.y + (after.y - before.y) * t;
            this.posZ = before.z + (after.z - before.z) * t;
            this.rotationYaw = before.yaw + NGTMath.wrapAngle(after.yaw - before.yaw) * t;
            this.rotationPitch = before.pitch + (after.pitch - before.pitch) * t;
            this.rotationRoll = before.roll + (after.roll - before.roll) * t;
        } else if (before != null) {
            this.posX = before.x;
            this.posY = before.y;
            this.posZ = before.z;
            this.rotationYaw = before.yaw;
            this.rotationPitch = before.pitch;
            this.rotationRoll = before.roll;
        }

        this.setRotation(this.rotationYaw, this.rotationPitch);
        this.setPosition(this.posX, this.posY, this.posZ);
    }

    @Override
    protected boolean canFitPassenger(Entity passenger) {
        return this.getPassengers().size() <= 0;
    }


    @Override
    public boolean canPassengerSteer() {
        return false;
    }

    @Override
    public Entity getControllingPassenger() {


        return null;
    }

    @Override
    public boolean shouldRiderSit() {
        return true;
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        this.removePassengerFromVehicle(passenger);
    }

    protected void removePassengerFromVehicle(Entity passenger) {
        this.fixRiderPosOnDismount(passenger, this);
    }

    /**
     * 降りたEntityがハマらないように位置修正する
     *
     */
    public void fixRiderPosOnDismount(Entity passenger, Entity ridingEntity) {
        Vec3 position = this.getSafeDismountPosition(passenger, ridingEntity);
        if (position != null) {
            passenger.setPositionAndUpdate(position.getX(), position.getY(), position.getZ());
        }
    }

    /** Finds a collision-free dismount location without moving the passenger. */
    public Vec3 getSafeDismountPosition(Entity passenger, Entity ridingEntity) {
        AxisAlignedBB vehicleAABB = null;

        try {
            ModelSetBase set = this.getResourceState().getResourceSet();
            vehicleAABB = set.getCollisionObj().getSizeBox();
        } catch (NullPointerException e) {
        }

        if (vehicleAABB == null) {
            vehicleAABB = new AxisAlignedBB(-1.5D, 0.0D, -2.0D, 1.5D, 3.0D, 2.0D);
        }


        Vec3 vec = PooledVec3.create(ridingEntity.posX - this.posX, ridingEntity.posY - this.posY, ridingEntity.posZ - this.posZ);

        vec = vec.rotateAroundY(-this.rotationYaw);
        double localY = vec.getY();
        double localZ = vec.getZ();
        float yawDif = NGTMath.wrapAngle(-passenger.rotationYaw - this.rotationYaw);

        double rightX = vehicleAABB.maxX + 0.5D;
        double leftX = vehicleAABB.minX - 0.5D;
        double preferredX = yawDif >= 0.0D ? rightX : leftX;
        double alternateX = yawDif >= 0.0D ? leftX : rightX;

        for (double candidateX : new double[]{preferredX, alternateX}) {
            Vec3 candidate = PooledVec3.create(candidateX, localY, localZ)
                    .rotateAroundY(this.rotationYaw);
            if (candidate.length() > 16.0D) {
                continue;
            }
            double x = this.posX + candidate.getX();
            double y = this.posY + candidate.getY();
            double z = this.posZ + candidate.getZ();
            double newY = this.getDismountY(x, y, z, y + 5.0D);
            if (newY - y >= 5.0D) {
                continue;
            }
            AxisAlignedBB box = passenger.getEntityBoundingBox().offset(
                    x - passenger.posX, newY - passenger.posY, z - passenger.posZ);
            if (this.world.getCollisionBoxes(passenger, box).isEmpty()) {
                return new Vec3(x, newY, z);
            }
        }
        return null;
    }

    private double getDismountY(double x, double y, double z, double maxY) {
        while (y < maxY) {
            if (this.world.isAirBlock(new BlockPos(x, y, z))) {
                break;
            }
            y += 1.0D;
        }
        return y;
    }

    @Override
    public void updatePassenger(Entity passenger) {
        if (this.isPassenger(passenger)) {
            this.updatePassengerPosition(passenger);
            if (!this.world.isRemote) {
                this.updatePassengerView(passenger, false);
            }
        }
    }

    /** Applies the current vehicle tick's orientation delta without changing position. */
    public void updatePassengerView(Entity passenger) {
        this.updatePassengerView(passenger, true);
    }

    /** Applies the view delta and optionally rotates the rendered body with the vehicle. */
    public void updatePassengerView(Entity passenger, boolean rotateBody) {
        float yawDelta = NGTMath.wrapAngle(this.rotationYaw - this.prevRotationYaw);
        passenger.rotationYaw -= yawDelta;
        passenger.rotationPitch -= NGTMath.wrapAngle(this.rotationPitch - this.prevRotationPitch);
        if (rotateBody && passenger instanceof EntityLivingBase) {
            ((EntityLivingBase) passenger).renderYawOffset -= yawDelta;
        }
    }

    /** Updates only the passenger's world position from the current vehicle pose. */
    public void updatePassengerPosition(Entity passenger) {
        if (!this.isPassenger(passenger)) {
            return;
        }
        Vec3 world = this.localToWorld(this.getRiderPos(passenger));
        passenger.setPosition(world.getX(), world.getY(), world.getZ());
    }

    /** Applies the vehicle's current rigid transform to a vehicle-local point. */
    public Vec3 localToWorld(Vec3 local) {
        return this.localToWorld(local, this.posX, this.posY, this.posZ,
                this.rotationYaw, this.rotationPitch, this.rotationRoll);
    }

    /** Applies an explicitly supplied vehicle pose to a vehicle-local point. */
    public Vec3 localToWorld(Vec3 local, double x, double y, double z,
                             float yaw, float pitch, float roll) {
        // MathHelper's trigonometric lookup is reduced to 4096 entries when
        // OptiFine Fast Math is enabled. At the end of a long carriage that
        // angle quantization becomes visible as lateral camera stepping.
        double rollRad = Math.toRadians(-roll);
        double rollCos = Math.cos(rollRad);
        double rollSin = Math.sin(rollRad);
        double x0 = local.getX() * rollCos + local.getY() * rollSin;
        double y0 = local.getY() * rollCos - local.getX() * rollSin;
        double z0 = local.getZ();

        double pitchRad = Math.toRadians(pitch);
        double pitchCos = Math.cos(pitchRad);
        double pitchSin = Math.sin(pitchRad);
        double x1 = x0;
        double y1 = y0 * pitchCos + z0 * pitchSin;
        double z1 = z0 * pitchCos - y0 * pitchSin;

        double yawRad = Math.toRadians(yaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);
        double x2 = x1 * yawCos + z1 * yawSin;
        double z2 = z1 * yawCos - x1 * yawSin;
        return new Vec3(x2 + x, y1 + y, z2 + z);
    }

    /** Converts a world point using an explicitly supplied inverse vehicle pose. */
    public Vec3 worldToLocal(Vec3 world, double x, double y, double z,
                             float yaw, float pitch, float roll) {
        double relativeX = world.getX() - x;
        double relativeY = world.getY() - y;
        double relativeZ = world.getZ() - z;

        double yawRad = Math.toRadians(-yaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);
        double x0 = relativeX * yawCos + relativeZ * yawSin;
        double y0 = relativeY;
        double z0 = relativeZ * yawCos - relativeX * yawSin;

        double pitchRad = Math.toRadians(-pitch);
        double pitchCos = Math.cos(pitchRad);
        double pitchSin = Math.sin(pitchRad);
        double x1 = x0;
        double y1 = y0 * pitchCos + z0 * pitchSin;
        double z1 = z0 * pitchCos - y0 * pitchSin;

        double rollRad = Math.toRadians(roll);
        double rollCos = Math.cos(rollRad);
        double rollSin = Math.sin(rollRad);
        double x2 = x1 * rollCos + y1 * rollSin;
        double y2 = y1 * rollCos - x1 * rollSin;
        return new Vec3(x2, y2, z1);
    }

    /** Converts a world point into local coordinates using the previous client pose. */
    @SideOnly(Side.CLIENT)
    public Vec3 previousWorldToLocal(Vec3 world) {
        return this.worldToLocal(world, this.lastTickPosX, this.lastTickPosY, this.lastTickPosZ,
                this.prevRotationYaw, this.prevRotationPitch, this.prevRotationRoll);
    }

    /** Generates a world point from the vehicle pose interpolated for this render frame. */
    @SideOnly(Side.CLIENT)
    public Vec3 interpolatedLocalToWorld(Vec3 local, float partialTick) {
        double x = this.lastTickPosX + (this.posX - this.lastTickPosX) * partialTick;
        double y = this.lastTickPosY + (this.posY - this.lastTickPosY) * partialTick;
        double z = this.lastTickPosZ + (this.posZ - this.lastTickPosZ) * partialTick;
        float yaw = this.prevRotationYaw + NGTMath.wrapAngle(this.rotationYaw - this.prevRotationYaw) * partialTick;
        float pitch = this.prevRotationPitch + (this.rotationPitch - this.prevRotationPitch) * partialTick;
        float roll = this.getInterpolatedRoll(partialTick);
        return this.localToWorld(local, x, y, z, yaw, pitch, roll);
    }

    public Vec3 getRiderPos(Entity passenger) {
        float[] pos = this.getResourceState().getResourceSet().getConfig().getPlayerPos()[this.getRiderPosIndex()];
        return PooledVec3.create((double) pos[0], (double) pos[1] + passenger.getYOffset(), (double) pos[2]);
    }

    protected int getRiderPosIndex() {
        return 0;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void applyOrientationToEntity(Entity entity) {


    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean teleport) {
        this.setPositionAndRotationDirect(x, y, z, yaw, pitch, posRotationIncrements, teleport, -1L);
    }

    @SideOnly(Side.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int par6, boolean par10, long serverTick) {
        this.setPositionAndRotationDirect(x, y, z, yaw, pitch, this.vehicleRoll, par6, par10, serverTick);
    }

    /** Queues one complete server pose so position, rotation and roll share a timestamp. */
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, float roll,
                                             int par6, boolean par10, long serverTick) {
        long tickKey = (serverTick >= 0) ? serverTick : this.ticksExisted;
        this.snapshotBuffer.addLast(new Snapshot(tickKey, x, y, z, yaw, pitch, roll));
        while (this.snapshotBuffer.size() > 16) {
            this.snapshotBuffer.removeFirst();
        }

        this.targetOffset = (double) tickKey - (double) this.ticksExisted;
        if (Double.isNaN(this.smoothedOffset)) {
            this.smoothedOffset = this.targetOffset;
        }
    }

    @SideOnly(Side.CLIENT)
    public void setRollAndSpeed(float speed, float roll) {
        this.setSpeed(speed);
        this.vehicleRoll = roll;
    }

    public float getRoll() {
        return this.rotationRoll;
    }

    /** Matches the roll interpolation used by the vehicle renderer. */
    @SideOnly(Side.CLIENT)
    public float getInterpolatedRoll(float partialTick) {
        return this.prevRotationRoll + (this.rotationRoll - this.prevRotationRoll) * partialTick;
    }

    public abstract float getSpeed();

    public abstract void setSpeed(float par1);

    @SideOnly(Side.CLIENT)
    @Override
    public void setVelocity(double par1, double par3, double par5) {
    }

    @SideOnly(Side.CLIENT)
    protected void updateAnimation() {
        ModelSetVehicleBase modelSet = this.getResourceState().getResourceSet();
        VehicleBaseConfig cfg = modelSet.getConfig();
        float speed = this.getSpeed();
        float f0 = speed * TO_ANGULAR_VELOCITY * cfg.wheelRotationSpeed * this.getMoveDir();

        this.wheelRotationR = (this.wheelRotationR + f0) % 360.0F;
        this.wheelRotationL = (this.wheelRotationL + f0) % 360.0F;


        int doorState = this.getVehicleState(TrainStateType.Door);
        boolean doorROpen = (doorState & 1) == 1;
        this.doorMoveR = this.updateDoorAnimation(modelSet, doorROpen, this.doorMoveR);
        boolean doorLOpen = (doorState & 2) == 2;
        this.doorMoveL = this.updateDoorAnimation(modelSet, doorLOpen, this.doorMoveL);


        int pantoState = this.getVehicleState(TrainStateType.Pantograph);

        if (pantoState == TrainState.Pantograph_Down.data) {
            if (this.pantograph_F < MAX_PANTOGRAPH_MOVE) {
                ++this.pantograph_F;
            }
            if (this.pantograph_B < MAX_PANTOGRAPH_MOVE) {
                ++this.pantograph_B;
            }
        } else {
            int[] ia = this.getPantographMaxHeight();

            if (this.pantograph_F > ia[0]) {
                --this.pantograph_F;
            } else if (this.pantograph_F < ia[0]) {
                ++this.pantograph_F;
            }

            if (this.pantograph_B > ia[1]) {
                --this.pantograph_B;
            } else if (this.pantograph_B < ia[1]) {
                ++this.pantograph_B;
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public float getRollsignAnimation() {
        return (float) this.rollsignAnimation / 16.0F;
    }

    @SideOnly(Side.CLIENT)
    public void setRollsignAnimation(int par1) {
        this.rollsignV = par1 * 16;
    }

    /**
     * -1.0~1.0
     */
    @SideOnly(Side.CLIENT)
    public float getSeatRotation() {
        return (float) this.seatRotation / MAX_SEAT_ROTATION;
    }

    protected int[] getPantographMaxHeight() {
        return new int[]{0, 0};
    }

    protected int updateDoorAnimation(ModelSetVehicleBase modelSet, boolean isOpen, int move) {
        if (isOpen) {
            if (move < MAX_DOOR_MOVE) {
                if (move == 0) {
                    RTMCore.proxy.playSound(this, modelSet.getConfig().sound_DoorOpen, 1.0F, 1.0F);
                }
                ++move;
            }
        } else {
            if (move > 0) {
                if (move == MAX_DOOR_MOVE) {
                    RTMCore.proxy.playSound(this, modelSet.getConfig().sound_DoorClose, 1.0F, 1.0F);
                }
                --move;
            }
        }
        return move;
    }

    /**
     * +1.0 or -1.0
     */
    public float getMoveDir() {
        return 1.0F;
    }

    /**
     * Server Only
     */
    private void setupFloors() {
        for (EntityFloor entity : this.vehicleFloors) {
            if (entity != null) {
                entity.setDead();
            }
        }

        T set = this.getResourceState().getResourceSet();
        this.floorLoaded = true;
        for (int i = 0; i < set.getConfig().getSlotPos().length; ++i) {
            float[] fa = set.getConfig().getSlotPos()[i];
            EntityFloor floor = new EntityFloor(this.world, this, new float[]{fa[0], fa[1] + this.getVehicleYOffset(), fa[2]}, (byte) fa[3]);
            if (this.world.spawnEntity(floor)) {
                this.vehicleFloors.add(floor);
            } else {
                this.floorLoaded = false;
                break;
            }
        }
    }

    /** Server-side creation of the optional rectangular train interior carrier. */
    private void setupWalkableFloor() {
        this.walkableFloorLoaded = true;
        if (!(this instanceof EntityTrainBase)) {
            return;
        }

        jp.ngt.rtm.modelpack.cfg.TrainConfig.WalkableConfig config =
                ((EntityTrainBase) this).getResourceState().getResourceSet().getConfig().walkable;
        if (config == null || !config.isValid()) {
            NGTLog.debug("[WalkableFloor] disabled train=%d", this.getEntityId());
            return;
        }

        EntityTrainWalkableFloor floor = new EntityTrainWalkableFloor(
                this.world, this, config.center, config.length, config.width);
        if (this.world.spawnEntity(floor)) {
            this.walkableFloor = floor;
            NGTLog.debug("[WalkableFloor] spawned train=%d floor=%d center=(%.2f,%.2f,%.2f) size=(%.2f,%.2f)",
                    this.getEntityId(), floor.getEntityId(), config.center[0], config.center[1], config.center[2],
                    config.length, config.width);
        } else {
            this.walkableFloorLoaded = false;
        }
    }

    @Override
    public String getName() {
        return this.getResourceState().getName();
    }

    @Override
    public ResourceState<T> getResourceState() {
        return this.state;
    }

    @Override
    public void updateResourceState() {
        if (this.world == null || !this.world.isRemote) {
            PacketNBT.sendToClient(this);
            this.floorLoaded = false;
            this.walkableFloorLoaded = false;
            if (this.walkableFloor != null) {
                this.walkableFloor.setDead();
                this.walkableFloor = null;
            }
        }

        if (this.world.isRemote) {
            this.soundUpdater.onModelChanged();
        }
    }

    @Override
    public int[] getSelectorPos() {
        return new int[]{this.getEntityId(), -1, 0};
    }

    @Override
    public boolean closeGui(ResourceState par1) {
        return true;
    }

    protected abstract ResourceType getSubType();

    @SideOnly(Side.CLIENT)
    protected boolean useInteriorLight() {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public int getBrightnessForRender() {
	/*if(this.shouldUseInteriorLight())
	{
		return 0xF000F0;
	}*/
        return super.getBrightnessForRender();
    }

    /**
     * 描画位置、座席位置に使用
     */
    public float getVehicleYOffset() {
        return 0.0F;
    }


    public Formation getFormation() {
        return null;
    }


    @Deprecated
    public byte getTrainStateData(int id) {
        return this.getVehicleState(TrainState.getStateType(id));
    }

    @Deprecated
    public void setTrainStateData(int id, byte data) {
        this.setVehicleState(TrainState.getStateType(id), data);
    }

    public byte getVehicleState(TrainStateType type) {
        return type.clap((byte) this.getResourceState().getDataMap().getInt(type.toString()), this);
    }

    public void setVehicleState(TrainStateType type, byte data) {
        this.getResourceState().getDataMap().setInt(type.toString(), type.clap(data, this), 3);
    }


    @SideOnly(Side.CLIENT)
    public void syncVehicleState(TrainStateType type, byte data) {
        RTMCore.NETWORK_WRAPPER.sendToServer(new PacketSetTrainState(this, type, data));
    }

    public VehicleNGTO getNGTO() {
        return null;
    }

    @Override
    public void onRemovedFromWorld() {
        this.removeWeatherEffectDummy();
        super.onRemovedFromWorld();
        com.anatawa12.fixRtm.rtm.entity.vehicle.EntityVehicleBaseKt.onRemovedFromWorld(this);
    }

    private void removeWeatherEffectDummy() {
        if (this.world == null || !this.world.isRemote) {
            return;
        }

        Iterator<Entity> iterator = this.world.weatherEffects.iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (entity instanceof WeatherEffectDummy && ((WeatherEffectDummy) entity).isLinkedTo(this)) {
                entity.setDead();
                iterator.remove();
            }
        }
    }

    @Override
    public void addEntityCrashInfo(net.minecraft.crash.CrashReportCategory category) {
        super.addEntityCrashInfo(category);
        com.anatawa12.fixRtm.rtm.entity.vehicle.EntityVehicleBaseKt.addEntityCrashInfo(this, category);
    }
}
