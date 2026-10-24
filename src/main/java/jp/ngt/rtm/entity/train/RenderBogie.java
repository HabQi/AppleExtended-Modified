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

package jp.ngt.rtm.entity.train;

import com.anatawa12.fixRtm.asm.config.MainConfig;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.math.NGTMath;
import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.ngtlib.renderer.TESRRenderStats;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.modelpack.cfg.VehicleBaseConfig;
import jp.ngt.rtm.modelpack.modelset.ModelSetTrain;
import jp.ngt.rtm.render.VehicleTexturePreloader;
import jp.ngt.rtm.render.VehicleFrustumCulling;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.HashMap;
import java.util.Map;

@SideOnly(Side.CLIENT)
public final class RenderBogie extends Render<EntityBogie> {
    private static final long MISSING_LOG_INTERVAL_TICKS = 20L;
    private static final long ACTIVE_LOG_INTERVAL_TICKS = 100L;
    private final Map<Integer, Long> missingLogTicks = new HashMap<>();
    private final Map<Integer, Long> waitingLogTicks = new HashMap<>();
    private final Map<Integer, Long> activeLogTicks = new HashMap<>();

    public RenderBogie(RenderManager renderManager) {
        super(renderManager);
    }

    private final void renderBogie(EntityBogie bogie, double par2, double par4, double par6, float entityYaw, float partialTick) {
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        //台車は線形補間したほうが滑らか(1.12
        //partialTickはMinecraft.timerで算出

        double x = par2;
        double y = par4;
        double z = par6;
        if (bogie.getTrain() != null) {
            EntityTrainBase train = bogie.getTrain();
            //RenderMngで足されてる補完値を引く
            double bogieFX = bogie.lastTickPosX + (bogie.posX - bogie.lastTickPosX) * (double) partialTick;
            double bogieFY = bogie.lastTickPosY + (bogie.posY - bogie.lastTickPosY) * (double) partialTick;
            double bogieFZ = bogie.lastTickPosZ + (bogie.posZ - bogie.lastTickPosZ) * (double) partialTick;

            float[][] pos = train.getResourceState().getResourceSet().getConfig().getBogiePos();
            int bogieIndex = bogie.getBogieId();
            Vec3 v31 = new Vec3(pos[bogieIndex][0], pos[bogieIndex][1], pos[bogieIndex][2]);
            v31 = v31.rotateAroundX(train.prevRotationPitch + NGTMath.wrapAngle(train.rotationPitch - train.prevRotationPitch) * partialTick);
            v31 = v31.rotateAroundY(train.prevRotationYaw + NGTMath.wrapAngle(train.rotationYaw - train.prevRotationYaw) * partialTick);
            double newX = v31.getX() + (train.lastTickPosX + ((train.posX - train.lastTickPosX) * partialTick));
            double newY = v31.getY() + (train.lastTickPosY + ((train.posY - train.lastTickPosY) * partialTick));
            double newZ = v31.getZ() + (train.lastTickPosZ + ((train.posZ - train.lastTickPosZ) * partialTick));
            x = par2 - bogieFX + newX;
            y = par4 - bogieFY + newY;
            z = par6 - bogieFZ + newZ;
        }

        //NGTLog.debug("(R) %d x%5.1f z%5.1f", bogie.getEntityId(), x, z);

        GL11.glTranslatef((float) x, (float) y + EntityTrainBase.TRAIN_HEIGHT, (float) z);
        float yaw = bogie.prevRotationYaw + NGTMath.wrapAngle(bogie.rotationYaw - bogie.prevRotationYaw) * partialTick;
        GL11.glRotatef(yaw, 0.0F, 1.0F, 0.0F);
        float pitch = bogie.prevRotationPitch + (bogie.rotationPitch - bogie.prevRotationPitch) * partialTick;
        GL11.glRotatef(-pitch, 1.0F, 0.0F, 0.0F);
        float roll = bogie.prevRotationRoll + (bogie.rotationRoll - bogie.prevRotationRoll) * partialTick;
        GL11.glRotatef(roll, 0.0F, 0.0F, 1.0F);

        byte index = bogie.getBogieId();
        boolean flag = true;
        EntityTrainBase renderTrain = bogie.getTrain();
        ModelSetTrain modelset = null;
        if (renderTrain != null) {
            modelset = renderTrain.getResourceState().getResourceSet();
            if (!modelset.isDummy()) {
                flag = false;
                if (VehicleTexturePreloader.INSTANCE.ensureReady(modelset)) {
                    VehicleBaseConfig cfg = modelset.getConfig();
                    modelset.bogieModels[index].render(bogie, cfg, 0, partialTick);
                    this.logActiveBogie(bogie, renderTrain);
                } else {
                    this.logWaitingBogie(bogie, renderTrain, modelset);
                }
            }
        }

        if (flag) {
            this.logMissingBogie(bogie, renderTrain, modelset);
            RTMCore.proxy.renderMissingModel();
        }

        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();
    }

    private void logMissingBogie(EntityBogie bogie, EntityTrainBase linkedTrain, ModelSetTrain modelset) {
        long currentTick = bogie.world.getTotalWorldTime();
        Long lastLogTick = this.missingLogTicks.get(bogie.getEntityId());
        if (lastLogTick != null && currentTick >= lastLogTick
                && currentTick - lastLogTick < MISSING_LOG_INTERVAL_TICKS) {
            return;
        }
        this.missingLogTicks.put(bogie.getEntityId(), currentTick);

        int trainId = bogie.getDataManager().get(EntityBogie.TRAIN_ID);
        Entity target = bogie.world.getEntityByID(trainId);
        String targetInfo = target == null
                ? "null"
                : target.getClass().getName() + "#" + target.getEntityId() + "(dead=" + target.isDead + ")";
        String linkedTrainInfo = linkedTrain == null
                ? "null"
                : linkedTrain.getClass().getName() + "#" + linkedTrain.getEntityId()
                + "(dead=" + linkedTrain.isDead + ")";
        String modelName = linkedTrain == null
                ? "<no-train>"
                : linkedTrain.getResourceState().getResourceName();
        String modelDummy = modelset == null ? "n/a" : Boolean.toString(modelset.isDummy());

        NGTLog.debug(
                "[BogieRenderMissing] bogie=%d bogieIndex=%d trainId=%d target=%s linkedTrain=%s model=%s dummy=%s pos=(%.2f,%.2f,%.2f)",
                bogie.getEntityId(), bogie.getBogieId(), trainId, targetInfo, linkedTrainInfo,
                modelName, modelDummy, bogie.posX, bogie.posY, bogie.posZ);
    }

    private void logWaitingBogie(EntityBogie bogie, EntityTrainBase linkedTrain, ModelSetTrain modelset) {
        long currentTick = bogie.world.getTotalWorldTime();
        Long lastLogTick = this.waitingLogTicks.get(bogie.getEntityId());
        if (lastLogTick != null && currentTick >= lastLogTick
                && currentTick - lastLogTick < MISSING_LOG_INTERVAL_TICKS) {
            return;
        }
        this.waitingLogTicks.put(bogie.getEntityId(), currentTick);

        NGTLog.debug(
                "[BogieRenderWaiting] bogie=%d bogieIndex=%d train=%d model=%s bogiePos=(%.2f,%.2f,%.2f) trainPos=(%.2f,%.2f,%.2f)",
                bogie.getEntityId(), bogie.getBogieId(), linkedTrain.getEntityId(),
                linkedTrain.getResourceState().getResourceName(), bogie.posX, bogie.posY, bogie.posZ,
                linkedTrain.posX, linkedTrain.posY, linkedTrain.posZ);
    }

    private void logActiveBogie(EntityBogie bogie, EntityTrainBase linkedTrain) {
        long currentTick = bogie.world.getTotalWorldTime();
        Long lastLogTick = this.activeLogTicks.get(bogie.getEntityId());
        if (lastLogTick != null && currentTick >= lastLogTick
                && currentTick - lastLogTick < ACTIVE_LOG_INTERVAL_TICKS) {
            return;
        }
        this.activeLogTicks.put(bogie.getEntityId(), currentTick);

        NGTLog.debug(
                "[BogieRenderActive] bogie=%d bogieIndex=%d train=%d model=%s bogiePos=(%.2f,%.2f,%.2f) trainPos=(%.2f,%.2f,%.2f) distance=%.2f",
                bogie.getEntityId(), bogie.getBogieId(), linkedTrain.getEntityId(),
                linkedTrain.getResourceState().getResourceName(), bogie.posX, bogie.posY, bogie.posZ,
                linkedTrain.posX, linkedTrain.posY, linkedTrain.posZ,
                Math.sqrt(bogie.getDistanceSq(linkedTrain)));
    }

    @Override
    public void doRender(EntityBogie par1, double par2, double par4, double par6, float par8, float par9) {
        if (!par1.isCurrentClientInstance()) {
            return;
        }
        this.renderBogie(par1, par2, par4, par6, par8, par9);
    }

    @Override
    public boolean shouldRender(EntityBogie bogie, ICamera camera, double camX, double camY, double camZ) {
        if (!bogie.isCurrentClientInstance()) {
            TESRRenderStats.recordBogie(false, true);
            return false;
        }

        boolean visible;
        if (!MainConfig.vehicleFrustumCulling) {
            visible = bogie.isInRangeToRender3d(camX, camY, camZ);
        } else {
            EntityTrainBase train = bogie.getTrain();
            if (train != null && VehicleFrustumCulling.isPlayerRidingVehicle(train)) {
                visible = true;
            } else {
                AxisAlignedBB bounds = bogie.getEntityBoundingBox();
                if (camera == null || !isUsable(bounds)) {
                    visible = true;
                } else {
                    visible = bogie.isInRangeToRender3d(camX, camY, camZ)
                            && camera.isBoundingBoxInFrustum(bounds.grow(0.75D, 0.75D, 0.75D));
                }
            }
        }
        TESRRenderStats.recordBogie(visible, !visible);
        return visible;
    }

    private static boolean isUsable(AxisAlignedBB bounds) {
        return bounds != null && !bounds.hasNaN()
                && !Double.isInfinite(bounds.minX) && !Double.isInfinite(bounds.minY)
                && !Double.isInfinite(bounds.minZ) && !Double.isInfinite(bounds.maxX)
                && !Double.isInfinite(bounds.maxY) && !Double.isInfinite(bounds.maxZ);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityBogie entity) {
        return new ResourceLocation("textures/train/bogie.png");
    }
}
