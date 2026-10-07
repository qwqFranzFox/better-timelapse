package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The session log's lines.
 *
 * <p>The format is the one docs/PLAN.md §5.2 promised, and it is checked here for the two properties that
 * make a log worth keeping: a line says what happened without needing the screen next to it, and a line
 * cannot be broken by the value inside it — a device name with a space in it, an exception message with a
 * newline in it, a missing battery reading — because a log that a script cannot read is a log nobody reads.
 */
class LogLinesTest {

    @Test
    void aShotLineCarriesTheFactsTheTimetableIsJudgedBy() {
        String line = LogLines.shot(7, 8, 30_000, 120, 4200, 916);

        assertTrue(line.startsWith("shot press=7 moment=8 "), line);
        assertTrue(line.contains("at=+30.000s"), line);
        assertTrue(line.contains("late=120ms"), line);
        assertTrue(line.contains("window=4200ms"), line);
        assertTrue(line.contains("cb=+916ms"), line);
    }

    @Test
    void aFrameWithNoCallbackSaysSoRatherThanClaimingZero() {
        assertTrue(LogLines.shot(1, 1, 0, 0, 2500, Frames.UNKNOWN).contains("cb=-"),
                "no callback is not a callback of zero");
    }

    @Test
    void theVerdictSaysWhatTheCardAnswered() {
        assertTrue(LogLines.verdict(7, 651, 1, true, 858).contains("result=ok"));
        assertTrue(LogLines.verdict(7, 651, 0, false, 858).contains("result=unverified"));
    }

    @Test
    void aValueCannotBreakTheLineItIsOn() {
        String line = LogLines.problem("write", "open failed: EACCES\n(second line)");

        assertFalse(line.contains("\n"), "a newline inside a value would split one event into two lines");
        assertTrue(line.contains("EACCES_(second_line)"), line);
    }

    @Test
    void unknownReadingsAreQuestionMarksAndNotZeroes() {
        assertTrue(LogLines.start("/mnt/sdcard/DCIM", Frames.UNKNOWN, -1, -1).contains("count=?"));
        assertTrue(LogLines.start("/mnt/sdcard/DCIM", Frames.UNKNOWN, -1, -1).contains("free=?"));
        assertTrue(LogLines.start("/mnt/sdcard/DCIM", Frames.UNKNOWN, -1, -1).contains("bat=?"));

        assertTrue(LogLines.body("ILCE-6300", "2.15", Frames.UNKNOWN, false, 0, 3700)
                .contains("exposure=?"));
    }

    @Test
    void theBodyLineSaysWhatAFrameCostsIncludingTheDarkFrame() {
        String line = LogLines.body("ILCE-6300", "2.15", 4000, true, 8000, 8200);

        assertTrue(line.contains("model=ILCE-6300"), line);
        assertTrue(line.contains("exposure=4000ms"), line);
        assertTrue(line.contains("nr=on"), line);
        assertTrue(line.contains("occupancy=8000ms"), line);
        assertTrue(line.contains("shortest=8200ms"), line);
    }

    @Test
    void thePlanLineIsTheOneThePanelShows() {
        Plan plan = new Plan();
        plan.set(Plan.ROW_INTERVAL, 5000);
        plan.set(Plan.ROW_SETTLE, 1);
        plan.set(Plan.ROW_SILENT, 1);

        String line = LogLines.plan(plan);

        assertTrue(line.contains("interval=5000ms"), line);
        assertTrue(line.contains("frames=300"), line);
        assertTrue(line.contains("settle=1200ms"), line);
        assertTrue(line.contains("silent=on"), line);
        assertTrue(line.contains("drive=single"), line);
        assertTrue(line.contains("paneloff=on"), line);
    }

    @Test
    void anUnlimitedRunSaysUnlimitedRatherThanAValueInAMinus() {
        Plan plan = new Plan();
        plan.set(Plan.ROW_FRAMES, Plan.FRAMES_UNLIMITED);
        assertTrue(LogLines.plan(plan).contains("frames=unlimited"), LogLines.plan(plan));
    }

    @Test
    void aFaultIsNamedInTheLogsOwnLanguage() {
        assertEquals("camera", LogLines.faultName(Engine.FAULT_CAMERA));
        assertEquals("card", LogLines.faultName(Engine.FAULT_CARD));
        assertEquals("write", LogLines.faultName(Engine.FAULT_WRITE));
        assertEquals("shutter", LogLines.faultName(Engine.FAULT_SHUTTER));
        assertEquals("aborted", LogLines.faultName(Engine.FAULT_ABORTED));
        assertEquals("none", LogLines.faultName(Engine.FAULT_NONE));
    }

    @Test
    void theRelativeClockIsSecondsWithMilliseconds() {
        assertEquals("+0.000s", LogLines.rel(0));
        assertEquals("+12.345s", LogLines.rel(12_345));
        assertEquals("+3600.000s", LogLines.rel(3_600_000));
        assertEquals("?", LogLines.rel(Frames.UNKNOWN));
    }

    @Test
    void theEndOfARunDoesNotClaimAVerdictThatIsStillPending() {
        String line = LogLines.end("done", 1, 0, 4, 1);
        assertTrue(line.contains("pending=1"), line);
        assertFalse(line.contains("unverified="), "a pending verdict is not a missing file: " + line);

        // The summary is the line that waits for the last verdict, and the first real log is why it exists:
        // END said verified=0 pending=1 one line above verdict result=ok.
        String summary = LogLines.summary(1, 1, 4, 0, 12_992);
        assertTrue(summary.contains("landed=1"), summary);
        assertTrue(summary.contains("verified=1"), summary);
        assertTrue(summary.contains("missed=0"), summary);
        assertTrue(summary.contains("skipped=4"), summary);
        assertTrue(summary.contains("elapsed=12.992s"), summary);
    }

    @Test
    void theLogNamesWhichClockIsWhich() {
        // A press and a moment are different counters, and the first real log mixed them: four skips and one
        // shot of a five-frame plan could not be told apart from five frames.
        assertTrue(LogLines.shot(1, 3, 1000, 17, 2500, Frames.UNKNOWN).startsWith("shot press=1 moment=3 "));
        assertTrue(LogLines.skip(2, 1000, 900, 3000).startsWith("skip moment=2 "));
        assertTrue(LogLines.verdict(1, 648, 1, true, 1636).startsWith("verdict press=1 "));
        assertTrue(LogLines.refused(4, 1000, 1, 5000, "err").startsWith("refused press=4 "));
    }

    @Test
    void aRowTheUserTurnedIsInTheFileAndSaysWhetherItMovedTheClock() {
        String line = LogLines.planEdit(Plan.ROW_INTERVAL, "5s", true);
        assertTrue(line.contains("row=interval"), line);
        assertTrue(line.contains("value=5s"), line);
        assertTrue(line.contains("rebased=yes"), line);

        assertTrue(LogLines.planEdit(Plan.ROW_FRAMES, "300 张", false).contains("rebased=no"));
        assertEquals("interval", LogLines.rowName(Plan.ROW_INTERVAL));
        assertEquals("paneloff", LogLines.rowName(Plan.ROW_PANEL_OFF));
        assertEquals("row9", LogLines.rowName(9), "a row nobody named still gets a name");
    }

    @Test
    void theFirstLineSaysWhatTheFileIs() {
        assertEquals("# better-timelapse session", LogLines.header());
        assertEquals("schema=" + LogLines.SCHEMA, LogLines.schema());
    }
}
