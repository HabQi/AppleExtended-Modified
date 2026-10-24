/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.entity.train.parts;

import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.util.NGTUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

import java.util.Set;

/** Tracks train floor visibility from its live position without sending movement packets. */
public final class FloorTrackerEntry extends EntityTrackerEntry {
    private static final int FLOOR_TRACKING_RANGE = 1024;

    private final Entity trackedEntity;
    private final int trackingRange;
    private int maxTrackingRange;

    public FloorTrackerEntry(Entity trackedEntity, int trackingRange, int maxTrackingRange) {
        super(trackedEntity, trackingRange, maxTrackingRange, Integer.MAX_VALUE, false);
        this.trackedEntity = trackedEntity;
        this.trackingRange = trackingRange;
        this.maxTrackingRange = maxTrackingRange;
    }

    @Override
    public boolean isVisibleTo(EntityPlayerMP player) {
        return this.isWithinTrackingRange(player.posX, player.posZ)
                && this.trackedEntity.isSpectatedByPlayer(player);
    }

    private boolean isWithinTrackingRange(double observerX, double observerZ) {
        int effectiveRange = Math.min(this.trackingRange, this.maxTrackingRange);
        double deltaX = observerX - this.trackedEntity.posX;
        double deltaZ = observerZ - this.trackedEntity.posZ;
        return deltaX >= -effectiveRange && deltaX <= effectiveRange
                && deltaZ >= -effectiveRange && deltaZ <= effectiveRange;
    }

    @Override
    public void setMaxRange(int maxTrackingRange) {
        super.setMaxRange(maxTrackingRange);
        this.maxTrackingRange = maxTrackingRange;
    }

    /** Ensures floors use this tracker even when EntityTracker loaded before the ASM hook. */
    public static boolean ensureTracking(Entity floor) {
        if (!(floor.world instanceof WorldServer)) {
            return false;
        }

        WorldServer world = (WorldServer) floor.world;
        EntityTracker tracker = world.getEntityTracker();
        EntityTrackerEntry current = tracker.trackedEntityHashTable.lookup(floor.getEntityId());
        if (current instanceof FloorTrackerEntry) {
            NGTLog.debug("[FloorTracker] active entity=%d type=%s source=asm observers=%d",
                    floor.getEntityId(), floor.getClass().getSimpleName(), current.trackingPlayers.size());
            return true;
        }
        if (current == null) {
            return false;
        }

        Set<EntityTrackerEntry> entries = getTrackedEntries(tracker);
        if (entries == null || !entries.remove(current)) {
            return false;
        }

        FloorTrackerEntry replacement = new FloorTrackerEntry(floor, FLOOR_TRACKING_RANGE,
                world.getMinecraftServer().getPlayerList().getEntityViewDistance());
        replacement.trackingPlayers.addAll(current.trackingPlayers);
        entries.add(replacement);
        tracker.trackedEntityHashTable.addKey(floor.getEntityId(), replacement);
        NGTLog.debug("[FloorTracker] active entity=%d type=%s source=tick-fallback observers=%d",
                floor.getEntityId(), floor.getClass().getSimpleName(), replacement.trackingPlayers.size());
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Set<EntityTrackerEntry> getTrackedEntries(EntityTracker tracker) {
        return (Set<EntityTrackerEntry>) NGTUtil.getField(EntityTracker.class, tracker,
                new String[]{"entries", "field_72793_b"});
    }
}
