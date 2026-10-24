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

package jp.ngt.ngtlib.event;

import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.RenderDiagnostics;
import jp.ngt.ngtlib.renderer.TESRFrustumCulling;
import jp.ngt.ngtlib.renderer.TESRRenderStats;
import jp.ngt.rtm.render.RailBuildProfiler;
import jp.ngt.rtm.render.RailResourceBuildQueue;
import jp.ngt.rtm.render.VehicleTexturePreloader;
import jp.ngt.rtm.render.VehicleFrustumCulling;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;

@SideOnly(Side.CLIENT)
public final class NGTEventHandlerClient {
    public static final NGTEventHandlerClient INSTANCE = new NGTEventHandlerClient();
    private static final long VEHICLE_STATS_INTERVAL_NANOS = 5_000_000_000L;
    private int renderFrames;
    private long nextVehicleStatsLogNanos;

    private NGTEventHandlerClient() {
    }

    @SubscribeEvent
    public void onChangeTexture(TextureStitchEvent.Post event) {
        RailResourceBuildQueue.INSTANCE.clear();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world != null) {
            for (TileEntity tileEntity : new ArrayList<>(minecraft.world.loadedTileEntityList)) {
                if (tileEntity instanceof TileEntityLargeRailCore) {
                    ((TileEntityLargeRailCore) tileEntity).markRailRenderDirty();
                }
            }
        }
        VehicleTexturePreloader.INSTANCE.clear();
        GLHelper.initGLList();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (RenderDiagnostics.ENABLED) {
                RailBuildProfiler.beginFrame();
            }
            TESRFrustumCulling.beginFrame();
            VehicleFrustumCulling.beginFrame();
            VehicleTexturePreloader.INSTANCE.processFrame();
            RailResourceBuildQueue.INSTANCE.processFrame();
        } else {
            GLHelper.processPendingDeletes();
            if (RenderDiagnostics.ENABLED) {
                RailBuildProfiler.endFrame();
                RailBuildProfiler.logIfDue(RailResourceBuildQueue.INSTANCE.getPendingCount(),
                        RailResourceBuildQueue.INSTANCE.getCompletedBytes());
            }
            if (RenderDiagnostics.ENABLED && ++this.renderFrames >= 300) {
                this.renderFrames = 0;
                TESRRenderStats.logAndReset();
                GLHelper.logResourceStats();
            }
            if (RenderDiagnostics.VEHICLE_CULLING_ENABLED) {
                long now = System.nanoTime();
                if (this.nextVehicleStatsLogNanos == 0L) {
                    this.nextVehicleStatsLogNanos = now + VEHICLE_STATS_INTERVAL_NANOS;
                } else if (now >= this.nextVehicleStatsLogNanos) {
                    this.nextVehicleStatsLogNanos = now + VEHICLE_STATS_INTERVAL_NANOS;
                    TESRRenderStats.logVehicleCullingAndReset();
                }
            }
        }
    }

	/*@SubscribeEvent
	public void onFinishRenderWorld(RenderWorldLastEvent event)
	{
		GLHelper.clearGLList();
	}*/

	/*@SubscribeEvent
	public void onOpenGui(GuiOpenEvent event)//Minecraft 830
	{
		GLHelper.clearGLList();
	}*/

    @SubscribeEvent
    public void onUnloadWorld(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            RailResourceBuildQueue.INSTANCE.clear();
            VehicleTexturePreloader.INSTANCE.clear();
            //GLHelper.clearGLList();
        }
    }
}
