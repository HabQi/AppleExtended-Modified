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

package jp.ngt.rtm.modelpack.cfg;

public class TrainConfig extends VehicleBaseConfig implements IConfigWithType {
    /** Optional rectangular passenger walk area in train-local coordinates. */
    public WalkableConfig walkable = new WalkableConfig();

    private String trainName;
    private String trainType;

    private ModelSource trainModel2;
    private ModelSource[] bogieModel3;

    @Deprecated
    private ModelSource bogieModel2;
    @Deprecated
    private String trainModel;
    @Deprecated
    private String bogieModel;
    @Deprecated
    private String trainTexture;
    @Deprecated
    private String bogieTexture;

    public String sound_BrakeRelease;
    public String sound_BrakeRelease2;
    public boolean muteJointSound;
    public float[][] jointDelay;

    public boolean isSingleTrain;

    private float[][] bogiePos;
    public float trainDistance;
    /**
     * 加速度<br>
     * N km/h/s -> N x 0.0006944
     */
    public float accelerateion;

    /**
     * ノッチごとの速度上限(1~5段)
     */
    public float[] maxSpeed;

    public float rolling;

    public float[][] pantoPos;

    public float rollSpeedCoefficient;
    public float rollVariationCoefficient;
    public float rollWidthCoefficient;

    public float[] accelerateions;
    public float[] deccelerations;

    public boolean useVariableAcceleration;
    public boolean useVariableDeceleration;

    @Override
    public void init() {
        super.init();

        if (this.walkable == null) {
            this.walkable = new WalkableConfig();
        }
        this.walkable.init();

        if (this.trainModel2 == null) {
            this.trainModel2 = new ModelSource();
            this.trainModel2.modelFile = this.trainModel;
            this.trainModel2.textures = new String[][]{{"default", this.trainTexture}};
        }

        if (this.bogieModel3 == null) {
            this.bogieModel3 = new ModelSource[2];
            if (this.bogieModel2 != null) {
                this.bogieModel3[0] = this.bogieModel3[1] = this.bogieModel2;
            } else {
                ModelSource model = new ModelSource();
                model.modelFile = this.bogieModel;
                model.textures = new String[][]{{"default", this.bogieTexture}};
                this.bogieModel3[0] = this.bogieModel3[1] = model;
            }
        }

        this.sound_BrakeRelease = this.fixSoundPath(this.sound_BrakeRelease);
        this.sound_BrakeRelease2 = this.fixSoundPath(this.sound_BrakeRelease2);

        if (this.bogiePos == null) {
            this.bogiePos = new float[][]{{0.0F, 0.0F, 7.125F}, {0.0F, 0.0F, -7.125F}};
        }

        if (this.trainDistance <= 0.0F) {
            this.trainDistance = 10.125F;
        }

        // trainDistance is already expressed in Minecraft blocks and represents
        // the half-length used by RTM's train connection logic.
        if (this.walkable.length <= 0.0F) {
            this.walkable.length = this.trainDistance * 2.0F;
        }
        if (this.walkable.width <= 0.0F) {
            this.walkable.width = 3.0F;
        }

        if (this.accelerateion <= 0.0F) {
            this.accelerateion = 0.001736F;
        }

        if (this.maxSpeed == null || this.maxSpeed.length < 5) {
            this.maxSpeed = new float[]{0.36F, 0.72F, 1.08F, 1.44F, 1.80F};
        }

        if (this.accelerateions == null || (!this.notDisplayCab && this.accelerateions.length != this.maxSpeed.length)) {
            this.accelerateions = new float[this.maxSpeed.length];
            java.util.Arrays.fill(this.accelerateions, this.accelerateion);
        }

        if (this.deccelerations == null || (!this.notDisplayCab && this.deccelerations.length != 9)) {
            this.deccelerations = new float[]{-0.0002F, -0.0005F, -0.001F, -0.0015F, -0.002F, -0.0025F, -0.003F, -0.0035F, -0.01F};
        }

        this.rolling *= 5.0F;

        if (this.jointDelay == null) {
            float f0 = 1.9F;
            this.jointDelay = new float[][]{{0.0F, f0}, {0.0F, f0}};
        }

        if (this.serverScriptPath == null) {
            this.useVariableAcceleration = false;
            this.useVariableDeceleration = false;
        }
    }

    public boolean isNotchInRange(int notch) {
        if (notch < 0)
            return -notch <= deccelerations.length - 1;
        else
            return notch <= maxSpeed.length;
    }

    public static class WalkableConfig {
        /** Enabled by default; individual model packs may explicitly disable it. */
        public boolean enabled = true;
        /** Train-local center. RTM's vertical model coordinates are already blocks. */
        public float[] center = new float[]{0.0F, 1.3F, 0.0F};
        /** Zero means derive the full length from trainDistance * 2. */
        public float length;
        public float width = 3.0F;
        /** Allows walking directly into an adjacent coupled carriage. */
        public boolean allowCarriageTransfer = true;
        /** Per-coupler permission, indexed by RTM bogie/connection side 0 and 1. */
        public boolean[] transferEnds = new boolean[]{true, true};
        /** Width of the centered gangway opening at each enabled end. */
        public float transferWidth = 1.0F;

        public void init() {
            if (center == null || center.length != 3) {
                center = new float[]{0.0F, 1.3F, 0.0F};
            }
            if (length < 0.0F) {
                length = 0.0F;
            }
            if (width < 0.0F) {
                width = 0.0F;
            }
            if (transferEnds == null || transferEnds.length != 2) {
                transferEnds = new boolean[]{true, true};
            }
            if (transferWidth <= 0.0F) {
                transferWidth = 1.0F;
            }
        }

        public boolean isValid() {
            return enabled && length > 0.0F && width > 0.0F;
        }
    }

    @Override
    public String getName() {
        return this.trainName;
    }

    @Override
    public ModelSource getModel() {
        return this.trainModel2;
    }

    public ModelSource getBogieModel(int par1) {
        return this.bogieModel3[par1];
    }

    public float[][] getBogiePos() {
        return this.bogiePos;
    }

    public static TrainConfig getDummyConfig() {
        TrainConfig config = new TrainConfig();
        config.trainName = "Dummy";
        config.trainType = "N";
        config.init();
        return config;
    }

    @Override
    public String getSubType() {
        return this.trainType;
    }
}
