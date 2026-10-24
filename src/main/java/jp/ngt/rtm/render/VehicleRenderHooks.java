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

package jp.ngt.rtm.render;

import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Completes vehicle submission when OptiFine omits an off-center vehicle chunk. */
@SideOnly(Side.CLIENT)
public final class VehicleRenderHooks {
    private static final int FALLBACK_PASS_BIT = 1 << 30;
    private static final Map<EntityVehicleBase<?>, Integer> SUBMITTED_MAIN_PASSES = new IdentityHashMap<>();
    private static final OptiFineProgramBridge OPTIFINE_PROGRAM = new OptiFineProgramBridge();

    private VehicleRenderHooks() {
    }

    static void beginFrame() {
        SUBMITTED_MAIN_PASSES.clear();
        VehicleTemporalReprojection.beginFrame();
    }

    public static void markSubmitted(EntityVehicleBase<?> vehicle,
                                     VehicleFrustumCulling.RenderStage renderStage) {
        if (vehicle == null || renderStage == VehicleFrustumCulling.RenderStage.SHADOW) {
            return;
        }
        int passBit = currentPassBit();
        Integer previous = SUBMITTED_MAIN_PASSES.get(vehicle);
        SUBMITTED_MAIN_PASSES.put(vehicle, (previous == null ? 0 : previous) | passBit);
    }

    /** Called from the transformed RenderGlobal immediately before block entities. */
    public static void renderMissingVehicles(float partialTicks) {
        if (VehicleFrustumCulling.isShadowPassActive()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || minecraft.getRenderManager() == null) {
            return;
        }

        int pass = MinecraftForgeClient.getRenderPass();
        int passBit = passBit(pass);
        ArrayList<EntityVehicleBase<?>> missing = new ArrayList<>();
        for (Entity entity : new ArrayList<>(minecraft.world.loadedEntityList)) {
            if (entity instanceof EntityVehicleBase && !entity.isDead
                    && entity.shouldRenderInPass(pass)) {
                EntityVehicleBase<?> vehicle = (EntityVehicleBase<?>) entity;
                Integer submitted = SUBMITTED_MAIN_PASSES.get(vehicle);
                if (submitted == null || (submitted & passBit) == 0) {
                    missing.add(vehicle);
                }
            }
        }
        Object previousProgram = !missing.isEmpty() && VehicleFrustumCulling.areShadersEnabled()
                ? OPTIFINE_PROGRAM.beginEntities() : null;
        try {
            RenderManager renderManager = minecraft.getRenderManager();
            for (EntityVehicleBase<?> vehicle : missing) {
                renderManager.renderEntityStatic(vehicle, partialTicks, false);
            }
        } finally {
            if (previousProgram != null) {
                OPTIFINE_PROGRAM.restore(previousProgram);
            }
        }
        VehicleTemporalReprojection.uploadFrameData();
    }

    private static int currentPassBit() {
        return passBit(MinecraftForgeClient.getRenderPass());
    }

    private static int passBit(int pass) {
        return pass >= 0 && pass < 30 ? 1 << pass : FALLBACK_PASS_BIT;
    }

    private static final class OptiFineProgramBridge {
        private final Field activeProgram;
        private final Method beginEntities;
        private final Method endEntities;
        private final Method useProgram;

        private OptiFineProgramBridge() {
            Field active = null;
            Method begin = null;
            Method end = null;
            Method use = null;
            try {
                Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
                Class<?> programClass = Class.forName("net.optifine.shaders.Program");
                active = shadersClass.getField("activeProgram");
                begin = shadersClass.getMethod("beginEntities");
                end = shadersClass.getMethod("endEntities");
                use = shadersClass.getMethod("useProgram", programClass);
            } catch (Throwable ignored) {
                // OptiFine is optional; the fixed pipeline needs no program switch.
            }
            this.activeProgram = active;
            this.beginEntities = begin;
            this.endEntities = end;
            this.useProgram = use;
        }

        private Object beginEntities() {
            if (this.activeProgram == null || this.beginEntities == null) {
                return null;
            }
            try {
                Object previous = this.activeProgram.get(null);
                this.beginEntities.invoke(null);
                return previous;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void restore(Object previousProgram) {
            try {
                if (this.endEntities != null) {
                    this.endEntities.invoke(null);
                }
                if (this.useProgram != null) {
                    this.useProgram.invoke(null, previousProgram);
                }
            } catch (Throwable ignored) {
                // RenderGlobal will select the next program at the normal stage boundary.
            }
        }
    }
}
