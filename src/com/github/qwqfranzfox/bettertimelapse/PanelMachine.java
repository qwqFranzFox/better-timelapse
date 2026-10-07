package com.github.qwqfranzfox.bettertimelapse;

/**
 * When the panel is full, one line, or almost nothing — as a state machine that can be tested.
 *
 * <p>The rules, in the user's words: the app opens full size; starting a run goes to the one line; C1 switches
 * between full and one line; a minute with no key at all in the one-line mode drops to the state word alone;
 * any key brings it back to one line; and the minimal state is not reachable by pressing anything — it is only
 * ever reached by waiting.
 *
 * <p>Why this is a class and not six {@code if}s in {@code MainActivity}: every panel bug in this project has
 * been a state machine living where nothing could run it. The mode was cycled by a key handler, the drift was a
 * constant in a draw call, and the resume that did not resume was a missing call in a key branch. Here the
 * transitions are data — {@link #recordingStarted()}, {@link #cycleKey()}, {@link #anyKey()},
 * {@link #idle()} — and {@link PanelMachineTest} walks them, including the ones that must NOT happen.
 *
 * <p>It knows nothing about Android or about the panel: it says which mode is wanted and the panel draws it.
 */
public final class PanelMachine {

    /** Idle time in the one-line mode before it drops to the state word alone. */
    public static final long IDLE_TO_MINIMAL_MS = 60_000;

    private int mode = PanelModes.FULL;
    private long lastKeyMs;
    private boolean recording;

    /** The mode to draw. */
    public int mode() {
        return mode;
    }

    /** A run has started: the panel stops showing the rows nobody needs while shooting. */
    public void recordingStarted(long nowMs) {
        recording = true;
        lastKeyMs = nowMs;
        mode = PanelModes.LABEL;
    }

    /** The run is over: back to the rows, because the next thing anyone does is set up another one. */
    public void recordingStopped() {
        recording = false;
        mode = PanelModes.FULL;
    }

    /** C1: the two sizes a person chooses between. The minimal state is never one of them. */
    public void cycleKey(long nowMs) {
        lastKeyMs = nowMs;
        mode = mode == PanelModes.FULL ? PanelModes.LABEL : PanelModes.FULL;
    }

    /**
     * Any other key, pressed or held.
     *
     * <p>It wakes the minimal state back to the one line — and it does nothing else, which is the point: from
     * the minimal state the only way out is to have been looking at the camera, so a key means "I am here",
     * not "change the layout".
     */
    public void anyKey(long nowMs) {
        lastKeyMs = nowMs;
        if (mode == PanelModes.MINIMAL) mode = PanelModes.LABEL;
    }

    /**
     * The clock says the given time has arrived.
     *
     * <p>Only one transition comes from time, and only from the one-line mode: a minute with no key at all drops
     * to the state word. Nothing else about the panel changes on its own.
     */
    public void tick(long nowMs) {
        if (mode != PanelModes.LABEL) return;
        if (nowMs - lastKeyMs < IDLE_TO_MINIMAL_MS) return;
        mode = PanelModes.MINIMAL;
    }

    /** How long the panel has been untouched, for the caller's own timer if it wants one. */
    public long idleMs(long nowMs) {
        return nowMs - lastKeyMs;
    }

    /** Whether a run is in progress, as this machine understands it. */
    public boolean recording() {
        return recording;
    }
}
