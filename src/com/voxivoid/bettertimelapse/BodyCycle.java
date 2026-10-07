package com.voxivoid.bettertimelapse;

/**
 * How long this body needs for one frame, learned from the frames it refused.
 *
 * <p>Nothing on this platform says how long a frame takes. There is no exposure time to read, no busy
 * flag to ask, and {@code takePicture} reports nothing back — so the app has been running on a constant
 * (2500 ms, measured once by Recipe Lab on this body with a short exposure). That constant is wrong the
 * moment somebody sets a long exposure: a ten-second exposure on a five-second interval is refused, and
 * the app would have gone on believing the body needs 2.5 s.
 *
 * <p>What a refusal proves is exact and needs no new API: a press that is refused at time {@code T},
 * where the previous press the camera accepted was at {@code P}, proves the body was <em>still busy</em>
 * {@code T - P} after that frame started. So the body's cycle is longer than that — and if {@code T - P}
 * is the interval the plan asked for, then <em>the interval itself is not achievable</em>, which is
 * precisely the sentence the panel needs to be able to say.
 *
 * <p>A lower bound is the honest shape for this: it can only grow as more refusals are seen, it never
 * claims to know the exact figure, and "the interval is under the body's floor" is a conclusion a lower
 * bound can support. The measured value is reset when the camera is reopened, because it belongs to the
 * body's current state (exposure, image quality, card) rather than to the app.
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class BodyCycle {

    /** Nothing refused yet: no lower bound is known. */
    public static final long UNKNOWN = 0;

    private long lastAcceptedMs = Schedule.NONE;
    private long lowerBoundMs = UNKNOWN;
    private int refusals;
    private int samples;

    /**
     * The camera took the press at {@code nowMs}.
     *
     * <p>This is what the next refusal is measured against: the body started a frame here, so its cycle
     * began here.
     */
    public void accepted(long nowMs) {
        lastAcceptedMs = nowMs;
    }

    /**
     * The camera refused a press at {@code nowMs}.
     *
     * <p>Proves the body was still busy {@code nowMs - lastAccepted} after the previous frame started,
     * so that difference is a lower bound on the body's cycle. Called with no accepted press before it,
     * the refusal says only that the body was busy — nothing to measure against, so nothing is claimed.
     */
    public void refused(long nowMs) {
        refusals++;
        if (lastAcceptedMs == Schedule.NONE) return;
        long busyFor = nowMs - lastAcceptedMs;
        if (busyFor <= 0) return;
        samples++;
        if (busyFor > lowerBoundMs) lowerBoundMs = busyFor;
    }

    /** The longest busy period proven so far, or {@link #UNKNOWN}. A lower bound, never an estimate. */
    public long lowerBoundMs() {
        return lowerBoundMs;
    }

    public boolean known() {
        return lowerBoundMs != UNKNOWN;
    }

    /** How many refusals were seen, and how many of them could be measured against a frame. */
    public int refusals() {
        return refusals;
    }

    public int samples() {
        return samples;
    }

    /**
     * The shortest interval worth offering the user, given what has been learned.
     *
     * <p>The constant floor is a floor because of how the shutter is held; this is a floor because of how
     * long the body stays busy. The larger of the two is the honest answer, and until something has been
     * refused the constant is all there is.
     */
    public long shortestIntervalMs() {
        return Math.max(Frames.shortestIntervalMs(), lowerBoundMs + MARGIN_MS);
    }

    /** Kept clear past the proven busy period before the next press is asked for. */
    public static final long MARGIN_MS = 400;

    /** Forget everything: a different body state (or a different body) may need something else. */
    public void reset() {
        lastAcceptedMs = Schedule.NONE;
        lowerBoundMs = UNKNOWN;
        refusals = 0;
        samples = 0;
    }
}
