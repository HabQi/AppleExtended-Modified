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

public class RailConfig extends ModelConfig {
    /**
     * 名前(重複不可)
     */
    private String railName;
    /**
     * モデル
     */
    public ModelSource model;

    /**
     * Optional client-only rail models selected by distance from the rail core.
     * Each entry replaces the main model once the viewer reaches {@code distance}.
     */
    public LodModel[] lodModels;

    /**
     * 道床幅, 1以上の奇数
     */
    public int ballastWidth;
    /**
     * 動物とかが線路を横切れるかどうか
     */
    public boolean allowCrossing;
    /**
     * クリエイティブタブに追加する際の道床ブロック
     */
    public BallastSet[] defaultBallast;

    @Deprecated
    public String railModel;
    @Deprecated
    public String railTexture;

    @Override
    public void init() {
        super.init();

        if (this.model == null) {
            this.model = new ModelSource();
            this.model.modelFile = this.railModel;
            this.model.textures = new String[][]{{"default", this.railTexture}};
            this.model.rendererPath = null;
        }

        if (this.lodModels != null) {
            for (LodModel lodModel : this.lodModels) {
                if (lodModel == null) {
                    continue;
                }
                if (lodModel.textures == null) {
                    lodModel.textures = this.model.textures;
                }
                if (lodModel.rendererPath == null) {
                    lodModel.rendererPath = this.model.rendererPath;
                }
            }
        }

        if (this.ballastWidth <= 0) {
            this.ballastWidth = 3;
        } else if ((this.ballastWidth & 1) == 0) {
            ++this.ballastWidth;
        }
    }

    @Override
    public String getName() {
        return this.railName;
    }


    public static RailConfig getDummy() {
        RailConfig cfg = new RailConfig();
        cfg.railName = "dummy";
        cfg.railModel = "1067mm_Wood";
        return cfg;
    }

    public ModelSource createLodModelSource(LodModel lodModel) {
        ModelSource source = new ModelSource();
        source.modelFile = lodModel.modelFile;
        source.textures = lodModel.textures;
        source.rendererPath = lodModel.rendererPath;
        source.vertexShaderPath = lodModel.vertexShaderPath;
        source.fragmentShaderPath = lodModel.fragmentShaderPath;
        return source;
    }

    public static class LodModel {
        /** Minimum viewer distance in blocks before this model is used. */
        public double distance;
        public String modelFile;
        public String[][] textures;
        public String rendererPath;
        public String vertexShaderPath;
        public String fragmentShaderPath;
    }

    public class BallastSet {
        public String blockName;
        public int blockMetadata;
        public float height;
    }
}
