package com.voxivoid.bettertimelapse;

/**
 * The order in which a borrowed camera is given back.
 *
 * <p>This exists because the teardown was wrong twice and no test could see it. Both times the cause was the
 * same shape: an operation that reads a field which the teardown had already cleared, so the step ran, returned
 * immediately, and read as if it had worked.
 *
 * <pre>
 *   close() { camera = null; ...; stopPreview(); }   // stopPreview starts `if (camera == null) return`
 * </pre>
 *
 * <p>The exit line in the session log said {@code preview=running} on every clean exit, which was the teardown
 * reporting itself honestly: nothing had been stopped. The reason no test caught it is the reason this class is
 * here — the order lived in shell code, and shell code in this project is only ever compiled, never run
 * (docs/DECISIONS.md A37, A48). So the order is a value now: it can be asserted, it is named, and a class that
 * carries it out cannot quietly reorganise it.
 *
 * <p>The order itself is not arbitrary. The preview goes first because it is the body's heaviest activity and
 * the one thing that keeps the imaging pipeline running after this app has stopped asking for frames; the
 * capture is cancelled next so a half-taken frame is not left open on the card; the handle is released last,
 * because releasing it is what makes the other two impossible.
 */
public final class Teardown {

    private Teardown() {}

    /** Stop the preview stream. Must come first: it needs the handle. */
    public static final String PREVIEW = "preview";

    /** Cancel a capture that is still open, so nothing is left half-written. */
    public static final String CAPTURE = "capture";

    /** Let go of the settings this app borrowed from the body. */
    public static final String SETTINGS = "settings";

    /** Release the camera handle itself. Nothing after this can touch the camera. */
    public static final String HANDLE = "handle";

    /** Clear every field, so nothing in the app believes the camera is still there. */
    public static final String FORGET = "forget";

    private static final String[] ORDER = { PREVIEW, CAPTURE, SETTINGS, HANDLE, FORGET };

    /** The steps, in the only order they work. */
    public static String[] steps() {
        return ORDER.clone();
    }

    /** Where a step sits in the sequence, or {@code -1} when it is not one. */
    public static int indexOf(String step) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(step)) return i;
        }
        return -1;
    }

    /**
     * Whether {@code first} has to happen before {@code second}.
     *
     * <p>The assertion the teardown keeps failing: a step that reads the camera handle may not run after the
     * handle has been given up, and forgetting the camera may not happen while the camera is still in use.
     */
    public static boolean before(String first, String second) {
        int a = indexOf(first);
        int b = indexOf(second);
        return a >= 0 && b >= 0 && a < b;
    }
}
