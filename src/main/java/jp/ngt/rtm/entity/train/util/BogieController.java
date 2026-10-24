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

package jp.ngt.rtm.entity.train.util;

import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.math.NGTMath;
import jp.ngt.ngtlib.math.PooledVec3;
import jp.ngt.ngtlib.math.Vec3;
import jp.ngt.rtm.entity.train.EntityBogie;
import jp.ngt.rtm.entity.train.EntityTrainBase;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public final class BogieController {
    private EntityBogie[] bogies = new EntityBogie[2];

    public EntityBogie getBogie(EntityTrainBase train, int bogieId) {
        if (train.world.isRemote) {
            this.setupBogieOnClient(train, bogieId);
        } else if (this.bogies[bogieId] == null) {
            this.setupBogieOnServer(train, bogieId);
        }
        return this.bogies[bogieId];
    }

    private EntityBogie getBogie(EntityTrainBase train, boolean isFront) {
        if (train == null) {
            return null;
        }
        EntityBogie first = this.getBogie(train, 0);
        if (first == null) {
            return null;
        }
        boolean b0 = first.isFront();
        return !(b0 ^ isFront) ? first : this.getBogie(train, 1);
    }

    /**
     * ClientではDWからの取得のみ
     */
    @SideOnly(Side.CLIENT)
    private boolean setupBogieOnClient(EntityTrainBase train, int bogieId) {
        Entity entity = train.world.getEntityByID(train.getBogieEntityId(bogieId));
        if (entity instanceof EntityBogie && !entity.isDead) {
            EntityBogie currentBogie = (EntityBogie) entity;
            int trainId = currentBogie.getDataManager().get(EntityBogie.TRAIN_ID);
            if (currentBogie.getBogieId() != bogieId || trainId != train.getEntityId()) {
                this.bogies[bogieId] = null;
                return false;
            }

            EntityBogie cachedBogie = this.bogies[bogieId];
            if (cachedBogie != null && cachedBogie != currentBogie) {
                boolean oldDead = cachedBogie.isDead;
                int oldIdentity = System.identityHashCode(cachedBogie);
                int newIdentity = System.identityHashCode(currentBogie);
                cachedBogie.discardClientDuplicate();
                NGTLog.debug(
                        "[BogieCacheRefreshed] train=%d bogieIndex=%d oldBogie=%d oldIdentity=%d oldDead=%s oldDeadAfterDiscard=%s newBogie=%d newIdentity=%d",
                        train.getEntityId(), bogieId, cachedBogie.getEntityId(), oldIdentity, oldDead,
                        cachedBogie.isDead, currentBogie.getEntityId(), newIdentity);
            }
            this.bogies[bogieId] = currentBogie;
            return true;
        }

        this.bogies[bogieId] = null;
        return false;
    }

    /**
     * Serverではスポーン処理等
     */
    private boolean setupBogieOnServer(EntityTrainBase train, int bogieId) {
        float trainYaw = NGTMath.wrapAngle(train.rotationYaw);
        float bogieYaw = (bogieId == 0 ? trainYaw : NGTMath.wrapAngle(trainYaw + 180.0F));
        float[][] bPosArray = train.getResourceState().getResourceSet().getConfig().getBogiePos();
        float bPos = bPosArray[bogieId][2];
        double x = train.posX + NGTMath.sin(trainYaw) * bPos;
        double y = train.posY;
        double z = train.posZ + NGTMath.cos(trainYaw) * bPos;

        if (train.world.isBlockLoaded(new BlockPos(x, y, z))) {
            EntityBogie bogie = new EntityBogie(train.world, (byte) bogieId, train);
            bogie.setPositionAndRotation(x, y, z, bogieYaw, 0.0F);
            boolean isFront = (train.getTrainDirection() == bogieId);
            bogie.setFront(isFront);
            bogie.setTrain(train);
            if (train.world.spawnEntity(bogie)) {
                train.setBogieEntityId(bogieId, bogie.getEntityId());
                this.bogies[bogieId] = bogie;
                NGTLog.debug("[BC]Spawn bogie on Server : %.0f %.0f", bogie.posX, bogie.posZ);//debug
                return true;
            }
        }
        return false;
    }

    /**
     * 各台車のonUpdate()を呼ぶ
     */
    public void updateBogies(EntityTrainBase train) {
        this.updateBogie(train, 0);
        this.updateBogie(train, 1);
    }

    private void updateBogie(EntityTrainBase train, int id) {
        EntityBogie bogie = this.getBogie(train, id);
        if (bogie != null && !bogie.isDead) {
            bogie.onBogieUpdate();
        }
    }

    public void setDead(EntityTrainBase train) {
        if (this.getBogie(train, 0) != null) {
            this.getBogie(train, 0).setDead();
            this.bogies[0] = null;
        }

        if (this.getBogie(train, 1) != null) {
            this.getBogie(train, 1).setDead();
            this.bogies[1] = null;
        }
    }

    /**
     * Trainから呼び出し、車両を移動する (ServerOnly)
     */
    public MotionState moveTrainWithBogie(EntityTrainBase train, EntityTrainBase prevTrain, float speed, boolean forceMove) {
        if (speed == 0.0F && !forceMove) {
            /**停車中に台車位置を調整*/
            this.updateBogiePos(train, 0, UpdateFlag.NONE);
            this.updateBogiePos(train, 1, UpdateFlag.NONE);
            return MotionState.STOP;
        } else {
            // The moving train owns the bogies being advanced. The previous
            // train is only the coupling reference and is null for the first
            // car in a formation.
            EntityBogie frontBogie = this.getBogie(train, true);
            EntityBogie backBogie = this.getBogie(train, false);
            if (frontBogie == null || backBogie == null) {
                return MotionState.STOP;
            }
            float[][] bogiePos = train.getResourceState().getResourceSet().getConfig().getBogiePos();
            float lengthF = bogiePos[0][2];
            float lengthB = bogiePos[1][2];
            float trainLength = MathHelper.abs(lengthF - lengthB);

            MotionState updateFlagFront = MotionState.NULL;
            MotionState updateFlagBack = MotionState.NULL;

            if (prevTrain == null) {
                //先頭車の前台車
                updateFlagFront = frontBogie.updateBogiePos(speed, 0.0F, null);
            } else {
                //先頭車以外の前台車
                //前車両の後台車との距離を保つように更新
                float[][] bogiePos2 = prevTrain.getResourceState().getResourceSet().getConfig().getBogiePos();
                double disTrain = train.getDefaultDistanceToConnectedTrain(prevTrain);
                double lenBF = Math.abs(bogiePos2[1 - prevTrain.getTrainDirection()][2]);
                double lenBB = Math.abs(bogiePos[train.getTrainDirection()][2]);
                float disBogie = (float) (disTrain - lenBF - lenBB);
                EntityBogie prevBogie = prevTrain.getBogie(1 - prevTrain.getTrainDirection());
                if (prevBogie == null) {
                    return MotionState.STOP;
                }
                updateFlagFront = frontBogie.updateBogiePos(speed, disBogie, prevBogie);
            }

            if (updateFlagFront == MotionState.MOVE) {
                //全ての後台車
                //同じ車両の前台車との距離を保つように更新
                updateFlagBack = backBogie.updateBogiePos(speed, trainLength, frontBogie);
                if (updateFlagBack == MotionState.MOVE) {
                    this.updateTrainPos(train, lengthF, lengthB);
                    return MotionState.MOVE;
                }
            }

            if (updateFlagFront == MotionState.FLY || updateFlagBack == MotionState.FLY) {
                return MotionState.FLY;
            }

            return MotionState.STOP;
        }
    }

	/*@Deprecated
	private void errorLog(EntityTrainBase train, double px, double py, double pz)
	{
		if(train.getFirstPassenger() instanceof EntityPlayer)
		{
			int x = MathHelper.floor_double(px);
			int y = MathHelper.floor_double(py);
        	int z = MathHelper.floor_double(pz);
    		NGTLog.sendChatMessage((EntityPlayer)train.getFirstPassenger(), "Rail not found > x:%s y:%s z:%s", x, y, z);
		}
	}*/

    /**
     * 台車2つを元に車体位置を更新
     */
    private void updateTrainPos(EntityTrainBase train, float lf, float lb) {
        //車体長分の先頭側台車の位置
        double d0 = Math.abs(lf) / (Math.abs(lf - lb));
        double[] fp = this.getBogie(train, 0).getPosBuf();
        double[] bp = this.getBogie(train, 1).getPosBuf();

        double x = fp[0] + (bp[0] - fp[0]) * d0;
        double y = (fp[1] + bp[1]) * 0.5D;
        double z = fp[2] + (bp[2] - fp[2]) * d0;

        double x0 = fp[0] - bp[0];
        double y0 = fp[1] - bp[1];
        double z0 = fp[2] - bp[2];
        float yaw = NGTMath.wrapAngle((float) NGTMath.toDegrees(Math.atan2(x0, z0)));
        float pitch = NGTMath.wrapAngle((float) NGTMath.toDegrees(Math.atan2(y0, Math.sqrt(x0 * x0 + z0 * z0))));
        float roll = (this.getBogie(train, 0).rotationRoll + -(this.getBogie(train, 1).rotationRoll)) * 0.5F;

        //カント分車体をずらす
        Vec3 vec = PooledVec3.create(0.0D, EntityTrainBase.TRAIN_HEIGHT, 0.0D);
        vec = vec.rotateAroundZ(-roll);
        //vec = vec.rotateAroundX(NGTMath.toRadians(pitch));
        vec = vec.rotateAroundY(yaw);
        x += vec.getX();
        y += vec.getY() - EntityTrainBase.TRAIN_HEIGHT;
        z += vec.getZ();

        train.setPositionAndRotation(x, y, z, yaw, pitch);
        train.updateRoll(roll);

        this.updateBogiePos(train, 0, UpdateFlag.ALL);
        this.updateBogiePos(train, 1, UpdateFlag.ALL);

        //double disF = this.getBogie(0).getDistance(fp[0], fp[1], fp[2]);
        //double disB = this.getBogie(1).getDistance(bp[0], bp[1], bp[2]);
        //NGTLog.debug("F:%4.3f, B:%4.3f, s:%4.3f", disF, disB, train.getSpeed());
        //NGTLog.debug("x:%6.2f, z:%6.2f, %d", fp[0], fp[2], (int)(train.worldObj.getTotalWorldTime() % 20));
    }

    /**
     * 車体位置を元に台車位置を更新<br>
     * 台車位置を再計算することで、車体とのずれを解消する
     */
    public void updateBogiePos(EntityTrainBase train, int bogieIndex, UpdateFlag flag) {
        float[][] pos = train.getResourceState().getResourceSet().getConfig().getBogiePos();
        Vec3 v31 = PooledVec3.create(pos[bogieIndex][0], pos[bogieIndex][1], pos[bogieIndex][2]);
        v31 = v31.rotateAroundX(train.rotationPitch);
        v31 = v31.rotateAroundY(train.rotationYaw);
        this.getBogie(train, bogieIndex).moveBogie(train, train.posX + v31.getX(), train.posY + v31.getY(), train.posZ + v31.getZ(), flag);
    }

    /**
     * 列車の移動状態
     */
    public enum MotionState {
        MOVE,
        STOP,
        FLY,
        NULL;
    }

    public enum UpdateFlag {
        /**
         * 全Rotationを更新
         */
        ALL,
        /**
         * Yawのみ更新、転車台で使用
         */
        YAW,
        /**
         * Rotation更新なし
         */
        NONE;
    }
}
