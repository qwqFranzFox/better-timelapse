package com.github.qwqfranzfox.bettertimelapse;

/**
 * The three sizes the panel can be, named somewhere both the panel and its state machine can see.
 *
 * <p>They were constants inside {@code PanelView}, which is a class nothing can run off the camera — so the
 * machine that decides between them could not name them without depending on Android. Three integers with
 * names is a small enough thing to share, and it is the difference between a state machine that is tested and
 * one that is described.
 */
public final class PanelModes {

    private PanelModes() {}

    /** Every row, the progress block, the legend and the diagnostics: the screen for setting a run up. */
    public static final int FULL = 0;

    /** One line: the state, the countdown, the progress bar and either the plan or the count. */
    public static final int LABEL = 1;

    /** The state word alone. Reached only by leaving the camera alone, and left by touching it. */
    public static final int MINIMAL = 2;

    public static String name(int mode) {
        switch (mode) {
            case FULL: return "full";
            case LABEL: return "label";
            case MINIMAL: return "minimal";
            default: return "?" + mode;
        }
    }
}
