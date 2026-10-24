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

package jp.ngt.rtm.rail;

import jp.ngt.ngtlib.block.BlockUtil;
import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.GLObject;
import jp.ngt.ngtlib.renderer.IRenderer;
import jp.ngt.ngtlib.renderer.NGTTessellator;
import jp.ngt.ngtlib.renderer.VertexArray2;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.render.RailBuildProfiler;
import jp.ngt.rtm.render.RailResourceBuildQueue;
import jp.ngt.rtm.rail.util.RailMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * ISimpleBlockRenderingHandlerが使えなくなったのでTileEntityとして描画する
 */
@SideOnly(Side.CLIENT)
public final class RenderRailBlock {
    private static final float SNOW_THICKNESS = 0.125F;

    private final List<int[]> blockPosList = new ArrayList<>();
    private final List<Block> preparedBlocks = new ArrayList<>();
    private final List<TileEntityLargeRailBase> preparedTiles = new ArrayList<>();
    private boolean preparedDataComplete;

    //VA版
    public void renderRailBlocksVA(TileEntityLargeRailCore tileEntity, double par2, double par4, double par6, float par8) {
        if (!(tileEntity.railBlocks instanceof VertexArray2)) {
            this.renderRailBlocks(tileEntity, par2, par4, par6, par8);
            return;
        }
        boolean hasGLList = GLHelper.isValid(tileEntity.railBlocks);
        if (!this.isResourceReady(tileEntity)) {
            RailResourceBuildQueue.INSTANCE.enqueueBlock(tileEntity);
            if (hasGLList && tileEntity.shouldRerenderBlock) {
                GL11.glPushMatrix();
                GL11.glTranslatef((float) (par2 - tileEntity.getX()), (float) (par4 - tileEntity.getY()), (float) (par6 - tileEntity.getZ()));
                NGTUtilClient.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                ((VertexArray2) tileEntity.railBlocks).render();
                GL11.glPopMatrix();
            }
            return;
        }
        if (!hasGLList) {
            tileEntity.railBlocks = GLHelper.generateVA();
        } else if (tileEntity.shouldRerenderBlock)//再描画
        {
            hasGLList = false;
        }

        if (!hasGLList)//ディスプレイリスト生成
        {
            if (this.renderBlocks(tileEntity, (IRenderer) tileEntity.railBlocks)) {
                tileEntity.shouldRerenderBlock = false;
                hasGLList = true;
            } else {
                tileEntity.shouldRerenderBlock = true;
                hasGLList = false;
            }
        }

        if (hasGLList)//ディスプレイリスト描画
        {
            GL11.glPushMatrix();
            GL11.glTranslatef((float) (par2 - tileEntity.getX()), (float) (par4 - tileEntity.getY()), (float) (par6 - tileEntity.getZ()));
            NGTUtilClient.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);//ディスプレイリストに入れると生成重い
            ((VertexArray2) tileEntity.railBlocks).render();
            GL11.glPopMatrix();
        }
    }

    //GLList版
    public void renderRailBlocks(TileEntityLargeRailCore tileEntity, double par2, double par4, double par6, float par8) {
        boolean hasGLList = GLHelper.isValid(tileEntity.railBlocks);
        if (!this.isResourceReady(tileEntity)) {
            RailResourceBuildQueue.INSTANCE.enqueueBlock(tileEntity);
            if (hasGLList) {
                GL11.glPushMatrix();
                GL11.glTranslatef((float) (par2 - tileEntity.getX()), (float) (par4 - tileEntity.getY()), (float) (par6 - tileEntity.getZ()));
                NGTUtilClient.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                GLHelper.callList(tileEntity.railBlocks);
                GL11.glPopMatrix();
            }
            return;
        }
        if (!hasGLList) {
            tileEntity.railBlocks = GLHelper.generateGLList(tileEntity.railBlocks);
        } else if (tileEntity.shouldRerenderBlock)//再描画
        {
            hasGLList = false;
        }

        if (!hasGLList)//ディスプレイリスト生成
        {
            NGTTessellator tessellator = NGTTessellator.instance;
            GLHelper.startCompile(tileEntity.railBlocks);
            if (this.renderBlocks(tileEntity, tessellator)) {
                tileEntity.shouldRerenderBlock = false;
                hasGLList = true;
            } else {
                tileEntity.shouldRerenderBlock = true;
                hasGLList = false;
            }
            GLHelper.endCompile();
        }

        if (hasGLList)//ディスプレイリスト描画
        {
            GL11.glPushMatrix();
            GL11.glTranslatef((float) (par2 - tileEntity.getX()), (float) (par4 - tileEntity.getY()), (float) (par6 - tileEntity.getZ()));
            NGTUtilClient.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);//ディスプレイリストに入れると生成重い
            GLHelper.callList(tileEntity.railBlocks);
            GL11.glPopMatrix();
        }
    }

    /**
     * @return 全ブロックの描画に成功
     */
    public boolean isResourceReady(TileEntityLargeRailCore rail) {
        return rail != null && !rail.shouldRerenderBlock && GLHelper.isValid(rail.railBlocks);
    }

    /** Builds available ballast data into a temporary display list. */
    public boolean buildResource(TileEntityLargeRailCore rail) {
        if (rail == null) {
            return false;
        }
        if (this.isResourceReady(rail)) {
            return true;
        }
        if (!this.prepareBlocks(rail)) {
            rail.shouldRerenderBlock = true;
            return false;
        }
        boolean dataComplete = this.preparedDataComplete;

        GLObject newList = GLHelper.generateGLList(null);
        boolean compiling = false;
        boolean success;
        long compileStartedAt = System.nanoTime();
        try {
            GLHelper.startCompile(newList);
            compiling = true;
            success = this.renderPreparedBlocks(rail, NGTTessellator.instance);
        } catch (RuntimeException | Error throwable) {
            GLHelper.deleteGLList(newList);
            throw throwable;
        } finally {
            this.clearPreparedBlocks();
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.BALLAST_COMPILE,
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
        if (!success) {
            GLHelper.deleteGLList(newList);
            rail.shouldRerenderBlock = true;
            return false;
        }

        GLObject oldList = rail.railBlocks;
        rail.railBlocks = newList;
        rail.shouldRerenderBlock = !dataComplete;
        rail.initializeBrightnessBaseline();
        GLHelper.deleteGLList(oldList);
        return dataComplete;
    }

    public boolean renderBlocks(TileEntityLargeRailCore rail, IRenderer renderer) {
        if (!this.prepareBlocks(rail)) {
            return false;
        }
        boolean dataComplete = this.preparedDataComplete;
        try {
            return this.renderPreparedBlocks(rail, renderer) && dataComplete;
        } finally {
            this.clearPreparedBlocks();
        }
    }

    private boolean prepareBlocks(TileEntityLargeRailCore rail) {
        this.clearPreparedBlocks();
        this.preparedDataComplete = true;
        long mapStartedAt = System.nanoTime();
        RailMap[] rms = rail.getAllRailMaps();
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.RAIL_MAP, System.nanoTime() - mapStartedAt);
        if (rms == null) {
            return false;
        }

        long blockListStartedAt = System.nanoTime();
        for (RailMap rm : rms) {
            List<int[]> list = rm.getRailBlockList(rail.getResourceState(), false);
			/*if(!this.checkRailBlock(tileEntity.getWorld(), list))
			{
				break;//ブロック取得失敗時は描画破棄→MissingBlockを描画するよう変更
			}*/
            this.blockPosList.addAll(list);
        }
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.BALLAST_BLOCK_LIST,
                System.nanoTime() - blockListStartedAt);

        if (this.blockPosList.size() < 2) {
            this.clearPreparedBlocks();
            return false;
        }

        World world = rail.getWorld();
        ResourceStateRail property = rail.getResourceState();
        long validationStartedAt = System.nanoTime();
        try {
            for (int[] position : this.blockPosList) {
                BlockPos blockPos = new BlockPos(position[0], position[1], position[2]);
                if (!world.isBlockLoaded(blockPos)) {
                    RailBuildProfiler.recordBallastDataIssue(RailBuildProfiler.BallastDataIssue.CHUNK_UNLOADED);
                    this.preparedDataComplete = false;
                    this.preparedBlocks.add(null);
                    this.preparedTiles.add(null);
                    continue;
                }

                Block block = world.getBlockState(blockPos).getBlock();
                TileEntityLargeRailBase tile = null;
                if (block instanceof BlockLargeRailBase && property.block != Blocks.AIR) {
                    TileEntity tileEntity = BlockUtil.getTileEntity(world, blockPos);
                    if (!(tileEntity instanceof TileEntityLargeRailBase)) {
                        RailBuildProfiler.recordBallastDataIssue(RailBuildProfiler.BallastDataIssue.TILE_ENTITY_MISSING);
                        this.preparedDataComplete = false;
                        this.preparedBlocks.add(null);
                        this.preparedTiles.add(null);
                        continue;
                    }
                    tile = (TileEntityLargeRailBase) tileEntity;
                } else if (!(block instanceof BlockLargeRailBase) && property.block != Blocks.AIR) {
                    RailBuildProfiler.recordBallastDataIssue(RailBuildProfiler.BallastDataIssue.ACTUAL_BLOCK_MISSING);
                }
                this.preparedBlocks.add(block);
                this.preparedTiles.add(tile);
            }
            return true;
        } finally {
            RailBuildProfiler.recordStage(RailBuildProfiler.Stage.BALLAST_VALIDATE,
                    System.nanoTime() - validationStartedAt);
        }
    }

    private boolean renderPreparedBlocks(TileEntityLargeRailCore rail, IRenderer renderer) {
        long tessellationStartedAt = System.nanoTime();
        renderer.startDrawing(GL11.GL_QUADS);
        for (int i = 0; i < this.blockPosList.size(); ++i) {
            int[] ia = this.blockPosList.get(i);
            World world = rail.getWorld();
            int x = ia[0];
            int y = ia[1];
            int z = ia[2];
            Block block = this.preparedBlocks.get(i);
            if (block == null) {
                continue;
            }
            TileEntityLargeRailBase tile = this.preparedTiles.get(i);
            if (tile == null) {
                if (rail.getResourceState().block != Blocks.AIR) {
                    this.renderMissingBlock(renderer, world, x, y, z);
                }
            } else {
                this.renderWorldBlock(rail, renderer, world, x, y, z,
                        block, tile);
            }
        }

        renderer.draw();
        RailBuildProfiler.recordStage(RailBuildProfiler.Stage.BALLAST_TESSELLATION,
                System.nanoTime() - tessellationStartedAt);

        return true;
    }

    private void renderWorldBlock(TileEntityLargeRailCore rail, IRenderer renderer, IBlockAccess world,
                                  int x, int y, int z, Block block, TileEntityLargeRailBase tile) {
        ResourceStateRail prop = rail.getResourceState();
        if (prop.block == Blocks.AIR) {
            return;
        }

        float[] fa = tile.getBlockHeights(x, y, z, prop.blockHeight, false);
        float y0 = fa[0];
        float y1 = fa[1];
        float y2 = fa[2];
        float y3 = fa[3];

        //renderer.setBrightness(block.getMixedBrightnessForBlock(world, tile.getPos()));
        BlockPos origPos = tile.getPos();
        int brightness = block.getPackedLightmapCoords(world.getBlockState(origPos), world, origPos);
        renderer.setBrightness(brightness);
        ;

        //雪の厚み
        if (prop.block.getMaterial(prop.block.getDefaultState()) == Material.CRAFTED_SNOW) {
            y0 += SNOW_THICKNESS;
            y1 += SNOW_THICKNESS;
            y2 += SNOW_THICKNESS;
            y3 += SNOW_THICKNESS;
        }

        TextureAtlasSprite icon;
        float minU;
        float maxU;
        float maxV;
        float v;

        IBlockState sideBlock = BlockUtil.getBlockState(world, x, y, z + 1);
        if (!sideBlock.isOpaqueCube() && !(sideBlock.getBlock() instanceof BlockLargeRailBase)) {
            icon = this.getIcon(prop.block, prop.meta, 3);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(204, 204, 204, 255);
            //renderer.setColorOpaque_F(0.8F, 0.8F, 0.8F);
            renderer.addVertexWithUV(x + 0.0F, y + y0, z + 1.0F, minU, maxV - (v * this.getV(y0)));
            renderer.addVertexWithUV(x + 0.0F, y + 0.0F, z + 1.0F, minU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y + 0.0F, z + 1.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y + y1, z + 1.0F, maxU, maxV - (v * this.getV(y1)));
        }

        sideBlock = BlockUtil.getBlockState(world, x + 1, y, z);
        if (!sideBlock.isOpaqueCube() && !(sideBlock.getBlock() instanceof BlockLargeRailBase)) {
            icon = this.getIcon(prop.block, prop.meta, 5);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(153, 153, 153, 255);
            //renderer.setColorOpaque_F(0.6F, 0.6F, 0.6F);
            renderer.addVertexWithUV(x + 1.0F, y + y1, z + 1.0F, minU, maxV - (v * this.getV(y1)));
            renderer.addVertexWithUV(x + 1.0F, y + 0.0F, z + 1.0F, minU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y + 0.0F, z + 0.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y + y2, z + 0.0F, maxU, maxV - (v * this.getV(y2)));
        }

        sideBlock = BlockUtil.getBlockState(world, x, y, z - 1);
        if (!sideBlock.isOpaqueCube() && !(sideBlock.getBlock() instanceof BlockLargeRailBase)) {
            icon = this.getIcon(prop.block, prop.meta, 2);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(204, 204, 204, 255);
            //renderer.setColorOpaque_F(0.8F, 0.8F, 0.8F);
            renderer.addVertexWithUV(x + 1.0F, y + y2, z + 0.0F, minU, maxV - (v * this.getV(y2)));
            renderer.addVertexWithUV(x + 1.0F, y + 0.0F, z + 0.0F, minU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y + 0.0F, z + 0.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y + y3, z + 0.0F, maxU, maxV - (v * this.getV(y3)));
        }

        sideBlock = BlockUtil.getBlockState(world, x - 1, y, z);
        if (!sideBlock.isOpaqueCube() && !(sideBlock.getBlock() instanceof BlockLargeRailBase)) {
            icon = this.getIcon(prop.block, prop.meta, 4);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(153, 153, 153, 255);
            //renderer.setColorOpaque_F(0.6F, 0.6F, 0.6F);
            renderer.addVertexWithUV(x + 0.0F, y + y3, z + 0.0F, minU, maxV - (v * this.getV(y3)));
            renderer.addVertexWithUV(x + 0.0F, y + 0.0F, z + 0.0F, minU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y + 0.0F, z + 1.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y + y0, z + 1.0F, maxU, maxV - (v * this.getV(y0)));
        }

        //上面
        {
            icon = this.getIcon(prop.block, prop.meta, 1);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(255, 255, 255, 255);
            //renderer.setColorOpaque_F(1.0F, 1.0F, 1.0F);
            renderer.addVertexWithUV(x + 0.0F, y + y0, z + 1.0F, minU, maxV - v);
            renderer.addVertexWithUV(x + 1.0F, y + y1, z + 1.0F, minU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y + y2, z + 0.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y + y3, z + 0.0F, maxU, maxV - v);
        }

        //下面
        sideBlock = BlockUtil.getBlockState(world, x, y - 1, z);
        if (!sideBlock.isOpaqueCube()) {
            icon = this.getIcon(prop.block, prop.meta, 0);
            minU = icon.getMinU();
            maxU = icon.getMaxU();
            maxV = icon.getMaxV();
            v = maxV - icon.getMinV();

            renderer.setColor(127, 127, 127, 255);
            //renderer.setColorOpaque_F(0.5F, 0.5F, 0.5F);
            renderer.addVertexWithUV(x + 0.0F, y, z + 0.0F, minU, maxV - v);
            renderer.addVertexWithUV(x + 1.0F, y, z + 0.0F, minU, maxV);
            renderer.addVertexWithUV(x + 1.0F, y, z + 1.0F, maxU, maxV);
            renderer.addVertexWithUV(x + 0.0F, y, z + 1.0F, maxU, maxV - v);
        }

    }

    private void clearPreparedBlocks() {
        this.blockPosList.clear();
        this.preparedBlocks.clear();
        this.preparedTiles.clear();
    }

    private void renderMissingBlock(IRenderer renderer, IBlockAccess world, int x, int y, int z) {
        TextureAtlasSprite icon = this.getIcon(Blocks.BEDROCK, 0, 1);
        float minU = icon.getMinU();
        float maxU = icon.getMaxU();
        float maxV = icon.getMaxV();
        float minV = icon.getMinV();
        float y0 = 0.0625F;

        renderer.setColor(255, 255, 255, 255);
        //renderer.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        renderer.addVertexWithUV(x + 0.0F, y + y0, z + 1.0F, minU, minV);
        renderer.addVertexWithUV(x + 1.0F, y + y0, z + 1.0F, minU, maxV);
        renderer.addVertexWithUV(x + 1.0F, y + y0, z + 0.0F, maxU, maxV);
        renderer.addVertexWithUV(x + 0.0F, y + y0, z + 0.0F, maxU, minV);
    }

    private TextureAtlasSprite getIcon(Block block, int meta, int side) {
        IBlockState state = block.getStateFromMeta(meta);
        return Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(state);
    }

    private float getV(float par1) {
        return par1 > 1.0F ? 1.0F : (par1 < 0.0F ? 0.0F : par1);
    }

    @Deprecated
    /**道床位置のTileEntityが取得可能か*/
    private boolean checkRailBlock(World world, List<int[]> list) {
        for (int[] pos : list) {
            TileEntityLargeRailBase tile = (TileEntityLargeRailBase) BlockUtil.getTileEntity(world, pos[0], pos[1], pos[2]);
            if (tile == null) {
                return false;
            }
            TileEntityLargeRailCore core = tile.getRailCore();
            if (core == null) {
                return false;
            }
        }
        return true;
    }
}
