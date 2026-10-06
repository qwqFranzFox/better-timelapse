package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The run itself: the whole of a several-hour shoot, in milliseconds, with the clock handed in.
 *
 * <p>The engine was built to be driven like this — one {@code EV_*} in, one {@code ACT_*} out — so the
 * cases nobody wants to reproduce at night are ordinary tests here: a camera that refuses three frames
 * in a row, a shell that is held up past a frame's moment, the last moment of a run going by with no
 * frame in it. Each of those was a real failure on the camera before it was a test.
 */
class EngineTest {

    private static Plan plan(int intervalMs, int frames, int delayMs) {
        Plan plan = new Plan();
        plan.set(Plan.ROW_INTERVAL, intervalMs);
        plan.set(Plan.ROW_FRAMES, frames);
        plan.set(Plan.ROW_DELAY, delayMs);
        return plan;
    }

    /**
     * The shell, doing what {@code MainActivity} does with the engine's answers and nothing else: carry
     * out the action, wait for the moment it was given, and hand the frame to the camera.
     */
    private static final class Shell {
        final Engine engine;
        long now;

        Shell(Plan plan, long startMs) {
            engine = new Engine(plan);
            now = startMs;
        }

        /** Step the engine until it wants something other than a timer, advancing the clock to it. */
        int pump() {
            int action = engine.step(Engine.EV_TICK, now);
            for (int guard = 0; action == Engine.ACT_ARM_TIMER; guard++) {
                assertTrue(guard < 10_000, "the engine is arming timers without making progress");
                long remaining = engine.remainingMs(now);
                if (remaining == Schedule.NONE) return Engine.ACT_NONE;   // nothing left to wait for
                now += Math.max(0, remaining);
                action = engine.step(Engine.EV_TICK, now);
            }
            return action;
        }

        /** Shoot one frame the way the shell does: press at the due moment, then release. */
        long shoot() {
            assertEquals(Engine.ACT_FIRE, pump(), "the engine should be asking for a frame here");
            long at = now;
            engine.step(Engine.EV_UNVERIFIED, at);      // taken, and not yet accounted for on the card
            return at;
        }

        void start() {
            engine.step(Engine.EV_ENTER, now);
        }
    }

    @Test
    void aPunctualShellGetsEveryFrameOnItsOwnMoment() {
        Shell shell = new Shell(plan(2000, 5, 0), 0);
        shell.start();

        long[] at = new long[5];
        for (int i = 0; i < at.length; i++) at[i] = shell.shoot();

        assertArrayEquals(new long[] { 0, 2000, 4000, 6000, 8000 }, at);
        assertEquals(5, shell.engine.landed());
        assertEquals(Engine.DONE, shell.engine.state(), "the last frame ends the run");
        assertEquals(2000, shell.engine.meanIntervalMs(), "the measured spacing is the one asked for");
    }

    @Test
    void theInitialDelayIsPartOfTheTimetable() {
        Shell shell = new Shell(plan(1000, 3, 2500), 500);
        shell.start();

        assertEquals(3000, shell.shoot(), "first frame at start + delay");
        assertEquals(4000, shell.shoot());
        assertEquals(5000, shell.shoot());
    }

    @Test
    void aShellHeldUpPastAMomentSkipsItInsteadOfFiringTwice() {
        Shell shell = new Shell(plan(2000, 4, 0), 0);          // press window 1600, margin 400
        shell.start();
        assertEquals(0, shell.shoot());

        // The body was not free until well past the second moment. Firing now would put this frame on
        // top of the next one; the moment is dropped and the next one is still taken where it was due.
        shell.now = 3200;
        assertEquals(4000, shell.shoot());

        assertEquals(1, shell.engine.skipped());
        assertEquals(2, shell.engine.landed());
    }

    @Test
    void aFrameThatIsOnlySlightlyLateIsStillTaken() {
        Shell shell = new Shell(plan(5000, 3, 0), 0);          // press window 2500, margin 2500
        shell.start();
        assertEquals(0, shell.shoot());

        // 1 s late: the release still fits before the next moment, so the frame is worth taking.
        shell.now = 6000;
        assertEquals(6000, shell.shoot());
        assertEquals(0, shell.engine.skipped());
    }

    @Test
    void theLastMomentGoingByEndsTheRunRatherThanStrandingIt() {
        Shell shell = new Shell(plan(2000, 2, 0), 0);
        shell.start();
        assertEquals(0, shell.shoot());

        // The camera never came free again. Without the run noticing that it had just skipped its final
        // moment, it sat in WAITING with no timer behind it: nothing on screen, nothing to press.
        shell.now = 9000;
        assertEquals(Engine.ACT_NONE, shell.pump());

        assertEquals(Engine.DONE, shell.engine.state());
        assertEquals(1, shell.engine.skipped());
        assertEquals(1, shell.engine.landed());
    }

    @Test
    void aRefusedShutterStopsTheRunAsAShutterProblem() {
        Shell shell = new Shell(plan(5000, 10, 0), 0);
        shell.start();

        String why = "RuntimeException: BurstableTakePicture failed";
        for (int i = 0; i < Engine.MAX_CONSECUTIVE_FAILURES; i++) {
            assertEquals(Engine.ACT_FIRE, shell.pump());
            shell.engine.onCallFailed(why, shell.now);
        }

        assertEquals(Engine.FAULT, shell.engine.state());
        assertEquals(Engine.FAULT_SHUTTER, shell.engine.fault());
        assertEquals(why, shell.engine.lastLoss(), "the platform's own words survive to the screen");
        assertEquals(0, shell.engine.landed(), "a refused frame is not a frame");
    }

    @Test
    void framesThatNeverReachTheCardStopTheRunAsAWriteProblem() {
        Shell shell = new Shell(plan(5000, 10, 0), 0);
        shell.start();

        for (int i = 0; i < Engine.MAX_CONSECUTIVE_FAILURES; i++) {
            assertEquals(Engine.ACT_FIRE, shell.pump());
            shell.engine.onFrameLost("no file", shell.now);
        }

        assertEquals(Engine.FAULT_WRITE, shell.engine.fault());
        assertTrue(shell.engine.faultText().length() > 0, "a fault with no words is not a diagnosis");
    }

    @Test
    void oneFailureInTheMiddleOfARunDoesNotEndIt() {
        Shell shell = new Shell(plan(5000, 6, 0), 0);
        shell.start();

        shell.shoot();
        assertEquals(Engine.ACT_FIRE, shell.pump());
        shell.engine.onFrameLost("no file", shell.now);
        shell.shoot();

        assertEquals(Engine.WAITING, shell.engine.state());
        assertEquals(2, shell.engine.landed(), "a single slow write must not throw away a night");
    }

    @Test
    void everyFrameStartsUnaccountedForAndTheCardClearsIt() {
        Shell shell = new Shell(plan(2000, 3, 0), 0);
        shell.start();
        shell.shoot();

        assertEquals(1, shell.engine.landed());
        assertEquals(1, shell.engine.unverified(), "taken, and not yet seen on the card");
        shell.engine.accountFor();
        assertEquals(0, shell.engine.unverified());
        shell.engine.accountFor();
        assertEquals(0, shell.engine.unverified(), "the count never goes below zero");
    }

    @Test
    void aSecondReportForTheSameFrameIsIgnored() {
        Shell shell = new Shell(plan(2000, 3, 0), 0);
        shell.start();
        shell.shoot();

        assertEquals(Engine.ACT_NONE, shell.engine.step(Engine.EV_SHUTTER, shell.now));
        assertEquals(1, shell.engine.landed(), "two reports of one frame are still one frame");
    }

    @Test
    void trashPausesAndTrashAgainPutsTheRunBack() {
        Shell shell = new Shell(plan(2000, 10, 0), 0);
        shell.start();
        shell.shoot();

        assertEquals(Engine.ACT_NONE, shell.engine.step(Engine.EV_TRASH, 1000));
        assertEquals(Engine.PAUSED, shell.engine.state());
        assertFalse(shell.engine.running(), "a paused run is not a run");
        assertFalse(shell.engine.canFire(), "and it must not fire");

        shell.now = 60_000;
        assertEquals(Engine.ACT_ARM_TIMER, shell.engine.step(Engine.EV_TRASH, shell.now));
        assertEquals(Engine.WAITING, shell.engine.state());
        assertEquals(0, shell.engine.remainingMs(shell.now), "the next frame is due as soon as it resumes");
        assertEquals(60_000, shell.shoot());
    }

    @Test
    void theCentreButtonAcknowledgesAFinishedRun() {
        Shell shell = new Shell(plan(1000, 2, 0), 0);
        shell.start();
        shell.shoot();
        shell.shoot();
        assertEquals(Engine.DONE, shell.engine.state());

        shell.engine.step(Engine.EV_ENTER, shell.now);

        assertEquals(Engine.IDLE, shell.engine.state());
        assertEquals(0, shell.engine.landed());
        assertEquals(0, shell.engine.unverified());
        assertEquals(0, shell.engine.fired());
    }

    @Test
    void holdingTrashStopsTheRunAndSaysTheUserDidIt() {
        Shell shell = new Shell(plan(1000, 100, 0), 0);
        shell.start();
        shell.shoot();

        shell.engine.step(Engine.EV_HOLD_TRASH, shell.now);

        assertEquals(Engine.FAULT, shell.engine.state());
        assertEquals(Engine.FAULT_ABORTED, shell.engine.fault());
        assertEquals(1, shell.engine.landed(), "what was shot before the stop is still counted");
    }

    @Test
    void canFireOnlyWhileAFrameIsInFlight() {
        Shell shell = new Shell(plan(5000, 3, 0), 0);
        shell.start();

        assertFalse(shell.engine.canFire(), "armed is not exposing");
        assertEquals(Engine.ACT_FIRE, shell.pump());
        assertTrue(shell.engine.canFire(), "the frame is in flight");
        shell.engine.step(Engine.EV_UNVERIFIED, shell.now);
        assertFalse(shell.engine.canFire(), "and it is over");
    }
}
