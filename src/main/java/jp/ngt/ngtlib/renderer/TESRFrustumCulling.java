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

package jp.ngt.ngtlib.renderer;

import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;

/** Shared, allocation-free frustum test for global TESRs. */
@SideOnly(Side.CLIENT)
public final class TESRFrustumCulling {
    private static final StageFrustum MAIN_FRUSTUM = new StageFrustum();
    private static final StageFrustum SHADOW_FRUSTUM = new StageFrustum();
    private static final boolean OPTIFINE_PRESENT;
    private static final Field OPTIFINE_SHADOW_PASS;

    static {
        boolean optiFinePresent = false;
        Field shadowPass = null;
        try {
            Class.forName("Config");
            optiFinePresent = true;
            shadowPass = Class.forName("net.optifine.shaders.Shaders").getField("isShadowPass");
        } catch (Throwable ignored) {
            // Vanilla uses only the main frustum; incompatible OptiFine versions fail open below.
        }
        OPTIFINE_PRESENT = optiFinePresent;
        OPTIFINE_SHADOW_PASS = shadowPass;
    }

    private TESRFrustumCulling() {
    }

    public static void beginFrame() {
        MAIN_FRUSTUM.requireRefresh();
        SHADOW_FRUSTUM.requireRefresh();
    }

    public static boolean isVisible(AxisAlignedBB box, BlockPos objectPos,
                                    double renderX, double renderY, double renderZ) {
        if (!isUsable(box) || objectPos == null) {
            return true;
        }
        StageFrustum stageFrustum = getCurrentFrustum();
        return stageFrustum == null || stageFrustum.isVisible(box,
                objectPos.getX() - renderX,
                objectPos.getY() - renderY,
                objectPos.getZ() - renderZ);
    }

    public static boolean isAnyVisible(AxisAlignedBB[] boxes, BlockPos objectPos,
                                       double renderX, double renderY, double renderZ) {
        if (boxes == null || boxes.length == 0 || objectPos == null) {
            return true;
        }
        StageFrustum stageFrustum = getCurrentFrustum();
        if (stageFrustum == null) {
            return true;
        }

        double cameraX = objectPos.getX() - renderX;
        double cameraY = objectPos.getY() - renderY;
        double cameraZ = objectPos.getZ() - renderZ;
        boolean tested = false;
        for (AxisAlignedBB box : boxes) {
            if (!isUsable(box)) {
                continue;
            }
            tested = true;
            if (stageFrustum.isVisible(box, cameraX, cameraY, cameraZ)) {
                return true;
            }
        }
        return !tested;
    }

    private static StageFrustum getCurrentFrustum() {
        if (!OPTIFINE_PRESENT) {
            return MAIN_FRUSTUM;
        }
        if (OPTIFINE_SHADOW_PASS == null) {
            return null;
        }
        try {
            return OPTIFINE_SHADOW_PASS.getBoolean(null) ? SHADOW_FRUSTUM : MAIN_FRUSTUM;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUsable(AxisAlignedBB box) {
        return box != null
                && !Double.isNaN(box.minX) && !Double.isNaN(box.minY) && !Double.isNaN(box.minZ)
                && !Double.isNaN(box.maxX) && !Double.isNaN(box.maxY) && !Double.isNaN(box.maxZ)
                && !Double.isInfinite(box.minX) && !Double.isInfinite(box.minY) && !Double.isInfinite(box.minZ)
                && !Double.isInfinite(box.maxX) && !Double.isInfinite(box.maxY) && !Double.isInfinite(box.maxZ)
                && box.minX <= box.maxX && box.minY <= box.maxY && box.minZ <= box.maxZ;
    }

    private static final class StageFrustum {
        private final ClippingHelper clippingHelper = new ClippingHelper();
        private final Frustum frustum = new Frustum(this.clippingHelper);
        private boolean refreshRequired = true;

        private void requireRefresh() {
            this.refreshRequired = true;
        }

        private boolean isVisible(AxisAlignedBB box, double cameraX, double cameraY, double cameraZ) {
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
            return this.frustum.isBoundingBoxInFrustum(box);
        }
    }
}
