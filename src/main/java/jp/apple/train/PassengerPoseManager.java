/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.network.PacketPassengerPoseSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative, non-persistent passenger pose state. */
public final class PassengerPoseManager {
    private static final Map<UUID, PassengerPoseState> STATES = new HashMap<>();

    private PassengerPoseManager() {
    }

    public static void handlePoseRequest(EntityPlayerMP player, PassengerPose requestedPose) {
        if (player == null || !(player.getRidingEntity() instanceof EntityTrainWalkableFloor)) {
            return;
        }
        setState(player, getState(player).withPose(requestedPose));
    }

    public static void handleHeightRequest(EntityPlayerMP player, int delta) {
        if (player == null || !(player.getRidingEntity() instanceof EntityTrainWalkableFloor)
                || (delta != -1 && delta != 1)) {
            return;
        }
        PassengerPoseState current = getState(player);
        if (current.getPose() == PassengerPose.LYING) {
            setState(player, current.adjustHeight(delta));
        }
    }

    public static PassengerPoseState getState(EntityPlayer player) {
        PassengerPoseState state = player == null ? null : STATES.get(player.getUniqueID());
        return state == null ? PassengerPoseState.DEFAULT : state;
    }

    public static boolean hasState(EntityPlayer player) {
        return player != null && STATES.containsKey(player.getUniqueID());
    }

    private static void setState(EntityPlayerMP player, PassengerPoseState state) {
        PassengerPoseState safeState = state == null ? PassengerPoseState.DEFAULT : state;
        PassengerPoseState previous = getState(player);
        if (safeState.isDefault()) {
            STATES.remove(player.getUniqueID());
        } else {
            STATES.put(player.getUniqueID(), safeState);
        }
        if (!previous.equals(safeState)) {
            broadcast(player, safeState);
        }
    }

    public static void clearState(EntityPlayerMP player) {
        if (player != null && STATES.remove(player.getUniqueID()) != null) {
            broadcast(player, PassengerPoseState.DEFAULT);
        }
    }

    public static void discardState(EntityPlayer player) {
        if (player != null) {
            STATES.remove(player.getUniqueID());
        }
    }

    public static void sendCurrentState(EntityPlayerMP observer, EntityPlayerMP target) {
        if (observer != null && target != null) {
            RTMCore.NETWORK_WRAPPER.sendTo(
                    new PacketPassengerPoseSync(target.getUniqueID(), getState(target)), observer);
        }
    }

    private static void broadcast(EntityPlayerMP player, PassengerPoseState state) {
        if (!(player.world instanceof WorldServer)) {
            return;
        }

        PacketPassengerPoseSync packet = new PacketPassengerPoseSync(player.getUniqueID(), state);
        Set<EntityPlayerMP> recipients = new HashSet<>();
        recipients.add(player);
        for (EntityPlayer trackingPlayer
                : ((WorldServer) player.world).getEntityTracker().getTrackingPlayers(player)) {
            if (trackingPlayer instanceof EntityPlayerMP) {
                recipients.add((EntityPlayerMP) trackingPlayer);
            }
        }
        for (EntityPlayerMP recipient : recipients) {
            RTMCore.NETWORK_WRAPPER.sendTo(packet, recipient);
        }
    }
}
