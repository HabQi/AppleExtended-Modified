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

import jp.ngt.ngtlib.io.FileType;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.io.NGTText;
import jp.ngt.ngtlib.io.ResourceLocationCustom;
import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.model.*;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.entity.train.EntityBogie;
import jp.ngt.rtm.modelpack.IResourceSelector;
import jp.ngt.rtm.modelpack.ModelPackManager;
import jp.ngt.rtm.modelpack.cfg.ModelConfig;
import jp.ngt.rtm.modelpack.cfg.ModelConfig.ModelSource;
import jp.ngt.rtm.modelpack.modelset.ModelSetBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

@SideOnly(Side.CLIENT)
public class ModelObject {
    private static ModelObject DUMMY;

    public final IModelNGT model;
    public final TextureSet[] textures;
    public final PartsRenderer renderer;

    public final boolean light;
    public final boolean alphaBlend;
    private final boolean useTexture;

    private String vshPath, fshPath;
    public boolean useShader;
    private int program;
    private final String modelFile;
    private boolean reportedInvalidTexture;

    public ModelObject(ModelSource par1, ModelSetBase par2, PartsRenderer par3, Object... args) {
        String s = par1.modelFile;
        this.modelFile = s;
        this.model = ModelPackManager.INSTANCE.loadModel(s, 4, true, par2.getConfig(), par2.md5);
        boolean objMtlCompatibilityMode = FileType.OBJ.match(s) && !s.equals(s.toLowerCase(java.util.Locale.ROOT));
        Material[] amaterial = this.getMaterials(this.getTextureMap(par1.textures), objMtlCompatibilityMode);
        this.textures = new TextureSet[amaterial.length];
        boolean useLight = false;
        boolean useAlpha = false;
        int i = amaterial.length != par1.textures.length ? 1 : amaterial.length;
        for (int j = 0; j < i; ++j) {
            Material mat = amaterial[j];
            String[] sa = par1.textures[i == 1 ? 0 : mat.id];
            String texOption = (sa.length >= 3) ? sa[2] : "";
            boolean flagLight = texOption.contains("Light");
            boolean flagAlpha = texOption.contains("AlphaBlend");
            boolean disableSubTex = texOption.contains("OneTex");
            int texSize = (flagLight && !disableSubTex ? 3 : 0);

            String[] lightTextureNames = new String[sa.length >= 4 ? sa.length - 3 : 0];
            if (lightTextureNames.length > 0) {
                for (int k = 0; k < lightTextureNames.length; ++k) {
                    lightTextureNames[k] = sa[k + 3];
                }
                texSize = lightTextureNames.length;
            }

            this.textures[mat.id] = new TextureSet(mat, texSize, flagAlpha, flagLight, lightTextureNames);
            useLight |= flagLight;
            useAlpha |= flagAlpha;
        }
        this.light = useLight;
        this.alphaBlend = useAlpha;
        this.useTexture = !(this.model.getType() == FileType.NGTO || this.model.getType() == FileType.NGTZ);

        if (this.textures[0] == null) {
            this.textures[0] = new TextureSet(new Material((byte) 0, new ResourceLocation("hoge")), 0, false, false);
        }

        this.renderer = (par3 == null) ? this.getPartsRenderer(par1.rendererPath, this.model, args) : par3;
        this.renderer.init(par2, this);

        this.vshPath = par1.vertexShaderPath;
        this.fshPath = par1.fragmentShaderPath;
    }

    public ModelObject(IModelNGT par1, TextureSet[] par2) {
        this.modelFile = "<runtime>";
        this.model = par1;
        this.textures = par2;
        this.light = false;
        this.alphaBlend = false;
        this.useTexture = true;
        this.renderer = this.getPartsRenderer(null, par1);
    }

    public ModelObject(IModelNGT par1, TextureSet[] par2, PartsRenderer<?, ?> par3) {
        this.modelFile = "<runtime>";
        this.model = par1;
        this.textures = par2;
        this.light = false;
        this.alphaBlend = false;
        this.useTexture = true;
        this.renderer = par3;
    }

    public static ModelObject getDummy() {
        if (DUMMY == null) {
            DUMMY = new ModelObject(ModelLoader.loadModel(new ResourceLocationCustom("models/ModelContainer_19g.obj"), VecAccuracy.LOW), new TextureSet[]{new TextureSet(new Material((byte) 0, ModelPackManager.INSTANCE.getResource("textures/container/19g_JRF_0.png")), 0, false, false)});
        }

        return DUMMY;
    }

    private PartsRenderer getPartsRenderer(String path, IModelNGT par2, Object... args) {
        boolean b0 = !(args.length >= 1 && ("isBogie".equals(args[0])));

        if (path != null) {
            return RTMRenderers.getRendererWithScript(ModelPackManager.INSTANCE.getResource(path), String.valueOf(b0));
        } else if (par2 instanceof MCModel) {
            if (args.length >= 1 && "vehicle".equals(args[0])) {
                return new MCVehicleRenderer(String.valueOf(b0));
            }
            return new MCModelRenderer(String.valueOf(b0));
        } else {
            return new BasicPartsRenderer();
        }
    }

    private void initShader() {
        if (this.vshPath != null && this.fshPath != null) {
            try {
                String vsh = NGTText.getText(new ResourceLocation(this.vshPath), true);
                String fsh = NGTText.getText(new ResourceLocation(this.fshPath), true);
                this.program = GLHelper.getShaderProgram(vsh, fsh);
                this.useShader = (this.program > 0);
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.program = -1;
    }

    public void render(Object entity, ModelConfig cfg, int pass, float partialTick) {
        this.preRender();

        GL11.glPushMatrix();

        if (!(entity instanceof EntityBogie)) {
            float[] fa = cfg.offset;
            GL11.glTranslated(fa[0], fa[1], fa[2]);
        }

        boolean isSelector = (entity instanceof IResourceSelector);
        boolean canUseColor = (cfg.useCustomColor && isSelector);

        this.renderer.preRender(entity, cfg.smoothing, cfg.doCulling, partialTick);

        if (!cfg.doCulling) {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }

        if (cfg.smoothing) {
            GL11.glShadeModel(GL11.GL_SMOOTH);
        }

        if (canUseColor) {
            GLHelper.setColor(((IResourceSelector) entity).getResourceState().color, 0xFF);
        }

        if (pass == 0) {
            this.renderWithTexture(entity, RenderPass.NORMAL, partialTick);
        } else if (pass == 1) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            this.renderWithTexture(entity, RenderPass.TRANSPARENT, partialTick);
            GL11.glDisable(GL11.GL_BLEND);

            GLHelper.disableLighting();
            GLHelper.setLightmapMaxBrightness();
            this.renderWithTexture(entity, RenderPass.LIGHT, partialTick);
            if (this.renderer.shouldRenderOutline(entity)) {
                this.renderWithTexture(entity, RenderPass.OUTLINE, partialTick);
            }
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GLHelper.enableLighting();
        }

        if (canUseColor) {
            GLHelper.setColor(0xFFFFFF, 0xFF);
        }

        if (cfg.smoothing) {
            GL11.glShadeModel(GL11.GL_FLAT);
        }

        GL11.glEnable(GL11.GL_CULL_FACE);

        this.renderer.postRender(entity, cfg.smoothing, cfg.doCulling, partialTick);

        GL11.glPopMatrix();

        this.postRender();
    }

    public void preRender() {
        if (!this.useShader && this.program == 0) {
            this.initShader();
        }

        if (this.useShader) {
            ARBShaderObjects.glUseProgramObjectARB(this.program);
        }
    }

    public void postRender() {
        if (this.useShader) {
            ARBShaderObjects.glUseProgramObjectARB(0);
        }
    }

    @SideOnly(Side.CLIENT)
    public void disposeClientResources() {
        if (this.program > 0) {
            GLHelper.deleteShaderProgram(this.program);
        }
        this.program = 0;
        this.useShader = false;
    }

    public void renderWithTexture(Object entity, RenderPass pass, float partialTick) {
        int emissiveProgram = RtmEmissivePass.begin(pass);
        try {
            for (int i = 0; i < this.textures.length; ++i) {
                TextureSet textureSet = this.textures[i];
                if (textureSet == null || textureSet.material == null) {
                    this.reportInvalidTexture(i, textureSet, pass);
                    continue;
                }

                if (this.useTexture) {
                    ResourceLocation texture = null;
                    if (pass == RenderPass.NORMAL) {
                        texture = textureSet.material.texture;
                    } else if (pass == RenderPass.TRANSPARENT) {
                        if (!textureSet.doAlphaBlend) {
                            continue;
                        }

                        texture = textureSet.material.texture;
                    } else if (pass == RenderPass.LIGHT || pass == RenderPass.LIGHT_FRONT || pass == RenderPass.LIGHT_BACK) {
                        if (!textureSet.doLighting) {
                            continue;
                        }

                        if (textureSet.subTextures != null) {
                            int textureIndex = pass.id - RenderPass.LIGHT.id;
                            if (textureIndex < 0 || textureIndex >= textureSet.subTextures.length) {
                                this.reportInvalidTexture(i, textureSet, pass);
                                continue;
                            }
                            texture = textureSet.subTextures[textureIndex];
                        } else {
                            texture = textureSet.material.texture;
                        }
                    } else {
                        texture = textureSet.material.texture;
                    }

                    if (texture == null) {
                        this.reportInvalidTexture(i, textureSet, pass);
                        continue;
                    }
                    NGTUtilClient.bindTexture(texture);

                    if (this.useShader) {
                        ARBShaderObjects.glUniform1iARB(
                                ARBShaderObjects.glGetUniformLocationARB(this.program, "texture"), 0);

                        int time = (int) (System.currentTimeMillis() % (24000 * 50));
                        ARBShaderObjects.glUniform1iARB(
                                ARBShaderObjects.glGetUniformLocationARB(this.program, "time"), time);
                    }
                }

                this.renderer.currentMatId = textureSet.material.id;
                this.renderer.render(entity, pass, partialTick);
            }
        } finally {
            RtmEmissivePass.end(emissiveProgram);
        }
    }

    private void reportInvalidTexture(int textureIndex, TextureSet textureSet, RenderPass pass) {
        if (this.reportedInvalidTexture) {
            return;
        }
        this.reportedInvalidTexture = true;
        String materialId = textureSet == null || textureSet.material == null
                ? "null" : Byte.toString(textureSet.material.id);
        NGTLog.debug("[ModelObject] Skipping invalid texture: model=%s slot=%d material=%s pass=%s",
                this.modelFile, textureIndex, materialId, pass.name());
    }

    public Material[] getMaterials(Map<String, String> map, boolean objMtlCompatibilityMode) {
        Map<String, Material> map1 = this.model.getMaterials();
        Material[] amaterial;

        if (map1.isEmpty()) {
            amaterial = new Material[]{new Material((byte) 0, ModelPackManager.INSTANCE.getResource(map.get("default")))};
        } else if (objMtlCompatibilityMode && map.size() == 1 && map.containsKey("default")) {
            amaterial = new Material[]{new Material((byte) 0, ModelPackManager.INSTANCE.getResource(map.get("default")))};
        } else {
            amaterial = new Material[map1.size()];
            Iterator<Entry<String, Material>> iterator = map1.entrySet().iterator();

            for (int i = 0; iterator.hasNext(); ++i) {
                Entry<String, Material> entry = iterator.next();
                String s = map.get(entry.getKey());
                if (s == null) {
                    s = map.get("default");
                }
                amaterial[i] = new Material(entry.getValue().id, ModelPackManager.INSTANCE.getResource(s));
            }
        }
        return amaterial;
    }

    protected Map<String, String> getTextureMap(String[][] par1) {
        Map<String, String> map = new HashMap<String, String>();
        for (String[] sa : par1) {
            map.put(sa[0], sa[1]);
        }
        return map;
    }
}
