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

import com.anatawa12.fixRtm.asm.config.MainConfig;
import jp.ngt.ngtlib.math.NGTMath;
import jp.ngt.rtm.entity.train.parts.EntityVehiclePart;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import jp.ngt.rtm.modelpack.modelset.ModelSetVehicleBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/** Frustum tests for vehicle bodies rendered in the main and shadow entity stages. */
@SideOnly(Side.CLIENT)
public final class VehicleFrustumCulling {
    private static final double BOUNDS_MARGIN = 1.0D;
    private static final StageFrustum MAIN_FRUSTUM = new StageFrustum();
    private static final StageFrustum SHADOW_FRUSTUM = new StageFrustum();
    private static final Map<EntityVehicleBase<?>, AxisAlignedBB> BODY_BOUNDS = new IdentityHashMap<>();
    private static ShaderMode shaderMode = ShaderMode.UNKNOWN;

    private VehicleFrustumCulling() {
    }

    public static void beginFrame() {
        MAIN_FRUSTUM.requireRefresh();
        SHADOW_FRUSTUM.requireRefresh();
        BODY_BOUNDS.clear();
        VehicleRenderHooks.beginFrame();
        shaderMode = OptiFineAdapter.queryShaderMode();
    }

    public static boolean areShadersEnabled() {
        return shaderMode == ShaderMode.ENABLED;
    }

    public static boolean isShadowPassActive() {
        return shaderMode == ShaderMode.ENABLED
                && Boolean.TRUE.equals(OptiFineAdapter.isShadowPass());
    }

    public static BodyRenderDecision evaluateBody(EntityVehicleBase<?> vehicle, float partialTick,
                                                  double renderX, double renderY, double renderZ) {
        RenderStage renderStage = getCurrentRenderStage();
        if (!MainConfig.vehicleFrustumCulling || vehicle == null || isPlayerRidingVehicle(vehicle)) {
            return BodyRenderDecision.visible(renderStage);
        }

        StageFrustum stageFrustum = getStageFrustum(renderStage);
        if (stageFrustum == null) {
            return BodyRenderDecision.UNKNOWN_RENDERED;
        }

        AxisAlignedBB bounds = getBodyBounds(vehicle, partialTick);
        if (!isUsable(bounds)) {
            return BodyRenderDecision.visible(renderStage);
        }

        double vehicleX = interpolate(vehicle.lastTickPosX, vehicle.posX, partialTick);
        double vehicleY = interpolate(vehicle.lastTickPosY, vehicle.posY, partialTick);
        double vehicleZ = interpolate(vehicle.lastTickPosZ, vehicle.posZ, partialTick);
        try {
            boolean visible = stageFrustum.isVisible(bounds,
                    vehicleX - renderX, vehicleY - renderY, vehicleZ - renderZ);
            return BodyRenderDecision.of(renderStage, visible);
        } catch (RuntimeException ignored) {
            return BodyRenderDecision.visible(renderStage);
        }
    }

    public static boolean isPlayerRidingVehicle(EntityVehicleBase<?> vehicle) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.player;
        if (player == null || vehicle == null) {
            return false;
        }

        Entity riding = player.getRidingEntity();
        if (riding == vehicle || vehicle.isPassenger(player)) {
            return true;
        }
        return riding instanceof EntityVehiclePart
                && ((EntityVehiclePart) riding).getVehicle() == vehicle;
    }

    private static RenderStage getCurrentRenderStage() {
        if (shaderMode == ShaderMode.UNKNOWN) {
            return RenderStage.UNKNOWN;
        }
        if (shaderMode == ShaderMode.DISABLED) {
            return RenderStage.MAIN;
        }

        Boolean shadowPass = OptiFineAdapter.isShadowPass();
        if (shadowPass == null) {
            return RenderStage.UNKNOWN;
        }
        return shadowPass ? RenderStage.SHADOW : RenderStage.MAIN;
    }

    private static StageFrustum getStageFrustum(RenderStage renderStage) {
        if (renderStage == RenderStage.MAIN) {
            return MAIN_FRUSTUM;
        }
        if (renderStage == RenderStage.SHADOW) {
            return SHADOW_FRUSTUM;
        }
        return null;
    }

    private static AxisAlignedBB getBodyBounds(EntityVehicleBase<?> vehicle, float partialTick) {
        if (BODY_BOUNDS.containsKey(vehicle)) {
            return BODY_BOUNDS.get(vehicle);
        }

        AxisAlignedBB bounds = createBodyBounds(vehicle, partialTick);
        BODY_BOUNDS.put(vehicle, bounds);
        return bounds;
    }

    private static AxisAlignedBB createBodyBounds(EntityVehicleBase<?> vehicle, float partialTick) {
        try {
            if (vehicle.getNGTO() != null || vehicle.getResourceState() == null) {
                return null;
            }

            ModelSetVehicleBase<?> modelSet = vehicle.getResourceState().getResourceSet();
            if (modelSet == null || modelSet.isDummy() || modelSet.modelObj == null
                    || modelSet.modelObj.model == null || modelSet.getConfig() == null) {
                return null;
            }

            float[] size = modelSet.modelObj.model.getSize();
            float[] offset = modelSet.getConfig().offset;
            if (!isUsable(size, 6) || !isUsable(offset, 3)) {
                return null;
            }

            double worldX = interpolate(vehicle.lastTickPosX, vehicle.posX, partialTick);
            double worldY = interpolate(vehicle.lastTickPosY, vehicle.posY, partialTick)
                    + vehicle.getVehicleYOffset();
            double worldZ = interpolate(vehicle.lastTickPosZ, vehicle.posZ, partialTick);
            double yaw = Math.toRadians(vehicle.prevRotationYaw
                    + NGTMath.wrapAngle(vehicle.rotationYaw - vehicle.prevRotationYaw) * partialTick);
            double pitch = Math.toRadians(-(vehicle.prevRotationPitch
                    + (vehicle.rotationPitch - vehicle.prevRotationPitch) * partialTick));
            double roll = Math.toRadians(vehicle.prevRotationRoll
                    + (vehicle.rotationRoll - vehicle.prevRotationRoll) * partialTick);

            double sinYaw = Math.sin(yaw);
            double cosYaw = Math.cos(yaw);
            double sinPitch = Math.sin(pitch);
            double cosPitch = Math.cos(pitch);
            double sinRoll = Math.sin(roll);
            double cosRoll = Math.cos(roll);
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (int corner = 0; corner < 8; ++corner) {
                double x = size[(corner & 1) == 0 ? 0 : 3] + offset[0];
                double y = size[(corner & 2) == 0 ? 1 : 4] + offset[1];
                double z = size[(corner & 4) == 0 ? 2 : 5] + offset[2];

                double rollX = x * cosRoll - y * sinRoll;
                double rollY = x * sinRoll + y * cosRoll;
                double pitchY = rollY * cosPitch - z * sinPitch;
                double pitchZ = rollY * sinPitch + z * cosPitch;
                double rotatedX = rollX * cosYaw + pitchZ * sinYaw;
                double rotatedZ = -rollX * sinYaw + pitchZ * cosYaw;
                double rotatedY = pitchY;

                double xWorld = worldX + rotatedX;
                double yWorld = worldY + rotatedY;
                double zWorld = worldZ + rotatedZ;
                minX = Math.min(minX, xWorld);
                minY = Math.min(minY, yWorld);
                minZ = Math.min(minZ, zWorld);
                maxX = Math.max(maxX, xWorld);
                maxY = Math.max(maxY, yWorld);
                maxZ = Math.max(maxZ, zWorld);
            }

            AxisAlignedBB bounds = new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ)
                    .grow(BOUNDS_MARGIN);
            return isUsable(bounds) ? bounds : null;
        } catch (RuntimeException ignored) {
            // Model resources may change while packs are reloading; rendering is safer than a false cull.
            return null;
        }
    }

    private static boolean isUsable(float[] values, int minimumLength) {
        if (values == null || values.length < minimumLength) {
            return false;
        }
        for (int i = 0; i < minimumLength; ++i) {
            if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUsable(AxisAlignedBB bounds) {
        return bounds != null && !bounds.hasNaN()
                && isFinite(bounds.minX) && isFinite(bounds.minY) && isFinite(bounds.minZ)
                && isFinite(bounds.maxX) && isFinite(bounds.maxY) && isFinite(bounds.maxZ)
                && bounds.minX <= bounds.maxX && bounds.minY <= bounds.maxY && bounds.minZ <= bounds.maxZ;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double interpolate(double previous, double current, float partialTick) {
        return previous + (current - previous) * (double) partialTick;
    }

    private enum ShaderMode {
        DISABLED,
        ENABLED,
        UNKNOWN
    }

    public enum RenderStage {
        MAIN,
        SHADOW,
        UNKNOWN
    }

    public enum BodyRenderDecision {
        MAIN_RENDERED(RenderStage.MAIN, true),
        MAIN_CULLED(RenderStage.MAIN, false),
        SHADOW_RENDERED(RenderStage.SHADOW, true),
        SHADOW_CULLED(RenderStage.SHADOW, false),
        UNKNOWN_RENDERED(RenderStage.UNKNOWN, true);

        private final RenderStage renderStage;
        private final boolean visible;

        BodyRenderDecision(RenderStage renderStage, boolean visible) {
            this.renderStage = renderStage;
            this.visible = visible;
        }

        public RenderStage getRenderStage() {
            return this.renderStage;
        }

        public boolean isVisible() {
            return this.visible;
        }

        private static BodyRenderDecision visible(RenderStage renderStage) {
            return of(renderStage, true);
        }

        private static BodyRenderDecision of(RenderStage renderStage, boolean visible) {
            if (renderStage == RenderStage.SHADOW) {
                return visible ? SHADOW_RENDERED : SHADOW_CULLED;
            }
            if (renderStage == RenderStage.MAIN) {
                return visible ? MAIN_RENDERED : MAIN_CULLED;
            }
            return UNKNOWN_RENDERED;
        }
    }

    private static final class StageFrustum {
        private final ClippingHelper clippingHelper = new ClippingHelper();
        private final Frustum frustum = new Frustum(this.clippingHelper);
        private boolean refreshRequired = true;

        private void requireRefresh() {
            this.refreshRequired = true;
        }

        private boolean isVisible(AxisAlignedBB bounds, double cameraX, double cameraY, double cameraZ) {
            if (this.refreshRequired) {
                ClippingHelper current = ClippingHelperImpl.getInstance();
                for (int plane = 0; plane < this.clippingHelper.frustum.length; ++plane) {
                    System.arraycopy(current.frustum[plane], 0,
                            this.clippingHelper.frustum[plane], 0,
                            this.clippingHelper.frustum[plane].length);
                }
                this.refreshRequired = false;
            }
            this.frustum.setPosition(cameraX, cameraY, cameraZ);
            return this.frustum.isBoundingBoxInFrustum(bounds);
        }
    }

    private static final class OptiFineAdapter {
        private static final boolean OPTIFINE_PRESENT;
        private static final Method IS_SHADERS;
        private static final Field IS_SHADOW_PASS;

        static {
            boolean present = false;
            Method isShaders = null;
            Field isShadowPass = null;
            try {
                Class<?> configClass = Class.forName("Config");
                present = true;
                isShaders = configClass.getMethod("isShaders");
            } catch (Throwable ignored) {
                // No OptiFine, or an incompatible Config API.
            }
            if (present) {
                try {
                    Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
                    isShadowPass = shadersClass.getField("isShadowPass");
                } catch (Throwable ignored) {
                    // A missing shadow-pass flag is handled conservatively when shaders are active.
                }
            }
            OPTIFINE_PRESENT = present;
            IS_SHADERS = isShaders;
            IS_SHADOW_PASS = isShadowPass;
        }

        private static ShaderMode queryShaderMode() {
            if (!OPTIFINE_PRESENT) {
                return ShaderMode.DISABLED;
            }
            if (IS_SHADERS == null) {
                return ShaderMode.UNKNOWN;
            }
            try {
                return Boolean.TRUE.equals(IS_SHADERS.invoke(null))
                        ? ShaderMode.ENABLED : ShaderMode.DISABLED;
            } catch (Throwable ignored) {
                return ShaderMode.UNKNOWN;
            }
        }

        private static Boolean isShadowPass() {
            if (IS_SHADOW_PASS == null) {
                return null;
            }
            try {
                return IS_SHADOW_PASS.getBoolean(null);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
