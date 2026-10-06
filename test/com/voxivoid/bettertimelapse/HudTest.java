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
        String plain = Hud.progressLine(10, 300, 0, 0, 20_000);
        assertTrue(plain.startsWith("已拍 10 / 300"), plain);
        assertTrue(plain.contains("已用"), plain);
        assertFalse(plain.contains("未核实"), "nothing unaccounted for means no such word");
        assertFalse(plain.contains("跳过"), plain);

        String odd = Hud.progressLine(10, 300, 2, 3, 20_000);
        assertTrue(odd.contains("未核实 2"), odd);
        assertTrue(odd.contains("跳过 3"), "a hole in the timeline is said out loud: " + odd);
    }

    @Test
    void anUnlimitedPlanDoesNotPretendToHaveAFinishingLine() {
        String line = Hud.progressLine(42, Plan.FRAMES_UNLIMITED, 0, 0, 1000);
        assertTrue(line.contains("42"), line);
        assertFalse(line.contains("/"), "an endless run has no denominator: " + line);
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
    void theLegendNamesBothHoldActions() {
        String legend = Hud.legend();
        assertTrue(legend.contains("演练"), legend);
        assertTrue(legend.contains("屏显"), legend);
        assertTrue(legend.contains("暂停"), legend);
    }
}
