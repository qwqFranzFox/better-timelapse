package com.voxivoid.bettertimelapse;

/**
 * The shooting plan: what the user asked for, and what the camera currently holds.
 *
 * <p>Two sets of values, and the difference between them is the whole point. {@code wanted} is what
 * the panel edits. {@code stored} is what the camera's settings store holds. They are equal until
 * the user changes something, and the exit rule — set the live parameters to the <em>stored</em>
 * values, never to a launch snapshot — is expressed here as {@link #revert()} rather than as
 * something the shell has to remember.
 *
 * <p>No {@code android.*} import may appear in this class: it is compiled by {@code tools/test.sh}
 * without {@code android.jar}, so a decision made here is testable off the camera. There is no
 * camera in a time-lapse plan, only arithmetic.
 */
public final class Plan {

    // ---------------------------------------------------------------- the rows, in panel order
    /** The rows the panel shows, in order. Adding one means adding a label and a value formatter. */
    public static final int ROW_INTERVAL = 0, ROW_FRAMES = 1, ROW_DELAY = 2, ROW_SETTLE = 3,
            ROW_SILENT = 4, ROW_DRIVE = 5, ROW_AEL = 6, ROW_PANEL_OFF = 7, ROW_CARD_CHECK = 8;
    public static final int ROW_COUNT = 9;

    /** Drive modes, as the labels the camera menus use. */
    public static final int DRIVE_SINGLE = 0, DRIVE_BURST = 1, DRIVE_BRACKET3 = 2;
    public static final int DRIVE_COUNT = 3;

    /** Framing values: {@code FRAMES_UNLIMITED} means "until stopped or the card is full". */
    public static final int FRAMES_UNLIMITED = -1;
    public static final int FRAMES_MAX = 9999;

    public static final int INTERVAL_MIN_MS = 100;
    public static final int INTERVAL_MAX_MS = 24 * 60 * 60 * 1000;
    public static final int DELAY_MAX_MS = 24 * 60 * 60 * 1000;

    /**
     * Settle delays, in milliseconds. After each frame the camera needs a moment to catch up before
     * the next parameter change or preview restart is worth attempting.
     *
     * <p>These five are recipe-lab's list too, and it is careful about what they are: "the right one
     * is a property of the camera, not of this table". The default below is therefore a starting
     * point, not a measurement — `docs/PLATFORM-FACTS.md` Q7 is where the real value gets recorded.
     */
    public static final int[] SETTLE_MS = { 800, 1200, 2000, 3000, 5000 };
    public static final int SETTLE_DEFAULT = 1;

    private final int[] wanted = new int[ROW_COUNT];
    private final int[] stored = new int[ROW_COUNT];

    public Plan() {
        reset();
    }

    /** The documented defaults: nothing here changes the camera on its own. */
    public void reset() {
        // 5 s, not the 2 s this used to be: the body refuses anything under about 2.9 s
        // (docs/PLATFORM-FACTS.md §4), so the old default meant the first thing a new user saw was the app
        // telling them their own default was impossible. 5 s is the value that has actually been shot on
        // this camera; a shorter one can be dialled in once it has been measured.
        wanted[ROW_INTERVAL] = 5000;
        wanted[ROW_FRAMES] = 300;
        wanted[ROW_DELAY] = 0;
        wanted[ROW_SETTLE] = SETTLE_DEFAULT;
        wanted[ROW_SILENT] = 0;      // off: on some bodies this is the option that crashes a run
        wanted[ROW_DRIVE] = DRIVE_SINGLE;
        wanted[ROW_AEL] = 0;
        // Off by default, and that is a measured decision rather than a preference: switching this body's
        // display output away for more than a few seconds makes it ask to repair its card data at the next
        // power-on, and the user's own comparisons pinned it on this action (A50, A51). The old app never
        // darkened its screen either -- its display-off call is commented out in the versions people actually
        // ran -- so off is also what "the known-good configuration" means here. What replaces it as the
        // default is the one-line panel: less light, less burn-in, and no display switch at all.
        wanted[ROW_PANEL_OFF] = 0;
        // On by default, because reading the card is what turns "the app pressed the shutter" into "the card
        // has the file", and that distinction is the reason this project exists (docs/DECISIONS.md A9). It is a
        // row rather than a constant because it is also the app's only claim on the camera's storage between
        // frames: the camera asks to repair its card data after runs at 10s and 15s but not at 5s, and the one
        // experiment that can tell "our reading does this" from "the camera minds the idle time" is to switch
        // the reading off and run the same plan (A49).
        wanted[ROW_CARD_CHECK] = 1;
        System.arraycopy(wanted, 0, stored, 0, ROW_COUNT);
    }

    public int get(int row) {
        return wanted[row];
    }

    public int stored(int row) {
        return stored[row];
    }

    public void set(int row, int value) {
        wanted[row] = clamp(row, value);
    }

    /**
     * Take a value the camera reported as this row's baseline: wanted and stored alike.
     *
     * <p>{@code stored} is documented as "what the camera's settings store holds", and it used to be a
     * copy of this app's own defaults — which made both the panel's 未存 marker and the exit rule into
     * statements about nothing, and made applying the plan a one-way switch: a setting this app turned on
     * could never be turned off, because the app believed the body already held its default. Seeding from
     * the body at open is what makes "wanted ≠ stored" mean something.
     */
    public void seed(int row, int value) {
        wanted[row] = clamp(row, value);
        stored[row] = wanted[row];
    }

    /** Whether this row differs from what the camera holds. */
    public boolean dirty(int row) {
        return wanted[row] != stored[row];
    }

    public boolean dirty() {
        for (int r = 0; r < ROW_COUNT; r++) {
            if (dirty(r)) return true;
        }
        return false;
    }

    /** Take the wanted values as the stored ones: what a successful store ends with. */
    public void commit() {
        System.arraycopy(wanted, 0, stored, 0, ROW_COUNT);
    }

    /** Throw away the edits and go back to what the camera holds. This is the exit rule. */
    public void revert() {
        System.arraycopy(stored, 0, wanted, 0, ROW_COUNT);
    }

    // ---------------------------------------------------------------- values the panel derives
    /** Interval between two frames, in milliseconds. */
    public int intervalMs() {
        return wanted[ROW_INTERVAL];
    }

    /** Frames wanted, or {@link #FRAMES_UNLIMITED}. */
    public int frames() {
        return wanted[ROW_FRAMES];
    }

    /** Delay before the first frame, in milliseconds. */
    public int delayMs() {
        return wanted[ROW_DELAY];
    }

    public int settleMs() {
        return SETTLE_MS[wanted[ROW_SETTLE]];
    }

    public boolean silentShutter() {
        return wanted[ROW_SILENT] != 0;
    }

    public int driveMode() {
        return wanted[ROW_DRIVE];
    }

    public boolean autoExposureLock() {
        return wanted[ROW_AEL] != 0;
    }

    /** Whether this run reads the card to confirm each frame, or trusts the shutter callback alone. */
    public boolean cardCheck() {
        return wanted[ROW_CARD_CHECK] != 0;
    }

    public boolean panelOff() {
        return wanted[ROW_PANEL_OFF] != 0;
    }

    /**
     * How long the whole plan takes: the initial delay plus the space between the first and last
     * frame, which is {@code (n - 1) × interval} for {@code n} frames.
     *
     * <p>The last frame ends the run. There is no interval to wait out after it, and the number on
     * screen has to be the number the user actually stands there for — an estimate that is one
     * interval long is a small lie, but it is the same kind of lie as the old app's, which computed
     * this in two places as {@code interval * shots} on one screen and
     * {@code (shots * perShot - shots) * interval} on the other and had them disagree by a factor of
     * three under bracketing (docs/PAIN-POINTS.md U-10, D-18).
     *
     * <p>Returns {@link #UNLIMITED} when the frame count is.
     */
    public static final long UNLIMITED = -1L;

    public long totalMs() {
        if (frames() == FRAMES_UNLIMITED) return UNLIMITED;
        return (long) delayMs() + (long) (frames() - 1) * intervalMs();
    }

    // ---------------------------------------------------------------- clamping
    /**
     * Keep a value inside its legal range. Every write goes through here, so no caller can put a row
     * into a state the formatter cannot render.
     */
    /**
     * A drive mode as the camera spells it.
     *
     * <p>Lower case, because that is what this body reports back — {@code single}, not {@code SINGLE}.
     * Here rather than at either call site because there are two of them now (applying the plan at a run's
     * start, and putting the body back on the way out), and two spellings of one wire value is exactly how
     * a setting stops taking effect in one of the two directions.
     */
    public static String driveWire(int mode) {
        return mode == DRIVE_BURST ? "burst" : mode == DRIVE_BRACKET3 ? "bracket" : "single";
    }

    public static int clamp(int row, int value) {
        switch (row) {
            case ROW_INTERVAL:
                return Math.max(INTERVAL_MIN_MS, Math.min(INTERVAL_MAX_MS, value));
            case ROW_FRAMES:
                if (value == FRAMES_UNLIMITED) return FRAMES_UNLIMITED;
                return Math.max(1, Math.min(FRAMES_MAX, value));
            case ROW_DELAY:
                return Math.max(0, Math.min(DELAY_MAX_MS, value));
            case ROW_SETTLE:
                return Math.max(0, Math.min(SETTLE_MS.length - 1, value));
            case ROW_DRIVE:
                return Math.max(0, Math.min(DRIVE_COUNT - 1, value));
            default: // the on/off rows
                return value != 0 ? 1 : 0;
        }
    }
}
