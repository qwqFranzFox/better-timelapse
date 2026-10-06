package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The timetable.
 *
 * <p>This is the part of the app that the old one got wrong in a way nobody could see from a
 * photograph of the screen: it re-armed a timer after every frame, so each frame's capture time was
 * added to the next wait and the phase walked away (docs/PAIN-POINTS.md U-18). The arithmetic that
 * replaced it is small enough to pin down here, and it is checked with the clock handed in rather
 * than read from the machine.
 */
class ScheduleTest {

    private static Plan plan(int intervalMs, int frames, int delayMs) {
        Plan plan = new Plan();
        plan.set(Plan.ROW_INTERVAL, intervalMs);
        plan.set(Plan.ROW_FRAMES, frames);
        plan.set(Plan.ROW_DELAY, delayMs);
        return plan;
    }

    @Test
    void frameNIsDueAtDelayPlusNIntervals() {
        Plan p = plan(2000, 5, 500);
        Schedule s = new Schedule();

        s.arm(1000, p);

        assertEquals(1500, s.nextDueMs(p), "frame 0 is due after the initial delay");
        s.advance();
        assertEquals(3500, s.nextDueMs(p), "frame 1 is one interval later");
        s.advance();
        assertEquals(5500, s.nextDueMs(p));
    }

    @Test
    void theTimetableDoesNotMoveWhenTheShellIsLate() {
        Plan p = plan(2000, 3, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        s.advance();                                    // frame 0 was taken, two seconds late

        // The next frame is still due where it was always due: 2000, not 2000 + however long the
        // first frame took. That difference is the whole of U-18.
        assertEquals(2000, s.nextDueMs(p));
        assertEquals(-1500, s.remainingMs(p, 3500), "a late shell sees a negative remaining");
    }

    @Test
    void theRunEndsAfterTheLastFrameAndNotBefore() {
        Plan p = plan(1000, 2, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        assertEquals(0, s.nextDueMs(p));
        s.advance();
        assertEquals(1000, s.nextDueMs(p));
        s.advance();

        assertEquals(Schedule.NONE, s.nextDueMs(p), "no third frame is due");
        assertEquals(Schedule.NONE, s.remainingMs(p, 1000));
    }

    @Test
    void anUnlimitedRunNeverRunsOutOfMoments() {
        Plan p = plan(1000, Plan.FRAMES_UNLIMITED, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        for (int i = 0; i < 500; i++) s.advance();

        assertEquals(500_000, s.nextDueMs(p));
    }

    @Test
    void pausingRemovesThePausedTimeFromTheElapsedClock() {
        Plan p = plan(1000, 10, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        s.pause(4000);
        s.resume(9000);

        assertEquals(5000, s.pausedForMs());
        assertEquals(10_000 - 0 - 5000, s.elapsedMs(10_000));
    }

    @Test
    void rebasingKeepsTheFramesStillToComeSpacedFromNow() {
        Plan p = plan(2000, 10, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        s.advance();
        s.advance();                                    // two frames taken, then the run was paused
        s.rebase(100_000, p);

        // The next frame (index 2) is due as soon as the run is back, not at 4000 where it was.
        assertEquals(100_000, s.nextDueMs(p));
        assertEquals(100_000, s.elapsedMs(100_000), "the elapsed clock does not jump when the grid moves");
    }

    @Test
    void aPauseAndRebaseDoesNotHandBackTheTimeAlreadyRun() {
        Plan p = plan(2000, 10, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        s.advance();                                    // one frame shot
        s.pause(1000);
        s.resume(60_000);
        s.rebase(60_000, p);                            // which is what the engine does on every resume

        // One second had been shot when the run was paused. Zeroing the pause bookkeeping here put the
        // clock back to that second *after* the resume as well, so a five-minute pause looked like the
        // app had lost the run.
        assertEquals(1000, s.elapsedMs(60_000), "the time already shot is still on the clock");
        assertEquals(60_000, s.nextDueMs(p), "and the next frame is due the moment it resumes");
    }

    @Test
    void finishedIsFinished() {
        Plan p = plan(1000, 4, 0);
        Schedule s = new Schedule();

        s.arm(0, p);
        s.finish();

        assertTrue(s.finished());
        assertEquals(Schedule.NONE, s.nextDueMs(p));
    }

    @Test
    void aClearedScheduleIsNotArmed() {
        Schedule s = new Schedule();
        assertFalse(s.armed());

        s.arm(0, plan(1000, 4, 0));
        assertTrue(s.armed());
        s.clear();
        assertFalse(s.armed());
    }

    @Test
    void theMeanIntervalIsMeasuredBetweenTheFirstAndLastFrame() {
        long[] times = { 1000, 3000, 5000, 7000 };
        assertEquals(2000, Schedule.meanInterval(times, 4));
        assertEquals(0, Schedule.meanInterval(times, 1), "one frame says nothing about the spacing");
        assertEquals(0, Schedule.meanInterval(times, 0));
    }

    @Test
    void driftIsTheMeanAgainstWhatWasAskedFor() {
        Plan p = plan(2000, 10, 0);
        assertEquals(150, Schedule.driftMs(2150, p), "a slower run drifts positive");
        assertEquals(-100, Schedule.driftMs(1900, p));
        assertEquals(0, Schedule.driftMs(0, p), "nothing measured yet is not a drift");
    }
}
