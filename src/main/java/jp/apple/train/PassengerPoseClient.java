/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client cache for poses received from the server. */
public final class PassengerPoseClient {
    public static final PassengerPoseClient INSTANCE = new PassengerPoseClient();
    private static final Map<UUID, PassengerPoseState> STATES = new ConcurrentHashMap<>();

    private PassengerPoseClient() {
    }

    public static void apply(UUID playerId, PassengerPoseState state) {
        PassengerPoseState safeState = state == null ? PassengerPoseState.DEFAULT : state;
        if (safeState.isDefault()) {
            STATES.remove(playerId);
        } else {
            STATES.put(playerId, safeState);
        }
    }

    public static PassengerPoseState getState(EntityPlayer player) {
        PassengerPoseState renderingState = PassengerPoseRenderState.getRenderingState(player);
        if (renderingState != null) {
            return renderingState;
        }
        if (!(player.getRidingEntity() instanceof EntityTrainWalkableFloor)) {
            return PassengerPoseState.DEFAULT;
        }
        PassengerPoseState state = STATES.get(player.getUniqueID());
        return state == null ? PassengerPoseState.DEFAULT : state;
    }

    public static PassengerPose getPose(EntityPlayer player) {
        return getState(player).getPose();
    }

    public static void clear() {
        STATES.clear();
        PassengerPoseRenderState.clear();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        clear();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote) {
            clear();
        }
    }
}
