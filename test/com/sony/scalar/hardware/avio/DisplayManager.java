package com.sony.scalar.hardware.avio;

/**
 * A stand-in for the camera's display class, shaped exactly like the real one.
 *
 * <p>It lives in the test tree and is never packaged into the APK: {@code build.sh} compiles {@code src/}
 * only. Its purpose is that the answers to "does this body have the call" and "which value turns the panel
 * off" stop being reasoning and become assertions — the first version of this app's display code looked for
 * {@code switchDisplayOutputTo(int)} and read these device ids as {@code Integer}s, and reported
 * 本机身关不了屏 on a body that has the call.
 *
 * <p>The shape below is the one recorded in {@code docs/PLATFORM-FACTS.md} §4 (upstream
 * {@code com.sony.scalar.hardware.avio.DisplayManager}, platform 1.0 revision onwards):
 * {@code switchDisplayOutputTo(String)} returning void, {@code DEVICE_ID_*} as {@code String}s whose value is
 * their own name, and a public no-argument constructor.
 *
 * <p>Two things it does that the real class cannot: it records every value it was switched to, and it can be
 * told to fail, so both the happy path and the "the body refused" path are testable.
 */
public class DisplayManager {

    public static final String DEVICE_ID_PANEL = "DEVICE_ID_PANEL";
    public static final String DEVICE_ID_NONE = "DEVICE_ID_NONE";
    public static final String DEVICE_ID_HDMI = "DEVICE_ID_HDMI";
    public static final String DEVICE_ID_FINDER = "DEVICE_ID_FINDER";

    /** Every value this stand-in has been switched to, in order. */
    public static final java.util.List<String> CALLS = new java.util.ArrayList<String>();

    /** When set, the call throws it instead of switching: the body refusing, which must be reported. */
    public static RuntimeException failWith;

    /** When set, the call returns false instead of switching: a boolean API refusing the value. */
    public static Boolean refuse;

    /** How many of these objects were constructed: a run must not build one per frame. */
    public static int constructions;

    public DisplayManager() {
        constructions++;
    }

    /**
     * Switch the output. {@code boolean} rather than the real class's {@code void}, deliberately.
     *
     * <p>The body's signature is {@code public void switchDisplayOutputTo(java.lang.String)}, and this app
     * reads the return value when there is one precisely so that a body which says "no" is not recorded as
     * having switched. A {@code void} stand-in cannot express that refusal, so the shape here is the
     * boolean-returning variant — and {@code off()}/{@code on()} still work against it, which is the
     * compatibility this exists to check.
     */
    public boolean switchDisplayOutputTo(String device) {
        if (failWith != null) throw failWith;
        if (Boolean.TRUE.equals(refuse)) return false;
        CALLS.add(device);
        return true;
    }

    /** Present on the real class, and useless to a reflective caller: it proves a miss is harmless. */
    public void finish() {
    }

    /** Reset between tests, so one test's calls are not another's. */
    public static void reset() {
        CALLS.clear();
        failWith = null;
        refuse = null;
        constructions = 0;
    }
}
