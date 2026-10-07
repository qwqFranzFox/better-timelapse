package com.github.qwqfranzfox.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The panel's sizes and what changes them.
 *
 * <p>Every panel bug in this project has been a state machine living where nothing could run it: a mode cycled
 * by a key handler, a drift constant inside a draw call, a resume that did not resume because a key branch was
 * missing a call. The rules are the user's, and they are here so that the ones which must NOT happen -- reaching
 * the minimal state with a key, timing out while a key is being held, drifting back to full on its own -- are
 * assertions rather than intentions.
 */
class PanelMachineTest {

    @Test
    void theAppOpensFullAndARunGoesToOneLine() {
        PanelMachine m = new PanelMachine();
        assertEquals(PanelModes.FULL, m.mode(), "the rows are what setting a run up needs");
        m.recordingStarted(0);
        assertEquals(PanelModes.LABEL, m.mode(), "shooting does not need the rows");
        m.recordingStopped();
        assertEquals(PanelModes.FULL, m.mode(), "and the next thing anyone does is set up another run");
    }

    @Test
    void c1SwitchesBetweenTheTwoSizesAPersonChooses() {
        PanelMachine m = new PanelMachine();
        m.cycleKey(0);
        assertEquals(PanelModes.LABEL, m.mode());
        m.cycleKey(1);
        assertEquals(PanelModes.FULL, m.mode());
        m.cycleKey(2);
        assertEquals(PanelModes.LABEL, m.mode());
    }

    @Test
    void theMinimalStateCannotBeReachedByPressingAnything() {
        // It is what the screen becomes when nobody is looking at it. A key that could put the screen there is a
        // key that can hide the app's own state by accident, so cycling ten times must never reach it.
        PanelMachine m = new PanelMachine();
        m.recordingStarted(0);
        for (int i = 0; i < 10; i++) {
            m.cycleKey(i);
            assertTrue(m.mode() == PanelModes.FULL || m.mode() == PanelModes.LABEL,
                    "cycling reached " + PanelModes.name(m.mode()));
        }
    }

    @Test
    void aMinuteWithoutAKeyDropsToOneWord() {
        PanelMachine m = new PanelMachine();
        m.recordingStarted(0);
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS - 1);
        assertEquals(PanelModes.LABEL, m.mode(), "not yet");
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS);
        assertEquals(PanelModes.MINIMAL, m.mode(), "a minute untouched drops the line to a word");
    }

    @Test
    void onlyTheOneLineModeTimesOut() {
        // Full is for setting a run up: it must stay put while somebody reads it, however long that takes.
        PanelMachine m = new PanelMachine();
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS * 10);
        assertEquals(PanelModes.FULL, m.mode(), "the full panel does not hide itself");
        m.recordingStarted(0);
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS * 10);
        assertEquals(PanelModes.MINIMAL, m.mode());
        // And minimal stays minimal: it is reached by waiting, so waiting longer must not change it again.
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS * 20);
        assertEquals(PanelModes.MINIMAL, m.mode());
    }

    @Test
    void anyKeyBringsTheWordBackToOneLineAndDoesNothingElse() {
        PanelMachine m = new PanelMachine();
        m.recordingStarted(0);
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS);
        assertEquals(PanelModes.MINIMAL, m.mode());

        m.anyKey(PanelMachine.IDLE_TO_MINIMAL_MS + 1);
        assertEquals(PanelModes.LABEL, m.mode(), "a key means somebody is here, so the line comes back");

        // From the one-line mode a key is not a layout change: it only restarts the wait.
        m.anyKey(PanelMachine.IDLE_TO_MINIMAL_MS + 2);
        assertEquals(PanelModes.LABEL, m.mode());
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS + 2 + PanelMachine.IDLE_TO_MINIMAL_MS - 1);
        assertEquals(PanelModes.LABEL, m.mode(), "the wait restarted when the key was pressed");
    }

    @Test
    void aKeyPressInTheFullModeDoesNotStartTheCountdownForTheMinimalState() {
        // The timer belongs to the one-line mode. Somebody turning the wheel through the rows is not idle, and
        // the machine must not treat a full-panel session as a wait.
        PanelMachine m = new PanelMachine();
        m.anyKey(0);
        m.tick(PanelMachine.IDLE_TO_MINIMAL_MS * 5);
        assertEquals(PanelModes.FULL, m.mode());
        assertFalse(m.recording());
        assertEquals(PanelMachine.IDLE_TO_MINIMAL_MS * 5, m.idleMs(PanelMachine.IDLE_TO_MINIMAL_MS * 5));
    }
}
