/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/** Per-render context shared with the walkable-floor sitting decision and ASM hook. */
public final class PassengerPoseRenderState {
    private static final ThreadLocal<Deque<Context>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private PassengerPoseRenderState() {
    }

    public static void begin(EntityPlayer player, EntityTrainWalkableFloor floor,
                             PassengerPoseState state) {
        CONTEXTS.get().push(new Context(player.getUniqueID(), floor, state));
    }

    public static void end(EntityPlayer player) {
        Deque<Context> contexts = CONTEXTS.get();
        UUID playerId = player.getUniqueID();
        for (Iterator<Context> iterator = contexts.iterator(); iterator.hasNext();) {
            if (iterator.next().playerId.equals(playerId)) {
                iterator.remove();
                break;
            }
        }
        if (contexts.isEmpty()) {
            CONTEXTS.remove();
        }
    }

    public static PassengerPoseState getRenderingState(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        for (Context context : CONTEXTS.get()) {
            if (context.playerId.equals(playerId)) {
                return context.state;
            }
        }
        return null;
    }

    public static EntityTrainWalkableFloor getRenderingFloor(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        for (Context context : CONTEXTS.get()) {
            if (context.playerId.equals(playerId)) {
                return context.floor;
            }
        }
        return null;
    }

    public static boolean shouldRiderSit(Entity floor) {
        Context context = CONTEXTS.get().peek();
        return context != null && context.floor == floor
                && context.state.getPose() == PassengerPose.SITTING;
    }

    public static void clear() {
        CONTEXTS.remove();
    }

    private static final class Context {
        final UUID playerId;
        final EntityTrainWalkableFloor floor;
        final PassengerPoseState state;

        Context(UUID playerId, EntityTrainWalkableFloor floor, PassengerPoseState state) {
            this.playerId = playerId;
            this.floor = floor;
            this.state = state;
        }
    }
}
