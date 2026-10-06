package com.voxivoid.bettertimelapse;

/**
 * Counting what actually reached the card, which is not the same as counting shutter presses.
 *
 * <p>The old app incremented a counter immediately after calling {@code burstableTakePicture}, a
 * method that returns void, and never looked at the card at all
 * (TimeLapse {@code ShootActivity.java:299-301}). So a full card, a write-protected card or a
 * camera that quietly dropped the frame all produced a screen that said the frames were being taken,
 * and the loss was discovered hours later when the card held a fraction of them
 * (docs/PAIN-POINTS.md U-06, U-16).
 *
 * <p>Here the two numbers are kept apart — frames the camera was asked for, and frames seen on the
 * card — and the difference between them is a fact the panel shows. A frame that the camera
 * accepted but never wrote becomes a fault, not a silent zero.
 *
 * <p>This class is the arithmetic only. Listing a directory is the shell's job, so everything here
 * can be checked off the camera: no {@code android.*} import, compiled by {@code tools/test.sh}
 * without {@code android.jar}.
 */
public final class Frames {

    private Frames() {}

    /** No file count available; the platform would not say. */
    public static final int UNKNOWN = -1;

    /**
     * How much the camera's file counter is expected to move for one press. Bracketing writes three
     * files per press, and getting this wrong is how the old app's estimate went wrong by a factor
     * of three (docs/PAIN-POINTS.md U-10, D-18).
     */
    public static int filesPerFrame(Plan plan) {
        return plan.driveMode() == Plan.DRIVE_BRACKET3 ? 3 : 1;
    }

    /** New files since the baseline, never negative (a card swapped between shots can go backwards). */
    public static int arrived(int baseline, int now) {
        if (baseline == UNKNOWN || now == UNKNOWN) return 0;
        return Math.max(0, now - baseline);
    }

    /**
     * How long to wait for the camera's own shutter callback before carrying on without it.
     *
     * <p>Not a verdict on the frame — the card is what decides that — but the release valve for the
     * state machine, which cannot fire the next frame while it believes one is in flight. Half the
     * interval, so a callback that never comes costs at most half a frame's spacing; never less than
     * {@value #CALLBACK_FLOOR_MS} ms, because a body that takes most of a second to report a frame is
     * still working, and never more than {@value #CALLBACK_CEIL_MS} ms, because past that the interval
     * itself is the smaller number and waiting longer would move the next frame.
     */
    public static long callbackTimeoutMs(Plan plan) {
        return clamp(plan.intervalMs() / 2, CALLBACK_FLOOR_MS, CALLBACK_CEIL_MS);
    }

    /**
     * When to look at the card for the frame that has just been taken.
     *
     * <p>Deliberately not immediately. The file is written <em>after</em> the shutter, so a walk taken
     * the instant the camera reports the frame finds nothing and would call a perfectly good frame
     * unwritten — which is exactly what it did, on every frame, before this was moved. Late in the
     * interval the file has had time to appear, and the answer is worth something. Never sooner than
     * the settle delay, which is this plan's own statement of how long the body needs after a frame.
     */
    public static long verdictDelayMs(Plan plan) {
        return clamp(Math.max(plan.intervalMs() * 3 / 4, plan.settleMs()),
                VERDICT_FLOOR_MS, VERDICT_CEIL_MS);
    }

    /** How many frames a gain of {@code files} new files accounts for. */
    public static int framesAccounted(int files, Plan plan) {
        if (files <= 0) return 0;
        return files / filesPerFrame(plan);
    }

    private static long clamp(long value, long low, long high) {
        return value < low ? low : (value > high ? high : value);
    }

    private static final long CALLBACK_FLOOR_MS = 800, CALLBACK_CEIL_MS = 2500;
    private static final long VERDICT_FLOOR_MS = 500, VERDICT_CEIL_MS = 5000;

    /**
     * Frames the card can still take, from the free space and a measured frame size. A floor, and
     * deliberately so: an estimate that runs out early costs a re-shoot, one that runs out late costs
     * the pictures.
     */
    public static long roomFor(long freeBytes, long bytesPerFrame) {
        if (freeBytes < 0 || bytesPerFrame <= 0) return UNKNOWN;
        return freeBytes / bytesPerFrame;
    }

    /**
     * Whether the free space is too small to be worth starting: fewer than this many frames left and
     * the run would fail part-way, which is worse than not starting.
     */
    public static final int MIN_FRAMES_TO_START = 10;

    public static boolean enoughRoom(long framesLeft, int planned) {
        if (framesLeft == UNKNOWN) return true;      // the card would not say; do not block on it
        if (planned == Plan.FRAMES_UNLIMITED) return framesLeft >= MIN_FRAMES_TO_START;
        return framesLeft >= Math.min(planned, MIN_FRAMES_TO_START);
    }

    /**
     * The state of the run against the plan, as one of the three things the panel can say: the frames
     * that landed, whether that matches the plan, and whether anything was lost on the way.
     */
    public static String verdict(int landed, int planned, int fired) {
        int missing = fired - landed;
        if (planned != Plan.FRAMES_UNLIMITED && landed >= planned) return "";
        if (missing <= 0) return "";
        return "\u5dee " + missing + " \u5f20";   // 差 N 张
    }
}
