/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketSetPassengers;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Server-side tracking and recovery events for RTM train passengers. */
public final class TrainPassengerHandler {
    @SubscribeEvent
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntityMounting() instanceof EntityPlayerMP) || event.getWorldObj().isRemote) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityMounting();
        if (event.isMounting()) {
            TrainRecoveryManager.recordBoarding(player, event.getEntityBeingMounted());
            if (event.getEntityBeingMounted() instanceof EntityTrainWalkableFloor) {
                PassengerPoseManager.clearState(player);
            }
        } else {
            if (event.getEntityBeingMounted() instanceof EntityTrainWalkableFloor) {
                PassengerPoseManager.clearState(player);
            }
            TrainRecoveryManager.clearAfterDismount(player, event.getEntityBeingMounted());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.side.isServer()
                && event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            if (PassengerPoseManager.hasState(player)
                    && !(player.getRidingEntity() instanceof EntityTrainWalkableFloor)) {
                PassengerPoseManager.clearState(player);
            }
            if (event.player.ticksExisted % 20 == 0) {
                TrainRecoveryManager.refreshSafePosition(player);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            PassengerPoseManager.clearState(player);
            TrainRecoveryManager.handleLogout(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            PassengerPoseManager.discardState(player);
            PassengerPoseManager.sendCurrentState(player, player);
            TrainRecoveryManager.handleLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(
            net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PassengerPoseManager.clearState((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PassengerPoseManager.clearState((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP) {
            PassengerPoseManager.discardState(event.getOriginal());
            PassengerPoseManager.discardState(event.getEntityPlayer());
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof EntityPlayerMP) {
            PassengerPoseManager.clearState((EntityPlayerMP) event.getEntityLiving());
        }
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP observer = (EntityPlayerMP) event.getEntityPlayer();
        Entity target = event.getTarget();
        if (target instanceof EntityPlayerMP) {
            PassengerPoseManager.sendCurrentState(observer, (EntityPlayerMP) target);
        }
        EntityTrainWalkableFloor floor = null;
        if (target instanceof EntityTrainWalkableFloor) {
            floor = (EntityTrainWalkableFloor) target;
        } else if (target.getRidingEntity() instanceof EntityTrainWalkableFloor) {
            floor = (EntityTrainWalkableFloor) target.getRidingEntity();
        }
        if (floor == null || floor.world.isRemote) {
            return;
        }

        observer.connection.sendPacket(new SPacketSetPassengers(floor));
        floor.sendInitialPassengerSnapshots(observer);
    }
}
