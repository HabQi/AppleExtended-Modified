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

package jp.ngt.ngtlib.renderer;

import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.render.VehicleFrustumCulling;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@SideOnly(Side.CLIENT)
public final class TESRRenderStats {
    private static final int TOP_MODEL_COUNT = 3;
    private static final long HOT_TOTAL_NANOS = 10_000_000L;
    private static final double HOT_AVERAGE_MICROS = 500.0D;
    private static long railCalls;
    private static long railRendered;
    private static long railCulled;
    private static long railNanos;
    private static long wireCalls;
    private static long wireRendered;
    private static long wireCulled;
    private static long wireNanos;
    private static long vehicleBodyMainRendered;
    private static long vehicleBodyMainCulled;
    private static long vehicleBodyShadowRendered;
    private static long vehicleBodyShadowCulled;
    private static long vehicleBodyUnknownRendered;
    private static long bogieRendered;
    private static long bogieCulled;
    private static final Map<String, ModelStat> railStats = new java.util.HashMap<>();
    private static final Map<String, StageStat> railStageStats = new java.util.HashMap<>();

    private TESRRenderStats() {
    }

    public static void recordRail(TileEntityLargeRailCore rail, boolean rendered, long nanos) {
        recordRail(rail, rendered, false, nanos);
    }

    public static void recordRail(TileEntityLargeRailCore rail, boolean rendered, boolean culled, long nanos) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        ++railCalls;
        if (rendered) {
            ++railRendered;
        }
        if (culled) {
            ++railCulled;
        }
        railNanos += nanos;

        if (rail != null && rendered) {
            String model = getModelName(rail);
            ModelStat stat = railStats.get(model);
            if (stat == null) {
                stat = new ModelStat(model);
                railStats.put(model, stat);
            }
            ++stat.calls;
            stat.nanos += nanos;
        }
    }

    public static void recordWire(boolean rendered, long nanos) {
        recordWire(rendered, false, nanos);
    }

    public static void recordWire(boolean rendered, boolean culled, long nanos) {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        ++wireCalls;
        if (rendered) {
            ++wireRendered;
        }
        if (culled) {
            ++wireCulled;
        }
        wireNanos += nanos;
    }

    public static void recordVehicleBody(VehicleFrustumCulling.BodyRenderDecision decision) {
        if (!RenderDiagnostics.VEHICLE_CULLING_ENABLED) {
            return;
        }
        switch (decision) {
            case MAIN_RENDERED:
                ++vehicleBodyMainRendered;
                break;
            case MAIN_CULLED:
                ++vehicleBodyMainCulled;
                break;
            case SHADOW_RENDERED:
                ++vehicleBodyShadowRendered;
                break;
            case SHADOW_CULLED:
                ++vehicleBodyShadowCulled;
                break;
            case UNKNOWN_RENDERED:
                ++vehicleBodyUnknownRendered;
                break;
        }
    }

    public static void recordBogie(boolean rendered, boolean culled) {
        if (!RenderDiagnostics.VEHICLE_CULLING_ENABLED) {
            return;
        }
        if (rendered) {
            ++bogieRendered;
        }
        if (culled) {
            ++bogieCulled;
        }
    }

    /** Records a renderer-script phase without changing the main TESR counters. */
    public static void recordRailStage(TileEntityLargeRailCore rail, String stage, long nanos) {
        if (!RenderDiagnostics.ENABLED || stage == null || nanos < 0L) {
            return;
        }
        String model = getModelName(rail);
        String key = model + "|" + stage;
        StageStat stat = railStageStats.get(key);
        if (stat == null) {
            stat = new StageStat(model, stage);
            railStageStats.put(key, stat);
        }
        ++stat.calls;
        stat.nanos += nanos;
    }

    public static void logAndReset() {
        if (!RenderDiagnostics.ENABLED) {
            return;
        }
        long currentRailCalls = railCalls;
        long currentRailRendered = railRendered;
        long currentRailCulled = railCulled;
        long currentWireCalls = wireCalls;
        long currentWireRendered = wireRendered;
        long currentWireCulled = wireCulled;
        long currentRailNanos = railNanos;
        long currentWireNanos = wireNanos;
        List<ModelStat> currentRailStats = new ArrayList<>(railStats.values());
        List<StageStat> currentStageStats = new ArrayList<>(railStageStats.values());

        railCalls = 0L;
        railRendered = 0L;
        railCulled = 0L;
        railNanos = 0L;
        wireCalls = 0L;
        wireRendered = 0L;
        wireCulled = 0L;
        wireNanos = 0L;
        railStats.clear();
        railStageStats.clear();

        if (currentRailCalls == 0L && currentWireCalls == 0L) {
            return;
        }

        NGTLog.debug("[TESRStats] rails calls=%d rendered=%d culled=%d avg=%.2fus total=%.2fms",
                currentRailCalls, currentRailRendered, currentRailCulled,
                averageMicros(currentRailNanos, currentRailCalls), currentRailNanos / 1.0E6D);
        NGTLog.debug("[TESRStats] connectors calls=%d rendered=%d culled=%d avg=%.2fus total=%.2fms",
                currentWireCalls, currentWireRendered, currentWireCulled,
                averageMicros(currentWireNanos, currentWireCalls), currentWireNanos / 1.0E6D);
        Collections.sort(currentRailStats, Comparator.comparingLong((ModelStat stat) -> stat.nanos).reversed());
        StringBuilder topModels = new StringBuilder();
        for (ModelStat stat : currentRailStats) {
            if (stat.nanos < HOT_TOTAL_NANOS && averageMicros(stat.nanos, stat.calls) < HOT_AVERAGE_MICROS) {
                continue;
            }
            appendStat(topModels, stat.model, stat.calls, stat.nanos);
            if (countEntries(topModels) >= TOP_MODEL_COUNT) {
                break;
            }
        }
        if (topModels.length() > 0) {
            NGTLog.debug("[TESRStats] topModels=%s", topModels.toString());
        }

        Collections.sort(currentStageStats, Comparator.comparingLong((StageStat stat) -> stat.nanos).reversed());
        StringBuilder topStages = new StringBuilder();
        for (StageStat stat : currentStageStats) {
            if (stat.nanos < HOT_TOTAL_NANOS && averageMicros(stat.nanos, stat.calls) < HOT_AVERAGE_MICROS) {
                continue;
            }
            appendStat(topStages, stat.model + "/" + stat.stage, stat.calls, stat.nanos);
            if (countEntries(topStages) >= TOP_MODEL_COUNT) {
                break;
            }
        }
        if (topStages.length() > 0) {
            NGTLog.debug("[TESRStats] topStages=%s", topStages.toString());
        }
    }

    public static void logVehicleCullingAndReset() {
        if (!RenderDiagnostics.VEHICLE_CULLING_ENABLED) {
            return;
        }

        long currentVehicleBodyMainRendered = vehicleBodyMainRendered;
        long currentVehicleBodyMainCulled = vehicleBodyMainCulled;
        long currentVehicleBodyShadowRendered = vehicleBodyShadowRendered;
        long currentVehicleBodyShadowCulled = vehicleBodyShadowCulled;
        long currentVehicleBodyUnknownRendered = vehicleBodyUnknownRendered;
        long currentBogieRendered = bogieRendered;
        long currentBogieCulled = bogieCulled;
        vehicleBodyMainRendered = 0L;
        vehicleBodyMainCulled = 0L;
        vehicleBodyShadowRendered = 0L;
        vehicleBodyShadowCulled = 0L;
        vehicleBodyUnknownRendered = 0L;
        bogieRendered = 0L;
        bogieCulled = 0L;

        NGTLog.debug("[VehicleStats] bodies.main rendered=%d culled=%d",
                currentVehicleBodyMainRendered, currentVehicleBodyMainCulled);
        NGTLog.debug("[VehicleStats] bodies.shadow rendered=%d culled=%d",
                currentVehicleBodyShadowRendered, currentVehicleBodyShadowCulled);
        if (currentVehicleBodyUnknownRendered > 0L) {
            NGTLog.debug("[VehicleStats] bodies.unknown rendered=%d",
                    currentVehicleBodyUnknownRendered);
        }
        NGTLog.debug("[VehicleStats] bogies rendered=%d culled=%d",
                currentBogieRendered, currentBogieCulled);
    }

    private static void appendStat(StringBuilder builder, String name, long calls, long nanos) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(name).append(':')
                .append(String.format(java.util.Locale.ROOT, "%.2fms/%.2fus", nanos / 1.0E6D,
                        averageMicros(nanos, calls)));
    }

    private static int countEntries(StringBuilder builder) {
        int count = builder.length() == 0 ? 0 : 1;
        for (int i = 0; i + 1 < builder.length(); ++i) {
            if (builder.charAt(i) == ',' && builder.charAt(i + 1) == ' ') {
                ++count;
            }
        }
        return count;
    }

    private static double averageMicros(long nanos, long calls) {
        return calls > 0L ? nanos / (double) calls / 1000.0D : 0.0D;
    }

    private static final class ModelStat {
        private final String model;
        private long calls;
        private long nanos;

        private ModelStat(String model) {
            this.model = model;
        }
    }

    private static String getModelName(TileEntityLargeRailCore rail) {
        try {
            if (rail != null && rail.getResourceState() != null
                    && rail.getResourceState().getResourceSet() != null
                    && rail.getResourceState().getResourceSet().getConfig() != null) {
                String name = rail.getResourceState().getResourceSet().getConfig().getName();
                return name != null ? name : "unknown";
            }
        } catch (RuntimeException ignored) {
            // Model resources can be unavailable while a chunk or pack is unloading.
        }
        return "unknown";
    }

    private static final class StageStat {
        private final String model;
        private final String stage;
        private long calls;
        private long nanos;

        private StageStat(String model, String stage) {
            this.model = model;
            this.stage = stage;
        }
    }
}
