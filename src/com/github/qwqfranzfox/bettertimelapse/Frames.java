package com.github.qwqfranzfox.bettertimelapse;

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
     * Whether a plan can be shot on this body, and if not, which of the three floors it is under.
     *
     * <p>The floors are different things and only one of them is a fact about the camera alone: the shutter
     * needs its press window and the plan its settle time; the frame occupies the body for the exposure plus,
     * with noise reduction on, a dark frame of the same length; and refusals measure what the body actually
     * needs, which is not known until something has been refused.
     *
     * <p>This decision lived in the activity for one build and lost its "everything is fine" branch to a
     * rename, which meant every plan was held back with 间隔 X 短于本机身所需的 3.7s no matter what X was —
     * shipped, on a camera, because no test covered it. It is here now, with tests, which is where this
     * project's own rule says a decision belongs.
     */
    public static final class Preflight {
        /** Why the plan does not fit, or {@link #NONE}. */
        public int reason = NONE;
        /** The longest of the floors in play: the number the plan has to reach. */
        public long shortestMs;
        public long exposureMs = UNKNOWN;
        public boolean longExposureNr;

        public boolean fits() {
            return reason == NONE;
        }
    }

    public static final int NONE = 0, SHUTTER = 1, EXPOSURE = 2, MEASURED = 3;

    /**
     * Decide whether the plan fits, from the three floors and the exposure.
     *
     * @param measuredFloorMs what refusals have proven, or {@link BodyCycle#UNKNOWN}.
     */
    public static Preflight preflight(Plan plan, long exposureMs, boolean longExposureNr, long measuredFloorMs) {
        Preflight out = new Preflight();
        out.exposureMs = exposureMs;
        out.longExposureNr = longExposureNr;

        long fromShutter = shortestIntervalMs(plan);
        long fromExposure = exposureMs > 0
                ? occupancyMs(exposureMs, longExposureNr) + EXPOSURE_MARGIN_MS : 0;
        long fromMeasured = measuredFloorMs > 0 ? measuredFloorMs : 0;

        out.shortestMs = Math.max(fromShutter, Math.max(fromExposure, fromMeasured));
        if (plan.intervalMs() >= out.shortestMs) {
            out.reason = NONE;
        } else if (fromMeasured > fromShutter && fromMeasured >= fromExposure) {
            out.reason = MEASURED;
        } else if (fromExposure > fromShutter) {
            out.reason = EXPOSURE;
        } else {
            out.reason = SHUTTER;
        }
        return out;
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

    // ---------------------------------------------------------------- the panel between frames
    /**
     * What one switch of the display output costs, allowing for both directions.
     *
     * <p>Switching off and switching back on are two platform calls with a surface behind them, and they are
     * budgeted together because a plan that cannot afford both cannot afford either: the failure mode of
     * getting this wrong is a panel that is still dark when the shutter fires, which is a lost frame rather
     * than a dim one.
     */
    public static final long PANEL_TRANSITION_MS = 300;

    /**
     * How long before a frame's own moment the panel has to be back on.
     *
     * <p>The panel is not a light bulb with a switch: switching the display output back is a platform call,
     * and this app has to re-attach its surface and redraw, and — most of all — the body has to have its
     * preview stream running again before it will accept a shutter. All of that belongs in the quiet stretch
     * before a frame, never at the frame's own moment, which is the deadline everything else in this class is
     * arranged around. 700 ms is chosen against the two numbers this body has shown: a settle delay of
     * 1200 ms and shutter callbacks arriving 259–438 ms after a press. Both constants are first values to be
     * measured on the camera, not derived ones (docs/DECISIONS.md A42).
     */
    public static final long PANEL_WAKE_BEFORE_MS = 700;

    /**
     * How long after a frame the panel is allowed to go dark.
     *
     * <p>The stretch right after a frame is the app's busy one — the counter changes, the log is appended, the
     * card is read — and it is also the only moment anything on the panel is worth reading, so the switch waits
     * until it is over. A second is generous against what that work actually costs; it is a value to measure on
     * the camera like the other two (docs/DECISIONS.md A42).
     */
    public static final long PANEL_OFF_AFTER_MS = 1000;

    /**
     * The shortest dark window worth the switch.
     *
     * <p>Without a floor here the arithmetic is happy with a 300 ms window on a two-second interval, which is
     * not a screen being off: it is a screen being flashed, which draws attention to the camera instead of
     * hiding it and saves no backlight worth counting. A plan that cannot stay dark for this long keeps its
     * panel lit, and the row says so.
     */
    public static final long PANEL_MIN_DARK_MS = Math.max(500, 2 * PANEL_TRANSITION_MS);

    /**
     * Whether this plan can darken the panel at all.
     *
     * <p>Two things have to fit between two frames: the stretch after a frame in which the panel stays lit for
     * the user, and the switch itself. A plan with no room for both does not pay for the feature rather than
     * flashing the panel once per frame — and the row on screen says which case it is instead of claiming a
     * setting that does nothing.
     */
    public static boolean panelStaysOff(Plan plan) {
        if (plan.intervalMs() <= PANEL_OFF_AFTER_MS + panelLitMs(plan)) return false;
        return panelDarkMs(plan) >= PANEL_MIN_DARK_MS;
    }

    /**
     * The longest the panel is left dark, however long the interval is.
     *
     * <p>Measured on the camera, and the reason this cap exists: switching the display output away for a long
     * stretch makes the body ask to repair its card data at the next power-on. Not the interval, not the card,
     * not this app's card reading — the user separated all three:
     *
     * <pre>
     *   5s,  关屏 ON    dark 2750ms   no repair
     *   10s, 关屏 ON    dark 8300ms   repair
     *   10s, 关屏 OFF   no screen-off no repair
     *   (and at 5s the screen is switched off twelve times a minute, so it is not how often)
     * </pre>
     *
     * <p>So it is how long the output stays away in one go. The cap is set at exactly the window the 5s run
     * used, 2750ms, for two reasons: it leaves that configuration untouched, down to the millisecond, so the
     * measurement it came from stays valid; and it does not spend any of the distance to the unsafe value,
     * 8300ms, on a guess. What is left in between is for a later experiment to claim, with the row that
     * switches this off entirely as the way to test it (docs/DECISIONS.md A50).
     *
     * <p>What the cap costs is the feature's whole point on a long interval: at 15s the panel is lit for
     * 11.25s of every 15 instead of being dark for 13.3s of it. A dark screen is worth less than a card the
     * camera thinks is damaged.
     */
    public static final long PANEL_DARK_MAX_MS = 2750;

    /**
     * The dark window a plan would actually get, in milliseconds, or {@code 0} when it gets none.
     *
     * <p>Capped at {@link #PANEL_DARK_MAX_MS}: the body tolerates a short stretch with its output switched
     * away and objects to a long one, so the panel stays lit for the remainder of a long interval instead of
     * risking the card.
     */
    public static long panelDarkMs(Plan plan) {
        long asked = plan.intervalMs() - PANEL_OFF_AFTER_MS - panelLitMs(plan);
        if (asked < PANEL_MIN_DARK_MS) return 0;
        return Math.min(asked, PANEL_DARK_MAX_MS);
    }

    /**
     * How long the panel is lit before each frame — the stretch in which it is worth having a screen at all.
     *
     * <p>Not a constant: a run can be asked for five seconds between frames or twenty minutes, and a wake-up a
     * fixed 700 ms before the shutter would leave the panel lit for 700 ms of a twenty-minute wait. So the lit
     * stretch is {@link #PANEL_WAKE_BEFORE_MS} or a quarter of the interval, whichever is longer, and the panel
     * is dark for everything between the two.
     */
    public static long panelLitMs(Plan plan) {
        // The lit stretch before a frame is now a bound and not a fraction of the plan. It used to be
        // max(700ms, interval/4), which on this body's 10s interval is 2.5 seconds of light before every frame
        // -- and measured on the camera, that is what the user sees: dark for 4 seconds, then the panel back
        // for the remaining 6. A quarter of the interval is not a reason to light a screen at night.
        //
        // So it is the floor, capped so a very long interval cannot turn it into something else: enough time
        // for the display switch, the surface and the preview the body needs before a shutter. 700 ms is a
        // first value for exactly that, and the frame's own lateness is what tests it (A44).
        return Math.min(PANEL_WAKE_BEFORE_MS, Math.max(1, plan.intervalMs() / 4));
    }

    /**
     * How long after a frame the panel goes dark, or {@link Schedule#NONE} when it does not.
     *
     * <p>The dark window is what is left of the interval after the lit stretch on both sides: on this body's
     * five-second interval the screen is dark for 2.75 s of every 5, and on a one-minute interval for 43 of
     * them. That is the shape the feature is for — the panel is there to be read just before a frame and dark
     * for the wait in which nobody is looking at it, and the exposure itself always happens with the panel lit
     * and the body settled.
     */
    public static long panelOffDelayMs(Plan plan) {
        if (!panelStaysOff(plan)) return Schedule.NONE;
        return PANEL_OFF_AFTER_MS;
    }

    /**
     * When the panel is switched back on, given where the run has got to.
     *
     * <p>An absolute instant on the run's own clock, like every other moment in this app: the frame is due at
     * {@code dueMs}, so the panel is due back at {@code dueMs - panelLitMs}. Returns {@link Schedule#NONE}
     * when there is no next frame — the end of a run has no deadline, so the caller wakes the panel once and
     * stops.
     *
     * <p>Absolute rather than "wait N ms", because the wait is what a delayed task cannot promise: a frame
     * that was answered late would otherwise have its wake-up pushed late with it, which is how the panel ends
     * up still black when the shutter fires.
     */
    public static long panelWakeAtMs(Plan plan, long nowMs, long dueMs) {
        if (dueMs == Schedule.NONE) return Schedule.NONE;
        // The wake is the far end of the capped dark window, and the lit stretch before the frame is whatever
        // is left of the interval after it: on a long interval that is minutes of light rather than the 700ms
        // floor, which is the cost of not keeping the output away from the body for longer.
        long lit = Math.max(panelLitMs(plan), plan.intervalMs() - PANEL_OFF_AFTER_MS - panelDarkMs(plan));
        long wakeAt = dueMs - lit;
        return wakeAt - nowMs <= 0 ? nowMs : wakeAt;    // already inside the window: wake where we stand
    }
}
