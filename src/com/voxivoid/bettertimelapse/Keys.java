package com.voxivoid.bettertimelapse;

/**
 * The keys: the scan codes the app reads, and the press/hold gesture it applies to them.
 *
 * <p>Every function has a route on a key <em>every</em> PlayMemories body has. The bodies differ —
 * the A5100 and A5000 have no Fn button, the A7S II's AEL did nothing in two separate reports, and
 * DISP arrives as plain wheel-up on the bodies where it is printed on the wheel's top — so a
 * per-model table is not maintainable and the app does not keep one
 * (docs/PLATFORM-FACTS.md §6).
 *
 * <p>The layout follows Recipe Lab's, deliberately: up/down and the wheel move between rows,
 * left/right and the wheel turn change the value. Anyone who has used one app can drive the other.
 *
 * <p>The gesture is a state machine rather than {@code onLongClick}, because the firmware does not
 * reliably deliver key-repeat and a hold implemented as a long click double-fires on some bodies.
 * The timer is posted by the caller on {@code ARM} and cancelled on the release.
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class Keys {

    private Keys() {}

    // ---------------------------------------------------------------- scan codes (ScalarInput)
    public static final int K_UP = 103, K_DOWN = 108, K_LEFT = 105, K_RIGHT = 106, K_ENTER = 232,
            K_MENU = 514, K_SK1 = 229, K_DELETE = 595, K_SK2 = 513, K_PLAY = 207, K_DISP = 608,
            K_FN = 520, K_AEL = 532, K_C1 = 622, K_S1 = 516, K_S2 = 518,
            K_WHEEL_CW = 522, K_WHEEL_CCW = 523, K_DIAL_CW = 525, K_DIAL_CCW = 526,
            K_MOVIE = 515, K_ZOOM_T = 610, K_ZOOM_W = 611;
    /** The absolute-position codes of the wheel and the top dial: probed, not bound. */
    public static final int K_WHEEL_STATUS = 521, K_DIAL_STATUS = 524;

    /**
     * The key that runs the panel's self-demo, so the whole screen can be seen without a camera
     * attached. The zoom lever is passed through to the firmware on purpose on every other screen;
     * for this one purpose it is a free key, and the About row says so.
     */
    public static final int K_DEMO = K_ZOOM_T;

    /** How long a key is held before its hold action fires instead of its press. */
    public static final long HOLD_MS = 600;

    /** Sony's name for a scan code, for the key logger. Unknown codes show as the number alone. */
    public static String name(int scan) {
        switch (scan) {
            case K_UP: return "UP";
            case K_DOWN: return "DOWN";
            case K_LEFT: return "LEFT";
            case K_RIGHT: return "RIGHT";
            case K_ENTER: return "ENTER";
            case K_MENU: return "MENU";
            case K_SK1: return "SK1";
            case K_SK2: return "SK2";
            case K_DELETE: return "DELETE";
            case K_PLAY: return "PLAY";
            case K_DISP: return "DISP";
            case K_FN: return "FN";
            case K_AEL: return "AEL";
            case K_C1: return "C1";
            case K_S1: return "S1";
            case 517: return "S1_2";
            case K_S2: return "S2";
            case K_WHEEL_CW: return "WHEEL+";
            case K_WHEEL_CCW: return "WHEEL-";
            case K_DIAL_CW: return "DIAL+";
            case K_DIAL_CCW: return "DIAL-";
            case K_MOVIE: return "MOVIE";
            case K_ZOOM_T: return "ZOOM_T";
            case K_ZOOM_W: return "ZOOM_W";
            case 572: return "MODE_DIAL";
            default: return "";
        }
    }

    /** TRASH on the bodies that print it, or the second soft key that stands for it on NEX. */
    public static boolean isTrash(int scan) {
        return scan == K_DELETE || scan == K_SK2;
    }

    /** MENU on the bodies that print it, or the first soft key on NEX. */
    public static boolean isMenu(int scan) {
        return scan == K_MENU || scan == K_SK1;
    }

    /**
     * Keys the app consumes so the firmware never sees them. AEL, C1 and DISP are not bound and not
     * wanted: TRASH hides the panel on every body, so AEL adds nothing; DISP would steal wheel-up on
     * a wheel-top body while on another body it would hide the panel mid-shoot. Swallowing them is
     * how the app keeps the camera's own menu out of a running time-lapse.
     */
    public static boolean swallowed(int scan) {
        switch (scan) {
            case K_AEL: case K_C1: case K_DISP: case K_PLAY:
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- press / hold
    /**
     * One key's press-or-hold. The short action runs on the release unless the hold already fired;
     * the hold is fired by a timer the caller posts when {@link #down} returns {@link #ARM}, so
     * nothing depends on the firmware delivering key-repeat events.
     */
    public static final class Hold {
        public static final int IGNORE = 0, ARM = 1, SHORT = 2, NONE = 3, HOLD = 4;
        private boolean down, fired;

        /** The key went down: ARM on a first press, IGNORE for a repeat or while it is still held. */
        public int down(int repeat) {
            if (repeat > 0 || down) return IGNORE;
            down = true;
            fired = false;
            return ARM;
        }

        /** The key came up: SHORT when it was down and the hold had not fired. */
        public int up() {
            boolean was = down, f = fired;
            down = false;
            fired = false;
            return was && !f ? SHORT : NONE;
        }

        /** The hold timer ran out: HOLD once per press, while the key is down. */
        public int fire() {
            if (!down || fired) return NONE;
            fired = true;
            return HOLD;
        }

        public boolean isDown() {
            return down;
        }

        public void reset() {
            down = false;
            fired = false;
        }
    }

    /**
     * Whether a TRASH hold that has fired counts as a hold. TRASH acts on its release, so a release
     * normally cancels the timer and a fired timer means the key is still down — except on a body
     * that loses the release. When the camera says the key is already up, the press was short; when
     * the camera will not say, it is a hold, and the worst case is a question that defaults to
     * Cancel.
     */
    public static boolean trashHoldActs(Boolean probeDown) {
        return probeDown == null || probeDown;
    }
}
