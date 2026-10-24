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

package jp.ngt.rtm.event;

import jp.apple.train.PassengerPose;
import jp.apple.train.PassengerPoseClient;
import jp.apple.train.PassengerPoseState;
import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.entity.train.parts.EntityArtillery;
import jp.ngt.rtm.entity.train.parts.EntityFloor;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.entity.train.parts.EntityVehiclePart;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import jp.ngt.rtm.network.PacketTrainWalkInput;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.Display;

import java.util.ArrayList;

/**
 * FMLのイベント
 */
@SideOnly(Side.CLIENT)
public final class RTMTickHandlerClient {
    private long trainWalkSequence;
    private EntityPlayer renderPositionPlayer;
    private double renderPosX, renderPosY, renderPosZ;
    private double renderPrevPosX, renderPrevPosY, renderPrevPosZ;
    private double renderLastTickPosX, renderLastTickPosY, renderLastTickPosZ;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderTickStart(RenderTickEvent event) {
        if (event.phase == Phase.START) {
            this.anchorFirstPersonCamera(event.renderTickTime);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTickRestore(RenderTickEvent event) {
        if (event.phase == Phase.END) {
            this.restoreFirstPersonCameraAnchor();
        }
    }

    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event)//Minecraft.runGameLoop()
    {
        if (event.phase == Phase.END && NGTUtilClient.getMinecraft().inGameHasFocus && Display.isActive()) {
            EntityPlayer player = NGTUtilClient.getMinecraft().player;
            if (player != null && player.isRiding() && player.getRidingEntity() instanceof EntityArtillery) {
                ((EntityArtillery) player.getRidingEntity()).updateYawAndPitch(player);
            }
        }
    }

    private void anchorFirstPersonCamera(float partialTick) {
        this.restoreFirstPersonCameraAnchor();

        EntityPlayer player = NGTUtilClient.getMinecraft().player;
        if (player == null || NGTUtilClient.getMinecraft().getRenderViewEntity() != player
                || NGTUtilClient.getMinecraft().gameSettings.thirdPersonView != 0) {
            return;
        }

        EntityVehicleBase<?> vehicle = this.getRiddenVehicle(player);
        if (vehicle == null || vehicle.isDead || vehicle.world != player.world) {
            return;
        }

        Entity riding = player.getRidingEntity();
        Vec3 local;
        if (riding == vehicle) {
            local = vehicle.getRiderPos(player);
        } else if (riding instanceof EntityTrainWalkableFloor) {
            local = ((EntityTrainWalkableFloor) riding)
                    .getInterpolatedPassengerLocalPosition(player, partialTick);
        } else if (riding instanceof EntityFloor) {
            EntityFloor floor = (EntityFloor) riding;
            local = floor.getPartVec().add(
                    0.0D, floor.getMountedYOffset() + player.getYOffset(), 0.0D);
        } else {
            local = null;
        }

        // Keep specialized vehicle parts on their existing positioning path.
        if (local == null) {
            return;
        }

        float eyeHeight = player.getEyeHeight();
        Vec3 cameraEye = vehicle.interpolatedLocalToWorld(local.add(0.0D, eyeHeight, 0.0D), partialTick);
        double poseCameraOffset = 0.0D;
        if (riding instanceof EntityTrainWalkableFloor) {
            PassengerPoseState poseState = PassengerPoseClient.getState(player);
            if (poseState.getPose() == PassengerPose.SITTING) {
                poseCameraOffset = -0.2D;
            } else if (poseState.getPose() == PassengerPose.LYING) {
                poseCameraOffset = poseState.getHeightOffset()
                        - PassengerPoseState.LYING_CAMERA_HEIGHT_CORRECTION;
            }
        }
        Vec3 cameraBase = cameraEye.add(0.0D, -eyeHeight + poseCameraOffset, 0.0D);

        this.renderPositionPlayer = player;
        this.renderPosX = player.posX;
        this.renderPosY = player.posY;
        this.renderPosZ = player.posZ;
        this.renderPrevPosX = player.prevPosX;
        this.renderPrevPosY = player.prevPosY;
        this.renderPrevPosZ = player.prevPosZ;
        this.renderLastTickPosX = player.lastTickPosX;
        this.renderLastTickPosY = player.lastTickPosY;
        this.renderLastTickPosZ = player.lastTickPosZ;
        player.posX = player.prevPosX = player.lastTickPosX = cameraBase.getX();
        player.posY = player.prevPosY = player.lastTickPosY = cameraBase.getY();
        player.posZ = player.prevPosZ = player.lastTickPosZ = cameraBase.getZ();
    }

    private void restoreFirstPersonCameraAnchor() {
        if (this.renderPositionPlayer != null) {
            this.renderPositionPlayer.posX = this.renderPosX;
            this.renderPositionPlayer.posY = this.renderPosY;
            this.renderPositionPlayer.posZ = this.renderPosZ;
            this.renderPositionPlayer.prevPosX = this.renderPrevPosX;
            this.renderPositionPlayer.prevPosY = this.renderPrevPosY;
            this.renderPositionPlayer.prevPosZ = this.renderPrevPosZ;
            this.renderPositionPlayer.lastTickPosX = this.renderLastTickPosX;
            this.renderPositionPlayer.lastTickPosY = this.renderLastTickPosY;
            this.renderPositionPlayer.lastTickPosZ = this.renderLastTickPosZ;
            this.renderPositionPlayer = null;
        }
    }

    private EntityVehicleBase<?> getRiddenVehicle(EntityPlayer player) {
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityVehicleBase) {
            return (EntityVehicleBase<?>) riding;
        }
        if (riding instanceof EntityVehiclePart) {
            return ((EntityVehiclePart) riding).getParentVehicle();
        }
        return null;
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent event)//runGameLoop()内で複数回呼ばれる
    {
        World world = NGTUtilClient.getMinecraft().world;
        if (!NGTUtilClient.getMinecraft().isGamePaused() && world != null) {
            if (event.phase == Phase.START) {
				/*if(!RenderMirror.INSTANCE.finishRender)
				{
					RenderMirror.INSTANCE.update();
				}*/
                //RTMCore.proxy.getFormationManager().updateFormations(world);

                this.captureTrainWalkInterpolationState(world);
                RTMKeyHandlerClient.INSTANCE.onTickStart();
            } else if (event.phase == Phase.END) {
                this.synchronizeVehiclePassengers(world);
                RTMKeyHandlerClient.INSTANCE.onTickEnd();
            }
        }
    }

    private void synchronizeVehiclePassengers(World world) {
        for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
            if (entity instanceof EntityVehicleBase && !entity.isDead) {
                ((EntityVehicleBase<?>) entity).synchronizeClientPassengerPositions();
            } else if (entity instanceof EntityTrainWalkableFloor && !entity.isDead
                    && !entity.getPassengers().isEmpty()) {
                ((EntityTrainWalkableFloor) entity).synchronizeClientPassengerPositions();
            }
        }
    }

    private void captureTrainWalkInterpolationState(World world) {
        for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
            if (entity instanceof EntityTrainWalkableFloor && !entity.isDead) {
                ((EntityTrainWalkableFloor) entity).captureClientPassengerInterpolationState();
            }
        }
    }

    /**
     * Captures train-walking input after Minecraft refreshes the keyboard state,
     * then consumes the horizontal input before EntityLivingBase.travel runs.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTrainWalkInput(InputUpdateEvent event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerSP)
                || !(event.getEntityPlayer().getRidingEntity() instanceof EntityTrainWalkableFloor)) {
            return;
        }

        EntityPlayerSP player = (EntityPlayerSP) event.getEntityPlayer();
        EntityTrainWalkableFloor carrier = (EntityTrainWalkableFloor) player.getRidingEntity();
        MovementInput movementInput = event.getMovementInput();
        float strafe = movementInput.moveStrafe;
        float forward = movementInput.moveForward;
        float[] localMovement = carrier.getLocalMovementInput(player, strafe, forward);
        if (Math.abs(localMovement[0]) > 1.0E-5F || Math.abs(localMovement[1]) > 1.0E-5F) {
            long sequence = ++this.trainWalkSequence;
            carrier.applyClientInput(player, localMovement[0], localMovement[1], sequence);
            RTMCore.NETWORK_WRAPPER.sendToServer(new PacketTrainWalkInput(
                    carrier.getEntityId(), localMovement[0], localMovement[1], sequence));
        }

        // A riding EntityPlayerSP still executes EntityLivingBase.travel. If
        // these values remain set, vanilla first walks the player in world
        // space and the carrier then moves it back to its predicted local
        // position, producing the visible forward/back oscillation.
        movementInput.moveStrafe = 0.0F;
        movementInput.moveForward = 0.0F;
        player.moveStrafing = 0.0F;
        player.moveForward = 0.0F;
    }
}
