package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What the screen says.
 *
 * <p>These are assertions about sentences, which sounds like the weakest kind of test until you remember
 * what this project is for: the old app's screen reported frames it never took, settings it never applied
 * and an interval it was not shooting (docs/PAIN-POINTS.md U-06, U-07, U-18). Every string on this panel
 * is a claim about the camera, and a claim is testable.
 */
class HudTest {

    @Test
    void everyRowCanBeDrawnOnTheScreen() {
        Plan plan = new Plan();
        for (int row = 0; row < Plan.ROW_COUNT; row++) {
            assertTrue(Hud.rowLabel(row).length() > 0, "row " + row + " has no label");
            assertTrue(Hud.rowValue(plan, row).length() > 0, "row " + row + " has no value");
            assertTrue(Steps.hint(row, plan.get(row)).length() > 0, "row " + row + " has no hint");
        }
    }

    @Test
    void theSilentRowSaysWhatTheValueDoesAndNotTheOtherWayRound() {
        Plan plan = new Plan();
        assertEquals("机械快门，有声", Hud.rowDetail(plan, Plan.ROW_SILENT), "off means the mechanical shutter");

        plan.set(Plan.ROW_SILENT, 1);
        assertEquals("电子快门，无声", Hud.rowDetail(plan, Plan.ROW_SILENT), "on means no shutter sound");

        // They were the wrong way round: "每张不动机械快门" is a consequence of the silent shutter being
        // ON, and it was shown when it was off.
        plan.set(Plan.ROW_SILENT, 0);
        assertFalse(Hud.rowDetail(plan, Plan.ROW_SILENT).contains("不动机械快门"));
    }

    @Test
    void theCameraRowsAreTheOnesARunCannotChangeUnderItself() {
        assertTrue(Hud.cameraRow(Plan.ROW_SILENT));
        assertTrue(Hud.cameraRow(Plan.ROW_DRIVE));
        assertTrue(Hud.cameraRow(Plan.ROW_AEL));

        assertFalse(Hud.cameraRow(Plan.ROW_INTERVAL), "the timetable is the user's to change mid-run");
        assertFalse(Hud.cameraRow(Plan.ROW_FRAMES));
        assertFalse(Hud.cameraRow(Plan.ROW_DELAY));
    }

    @Test
    void aLockedRowSaysWhyItWillNotMove() {
        Plan plan = new Plan();
        String locked = Hud.rowDetail(plan, Plan.ROW_SILENT, true);
        assertTrue(locked.contains("运行中"), "a locked row must say what is stopping it: " + locked);
        assertEquals(Hud.rowDetail(plan, Plan.ROW_INTERVAL, true), Hud.rowDetail(plan, Plan.ROW_INTERVAL, false),
                "a row the run does not lock is unaffected by the lock");
    }

    @Test
    void thePanelSaysTheShutterSettingIsOffWhenItIsOff() {
        Plan plan = new Plan();
        String off = Hud.rowValue(plan, Plan.ROW_SILENT);
        plan.set(Plan.ROW_SILENT, 1);
        assertFalse(off.equals(Hud.rowValue(plan, Plan.ROW_SILENT)), "the value follows the plan");
    }

    @Test
    void theProgressLineCountsWhatTheCardCouldNotAccountFor() {
        String plain = Hud.progressLine(10, 300, 10, 0, 0, 20_000);
        assertTrue(plain.startsWith("已拍 10 / 300"), plain);
        assertTrue(plain.contains("已用"), plain);
        assertFalse(plain.contains("未核实"), "nothing unaccounted for means no such word");
        assertFalse(plain.contains("跳过"), plain);

        String odd = Hud.progressLine(10, 300, 8, 2, 3, 20_000);
        assertTrue(odd.contains("未核实 2"), odd);
        assertTrue(odd.contains("跳过 3"), "a hole in the timeline is said out loud: " + odd);
    }

    @Test
    void anUnlimitedPlanDoesNotPretendToHaveAFinishingLine() {
        String line = Hud.progressLine(42, Plan.FRAMES_UNLIMITED, 42, 0, 0, 1000);
        assertTrue(line.contains("42"), line);
        assertFalse(line.contains("/"), "an endless run has no denominator: " + line);
    }

    @Test
    void theDimmedLineAnswersIsItWorking() {
        String plain = Hud.labelLine(12, 300, 0, 0);
        assertTrue(plain.startsWith("已拍 12 / 300"), plain);
        assertFalse(plain.contains("跳过"), "no holes means no such word");

        String odd = Hud.labelLine(12, 300, 1, 2);
        assertTrue(odd.contains("跳过 1"), odd);
        assertTrue(odd.contains("未核实 2"), odd);

        String endless = Hud.labelLine(42, Plan.FRAMES_UNLIMITED, 0, 0);
        assertFalse(endless.contains("/"), "an endless run has no denominator: " + endless);
    }

    @Test
    void aRunThatKeepsSixFramesOutOfSeventeenSaysSo() {
        // Measured on the camera: 落盘 17, 核实 6, and the panel said neither in words. The old app reported
        // the seventeen and nothing else.
        assertTrue(Hud.worthSaying(17, 6), "six of seventeen is worth interrupting the user for");
        assertTrue(Hud.worthSaying(5, 2));
        assertFalse(Hud.worthSaying(17, 15), "in-flight writes make a small gap normal");
        assertFalse(Hud.worthSaying(4, 0), "four frames is not enough to draw a conclusion from");

        String line = Hud.shortfallLine(17, 6);
        assertTrue(line.contains("6 / 17"), line);
        assertTrue(line.contains("间隔"), "the line has to say what to change: " + line);
    }

    @Test
    void theProgressLinePutsTheTakenAndTheAccountedSideBySide() {
        String line = Hud.progressLine(17, 135, 6, 11, 0, 1000);
        assertTrue(line.startsWith("已拍 17 / 135"), line);
        assertTrue(line.contains("核实 6"), "the two numbers belong together: " + line);
        assertTrue(line.contains("未核实 11"), line);
    }

    @Test
    void faultsAreNamedInTheUsersWordsNotThePlatforms() {
        Plan plan = new Plan();
        Engine engine = new Engine(plan);
        engine.step(Engine.EV_ENTER, 0);
        long now = 0;
        for (int i = 0; i < Engine.MAX_CONSECUTIVE_FAILURES; i++) {
            // The shell's own loop in three lines: wait for the moment, take the frame, lose it. A loss
            // only counts for a frame that was actually in flight.
            int action = engine.step(Engine.EV_TICK, now);
            if (action == Engine.ACT_ARM_TIMER) action = engine.step(Engine.EV_TICK, now);
            assertEquals(Engine.ACT_FIRE, action, "frame " + i + " should be due");
            engine.step(Engine.EV_TIMEOUT, now);
            now += plan.intervalMs();
        }

        assertEquals(Engine.FAULT_WRITE, engine.fault());
        assertEquals("连续 " + Engine.MAX_CONSECUTIVE_FAILURES + " 张未写入", engine.faultText());
    }

    @Test
    void everyStateHasAWordAndAPlainSentence() {
        int[] states = { Engine.IDLE, Engine.ARMED, Engine.WAITING, Engine.EXPOSING, Engine.PAUSED,
                Engine.DONE, Engine.FAULT };
        for (int i = 0; i < states.length; i++) {
            assertTrue(Hud.stateName(states[i]).length() > 0, "state " + states[i] + " has no name");
            assertTrue(Hud.statePlain(states[i]).length() > 0, "state " + states[i] + " has no sentence");
        }
    }

    @Test
    void theLegendNamesTheKeysThatDoSomethingOnTheirOwn() {
        String legend = Hud.legend();
        assertTrue(legend.contains("换行"), legend);
        assertTrue(legend.contains("开始"), legend);
        assertTrue(legend.contains("暂停"), legend);

        // Hold actions are deliberately absent (PLAN §4.1 item 6): they are for the people who go looking.
        // Adding them back is how this row grew long enough to be drawn through the font note next to it.
        assertFalse(legend.contains("长按"), legend);
    }

    @Test
    void theCountdownFractionFillsUpAsTheFrameApproaches() {
        // The one-line panel draws this fraction as a filled rectangle, and the fraction itself is here so it
        // can be checked off the camera -- the edge cases are the ones that were wrong before: an interval of
        // zero, a frame already late, a wait that has not started.
        assertEquals(0f, Hud.countdownFraction(10_000, 10_000), 0.0001f, "nothing elapsed yet");
        assertEquals(0.5f, Hud.countdownFraction(5_000, 10_000), 0.0001f, "half way");
        assertEquals(1f, Hud.countdownFraction(0, 10_000), 0.0001f, "the moment is here");
        assertEquals(1f, Hud.countdownFraction(-500, 10_000), 0.0001f, "late is still full");
        assertEquals(0f, Hud.countdownFraction(20_000, 10_000), 0.0001f, "a wait longer than the interval");
        assertEquals(Frames.UNKNOWN, Hud.countdownFraction(Schedule.NONE, 10_000), 0.0001f, "no frame, no bar");
        assertEquals(Frames.UNKNOWN, Hud.countdownFraction(1_000, 0), 0.0001f, "no interval to be a fraction of");
        assertEquals(Frames.UNKNOWN, Hud.countdownFraction(1_000, -5), 0.0001f, "a nonsense interval draws nothing");
    }
}
