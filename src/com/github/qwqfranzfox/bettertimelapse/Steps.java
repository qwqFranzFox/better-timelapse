package com.github.qwqfranzfox.bettertimelapse;

/**
 * How much one input changes a value: <b>left/right take big steps, the wheel takes the smallest
 * one</b>.
 *
 * <p>The split is deliberate. Sweeping the wheel is the fast, imprecise motion — a thumb can spin it
 * many detents without looking — so it is the one that must never overshoot a value the user cares
 * about. Left/right are deliberate presses, so they carry the jumps. Both are shown on screen next
 * to the value, so neither is a surprise.
 *
 * <p>This class exists because of the old app's worst usability defect. Its interval slider mapped
 * 119 detents onto six bands whose step size jumped from 0.5 s to 30 s to 60 s
 * (TimeLapse {@code SettingsActivity.java:166-198}), there was no inverse mapping, and the panel
 * never said what a detent was worth — so "one click left" could be half a second or a minute, and
 * one position to the left of 1.0 s silently turned the shots slider into a seconds slider
 * (docs/PAIN-POINTS.md U-07, U-08).
 *
 * <p>No {@code android.*} import: this is arithmetic, and {@code tools/test.sh} compiles it without
 * {@code android.jar}.
 */
public final class Steps {

    private Steps() {}

    // ---------------------------------------------------------------- interval
    /**
     * The interval step for left/right: the deliberate jump. It never changes by more than about a
     * factor of two between neighbouring values, so a press is always roughly what the last press
     * was worth.
     */
    public static int intervalArrow(int ms) {
        if (ms < 1000) return 500;
        if (ms < 2000) return 500;
        if (ms < 10000) return 1000;
        if (ms < 40000) return 5000;
        if (ms < 120000) return 15000;
        if (ms < 600000) return 30000;
        if (ms < 3600000) return 300000;
        return 600000;
    }

    /**
     * The interval step for the wheel: the smallest step the value's own resolution allows, so a
     * spin can always be corrected by spinning back.
     */
    public static int intervalWheel(int ms) {
        return Math.max(100, intervalArrow(ms) / 10);
    }

    // ---------------------------------------------------------------- frames
    /**
     * Left/right on the frames row: 5 at a time for a short run, growing once the count is large
     * enough that five would be a crawl. Deliberately small at the scale a night shoot actually
     * uses, so a press is a nudge and not a jump — the wheel is there for single frames.
     */
    public static int framesArrow(int frames) {
        if (frames == Plan.FRAMES_UNLIMITED) return 1;
        if (frames <= 1000) return 5;
        if (frames <= 3000) return 25;
        if (frames <= 6000) return 100;
        return 500;
    }

    /** The wheel on the frames row: one frame, always. */
    public static int framesWheel(int frames) {
        return 1;
    }

    // ---------------------------------------------------------------- initial delay
    public static int delayArrow(int ms) {
        if (ms < 60000) return 5000;
        if (ms < 600000) return 60000;
        if (ms < 3600000) return 300000;
        return 1800000;
    }

    public static int delayWheel(int ms) {
        return ms < 60000 ? 1000 : 60000;
    }

    // ---------------------------------------------------------------- the generic door
    /**
     * The step for a row, coarse or fine. The list rows (settle delay, drive mode) step by one
     * position in their list; the on/off rows toggle; the three numeric rows use their band tables.
     */
    public static int step(int row, int value, boolean wheel) {
        switch (row) {
            case Plan.ROW_INTERVAL:
                return wheel ? intervalWheel(value) : intervalArrow(value);
            case Plan.ROW_FRAMES:
                return wheel ? framesWheel(value) : framesArrow(value);
            case Plan.ROW_DELAY:
                return wheel ? delayWheel(value) : delayArrow(value);
            case Plan.ROW_SETTLE:
            case Plan.ROW_DRIVE:
                return 1;
            default:
                return 1;
        }
    }

    /**
     * Step a value, clamped by the plan's own rules. A list row wraps (its positions are a cycle);
     * a numeric row stops at its end, because silently wrapping 24 h back to 0.1 s while the user is
     * turning the wheel would be its own defect.
     */
    public static int apply(int row, int value, boolean wheel, int direction) {
        if (direction == 0) return Plan.clamp(row, value);
        if (row == Plan.ROW_SETTLE) {
            int n = Plan.SETTLE_MS.length;
            return ((value + direction) % n + n) % n;
        }
        if (row == Plan.ROW_DRIVE) {
            int n = Plan.DRIVE_COUNT;
            return ((value + direction) % n + n) % n;
        }
        if (row == Plan.ROW_SILENT || row == Plan.ROW_AEL || row == Plan.ROW_PANEL_OFF
                || row == Plan.ROW_CARD_CHECK) {
            return value != 0 ? 0 : 1;
        }
        // The frames row walks through "unlimited" on its way up and back down again, so the state
        // is reachable in both directions and is never a trap. It only steps off the end *after*
        // reaching the end: turning up from 9990 must show 9999 before it shows unlimited, or the
        // scale has the same unpredictable jump the old app's slider had.
        if (row == Plan.ROW_FRAMES) {
            // "Unlimited" is a place on the scale, not a wall. One turn in either direction leaves
            // it — a turn that changes nothing reads as a broken wheel.
            if (value == Plan.FRAMES_UNLIMITED) return Plan.FRAMES_MAX;
            int next = value + direction * step(row, value, wheel);
            if (direction > 0 && next > Plan.FRAMES_MAX) {
                return value < Plan.FRAMES_MAX ? Plan.FRAMES_MAX : Plan.FRAMES_UNLIMITED;
            }
            return Plan.clamp(row, next);
        }
        return Plan.clamp(row, value + direction * step(row, value, wheel));
    }

    /**
     * What each input is worth, as the panel prints it next to the value. Left/right first, because
     * that is the bigger step, then the wheel. This is the string that makes the old app's surprise
     * step size impossible.
     */
    public static String hint(int row, int value) {
        switch (row) {
            case Plan.ROW_INTERVAL:
                return "\u2190\u2192 \u00b1" + shortMs(intervalArrow(value))
                        + "  \u6eda\u8f6e \u00b1" + shortMs(intervalWheel(value));
            case Plan.ROW_FRAMES:
                if (value == Plan.FRAMES_UNLIMITED) return "\u2190\u2192 \u6709\u9650  \u6eda\u8f6e \u00b11";
                return "\u2190\u2192 \u00b1" + framesArrow(value) + "  \u6eda\u8f6e \u00b11";
            case Plan.ROW_DELAY:
                return "\u2190\u2192 \u00b1" + shortMs(delayArrow(value))
                        + "  \u6eda\u8f6e \u00b1" + shortMs(delayWheel(value));
            case Plan.ROW_SETTLE:
                return "\u5de6/\u53f3 \u5faa\u73af " + settleList();
            case Plan.ROW_DRIVE:
                return "\u5de6/\u53f3 \u5faa\u73af";
            default:
                return "\u5f00 / \u5173";
        }
    }

    private static String settleList() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Plan.SETTLE_MS.length; i++) {
            if (i > 0) b.append('/');
            b.append(shortMs(Plan.SETTLE_MS[i]));
        }
        return b.toString();
    }

    /**
     * A duration for a place where space is tight: {@code 500ms}, {@code 2s}, {@code 30s},
     * {@code 5min}, {@code 1h}. Decimal seconds keep one place, because an interval of 2.5 s is a
     * real thing to want.
     */
    public static String shortMs(int ms) {
        if (ms < 1000) return ms + "ms";
        if (ms % 1000 == 0 && ms < 60000) return (ms / 1000) + "s";
        if (ms < 60000) return trimZero(ms / 1000.0, 1) + "s";
        if (ms % 60000 == 0) return (ms / 60000) + "min";
        if (ms < 3600000) return trimZero(ms / 60000.0, 1) + "min";
        return (ms / 3600000) + "h";
    }

    private static String trimZero(double v, int places) {
        String s = String.format(java.util.Locale.US, "%." + places + "f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
