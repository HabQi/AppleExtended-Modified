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

package jp.ngt.rtm.rail;

import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.RenderDiagnostics;
import jp.ngt.ngtlib.renderer.TESRFrustumCulling;
import jp.ngt.ngtlib.renderer.TESRRenderStats;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.modelpack.modelset.ModelSetRail;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.render.RailPartsRendererBase;
import jp.ngt.rtm.render.ModelObject;
import net.minecraft.entity.Entity;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

@SideOnly(Side.CLIENT)
public class RenderLargeRail extends TileEntitySpecialRenderer<TileEntityLargeRailCore> {
    public static final RenderLargeRail INSTANCE = new RenderLargeRail();
    public static final RenderRailBlock BLOCK_RENDERER = new RenderRailBlock();

    protected RenderLargeRail() {
    }

    @Override
    public void render(TileEntityLargeRailCore tileEntity, double d0, double d1, double d2, float f, int i, float alpha) {
        long start = RenderDiagnostics.ENABLED ? System.nanoTime() : 0L;
        boolean rendered = false;
        boolean culled = false;
        try {
            if (!this.canRender(tileEntity)) {
                return;
            }
            if (!TESRFrustumCulling.isAnyVisible(tileEntity.getRenderBoundingBoxes(),
                    tileEntity.getPos(), d0, d1, d2)) {
                culled = true;
                return;
            }
            this.renderTileEntityLargeRail(tileEntity, d0, d1, d2, f);
            rendered = true;
        } finally {
            if (RenderDiagnostics.ENABLED) {
                TESRRenderStats.recordRail(tileEntity, rendered, culled, System.nanoTime() - start);
            }
        }
    }

    @Override
    public final boolean isGlobalRenderer(TileEntityLargeRailCore tileEntity) {
        return true;
    }

    private boolean canRender(TileEntityLargeRailCore rail) {
        return rail != null && !rail.isInvalid() && rail.isLoaded()
                && rail.areAllRailEndpointChunksReadyToRender()
                && rail.getWorld() != null
                && rail.getWorld() == NGTUtilClient.getMinecraft().world;
    }

    private void renderTileEntityLargeRail(TileEntityLargeRailCore rail, double par2, double par4, double par6, float par8) {
        jp.apple.replaymod.compat.ReplaySyncManager.checkRailModel(rail);

        GL11.glPushMatrix();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPushAttrib(GL11.GL_LIGHTING_BIT + GL11.GL_ENABLE_BIT);

        ModelSetRail modelSet = rail.getResourceState().getResourceSet();
        if (modelSet != null && !modelSet.isDummy()) {
            GLHelper.disableLighting();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            BLOCK_RENDERER.renderRailBlocks(rail, par2, par4, par6, par8);
            GLHelper.enableLighting();
            NGTUtilClient.getMinecraft().entityRenderer.enableLightmap();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            try {
                this.renderRailSlot(rail, modelSet, 0, par2, par4, par6, par8);

                for (int i = 0; i < rail.subRails.size(); ++i) {
                    ResourceStateRail state = rail.subRails.get(i);
                    this.renderRailSlot(rail, state.getResourceSet(), i + 1, par2, par4, par6, par8);
                }
            } catch (ClassCastException e) {
                ;
            }
        }

        GL11.glPopAttrib();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glPopMatrix();
    }

    private void renderRailSlot(TileEntityLargeRailCore rail, ModelSetRail modelSet, int slot,
                                double x, double y, double z, float partialTicks) {
        Entity viewer = NGTUtilClient.getMinecraft().getRenderViewEntity();
        double distanceSq = viewer == null ? 0.0D : viewer.getDistanceSq(rail.getPos());
        ModelObject selected = modelSet.getModelForDistanceSq(distanceSq);
        RailPartsRendererBase renderer = (RailPartsRendererBase) selected.renderer;
        if (selected == modelSet.modelObj) {
            renderer.renderRail(rail, slot, x, y, z, partialTicks);
        } else {
            renderer.renderLodRail(rail, slot, x, y, z, partialTicks);
        }
    }
}
