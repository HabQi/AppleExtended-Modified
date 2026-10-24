/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.entity.train.parts;

import java.util.ArrayDeque;
import java.util.Deque;

/** Pure state helpers shared by train-walking prediction and authority logic. */
final class TrainWalkLogic {
    static final double WALK_SPEED = 0.15D;
    static final double CORRECTION_EPSILON = 0.02D;
    static final double SNAP_DISTANCE = 1.5D;
    static final int MAX_PREDICTION_HISTORY = 256;
    static final int MAX_SERVER_INPUT_QUEUE = 32;
    static final long MAX_INPUT_SEQUENCE_ADVANCE = 64L;
    static final double SNAPSHOT_POSITION_EPSILON = 1.0E-5D;

    private TrainWalkLogic() {
    }

    static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    static double[] normalize(double x, double z) {
        double magnitude = Math.sqrt(x * x + z * z);
        if (magnitude > 1.0D) {
            return new double[]{x / magnitude, z / magnitude};
        }
        return new double[]{x, z};
    }

    static boolean isNewerSequence(Long previous, long candidate) {
        return candidate >= 0L && (previous == null || candidate > previous);
    }

    static boolean shouldBroadcastSnapshot(double positionDelta, Long previousAcknowledgement,
                                           long acknowledgement) {
        return positionDelta > SNAPSHOT_POSITION_EPSILON
                || previousAcknowledgement == null
                || previousAcknowledgement != acknowledgement;
    }

    static double correctionBlend(double distance) {
        if (distance <= CORRECTION_EPSILON) {
            return 0.0D;
        }
        return distance > SNAP_DISTANCE ? 1.0D : 0.5D;
    }

    static float walkAnimationTarget(double localDeltaX, double localDeltaZ) {
        return (float) Math.min(1.0D,
                Math.sqrt(localDeltaX * localDeltaX + localDeltaZ * localDeltaZ) * 4.0D);
    }

    static float smoothWalkAnimationAmount(float previousAmount, float targetAmount) {
        float next = previousAmount + (targetAmount - previousAmount) * 0.4F;
        return targetAmount == 0.0F && next < 0.01F ? 0.0F : next;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class PendingInput {
        final float moveX;
        final float moveZ;
        final long sequence;

        PendingInput(float moveX, float moveZ, long sequence) {
            this.moveX = moveX;
            this.moveZ = moveZ;
            this.sequence = sequence;
        }
    }

    static final class InputAccumulator {
        private Long lastReceivedSequence;
        private final Deque<PendingInput> pending = new ArrayDeque<>();

        boolean offer(float moveX, float moveZ, long sequence) {
            if (!isFinite(moveX) || !isFinite(moveZ)
                    || !isNewerSequence(this.lastReceivedSequence, sequence)
                    || (this.lastReceivedSequence != null
                    && sequence - this.lastReceivedSequence > MAX_INPUT_SEQUENCE_ADVANCE)
                    || this.pending.size() >= MAX_SERVER_INPUT_QUEUE) {
                return false;
            }
            double[] normalized = normalize(moveX, moveZ);
            this.lastReceivedSequence = sequence;
            this.pending.addLast(new PendingInput((float) normalized[0], (float) normalized[1], sequence));
            return true;
        }

        PendingInput drain() {
            return this.pending.pollFirst();
        }

        boolean hasPending() {
            return !this.pending.isEmpty();
        }

        int size() {
            return this.pending.size();
        }
    }

    static final class PredictionHistory {
        private final Deque<PendingInput> inputs = new ArrayDeque<>();
        private boolean overflowed;

        void record(float moveX, float moveZ, long sequence) {
            if (this.overflowed) {
                return;
            }
            if (this.inputs.size() >= MAX_PREDICTION_HISTORY) {
                this.inputs.clear();
                this.overflowed = true;
                return;
            }
            this.inputs.addLast(new PendingInput(moveX, moveZ, sequence));
        }

        ReplayedPosition reconcile(long acknowledgedSequence, double baseX, double baseZ,
                                   double minX, double maxX, double minZ, double maxZ) {
            if (this.overflowed) {
                this.inputs.clear();
                this.overflowed = false;
                return new ReplayedPosition(baseX, baseZ, true);
            }

            while (!this.inputs.isEmpty() && this.inputs.peekFirst().sequence <= acknowledgedSequence) {
                this.inputs.removeFirst();
            }

            double x = baseX;
            double z = baseZ;
            for (PendingInput input : this.inputs) {
                x = clamp(x + input.moveX * WALK_SPEED, minX, maxX);
                z = clamp(z + input.moveZ * WALK_SPEED, minZ, maxZ);
            }
            return new ReplayedPosition(x, z, false);
        }

        void clear() {
            this.inputs.clear();
            this.overflowed = false;
        }

        int size() {
            return this.inputs.size();
        }

        boolean isOverflowed() {
            return this.overflowed;
        }
    }

    static final class ReplayedPosition {
        final double x;
        final double z;
        final boolean historyDiscarded;

        ReplayedPosition(double x, double z, boolean historyDiscarded) {
            this.x = x;
            this.z = z;
            this.historyDiscarded = historyDiscarded;
        }
    }
}
