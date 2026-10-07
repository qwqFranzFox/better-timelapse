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

    private static final long VERDICT_FLOOR_MS = 500, VERDICT_CEIL_MS = 5000;

    /**
     * How long this platform wants between the shutter's press and its release.
     *
     * <p>2500 ms, and it is measured rather than invented: it is {@code DevTools.SHUTTER_MS} in Recipe
     * Lab, whose sample run shot 77 frames on this body (ILCE-6001, firmware 3.21) with a
     * {@code takePicture} / {@code cancelTakePicture} pair that far apart. It is the single number that
     * decides whether a given interval is possible at all: press and release cannot overlap, so nothing
     * shorter than this can be shot on this body, whatever the plan says.
     */
    public static final long PRESS_MS = 2500;

    /** Kept clear between the release and the next press: the body is not instantly ready again. */
    public static final long RELEASE_MARGIN_MS = 400;

    /**
     * The exposure the body is set to, read from the two parameters that hold it.
     *
     * <p>Sony reports a shutter speed as a fraction: {@code shutter-speed-nume} over
     * {@code shutter-speed-deno}. Four over one is four seconds; one over sixty is a sixtieth. A zero
     * denominator is bulb (the body holds the shutter open until it is told to stop, so there is no length
     * to read), and a missing key means the body would not say — both come back as {@link #UNKNOWN}.
     *
     * <p>This is the number the whole minimum-interval question turns on, and the camera does answer it:
     * measured on this body, {@code shutter-speed-nume=4 shutter-speed-deno=1} in manual mode.
     */
    public static long exposureMs(String nume, String deno) {
        long numerator = number(nume);
        long denominator = number(deno);
        if (numerator <= 0 || denominator <= 0) return UNKNOWN;
        return numerator * 1000L / denominator;
    }

    private static long number(String text) {
        if (text == null) return UNKNOWN;
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /**
     * How long one frame occupies the body.
     *
     * <p>The exposure, and then — when long-exposure noise reduction is on — a second, dark frame of the
     * same length, which the body takes immediately afterwards. Measured on this body:
     * {@code long-exposure-nr=true} with a four-second exposure, so a frame that looks like four seconds
     * occupies it for eight. An interval worked out from the exposure alone would be half of what is needed.
     */
    public static long occupancyMs(long exposureMs, boolean longExposureNr) {
        if (exposureMs <= 0) return 0;
        return longExposureNr ? exposureMs * 2 : exposureMs;
    }

    /**
     * The shortest interval this body can be asked for: the shutter's own press window, plus the time the
     * plan gives the body to recover after a frame.
     *
     * <p>The second half used to be a 400 ms constant, which made the floor 2.9 s — and the camera then
     * shot 17 frames at a 3 s interval and kept six of them (photographed, `核实 6 / 落盘 17`). Recipe Lab's
     * loop on this same body leaves 2500 ms for the press and then waits its settle delay, default 1200 ms:
     * 3.7 s, which is the number that has actually been shot here. So the settle row is part of the floor
     * rather than a floor of its own, and the row finally decides something the user can see.
     */
    public static long shortestIntervalMs(Plan plan) {
        return shortestIntervalMs(plan, UNKNOWN, false);
    }

    /**
     * The same, with the exposure taken into account.
     *
     * <p>Two constraints, and the longer one wins. The shutter needs its press window and the body its
     * settle time; and the frame itself occupies the body for the exposure plus — with noise reduction on —
     * a dark frame of the same length. A plan that ignores the second constraint asks the camera for
     * something it cannot do, which is what four seconds of exposure on a five-second interval is.
     */
    public static long shortestIntervalMs(Plan plan, long exposureMs, boolean longExposureNr) {
        long fromShutter = PRESS_MS + Math.max(RELEASE_MARGIN_MS, plan.settleMs());
        long occupancy = occupancyMs(exposureMs, longExposureNr);
        return Math.max(fromShutter, occupancy + EXPOSURE_MARGIN_MS);
    }

    /**
     * How long the shutter is held: the body's press window, shortened when the interval cannot afford
     * it. Shortening it is a compromise, not a fix — a release before the body has finished is exactly
     * how a frame comes out wrong — which is why {@link #shortestIntervalMs} is shown to the user
     * rather than applied quietly.
     */
    public static long pressWindowMs(Plan plan) {
        return pressWindowMs(plan, 0);
    }

    /**
     * The same, told how long the body's own shutter callback takes to arrive.
     *
     * <p>A callback that comes back ten seconds after the press is this body saying its exposure took ten
     * seconds. Releasing the shutter at 2500 ms then would cut the exposure off mid-frame — so the window
     * grows to cover what the body has already shown it needs, and never past the point where the next
     * frame's moment would be eaten. {@code exposureHintMs} of zero (no callback ever seen, which is what
     * a plain {@code takePicture} does on some bodies) leaves the constant in charge.
     */
    public static long pressWindowMs(Plan plan, long exposureHintMs) {
        long wanted = Math.max(PRESS_MS, exposureHintMs + EXPOSURE_MARGIN_MS);
        long latest = Math.max(PRESS_FLOOR_MS, plan.intervalMs() - RELEASE_MARGIN_MS);
        return clamp(wanted, PRESS_FLOOR_MS, latest);
    }

    /** Kept clear after a shutter callback before the shutter is released. */
    public static final long EXPOSURE_MARGIN_MS = 200;

    private static final long PRESS_FLOOR_MS = 600;

    /**
     * Whether a frame whose moment has already passed is still worth taking.
     *
     * <p>A frame can only start if its release fits before the next frame's moment, so the run may start
     * one <em>slack</em> late and no later: {@code interval - pressWindow}. Past that, taking the frame
     * would put it on top of the next one and drag the rest of the run with it — which is precisely the
     * old app's silent catch-up shooting (docs/PAIN-POINTS.md U-18). A moment that cannot be used is
     * dropped and counted instead, and the frame after it is still taken where it was always due.
     *
     * <p>The slack is smallest exactly when the interval is short: at 2 s on this body it is 400 ms, so
     * a shell held up by half a second loses that moment rather than the whole sequence's phase.
     */
    public static boolean stillFits(Plan plan, long remainingMs) {
        if (remainingMs > 0) return true;
        return -remainingMs <= plan.intervalMs() - pressWindowMs(plan);
    }

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
}
