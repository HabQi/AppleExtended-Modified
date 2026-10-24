/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import net.minecraft.client.renderer.GlStateManager;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Client-only entry points called from transformed vanilla rendering code. */
@SideOnly(Side.CLIENT)
public final class PassengerPoseRenderHooks {
    private PassengerPoseRenderHooks() {
    }

    public static boolean applyLyingRotations(Object renderedEntity, float partialTicks) {
        if (!(renderedEntity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) renderedEntity;
        PassengerPoseState state = PassengerPoseClient.getState(player);
        if (state.getPose() == PassengerPose.SITTING) {
            GlStateManager.translate(0.0F, -0.2F, 0.0F);
            return false;
        }
        if (state.getPose() != PassengerPose.LYING) {
            return false;
        }

        EntityTrainWalkableFloor floor = PassengerPoseRenderState.getRenderingFloor(player);
        if (floor == null && player.getRidingEntity() instanceof EntityTrainWalkableFloor) {
            floor = (EntityTrainWalkableFloor) player.getRidingEntity();
        }
        if (floor == null) {
            return false;
        }

        EntityVehicleBase<?> vehicle = floor.getParentVehicle();
        Entity yawSource = vehicle == null ? floor : vehicle;
        float vehicleYaw = yawSource.prevRotationYaw
                + MathHelper.wrapDegrees(yawSource.rotationYaw - yawSource.prevRotationYaw) * partialTicks;

        double yawRadians = Math.toRadians(vehicleYaw);
        float feetOffsetX = (float) (-PassengerPoseState.LYING_HEAD_ALIGNMENT_OFFSET
                * Math.cos(yawRadians));
        float feetOffsetZ = (float) (PassengerPoseState.LYING_HEAD_ALIGNMENT_OFFSET
                * Math.sin(yawRadians));
        GlStateManager.translate(feetOffsetX, state.getHeightOffset(), feetOffsetZ);
        // After the Z rotation below, the model's body axis is vehicle-local X.
        // RTM's train length uses local Z, so applying the vehicle yaw directly
        // keeps the body perpendicular to the car. The extra 180 fixes the head
        // toward vehicle-local right without changing the perpendicular axis.
        GlStateManager.rotate(vehicleYaw + 180.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(270.0F, 0.0F, 1.0F, 0.0F);
        return true;
    }
}
