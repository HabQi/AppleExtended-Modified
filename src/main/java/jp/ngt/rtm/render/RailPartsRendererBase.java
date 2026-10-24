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

import jp.ngt.ngtlib.io.ScriptUtil;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.math.NGTMath;
import jp.ngt.ngtlib.renderer.*;
import jp.ngt.ngtlib.renderer.model.Face;
import jp.ngt.ngtlib.renderer.model.GroupObject;
import jp.ngt.ngtlib.renderer.model.Vertex;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.modelpack.modelset.ModelSetRail;
import jp.ngt.rtm.rail.RenderMarkerBlock;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.TileEntityLargeRailSwitchCore;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class RailPartsRendererBase extends TileEntityPartsRenderer<ModelSetRail> {
    protected int currentRailIndex;
    private ModelObject asyncGeometryModel;
    private RailMeshSnapshot.GroupData[] asyncGeometry;
    private boolean renderingLod;

    //メモ:これ使うとかえって速度落ちる
    //((Invocable)this.getScript()).getInterface(RailScript.class);

    public RailPartsRendererBase(String... par1) {
        super(par1);
    }

    @Override
    public void init(ModelSetRail par1, ModelObject par2) {
        super.init(par1, par2);
        this.asyncGeometryModel = null;
        this.asyncGeometry = null;
        try {
            if (this.supportsAsyncStaticMesh()) {
                this.asyncGeometry = copyGeometry(par2);
                this.asyncGeometryModel = par2;
            }
        } catch (Throwable throwable) {
            NGTLog.debug("[RailBuildQueue] async geometry prewarm failed for %s: %s",
                    par1.getConfig().getName(), throwable.toString());
        }
    }

    /** Custom renderer subclasses must explicitly opt in to the immutable mesh path. */
    public boolean supportsAsyncStaticMesh() {
        return false;
    }

    /**
     * RenderLargeRailから呼ばれる
     */
    public void renderRail(TileEntityLargeRailCore tileEntity, int index, double par2, double par4, double par6, float par8) {
        this.currentRailIndex = index;
        tileEntity.syncLegacyRailRenderDirty();
        boolean hasResource = this.hasValidResource(tileEntity, index);
        if (!this.isResourceReady(tileEntity, index)) {
            RailResourceBuildQueue.INSTANCE.enqueueRail(tileEntity, this, index);
        }
        if (!hasResource) {
            return;
        }
        this.renderRailStatic(tileEntity, par2, par4, par6, par8);
        this.renderRailDynamic(tileEntity, par2, par4, par6, par8);
    }

    /** Renders a cached far-distance model without running dynamic rail animation. */
    public void renderLodRail(TileEntityLargeRailCore tileEntity, int index,
                              double par2, double par4, double par6, float par8) {
        this.currentRailIndex = index;
        tileEntity.syncLegacyRailRenderDirty();
        boolean previous = this.renderingLod;
        this.renderingLod = true;
        try {
            this.renderRailStatic(tileEntity, par2, par4, par6, par8);
        } finally {
            this.renderingLod = previous;
        }
    }

    /** Exposes the active rail slot to model-pack scripts. */
    public final int getCurrentRailIndex() {
        return this.currentRailIndex;
    }

    public boolean isResourceReady(TileEntityLargeRailCore rail, int index) {
        if (!this.hasValidResource(rail, index)) {
            return false;
        }
        GLObject object = rail.glLists[index];
        if (object instanceof RailVertexBuffer) {
            return ((RailVertexBuffer) object).matches(rail.getRailRenderGeneration(),
                    this.getModelObject(), index);
        }
        return !rail.shouldRerenderRail;
    }

    /** Returns whether an existing GL resource can still be drawn while a replacement is queued. */
    public boolean hasValidResource(TileEntityLargeRailCore rail, int index) {
        return rail != null && rail.glLists != null && index >= 0 && index < rail.glLists.length
                && rail.glLists.length == rail.subRails.size() + 1
                && GLHelper.isValid(rail.glLists[index]);
    }

    public boolean isCurrentRenderer(TileEntityLargeRailCore rail, int index) {
        if (rail == null || index < 0) {
            return false;
        }
        if (index == 0) {
            return rail.getResourceState().getResourceSet() != null
                    && rail.getResourceState().getResourceSet().modelObj.renderer == this;
        }
        return index - 1 < rail.subRails.size()
                && rail.subRails.get(index - 1).getResourceSet() != null
                && rail.subRails.get(index - 1).getResourceSet().modelObj.renderer == this;
    }

    /** Captures mutable world and script inputs before the worker receives the mesh. */
    RailMeshSnapshot captureMeshSnapshot(TileEntityLargeRailCore rail, int index) {
        if (!this.supportsAsyncStaticMesh() || rail == null || !this.isCurrentRenderer(rail, index)) {
            return null;
        }
        this.currentRailIndex = index;
        float[][] positions = this.createRailPos(rail);
        if (positions == null) {
            return null;
        }
        BlockPos pos = rail.getPos();
        int[] brightness = this.getRailBrightness(rail.getWorld(), pos.getX(), pos.getY(), pos.getZ(), positions);
        FloatBuffer matrixBuffer = this.createMatrix(positions);
        float[] matrices = new float[matrixBuffer.capacity()];
        for (int i = 0; i < matrices.length; ++i) {
            matrices[i] = matrixBuffer.get(i);
        }

        RailMeshSnapshot.GroupData[] groups = this.getAsyncGeometry();

        boolean[][] selectedGroups = new boolean[positions.length][groups.length];
        long vertexCount = 0L;
        for (int section = 0; section < positions.length; ++section) {
            for (int groupIndex = 0; groupIndex < groups.length; ++groupIndex) {
                RailMeshSnapshot.GroupData group = groups[groupIndex];
                if (group.name.startsWith("side") && section != 0 && section != positions.length - 1) {
                    continue;
                }
                if (this.shouldRenderObject(rail, group.name, positions.length, section)) {
                    selectedGroups[section][groupIndex] = true;
                    vertexCount += group.vertexCount;
                }
            }
        }
        if (vertexCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Rail mesh has too many vertices: " + vertexCount);
        }
        return new RailMeshSnapshot(matrices, brightness, groups, selectedGroups, (int) vertexCount);
    }

    private synchronized RailMeshSnapshot.GroupData[] getAsyncGeometry() {
        ModelObject model = this.getModelObject();
        if (this.asyncGeometryModel == model && this.asyncGeometry != null) {
            return this.asyncGeometry;
        }
        this.asyncGeometry = copyGeometry(model);
        this.asyncGeometryModel = model;
        return this.asyncGeometry;
    }

    private static RailMeshSnapshot.GroupData[] copyGeometry(ModelObject model) {
        List<GroupObject> sourceGroups = model.model.getGroupObjects();
        RailMeshSnapshot.GroupData[] groups = new RailMeshSnapshot.GroupData[sourceGroups.size()];
        for (int groupIndex = 0; groupIndex < sourceGroups.size(); ++groupIndex) {
            groups[groupIndex] = copyGroup(sourceGroups.get(groupIndex));
        }
        return groups;
    }

    private static RailMeshSnapshot.GroupData copyGroup(GroupObject group) {
        int vertexCount = 0;
        for (Face face : group.faces) {
            vertexCount += face.vertices.length;
        }
        float[] vertices = new float[vertexCount * 5];
        float[] normals = new float[vertexCount * 3];
        float[] midTexCoords = new float[vertexCount * 2];
        float[] tangents = new float[vertexCount * 4];
        int output = 0;
        for (Face face : group.faces) {
            float nx = face.faceNormal.getX();
            float ny = face.faceNormal.getY();
            float nz = face.faceNormal.getZ();
            float[] midTexCoord = calculateMidTexCoord(face);
            for (int i = 0; i < face.vertices.length; ++i) {
                int offset = output * 5;
                vertices[offset] = face.vertices[i].getX();
                vertices[offset + 1] = face.vertices[i].getY();
                vertices[offset + 2] = face.vertices[i].getZ();
                if (face.textureCoordinates != null && i < face.textureCoordinates.length
                        && face.textureCoordinates[i] != null) {
                    vertices[offset + 3] = face.textureCoordinates[i].getU();
                    vertices[offset + 4] = face.textureCoordinates[i].getV();
                }
                int normalOffset = output * 3;
                normals[normalOffset] = nx;
                normals[normalOffset + 1] = ny;
                normals[normalOffset + 2] = nz;
                int midOffset = output * 2;
                midTexCoords[midOffset] = midTexCoord[0];
                midTexCoords[midOffset + 1] = midTexCoord[1];
                ++output;
            }
            for (int triangle = 0; triangle < face.vertices.length; triangle += 3) {
                float[] tangent = calculateTangent(face, triangle, nx, ny, nz);
                int triangleEnd = Math.min(triangle + 3, face.vertices.length);
                for (int i = triangle; i < triangleEnd; ++i) {
                    int tangentOffset = (output - face.vertices.length + i) * 4;
                    tangents[tangentOffset] = tangent[0];
                    tangents[tangentOffset + 1] = tangent[1];
                    tangents[tangentOffset + 2] = tangent[2];
                    tangents[tangentOffset + 3] = tangent[3];
                }
            }
        }
        return new RailMeshSnapshot.GroupData(group.name, vertices, normals, midTexCoords, tangents);
    }

    private static float[] calculateMidTexCoord(Face face) {
        float minU = Float.POSITIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < face.textureCoordinates.length; ++i) {
            if (face.textureCoordinates[i] == null) {
                continue;
            }
            float u = face.textureCoordinates[i].getU();
            float v = face.textureCoordinates[i].getV();
            minU = Math.min(minU, u);
            minV = Math.min(minV, v);
            maxU = Math.max(maxU, u);
            maxV = Math.max(maxV, v);
        }
        if (!Float.isFinite(minU) || !Float.isFinite(minV)) {
            return new float[]{0.0F, 0.0F};
        }
        return new float[]{(minU + maxU) * 0.5F, (minV + maxV) * 0.5F};
    }

    private static float[] calculateTangent(Face face, int start, float nx, float ny, float nz) {
        if (start + 2 >= face.vertices.length || face.textureCoordinates[start] == null
                || face.textureCoordinates[start + 1] == null || face.textureCoordinates[start + 2] == null) {
            float[] fallback = RailMeshSnapshot.fallbackTangent(nx, ny, nz);
            return new float[]{fallback[0], fallback[1], fallback[2], 1.0F};
        }

        Vertex v0 = face.vertices[start];
        Vertex v1 = face.vertices[start + 1];
        Vertex v2 = face.vertices[start + 2];
        float edge1X = v1.getX() - v0.getX();
        float edge1Y = v1.getY() - v0.getY();
        float edge1Z = v1.getZ() - v0.getZ();
        float edge2X = v2.getX() - v0.getX();
        float edge2Y = v2.getY() - v0.getY();
        float edge2Z = v2.getZ() - v0.getZ();
        float du1 = face.textureCoordinates[start + 1].getU() - face.textureCoordinates[start].getU();
        float dv1 = face.textureCoordinates[start + 1].getV() - face.textureCoordinates[start].getV();
        float du2 = face.textureCoordinates[start + 2].getU() - face.textureCoordinates[start].getU();
        float dv2 = face.textureCoordinates[start + 2].getV() - face.textureCoordinates[start].getV();
        float determinant = du1 * dv2 - dv1 * du2;
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= 1.0E-8F) {
            float[] fallback = RailMeshSnapshot.fallbackTangent(nx, ny, nz);
            return new float[]{fallback[0], fallback[1], fallback[2], 1.0F};
        }

        float inverse = 1.0F / determinant;
        float tx = (edge1X * dv2 - edge2X * dv1) * inverse;
        float ty = (edge1Y * dv2 - edge2Y * dv1) * inverse;
        float tz = (edge1Z * dv2 - edge2Z * dv1) * inverse;
        float normalProjection = tx * nx + ty * ny + tz * nz;
        tx -= nx * normalProjection;
        ty -= ny * normalProjection;
        tz -= nz * normalProjection;
        float tangentLengthSquared = tx * tx + ty * ty + tz * tz;
        if (!Float.isFinite(tangentLengthSquared) || tangentLengthSquared <= 1.0E-12F) {
            float[] fallback = RailMeshSnapshot.fallbackTangent(nx, ny, nz);
            return new float[]{fallback[0], fallback[1], fallback[2], 1.0F};
        }
        float tangentLength = (float) Math.sqrt(tangentLengthSquared);
        tx /= tangentLength;
        ty /= tangentLength;
        tz /= tangentLength;

        float bx = (edge2X * du1 - edge1X * du2) * inverse;
        float by = (edge2Y * du1 - edge1Y * du2) * inverse;
        float bz = (edge2Z * du1 - edge1Z * du2) * inverse;
        float crossX = ny * tz - nz * ty;
        float crossY = nz * tx - nx * tz;
        float crossZ = nx * ty - ny * tx;
        float handedness = crossX * bx + crossY * by + crossZ * bz < 0.0F ? -1.0F : 1.0F;
        return new float[]{tx, ty, tz, handedness};
    }

    /** Builds one static rail slot into a temporary display list. */
    public boolean buildResource(TileEntityLargeRailCore rail, int index) {
        if (rail == null || index < 0 || index >= rail.subRails.size() + 1) {
            return false;
        }
        int expectedLength = rail.subRails.size() + 1;
        if (rail.glLists == null) {
            rail.glLists = new GLObject[expectedLength];
        } else if (rail.glLists.length != expectedLength) {
            GLObject[] oldLists = rail.glLists;
            rail.glLists = new GLObject[expectedLength];
            int copyLength = Math.min(oldLists.length, expectedLength);
            System.arraycopy(oldLists, 0, rail.glLists, 0, copyLength);
            for (int i = copyLength; i < oldLists.length; ++i) {
                GLHelper.deleteGLList(oldLists[i]);
            }
        }
        float[][] positions = this.createRailPos(rail);
        if (positions == null) {
            return false;
        }
        BlockPos pos = rail.getPos();
        long stageStartedAt = System.nanoTime();
        int[] brightness = this.getRailBrightness(rail.getWorld(), pos.getX(), pos.getY(), pos.getZ(), positions);
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_LIGHTING, System.nanoTime() - stageStartedAt);
        stageStartedAt = System.nanoTime();
        FloatBuffer matrices = this.createMatrix(positions);
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_MATRIX, System.nanoTime() - stageStartedAt);
        GLObject newList = GLHelper.generateGLList(null);
        boolean compiling = false;
        long compileStartedAt = System.nanoTime();
        try {
            this.currentRailIndex = index;
            GLHelper.startCompile(newList);
            compiling = true;
            this.tessellateParts(rail, matrices, brightness, this.getModelObject().model.getGroupObjects());
        } catch (RuntimeException | Error throwable) {
            GLHelper.deleteGLList(newList);
            throw throwable;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_COMPILE,
                    System.nanoTime() - compileStartedAt);
            if (compiling) {
                long finalizeStartedAt = System.nanoTime();
                try {
                    GLHelper.endCompile();
                } finally {
                    RailBuildProfiler.recordStage(RailBuildProfiler.Stage.GL_FINALIZE,
                            System.nanoTime() - finalizeStartedAt);
                }
            }
        }

        GLObject oldList = rail.glLists[index];
        rail.glLists[index] = newList;
        rail.shouldRerenderRail = false;
        rail.initializeBrightnessBaseline();
        GLHelper.deleteGLList(oldList);
        return true;
    }

    /*スクリプト呼び出しメソッド**********************************************************************************/

    /**
     * 形状固定の部分を描画
     */
    protected void renderRailStatic(TileEntityLargeRailCore tileEntity, double x, double y, double z, float par8) {
        //Script介さず直接renderStaticParts()呼んでも速度変わらん
        ScriptUtil.doScriptIgnoreError(this.getScript(), "renderRailStatic", tileEntity, x, y, z, par8, 0);
    }

    /**
     * 形状が変化する部分を描画
     */
    protected void renderRailDynamic(TileEntityLargeRailCore tileEntity, double x, double y, double z, float par8) {
        ScriptUtil.doScriptIgnoreError(this.getScript(), "renderRailDynamic", tileEntity, x, y, z, par8, 0);
    }

    /**
     * オブジェクトを描画するかどうか
     *
     * @param objName
     * @param len     セグメント総数
     * @param pos     何番目のセグメントか
     *
     */
    protected boolean shouldRenderObject(TileEntityLargeRailCore tileEntity, String objName, int len, int pos) {
        return (Boolean) ScriptUtil.doScriptIgnoreError(this.getScript(), "shouldRenderObject", tileEntity, objName, len, pos);
    }

    /*+***************************************************************************************************/

    /**
     * 固定パーツをGLListで描画<br>
     * スクリプトから呼び出し
     */
    public void renderStaticParts(TileEntityLargeRailCore rail, double par2, double par4, double par6) {
        if (this.renderingLod) {
            this.renderLodStaticParts(rail, par2, par4, par6);
            return;
        }
        this.renderQueuedStaticResource(rail, par2, par4, par6);
    }

    private void renderLodStaticParts(TileEntityLargeRailCore rail, double par2, double par4, double par6) {
        GLObject resource = rail.getLodGLList(this.getModelObject(), this.currentRailIndex);
        if (!GLHelper.isValid(resource)) {
            if (!this.buildLodResource(rail, this.currentRailIndex)) {
                return;
            }
            resource = rail.getLodGLList(this.getModelObject(), this.currentRailIndex);
        }
        if (!GLHelper.isValid(resource)) {
            return;
        }

        RailPosition rp = rail.getRailPositions()[0];
        double x = rp.posX - (double) rp.blockX;
        double y = rp.posY - (double) rp.blockY - 0.0625D;
        double z = rp.posZ - (double) rp.blockZ;
        GL11.glPushMatrix();
        GL11.glTranslatef((float) (par2 + x), (float) (par4 + y), (float) (par6 + z));
        this.bindTexture(this.getModelObject().textures[0].material.texture);
        GLHelper.callList(resource);
        GL11.glPopMatrix();
    }

    private boolean buildLodResource(TileEntityLargeRailCore rail, int index) {
        float[][] positions = this.createRailPos(rail);
        if (positions == null) {
            return false;
        }
        BlockPos pos = rail.getPos();
        int[] brightness = this.getRailBrightness(rail.getWorld(), pos.getX(), pos.getY(), pos.getZ(), positions);
        FloatBuffer matrices = this.createMatrix(positions);
        GLObject resource = GLHelper.generateGLList(null);
        boolean compiling = false;
        try {
            this.currentRailIndex = index;
            GLHelper.startCompile(resource);
            compiling = true;
            this.tessellateParts(rail, matrices, brightness, this.getModelObject().model.getGroupObjects());
        } catch (RuntimeException | Error throwable) {
            GLHelper.deleteGLList(resource);
            throw throwable;
        } finally {
            if (compiling) {
                GLHelper.endCompile();
            }
        }

        rail.setLodGLList(this.getModelObject(), index, resource);
        return true;
    }

    @SuppressWarnings("unused")
    private void renderStaticPartsLegacy(TileEntityLargeRailCore rail, double par2, double par4, double par6) {
        if (!this.hasValidResource(rail, this.currentRailIndex)) {
            RailResourceBuildQueue.INSTANCE.enqueueRail(rail, this, this.currentRailIndex);
            return;
        }
        if (rail.shouldRerenderRail) {
            this.renderExistingStaticResource(rail, par2, par4, par6);
            return;
        }
        boolean hasGLList = true;

        if (rail.glLists == null) {
            rail.glLists = new GLObject[rail.subRails.size() + 1];
            hasGLList = false;
        } else if (rail.glLists.length != rail.subRails.size() + 1) {
			/*GLObject[] oldList = rail.glLists;
			rail.glLists = new GLObject[rail.subRails.size() + 1];
			for(int i = 0; i < oldList.length; ++i)
			{
				rail.glLists[i] = oldList[i];
			}
			hasGLList = oldList.length > this.currentRailIndex;*/
            for (int i = 0; i < rail.glLists.length; ++i) {
                GLHelper.deleteGLList(rail.glLists[i]);
            }
            rail.glLists = new GLObject[rail.subRails.size() + 1];
            hasGLList = false;
        }

        if (hasGLList) {
            hasGLList = GLHelper.isValid(rail.glLists[this.currentRailIndex]);
        }

        if (!hasGLList) {
            rail.glLists[this.currentRailIndex] = GLHelper.generateGLList(rail.glLists[this.currentRailIndex]);
        } else if (rail.shouldRerenderRail)//再描画
        {
            hasGLList = false;
        }

        if (!hasGLList)//ディスプレイリスト生成
        {
            float[][] fa = this.createRailPos(rail);
            if (fa != null) {
                BlockPos pos = rail.getPos();
                int[] brightness = this.getRailBrightness(rail.getWorld(), pos.getX(), pos.getY(), pos.getZ(), fa);
                FloatBuffer fb = this.createMatrix(fa);

                GLHelper.startCompile(rail.glLists[this.currentRailIndex]);//GL_COMPILE_AND_EXECUTEは画面がチラつく
                this.tessellateParts(rail, fb, brightness, this.modelSet.modelObj.model.getGroupObjects());
                GLHelper.endCompile();

                rail.shouldRerenderRail = false;
                hasGLList = true;
            } else {
                rail.shouldRerenderRail = true;
                hasGLList = false;
                //GLHelper.deleteGLList(rail.glList);
                //rail.glList = null;
            }
        }

        if (hasGLList)//ディスプレイリスト描画
        {
            RailPosition rp = rail.getRailPositions()[0];
            double x = rp.posX - (double) rp.blockX;
            double y = rp.posY - (double) rp.blockY - 0.0625D;
            double z = rp.posZ - (double) rp.blockZ;
            GL11.glPushMatrix();
            GL11.glTranslatef((float) (par2 + x), (float) (par4 + y), (float) (par6 + z));
            this.bindTexture(this.getModelObject().textures[0].material.texture);//ディスプレイリストに入れると生成重い
            GLHelper.callList(rail.glLists[this.currentRailIndex]);
            GL11.glPopMatrix();
        }
    }

    private void renderQueuedStaticResource(TileEntityLargeRailCore rail,
                                            double par2, double par4, double par6) {
        if (!this.isResourceReady(rail, this.currentRailIndex)) {
            RailResourceBuildQueue.INSTANCE.enqueueRail(rail, this, this.currentRailIndex);
        }
        if (this.hasValidResource(rail, this.currentRailIndex)) {
            this.renderExistingStaticResource(rail, par2, par4, par6);
        }
    }

    private void renderExistingStaticResource(TileEntityLargeRailCore rail, double par2, double par4, double par6) {
        RailPosition rp = rail.getRailPositions()[0];
        double x = rp.posX - (double) rp.blockX;
        double y = rp.posY - (double) rp.blockY - 0.0625D;
        double z = rp.posZ - (double) rp.blockZ;
        GL11.glPushMatrix();
        GL11.glTranslatef((float) (par2 + x), (float) (par4 + y), (float) (par6 + z));
        this.bindTexture(this.getModelObject().textures[0].material.texture);
        GLObject resource = rail.glLists[this.currentRailIndex];
        if (resource instanceof RailVertexBuffer) {
            ((RailVertexBuffer) resource).render();
        } else {
            GLHelper.callList(resource);
        }
        GL11.glPopMatrix();
    }

    /**
     * 各レールパーツの位置と向き
     *
     * @return {x, y, z, yaw, pitch, roll}
     */
    protected float[][] createRailPos(TileEntityLargeRailCore par1) {
        float[] rev = RailPosition.REVISION[par1.getRailPositions()[0].direction];
        long mapStartedAt = System.nanoTime();
        RailMap[] rms = par1.getAllRailMaps();
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_MAP, System.nanoTime() - mapStartedAt);
        long geometryStartedAt = System.nanoTime();
        try {
            if (rms != null) {
                List<float[]> list = new ArrayList<float[]>();
                for (RailMap rm : rms) {
                    int max = (int) (rm.getLength() * 2.0D);
                    double[] stPoint = rm.getRailPos(max, 0);
                    //double startH = rm.getRailHeight(max, 0);//カント付けた時端が沈む
                    double startH = rm.getStartRP().posY;
                    float moveX = (float) (stPoint[1] - ((double) par1.getStartPoint()[0] + 0.5D + (double) rev[0]));
                    float moveZ = (float) (stPoint[0] - ((double) par1.getStartPoint()[2] + 0.5D + (double) rev[1]));

                    for (int i = 0; i <= max; ++i) {
                        double[] curPoint = rm.getRailPos(max, i);
                        float[] array = {
                                moveX + (float) (curPoint[1] - stPoint[1]),
                                (float) (rm.getRailHeight(max, i) - startH),//0.0F,
                                moveZ + (float) (curPoint[0] - stPoint[0]),
                                rm.getRailRotation(max, i),
                                -rm.getRailPitch(max, i),
                                rm.getCant(max, i)
                        };

                        list.add(array);
                    }
                }
                return list.toArray(new float[list.size()][5]);
            }
            return null;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_GEOMETRY,
                    System.nanoTime() - geometryStartedAt);
        }
    }

    private final FloatBuffer convBuf = FloatBuffer.wrap(new float[]{
            1.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 0.0F,
            0.0F, 0.0F, 1.0F, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F});

    /**
     * レンダリング用同時変換行列を作製
     */
    protected final FloatBuffer createMatrix(float[][] rp) {
        //rpにNaNが含まれるとダメ
        FloatBuffer buffer = FloatBuffer.allocate(rp.length << 4);
        for (int i = 0; i < rp.length; ++i) {
            FloatBuffer fb = this.convBuf;
            fb = NGTRenderHelper.translate(fb, rp[i][0], rp[i][1], rp[i][2]);
            fb = NGTRenderHelper.rotate(fb, NGTMath.toRadians(rp[i][3]), 'Y');
            fb = NGTRenderHelper.rotate(fb, NGTMath.toRadians(rp[i][4]), 'X');
            fb = NGTRenderHelper.rotate(fb, NGTMath.toRadians(rp[i][5]), 'Z');
            buffer.put(fb);
        }
        return buffer;
    }

    //Entity.getBrightnessForRender()はRenderManager.renderEntityStatic()で呼ばれる

    /**
     * レール上の明るさを一括取得
     */
    protected final int[] getRailBrightness(World world, int x, int y, int z, float[][] rp) {
        int[] fa = new int[rp.length];
        for (int i = 0; i < rp.length; ++i) {
            int x0 = x + NGTMath.floor(rp[i][0]);
            int y0 = y + NGTMath.floor(rp[i][1]);
            int z0 = z + NGTMath.floor(rp[i][2]);
            fa[i] = this.getBrightness(world, x0, y0, z0);
        }
        //NGTLog.debug("br %d", fa.length);
        return fa;
    }

    /**
     * 指定座標の明るさ取得(バグ回避処理付き)
     */
    public int getBrightness(World world, int x, int y, int z) {
        int brightness = this.getWorldBrightness(world, x, y, z);
        if (brightness <= 0) {
            brightness = this.getWorldBrightness(world, x, y + 1, z);//一部黒くなる問題回避
        }
        return brightness;
    }

    /**
     * 指定座標の明るさ取得
     */
    private final int getWorldBrightness(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.isBlockLoaded(pos) ? world.getCombinedLight(pos, 0) : 0;
    }

    /**
     * 引数のパーツを一括描画<br>
     * GLListコンパイル用
     *
     * @param matrix     セグメントごとの座標&角度
     * @param brightness セグメントごとの明るさ
     * @param lists      セグメントの構成ObjectのList x セグメント数
     */
    private final void tessellateParts(TileEntityLargeRailCore tileEntity, FloatBuffer matrix, int[] brightness, List<GroupObject> gObjList) {
        //IRenderer tessellator = NGTTessellator.instance;
        IRenderer tessellator = PolygonRenderer.INSTANCE;
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        int capacity = matrix.capacity() >> 4;
        for (int i = 0; i < capacity; ++i) {
            tessellator.setBrightness(brightness[i]);
            for (int j = 0; j < gObjList.size(); ++j) {
                GroupObject group = gObjList.get(j);
                if (group.name.startsWith("side") && !(i == 0 || i == capacity - 1)) {
                    continue;
                }//レールの端以外は断面を描画しない, +1~2fps

                if (!this.shouldRenderObject(tileEntity, group.name, capacity, i)) {
                    continue;
                }//描画するかスクリプト側で判断

                for (int k = 0; k < group.faces.size(); ++k) {
                    Face face = group.faces.get(k);
                    NGTRenderHelper.addFaceWithMatrix(face, tessellator, matrix, i, false);
                }
            }
        }
        tessellator.draw();
    }

    public String[] getAllObjNames() {
        List<GroupObject> gObj = this.modelObj.model.getGroupObjects();
        String[] aStr = new String[gObj.size()];
        for (int i = 0; i < aStr.length; ++i) {
            aStr[i] = gObj.get(i).name;
        }
        return aStr;
    }

    public boolean isSwitchRail(TileEntityLargeRailCore tileEntity) {
        return tileEntity.getAllRailMaps().length > 1;
    }

    public void renderRailMapStatic(TileEntityLargeRailSwitchCore tileEntity, RailMap rm, int max, int startIndex, int endIndex, Parts... pArray) {
        double[] origPos = rm.getRailPos(max, 0);
        double origHeight = rm.getRailHeight(max, 0);
        int[] startPos = tileEntity.getStartPoint();
        float[] revXZ = RailPosition.REVISION[tileEntity.getRailPositions()[0].direction];
        //レール全体の始点からの移動差分
        float moveX = (float) (origPos[1] - ((double) startPos[0] + 0.5D + (double) revXZ[0]));
        float moveZ = (float) (origPos[0] - ((double) startPos[2] + 0.5D + (double) revXZ[1]));

        //頂点-中間点
        for (int i = startIndex; i <= endIndex; ++i) {
            double[] p1 = rm.getRailPos(max, i);
            double h = rm.getRailHeight(max, i);
            float x0 = moveX + (float) (p1[1] - origPos[1]);
            float y0 = (float) (h - origHeight);
            float z0 = moveZ + (float) (p1[0] - origPos[0]);
            float yaw = rm.getRailRotation(max, i);
            float pitch = rm.getRailPitch(max, i);

            this.setBrightness(this.getBrightness(tileEntity.getWorld(),
                    NGTMath.floor(origPos[1] + x0), tileEntity.getPos().getY(), NGTMath.floor(origPos[0] + z0)));
            GL11.glPushMatrix();
            GL11.glTranslatef(x0, y0, z0);
            GL11.glRotatef(yaw, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(-pitch, 1.0F, 0.0F, 0.0F);
            for (int j = 0; j < pArray.length; ++j) {
                pArray[j].render(this);
            }
            GL11.glPopMatrix();
        }
    }

    public ModelObject getModelObject() {
        return this.modelObj;
    }

    public void setBrightness(int par1) {
        GLHelper.setBrightness(par1);
    }

    //debug//////////////////////////////////////////////////////

    protected void renderRailPosArray(RailPosition[] rps, RailMap[] rms) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GLHelper.disableLighting();
        float lineWidth = (float) NGTUtilClient.getMinecraft().displayHeight * 0.005F;
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        GL11.glLineWidth(lineWidth);

        double sx = rps[0].posX;
        double sy = rps[0].posY;
        double sz = rps[0].posZ;

        for (RailPosition rp : rps) {
            this.renderRailPos(rp, sx, sy, sz);
        }

        for (RailMap rm : rms) {
            this.renderRailMap(rm, sx, sy, sz);
        }

        GL11.glLineWidth(prevLineWidth);
        GLHelper.enableLighting();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void renderRailPos(RailPosition rp, double sx, double sy, double sz) {
        float lineLen = 2.0F;

        GL11.glPushMatrix();
        GL11.glTranslatef((float) (rp.posX - sx), (float) (rp.posY - sy), (float) (rp.posZ - sz));

        RenderMarkerBlock.renderLine(0.0F, 0.0F, 0.0F, 0.0F, lineLen, 0.0F, 0xFF0000);

        GL11.glPushMatrix();
        GL11.glRotatef(rp.anchorYaw, 0.0F, 1.0F, 0.0F);
        RenderMarkerBlock.renderLine(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, rp.anchorLengthHorizontal, 0xFF0000);

        GL11.glPushMatrix();
        GL11.glRotatef(-rp.anchorPitch, 1.0F, 0.0F, 0.0F);
        RenderMarkerBlock.renderLine(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, rp.anchorLengthVertical, 0xFF0000);
        GL11.glPopMatrix();

        GL11.glPushMatrix();
        GL11.glRotatef(rp.cantEdge, 0.0F, 0.0F, 1.0F);
        RenderMarkerBlock.renderLine(0.0F, 0.0F, 0.0F, lineLen, 0.0F, 0.0F, 0xFF0000);
        RenderMarkerBlock.renderLine(0.0F, 0.0F, 0.0F, -lineLen, 0.0F, 0.0F, 0xFF0000);
        GL11.glPopMatrix();

        GL11.glPopMatrix();

        GL11.glPopMatrix();
    }

    private void renderRailMap(RailMap rm, double sx, double sy, double sz) {
        GL11.glPushMatrix();
        GL11.glTranslatef((float) (rm.getStartRP().posX - sx), (float) (rm.getStartRP().posY - sy), (float) (rm.getStartRP().posZ - sz));
        int split = (int) (rm.getLength() * 2.0D);
        double[] stPoint = rm.getRailPos(split, 0);
        double startH = rm.getStartRP().posY;
        float moveX = (float) (stPoint[1] - sx);
        float moveZ = (float) (stPoint[0] - sz);
        for (int i = 0; i <= split; ++i) {
            double[] curPoint = rm.getRailPos(split, i);
            float x = moveX + (float) (curPoint[1] - stPoint[1]);
            float y = (float) (rm.getRailHeight(split, i) - startH);
            float z = moveZ + (float) (curPoint[0] - stPoint[0]);
            RenderMarkerBlock.renderLine(x, y, z, x, y + 1.5F, z, 0x00FF00);
        }
        GL11.glPopMatrix();
    }
}
