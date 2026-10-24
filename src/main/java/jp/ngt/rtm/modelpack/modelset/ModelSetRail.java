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

package jp.ngt.rtm.modelpack.modelset;

import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.entity.util.CollisionObj;
import jp.ngt.rtm.modelpack.ModelPackManager;
import jp.ngt.rtm.modelpack.cfg.RailConfig;
import jp.ngt.rtm.modelpack.cfg.ModelConfig.ModelSource;
import jp.ngt.rtm.render.BasicRailPartsRenderer;
import jp.ngt.rtm.render.ModelObject;
import jp.ngt.rtm.render.PartsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ModelSetRail extends ModelSetBase<RailConfig> {
    @SideOnly(Side.CLIENT)
    private RailLodModel[] lodModels = new RailLodModel[0];

    public ModelSetRail() {
        super();
    }

    public ModelSetRail(RailConfig par1) {
        super(par1);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void constructOnClient() {
        super.constructOnClient();

        if (this.isDummy()) {
            this.modelObj = ModelObject.getDummy();
            this.buttonTexture = ModelPackManager.INSTANCE.getResource("textures/signal/button_4cB.png");
        } else {
            RailConfig cfg = this.getConfig();
            PartsRenderer renderer = (!PartsRenderer.validPath(cfg.model.rendererPath)) ? new BasicRailPartsRenderer() : null;
            this.modelObj = new ModelObject(cfg.model, this, renderer);
            this.lodModels = this.constructLodModels(cfg);
            this.buttonTexture = ModelPackManager.INSTANCE.getResource(cfg.buttonTexture);
        }
    }

    @SideOnly(Side.CLIENT)
    private RailLodModel[] constructLodModels(RailConfig cfg) {
        if (cfg.lodModels == null || cfg.lodModels.length == 0) {
            return new RailLodModel[0];
        }

        List<RailLodModel> models = new ArrayList<>();
        for (RailConfig.LodModel lodConfig : cfg.lodModels) {
            if (lodConfig == null || lodConfig.distance <= 0.0D
                    || lodConfig.modelFile == null || lodConfig.modelFile.isEmpty()
                    || lodConfig.textures == null || lodConfig.textures.length == 0) {
                continue;
            }
            ModelSource source = cfg.createLodModelSource(lodConfig);
            PartsRenderer renderer = (!PartsRenderer.validPath(lodConfig.rendererPath))
                    ? new BasicRailPartsRenderer() : null;
            models.add(new RailLodModel(lodConfig.distance,
                    new ModelObject(source, this, renderer)));
        }
        models.sort(Comparator.comparingDouble(model -> model.distanceSq));
        return models.toArray(new RailLodModel[models.size()]);
    }

    /** Returns the main model or the farthest configured LOD whose threshold has been reached. */
    @SideOnly(Side.CLIENT)
    public ModelObject getModelForDistanceSq(double distanceSq) {
        ModelObject selected = this.modelObj;
        for (RailLodModel lodModel : this.lodModels) {
            if (distanceSq < lodModel.distanceSq) {
                break;
            }
            selected = lodModel.modelObject;
        }
        return selected;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void disposeClientResources(Set<ModelObject> disposed) {
        super.disposeClientResources(disposed);
        for (RailLodModel lodModel : this.lodModels) {
            if (disposed.add(lodModel.modelObject)) {
                lodModel.modelObject.disposeClientResources();
            }
        }
    }

    @Override
    public RailConfig getDummyConfig() {
        return RailConfig.getDummy();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderModelInGui(Minecraft par1) {
        ModelObject mo = this.modelObj;
        NGTUtilClient.bindTexture(mo.textures[0].material.texture);
        mo.model.renderAll(false);
    }

    @Override
    public CollisionObj getCollisionObj() {
        return null;
    }

    @SideOnly(Side.CLIENT)
    private static final class RailLodModel {
        private final double distanceSq;
        private final ModelObject modelObject;

        private RailLodModel(double distance, ModelObject modelObject) {
            this.distanceSq = distance * distance;
            this.modelObject = modelObject;
        }
    }
}
