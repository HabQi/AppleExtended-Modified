/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

/** Immutable visual state for one walkable-train passenger. */
public final class PassengerPoseState {
    public static final int MIN_LYING_HEIGHT_STEP = 3;
    public static final int MAX_HEIGHT_STEP = 26;
    public static final float HEIGHT_PER_STEP = 0.1F;
    public static final float LYING_HEAD_ALIGNMENT_OFFSET = 1.4F;
    public static final float LYING_CAMERA_HEIGHT_CORRECTION = 1.4F;
    public static final PassengerPoseState DEFAULT =
            new PassengerPoseState(PassengerPose.STANDING, 0);

    private final PassengerPose pose;
    private final int heightStep;

    public PassengerPoseState(PassengerPose pose, int heightStep) {
        this.pose = pose == null ? PassengerPose.STANDING : pose;
        int minimumHeight = this.pose == PassengerPose.LYING ? MIN_LYING_HEIGHT_STEP : 0;
        this.heightStep = Math.max(minimumHeight, Math.min(MAX_HEIGHT_STEP, heightStep));
    }

    public PassengerPose getPose() {
        return this.pose;
    }

    public int getHeightStep() {
        return this.heightStep;
    }

    public float getHeightOffset() {
        return this.heightStep * HEIGHT_PER_STEP;
    }

    public PassengerPoseState withPose(PassengerPose newPose) {
        return new PassengerPoseState(newPose, this.heightStep);
    }

    public PassengerPoseState adjustHeight(int delta) {
        return new PassengerPoseState(this.pose, this.heightStep + delta);
    }

    public boolean isDefault() {
        return this.pose == PassengerPose.STANDING && this.heightStep == 0;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PassengerPoseState)) {
            return false;
        }
        PassengerPoseState other = (PassengerPoseState) object;
        return this.pose == other.pose && this.heightStep == other.heightStep;
    }

    @Override
    public int hashCode() {
        return 31 * this.pose.hashCode() + this.heightStep;
    }
}
