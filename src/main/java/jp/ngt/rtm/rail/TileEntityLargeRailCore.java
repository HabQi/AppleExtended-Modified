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

import jp.ngt.ngtlib.math.AABBInt;
import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.GLObject;
import jp.ngt.ngtlib.util.NGTUtil;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.RTMResource;
import jp.ngt.rtm.modelpack.IResourceSelector;
import jp.ngt.rtm.modelpack.state.ResourceState;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import jp.ngt.rtm.network.PacketLargeRailCore;
import jp.ngt.rtm.render.ModelObject;
import jp.ngt.rtm.render.RailResourceBuildQueue;
import jp.ngt.rtm.rail.util.RailMap;
import jp.ngt.rtm.rail.util.RailMapBasic;
import jp.ngt.rtm.rail.util.RailMapCustom;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class TileEntityLargeRailCore extends TileEntityLargeRailBase implements IResourceSelector {
    private static final int BRIGHTNESS_CHECK_INTERVAL = 200;
    private static final int RENDER_BOUND_SAMPLES_PER_SEGMENT = 32;
    private static final int MAX_RENDER_BOUND_SAMPLES = 32768;
    private static final double MIN_RENDER_BOUND_HORIZONTAL_MARGIN = 4.0D;
    private static final double MIN_RENDER_BOUND_BELOW_MARGIN = 2.0D;
    private static final double MIN_RENDER_BOUND_ABOVE_MARGIN = 5.0D;
    public boolean breaking;
    protected boolean isCollidedTrain = false;
    public boolean colliding = false;
    private int signal = 0;
    private ResourceStateRail state = new ResourceStateRail(RTMResource.RAIL, this);
    public final List<ResourceStateRail> subRails = new ArrayList<>();

    protected RailPosition[] railPositions;
    protected RailMap railmap;

    @SideOnly(Side.CLIENT)
    private AxisAlignedBB renderAABB;
    @SideOnly(Side.CLIENT)
    private AxisAlignedBB[] renderBoundingBoxes;
    @SideOnly(Side.CLIENT)
    private boolean renderBoundingBoxesInitialized;

    @SideOnly(Side.CLIENT)
    public GLObject[] glLists;
    @SideOnly(Side.CLIENT)
    private Map<ModelObject, GLObject>[] lodGlLists;
    @SideOnly(Side.CLIENT)
    public GLObject railBlocks;
    @SideOnly(Side.CLIENT)
    private Map<String, GLObject> scriptGLLists;
    // Common-side methods update these compatibility flags after changing rail data.
    public boolean shouldRerenderRail;
    public boolean shouldRerenderBlock;
    @SideOnly(Side.CLIENT)
    private long railRenderGeneration;
    @SideOnly(Side.CLIENT)
    private boolean railRenderBuildPending;
    @SideOnly(Side.CLIENT)
    private int brightness;
    @SideOnly(Side.CLIENT)
    private boolean brightnessBaselineInitialized;
    @SideOnly(Side.CLIENT)
    private boolean brightnessCheckStaggerInitialized;
    private int count;
    protected int fixRTMRailMapVersion;

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        jp.apple.replaymod.compat.ReplaySyncManager.patchRailMetadata(this, nbt);

        super.readFromNBT(nbt);
        this.readRailStates(nbt);
        this.readRailData(nbt);
        if (this.world != null && this.world.isRemote) {
            this.renderAABB = null;
            this.renderBoundingBoxes = null;
            this.renderBoundingBoxesInitialized = false;
        }
    }

    public void readRailStates(NBTTagCompound nbt) {
        this.state.readFromNBT(nbt.getCompoundTag("State"));
        if (this.state.version < 1) {
            this.state.readFromNBT(nbt.getCompoundTag("Property"));
        }

        this.subRails.clear();
        NBTTagList list = nbt.getTagList("SubRails", 10);
        for (int i = 0; i < list.tagCount(); ++i) {
            NBTTagCompound nbt1 = list.getCompoundTagAt(i);
            ResourceStateRail state1 = new ResourceStateRail(RTMResource.RAIL, this);
            state1.readFromNBT(nbt1);
            this.subRails.add(state1);
        }
    }

    protected void readRailData(NBTTagCompound nbt) {
        if (nbt.hasKey("StartRP")) {
            this.railPositions = new RailPosition[2];
            this.railPositions[0] = RailPosition.readFromNBT(nbt.getCompoundTag("StartRP"));
            this.railPositions[1] = RailPosition.readFromNBT(nbt.getCompoundTag("EndRP"));
            this.fixRTMRailMapVersion = nbt.getInteger("fixRTMRailMapVersion");
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        this.writeRailStates(nbt);
        this.writeRailData(nbt);
        return nbt;
    }

    public void writeRailStates(NBTTagCompound nbt) {
        nbt.setTag("State", this.state.writeToNBT());
        NBTTagList tagList = new NBTTagList();
        for (ResourceStateRail state1 : this.subRails) {
            tagList.appendTag(state1.writeToNBT());
        }
        nbt.setTag("SubRails", tagList);
    }

    protected void writeRailData(NBTTagCompound nbt) {
        nbt.setTag("StartRP", this.railPositions[0].writeToNBT());
        nbt.setTag("EndRP", this.railPositions[1].writeToNBT());
        nbt.setInteger("fixRTMRailMapVersion", this.fixRTMRailMapVersion);
    }

    @Override
    public void setStartPoint(int x, int y, int z) {
        this.startPoint[0] = x;
        this.startPoint[1] = y;
        this.startPoint[2] = z;
    }

    public void createRailMap() {
        if (this.isLoaded()) {
            if (this.getRailPositions()[0].hasScript()) {
                RailPosition railposition = this.getRailPositions()[0];
                this.railmap = new RailMapCustom(railposition, railposition.scriptName, railposition.scriptArgs);
            } else {
                this.railmap = new RailMapBasic(this.railPositions[0], this.railPositions[1], fixRTMRailMapVersion);
            }
        }
    }

    public boolean isLoaded() {
        return this.railPositions != null && this.railPositions.length > 0;
    }

    public RailPosition[] getRailPositions() {
        return this.railPositions;
    }

    /** Waits until every endpoint is inside the configured render radius and its chunk data is present. */
    @SideOnly(Side.CLIENT)
    public boolean areAllRailEndpointChunksReadyToRender() {
        if (this.world == null || this.railPositions == null || this.railPositions.length < 2) {
            return false;
        }

        Entity viewEntity = NGTUtilClient.getMinecraft().getRenderViewEntity();
        if (viewEntity == null) {
            return false;
        }
        double viewDistance = NGTUtilClient.getMinecraft().gameSettings.renderDistanceChunks * 16.0D;
        double viewDistanceSq = viewDistance * viewDistance;

        for (RailPosition railPosition : this.railPositions) {
            if (railPosition == null) {
                return false;
            }
            double deltaX = railPosition.posX - viewEntity.posX;
            double deltaZ = railPosition.posZ - viewEntity.posZ;
            if (deltaX * deltaX + deltaZ * deltaZ > viewDistanceSq
                    || !this.world.isBlockLoaded(
                            new BlockPos(railPosition.blockX, railPosition.blockY, railPosition.blockZ))) {
                return false;
            }
        }
        return true;
    }

    public void setRailPositions(RailPosition[] par1) {
        if (this.world != null && this.world.isRemote) {
            RailResourceBuildQueue.INSTANCE.cancel(this);
            this.clearScriptGLLists();
        }
        this.railPositions = par1;
        this.markRailRenderDirty();
        this.shouldRerenderBlock = true;
    }

    @SideOnly(Side.CLIENT)
    public long getRailRenderGeneration() {
        return this.railRenderGeneration;
    }

    /** Invalidates every static rail slot while allowing the previous GPU resource to remain visible. */
    public void markRailRenderDirty() {
        if (this.world != null && this.world.isRemote) {
            this.renderAABB = null;
            this.renderBoundingBoxes = null;
            this.renderBoundingBoxesInitialized = false;
            ++this.railRenderGeneration;
            this.railRenderBuildPending = true;
            RailResourceBuildQueue.INSTANCE.cancel(this);
            this.clearLodGLLists();
        }
        this.shouldRerenderRail = true;
    }

    /** Converts writes to the legacy public dirty flag into a new async generation. */
    @SideOnly(Side.CLIENT)
    public void syncLegacyRailRenderDirty() {
        if (this.shouldRerenderRail && !this.railRenderBuildPending) {
            this.markRailRenderDirty();
        }
    }

    @SideOnly(Side.CLIENT)
    public void beginRailRenderBuild(long generation) {
        if (this.railRenderGeneration == generation) {
            this.railRenderBuildPending = true;
            this.shouldRerenderRail = true;
        }
    }

    @SideOnly(Side.CLIENT)
    public void finishRailRenderBuild(long generation) {
        if (this.railRenderGeneration == generation) {
            this.railRenderBuildPending = false;
            this.shouldRerenderRail = false;
        }
    }

    public int getSignal() {
        return this.signal;
    }

    public void setSignal(int par1) {
        this.signal = par1;
    }

    @Override
    public TileEntityLargeRailCore getRailCore() {
        return this;
    }

    @Override
    public void sendPacket() {
        if ((this.world == null || !this.world.isRemote) && this.isLoaded()) {
            RTMCore.NETWORK_WRAPPER.sendToAll(new PacketLargeRailCore(this, this.getPacketType()));
        }
    }

    public byte getPacketType() {
        return PacketLargeRailCore.TYPE_NORMAL;
    }

    @Override
    public void onChunkUnload() {
        if (this.world.isRemote) {
            this.markRailRenderDirty();
            this.deleteGLList();
        }
    }

    @Override
    public void invalidate() {
        if (this.world.isRemote) {
            this.markRailRenderDirty();
            this.deleteGLList();
        }
    }

    @SideOnly(Side.CLIENT)
    private void deleteGLList() {
        if (this.glLists != null) {
            for (GLObject glList : this.glLists) {
                GLHelper.deleteGLList(glList);
            }
        }
        GLHelper.deleteGLList(this.railBlocks);
        this.clearLodGLLists();
        this.clearScriptGLLists();
        this.glLists = new GLObject[this.subRails.size() + 1];
        this.railBlocks = null;
    }

    @SideOnly(Side.CLIENT)
    public GLObject getLodGLList(ModelObject modelObject, int slot) {
        if (modelObject == null || this.lodGlLists == null || slot < 0 || slot >= this.lodGlLists.length) {
            return null;
        }
        Map<ModelObject, GLObject> slotLists = this.lodGlLists[slot];
        return slotLists == null ? null : slotLists.get(modelObject);
    }

    @SideOnly(Side.CLIENT)
    public void setLodGLList(ModelObject modelObject, int slot, GLObject resource) {
        if (modelObject == null || slot < 0) {
            return;
        }
        this.ensureLodGLSlots();
        if (slot >= this.lodGlLists.length) {
            GLHelper.deleteGLList(resource);
            return;
        }

        Map<ModelObject, GLObject> slotLists = this.lodGlLists[slot];
        if (slotLists == null) {
            slotLists = new HashMap<>();
            this.lodGlLists[slot] = slotLists;
        }
        GLObject old = resource == null ? slotLists.remove(modelObject) : slotLists.put(modelObject, resource);
        if (old != null && old != resource) {
            GLHelper.deleteGLList(old);
        }
    }

    @SideOnly(Side.CLIENT)
    private void ensureLodGLSlots() {
        int expected = this.subRails.size() + 1;
        if (this.lodGlLists == null || this.lodGlLists.length != expected) {
            this.clearLodGLLists();
            @SuppressWarnings("unchecked")
            Map<ModelObject, GLObject>[] lists = new Map[expected];
            this.lodGlLists = lists;
        }
    }

    @SideOnly(Side.CLIENT)
    private void clearLodGLLists() {
        if (this.lodGlLists == null) {
            return;
        }
        for (Map<ModelObject, GLObject> slotLists : this.lodGlLists) {
            if (slotLists == null) {
                continue;
            }
            for (GLObject resource : slotLists.values()) {
                GLHelper.deleteGLList(resource);
            }
            slotLists.clear();
        }
        this.lodGlLists = null;
    }

    @SideOnly(Side.CLIENT)
    private void clearScriptGLLists() {
        if (this.scriptGLLists == null) {
            return;
        }
        for (GLObject scriptList : this.scriptGLLists.values()) {
            GLHelper.deleteGLList(scriptList);
        }
        this.scriptGLLists.clear();
    }

    @SideOnly(Side.CLIENT)
    private Map<String, GLObject> getOrCreateScriptGLLists() {
        if (this.scriptGLLists == null) {
            this.scriptGLLists = new HashMap<>();
        }
        return this.scriptGLLists;
    }

    /** Compatibility cache exposed to model-pack scripts for switch geometry. */
    @SideOnly(Side.CLIENT)
    public GLObject getScriptGLList(String key) {
        return key == null || this.scriptGLLists == null ? null : this.scriptGLLists.get(key);
    }

    /** Stores a script-owned display list and releases the replaced list. */
    @SideOnly(Side.CLIENT)
    public void setScriptGLList(String key, GLObject value) {
        if (key == null) {
            return;
        }
        GLObject old;
        if (value == null) {
            old = this.scriptGLLists == null ? null : this.scriptGLLists.remove(key);
        } else {
            old = this.getOrCreateScriptGLLists().put(key, value);
        }
        if (old != null && old != value) {
            GLHelper.deleteGLList(old);
        }
    }

    public void replaceRail(ResourceStateRail state) {
        this.getResourceState().readFromNBT(state.writeToNBT());
        this.subRails.clear();
        this.sendPacket();
    }

    public void addSubRail(ResourceStateRail state) {
        ResourceStateRail newState = new ResourceStateRail(RTMResource.RAIL, this);
        newState.readFromNBT(state.writeToNBT());
        ResourceStateRail oldState = null;
        for (ResourceStateRail state1 : this.subRails) {
            if (state1.getResourceName().equals(newState.getResourceName())) {
                oldState = state1;
                break;
            }
        }

        if (oldState == null) {
            if (!this.getResourceState().getResourceName().equals(newState.getResourceName())) {
                this.subRails.add(newState);
            }
        } else {
            this.subRails.remove(oldState);
        }

        this.sendPacket();
    }

    @Override
    public void update() {
        super.update();

        if (!this.world.isRemote) {
            this.isCollidedTrain = this.colliding;
            this.colliding = false;
        } else {
            if (!this.brightnessCheckStaggerInitialized) {
                this.count = Math.floorMod(this.getPos().hashCode(), BRIGHTNESS_CHECK_INTERVAL);
                this.brightnessCheckStaggerInitialized = true;
            }
            if (++this.count >= BRIGHTNESS_CHECK_INTERVAL) {
                this.updateBrightness();
                this.count = 0;
            }

        }
    }

    @SideOnly(Side.CLIENT)
    private void updateBrightness() {
        int light = NGTUtil.getSkyLight(this.getWorld(), this.getPos());
        if (!this.brightnessBaselineInitialized) {
            this.brightness = light;
            this.brightnessBaselineInitialized = true;
            return;
        }
        if (this.brightness != light) {
            this.brightness = light;
            this.markRailRenderDirty();
            this.shouldRerenderBlock = true;
        }
    }

    @SideOnly(Side.CLIENT)
    public void initializeBrightnessBaseline() {
        if (!this.brightnessBaselineInitialized && this.world != null) {
            this.brightness = NGTUtil.getSkyLight(this.world, this.getPos());
            this.brightnessBaselineInitialized = true;
        }
    }

    @SideOnly(Side.CLIENT)
    private void resetBrightnessTracking() {
        this.brightnessBaselineInitialized = false;
        this.brightnessCheckStaggerInitialized = false;
        this.count = 0;
    }

    @Override
    public RailMap getRailMap(Entity entity) {
        if (this.railmap == null) {
            this.createRailMap();
        }
        return this.railmap;
    }

    @Nullable
    public RailMap[] getAllRailMaps() {
        RailMap rm = this.getRailMap(null);
        return rm != null ? new RailMap[]{rm} : null;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public double getMaxRenderDistanceSquared() {
        return NGTUtil.getChunkLoadDistanceSq() * 4;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if (!this.isLoaded()) {
            return INFINITE_EXTENT_AABB;
        }

        if (this.renderAABB == null) {
            this.renderAABB = this.getRenderAABB();
            if (this.renderAABB == null) {
                return INFINITE_EXTENT_AABB;
            }
        }
        return this.renderAABB;
    }

    @SideOnly(Side.CLIENT)
    public AxisAlignedBB[] getRenderBoundingBoxes() {
        if (!this.isLoaded()) {
            return null;
        }
        if (!this.renderBoundingBoxesInitialized) {
            this.renderBoundingBoxesInitialized = true;
            this.renderBoundingBoxes = this.createRenderBoundingBoxes();
        }
        return this.renderBoundingBoxes;
    }

    @SideOnly(Side.CLIENT)
    private AxisAlignedBB[] createRenderBoundingBoxes() {
        try {
            RailMap[] railMaps = this.getAllRailMaps();
            if (railMaps == null || railMaps.length == 0) {
                return null;
            }

            double horizontalMargin = MIN_RENDER_BOUND_HORIZONTAL_MARGIN;
            double belowMargin = MIN_RENDER_BOUND_BELOW_MARGIN;
            double aboveMargin = MIN_RENDER_BOUND_ABOVE_MARGIN;
            for (RailPosition railPosition : this.railPositions) {
                if (railPosition == null) {
                    return null;
                }
                horizontalMargin = Math.max(horizontalMargin,
                        Math.max(Math.abs(railPosition.constLimitWN), Math.abs(railPosition.constLimitWP)) + 1.0D);
                belowMargin = Math.max(belowMargin, Math.max(0.0D, -railPosition.constLimitHN) + 1.0D);
                aboveMargin = Math.max(aboveMargin, Math.max(0.0D, railPosition.constLimitHP) + 1.0D);
            }

            List<AxisAlignedBB> boxes = new ArrayList<>();
            for (RailMap railMap : railMaps) {
                if (railMap == null || !this.addRenderBoundingBoxes(
                        boxes, railMap, horizontalMargin, belowMargin, aboveMargin)) {
                    return null;
                }
            }
            return boxes.isEmpty() ? null : boxes.toArray(new AxisAlignedBB[boxes.size()]);
        } catch (RuntimeException | LinkageError ignored) {
            // Invalid or unavailable script geometry must remain visible rather than be incorrectly culled.
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private boolean addRenderBoundingBoxes(List<AxisAlignedBB> boxes, RailMap railMap,
                                           double horizontalMargin, double belowMargin, double aboveMargin) {
        double length = railMap.getLength();
        if (!Double.isFinite(length) || length < 0.0D) {
            return false;
        }
        int split = (int) Math.min(MAX_RENDER_BOUND_SAMPLES,
                Math.max(1.0D, Math.ceil(length * 2.0D)));

        for (int start = 0; start < split; start += RENDER_BOUND_SAMPLES_PER_SEGMENT) {
            int end = Math.min(split, start + RENDER_BOUND_SAMPLES_PER_SEGMENT);
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (int index = start; index <= end; ++index) {
                double[] position = railMap.getRailPos(split, index);
                double height = railMap.getRailHeight(split, index);
                if (position == null || position.length < 2
                        || !Double.isFinite(position[0]) || !Double.isFinite(position[1])
                        || !Double.isFinite(height)) {
                    return false;
                }
                double x = position[1];
                double z = position[0];
                minX = Math.min(minX, x);
                minY = Math.min(minY, height);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, height);
                maxZ = Math.max(maxZ, z);
            }

            boxes.add(new AxisAlignedBB(
                    minX - horizontalMargin, minY - belowMargin, minZ - horizontalMargin,
                    maxX + horizontalMargin, maxY + aboveMargin, maxZ + horizontalMargin));
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    protected AxisAlignedBB getRenderAABB() {
        AABBInt box = this.getRailSize();
        AxisAlignedBB aabb = new AxisAlignedBB(box.minX - 1, box.minY, box.minZ - 1, box.maxX + 2, box.maxY + 2, box.maxZ + 2);
        if (aabb.maxX - aabb.minX <= 3 && aabb.maxZ - aabb.minZ <= 3) {
            return null;
        }
        return aabb;
    }

    public AABBInt getRailSize() {
        int startX = this.railPositions[0].blockX;
        int startY = this.railPositions[0].blockY;
        int startZ = this.railPositions[0].blockZ;
        int endX = this.railPositions[1].blockX;
        int endY = this.railPositions[1].blockY;
        int endZ = this.railPositions[1].blockZ;

        int minX = startX <= endX ? startX : endX;
        int maxX = startX >= endX ? startX : endX;
        int minY = startY <= endY ? startY : endY;
        int maxY = startY >= endY ? startY : endY;
        int minZ = startZ <= endZ ? startZ : endZ;
        int maxZ = startZ >= endZ ? startZ : endZ;
        return new AABBInt(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public void setPos(int x, int y, int z, int prevX, int prevY, int prevZ) {
        if (this.world != null && this.world.isRemote) {
            RailResourceBuildQueue.INSTANCE.cancel(this);
            this.clearScriptGLLists();
            this.resetBrightnessTracking();
        }
        int difX = x - prevX;
        int difY = y - prevY;
        int difZ = z - prevZ;
        for (RailPosition rp : this.railPositions) {
            rp.movePos(difX, difY, difZ);
        }
        super.setPos(x, y, z, prevX, prevY, prevZ);
        this.markRailRenderDirty();
        this.shouldRerenderBlock = true;
    }

    @Override
    public void updateResourceState() {
        if (this.world != null && this.world.isRemote) {
            RailResourceBuildQueue.INSTANCE.cancel(this);
            this.clearScriptGLLists();
        }
        if (this.world == null || !this.world.isRemote) {
            this.markDirty();
            this.sendPacket();
        }
        this.markRailRenderDirty();
        this.shouldRerenderBlock = true;
    }

    @Override
    public int[] getSelectorPos() {
        return new int[]{this.getPos().getX(), this.getPos().getY(), this.getPos().getZ()};
    }

    @Override
    public boolean closeGui(ResourceState par1) {
        return true;
    }

    @Override
    public ResourceStateRail getResourceState() {
        return this.state;
    }

    public abstract String getRailShapeName();
}
