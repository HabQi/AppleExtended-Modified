/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

/** Visual-only poses available to players walking inside a train. */
public enum PassengerPose {
    STANDING((byte) 0),
    SITTING((byte) 1),
    LYING((byte) 2);

    private final byte networkId;

    PassengerPose(byte networkId) {
        this.networkId = networkId;
    }

    public byte getNetworkId() {
        return this.networkId;
    }

    /** Unknown network values are deliberately treated as the harmless default. */
    public static PassengerPose fromNetworkId(byte networkId) {
        for (PassengerPose pose : values()) {
            if (pose.networkId == networkId) {
                return pose;
            }
        }
        return STANDING;
    }
}
