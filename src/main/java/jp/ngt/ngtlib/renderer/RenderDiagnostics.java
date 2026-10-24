/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Central switch for temporary render-performance diagnostics.
 */
package jp.ngt.ngtlib.renderer;

public final class RenderDiagnostics {
    /** Keep disabled for release builds. */
    public static final boolean ENABLED = false;

    /** Temporary counts for vehicle body and bogie frustum culling only. */
    public static final boolean VEHICLE_CULLING_ENABLED = true;

    private RenderDiagnostics() {
    }
}
