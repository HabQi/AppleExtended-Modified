/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.rtm.entity.train.EntityTrainBase;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.entity.train.parts.EntityVehiclePart;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent safe-position state for players riding RTM trains. */
public final class TrainRecoveryManager {
    static final String RECOVERY_KEY = "AppleExtendedTrainRecovery";
    private static final int DATA_VERSION = 1;
    private static final Set<String> RTM_ROOT_IDS = new HashSet<>(Arrays.asList(
            "rtm:floor", "rtm:walkable_floor", "rtm:electric_car", "rtm:diesel_car",
            "rtm:test_car", "rtm:freight_car", "rtm:tanker",
            "RTM.E.Floor", "RTM.E.WalkableFloor", "RTM.E.ElectricCar", "RTM.E.DieselCar",
            "RTM.E.TrainTest", "RTM.E.FreightCar", "RTM.E.Tanker"));
    private static final Set<UUID> PROTECTED_DISMOUNTS = new HashSet<>();

    private TrainRecoveryManager() {
    }

    public static void recordBoarding(EntityPlayerMP player, Entity mounted) {
        EntityTrainBase train = findParentTrain(mounted);
        if (train == null || !isFinite(player.posX, player.posY, player.posZ)) {
            return;
        }
        writeRecovery(player, new RecoveryPosition(player.dimension, player.posX, player.posY, player.posZ,
                train.getUniqueID()));
    }

    public static void refreshSafePosition(EntityPlayerMP player) {
        Entity mounted = player.getRidingEntity();
        EntityTrainBase train = findParentTrain(mounted);
        if (train == null) {
            return;
        }
        Vec3 safe = findSafePosition(player, mounted, train);
        if (safe != null && isFinite(safe.getX(), safe.getY(), safe.getZ())) {
            writeRecovery(player, new RecoveryPosition(player.dimension, safe.getX(), safe.getY(), safe.getZ(),
                    train.getUniqueID()));
        }
    }

    public static void clearAfterDismount(EntityPlayerMP player, Entity mounted) {
        if (findParentTrain(mounted) != null && !PROTECTED_DISMOUNTS.contains(player.getUniqueID())) {
            player.getEntityData().removeTag(RECOVERY_KEY);
        }
    }

    public static void handleLogout(EntityPlayerMP player) {
        Entity mounted = player.getRidingEntity();
        EntityTrainBase train = findParentTrain(mounted);
        if (train == null) {
            return;
        }

        Vec3 safe = findSafePosition(player, mounted, train);
        if (safe == null) {
            RecoveryPosition previous = readRecovery(player.getEntityData());
            if (previous != null && previous.dimension == player.dimension) {
                safe = new Vec3(previous.x, previous.y, previous.z);
            }
        }
        if (safe != null) {
            writeRecovery(player, new RecoveryPosition(player.dimension, safe.getX(), safe.getY(), safe.getZ(),
                    train.getUniqueID()));
        }

        PROTECTED_DISMOUNTS.add(player.getUniqueID());
        try {
            if (mounted instanceof EntityTrainWalkableFloor) {
                ((EntityTrainWalkableFloor) mounted).dismountImmediately(player);
            } else {
                player.dismountRidingEntity();
            }
        } finally {
            PROTECTED_DISMOUNTS.remove(player.getUniqueID());
        }
        if (safe != null) {
            player.setPositionAndUpdate(safe.getX(), safe.getY(), safe.getZ());
        }
    }

    public static void handleLogin(EntityPlayerMP player) {
        RecoveryPosition recovery = readRecovery(player.getEntityData());
        Entity mounted = player.getRidingEntity();
        EntityTrainBase mountedTrain = findParentTrain(mounted);
        if (mounted != null && mountedTrain == null) {
            recovery = null;
        } else if (mountedTrain != null) {
            Entity root = player.getLowestRidingEntity();
            player.dismountRidingEntity();
            if (root != player) {
                for (Entity passenger : new ArrayList<>(root.getRecursivePassengers())) {
                    if (passenger != player) {
                        passenger.setDead();
                    }
                }
                root.setDead();
            }
        }

        if (recovery != null && recovery.dimension == player.dimension) {
            player.setPositionAndUpdate(recovery.x, recovery.y, recovery.z);
        }
        player.getEntityData().removeTag(RECOVERY_KEY);

        for (Entity entity : new ArrayList<>(player.world.loadedEntityList)) {
            if (entity instanceof EntityTrainBase) {
                ((EntityTrainBase) entity).repairWalkableFloorReference();
            }
        }
    }

    /** Called from the EntityPlayerMP NBT transformer. */
    public static void beforePlayerSave(Object playerObject, Object compoundObject) {
        if (!(playerObject instanceof EntityPlayerMP) || !(compoundObject instanceof NBTTagCompound)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) playerObject;
        NBTTagCompound compound = (NBTTagCompound) compoundObject;
        Entity mounted = player.getRidingEntity();
        if (findParentTrain(mounted) == null) {
            if (mounted != null) {
                removeRecoveryData(player, compound);
            }
            return;
        }

        refreshSafePosition(player);
        RecoveryPosition recovery = readRecovery(player.getEntityData());
        compound.removeTag("RootVehicle");
        if (recovery != null) {
            applyRecoveryToNbt(compound, recovery);
            NBTTagCompound forgeData = compound.getCompoundTag("ForgeData");
            forgeData.setTag(RECOVERY_KEY, recovery.write());
            compound.setTag("ForgeData", forgeData);
        }
    }

    /** Called before PlayerList returns NBT to the code that restores RootVehicle. */
    public static void afterPlayerDataRead(Object playerObject, Object compoundObject) {
        if (!(playerObject instanceof EntityPlayerMP) || !(compoundObject instanceof NBTTagCompound)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) playerObject;
        NBTTagCompound compound = (NBTTagCompound) compoundObject;
        boolean hasRootVehicle = compound.hasKey("RootVehicle", 10);
        boolean removedRtmRoot = removeRtmRootVehicle(compound);
        if (hasRootVehicle && !removedRtmRoot) {
            removeRecoveryData(player, compound);
            return;
        }

        RecoveryPosition recovery = readRecovery(compound.getCompoundTag("ForgeData"));
        if (recovery != null) {
            applyRecoveryToNbt(compound, recovery);
            player.dimension = recovery.dimension;
            player.setPosition(recovery.x, recovery.y, recovery.z);
        }
    }

    static boolean isRtmRootVehicle(NBTTagCompound playerNbt) {
        if (!playerNbt.hasKey("RootVehicle", 10)) {
            return false;
        }
        NBTTagCompound root = playerNbt.getCompoundTag("RootVehicle");
        if (!root.hasKey("Entity", 10)) {
            return false;
        }
        String id = root.getCompoundTag("Entity").getString("id");
        return RTM_ROOT_IDS.contains(id);
    }

    static boolean removeRtmRootVehicle(NBTTagCompound playerNbt) {
        if (!isRtmRootVehicle(playerNbt)) {
            return false;
        }
        playerNbt.removeTag("RootVehicle");
        return true;
    }

    private static void removeRecoveryData(EntityPlayerMP player, NBTTagCompound playerNbt) {
        player.getEntityData().removeTag(RECOVERY_KEY);
        if (playerNbt.hasKey("ForgeData", 10)) {
            playerNbt.getCompoundTag("ForgeData").removeTag(RECOVERY_KEY);
        }
    }

    static void applyRecoveryToNbt(NBTTagCompound compound, RecoveryPosition recovery) {
        NBTTagList position = new NBTTagList();
        position.appendTag(new NBTTagDouble(recovery.x));
        position.appendTag(new NBTTagDouble(recovery.y));
        position.appendTag(new NBTTagDouble(recovery.z));
        compound.setTag("Pos", position);
        compound.setInteger("Dimension", recovery.dimension);
    }

    private static Vec3 findSafePosition(EntityPlayerMP player, Entity mounted, EntityTrainBase train) {
        if (mounted instanceof EntityTrainWalkableFloor) {
            return ((EntityTrainWalkableFloor) mounted).getSafeDismountPosition(player);
        }
        return train.getSafeDismountPosition(player, mounted == null ? train : mounted);
    }

    private static EntityTrainBase findParentTrain(Entity mounted) {
        if (mounted instanceof EntityTrainBase) {
            return (EntityTrainBase) mounted;
        }
        if (mounted instanceof EntityVehiclePart) {
            EntityVehicleBase vehicle = ((EntityVehiclePart) mounted).getParentVehicle();
            return vehicle instanceof EntityTrainBase ? (EntityTrainBase) vehicle : null;
        }
        return null;
    }

    private static void writeRecovery(EntityPlayerMP player, RecoveryPosition recovery) {
        player.getEntityData().setTag(RECOVERY_KEY, recovery.write());
    }

    private static RecoveryPosition readRecovery(NBTTagCompound holder) {
        if (!holder.hasKey(RECOVERY_KEY, 10)) {
            return null;
        }
        NBTTagCompound data = holder.getCompoundTag(RECOVERY_KEY);
        int dimension = data.getInteger("Dimension");
        double x = data.getDouble("X");
        double y = data.getDouble("Y");
        double z = data.getDouble("Z");
        if (data.getInteger("Version") != DATA_VERSION || !isFinite(x, y, z)
                || !DimensionManager.isDimensionRegistered(dimension)
                || DimensionManager.getWorld(dimension) == null
                || !data.hasKey("TrainUUIDMost", 4) || !data.hasKey("TrainUUIDLeast", 4)) {
            return null;
        }
        UUID trainId = new UUID(data.getLong("TrainUUIDMost"), data.getLong("TrainUUIDLeast"));
        if (trainId.getMostSignificantBits() == 0L && trainId.getLeastSignificantBits() == 0L) {
            return null;
        }
        return new RecoveryPosition(dimension, x, y, z, trainId);
    }

    private static boolean isFinite(double... values) {
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }

    static final class RecoveryPosition {
        final int dimension;
        final double x;
        final double y;
        final double z;
        final UUID trainId;

        RecoveryPosition(int dimension, double x, double y, double z, UUID trainId) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.trainId = trainId;
        }

        NBTTagCompound write() {
            NBTTagCompound data = new NBTTagCompound();
            data.setInteger("Version", DATA_VERSION);
            data.setInteger("Dimension", this.dimension);
            data.setDouble("X", this.x);
            data.setDouble("Y", this.y);
            data.setDouble("Z", this.z);
            data.setLong("TrainUUIDMost", this.trainId.getMostSignificantBits());
            data.setLong("TrainUUIDLeast", this.trainId.getLeastSignificantBits());
            return data;
        }
    }
}
