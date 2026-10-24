/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.entity.train.parts;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The walkable carrier is an invisible server/client entity. */
@SideOnly(Side.CLIENT)
public final class RenderTrainWalkableFloor extends Render<EntityTrainWalkableFloor> {
    public RenderTrainWalkableFloor(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(EntityTrainWalkableFloor entity, double x, double y, double z, float yaw, float partialTicks) {
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTrainWalkableFloor entity) {
        return null;
    }
}
