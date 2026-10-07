package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The plan: the values the panel edits, what they are clamped to, and the exit rule.
 *
 * <p>The defaults are a promise about what this app does to a camera it has just been installed on.
 * Every one of them is checked here, because "harmless by default" is a property of the numbers and not
 * of the intention behind them.
 */
class PlanTest {

    @Test
    void theDefaultsChangeNothingOnTheCamera() {
        Plan p = new Plan();

        assertFalse(p.silentShutter(), "silent shutter is off until asked for");
        assertFalse(p.autoExposureLock(), "AEL is off until asked for");
        assertEquals(Plan.DRIVE_SINGLE, p.driveMode(), "one press, one frame");
        assertEquals(0, p.delayMs());
        assertEquals(5000, p.intervalMs());
        assertEquals(300, p.frames());
    }

    @Test
    void theDefaultIntervalIsOneThisBodyCanActuallyShoot() {
        // The default used to be 2 s, which this camera refuses (its floor is about 2.9 s), so a new user's
        // first run began with the app warning them about the app's own default.
        assertTrue(new Plan().intervalMs() >= Frames.shortestIntervalMs(),
                "the plan a fresh install starts on has to be shootable on the body it runs on");
    }

    @Test
    void everyRowKeepsItsValueInsideItsOwnRange() {
        Plan p = new Plan();

        p.set(Plan.ROW_INTERVAL, 0);
        assertEquals(Plan.INTERVAL_MIN_MS, p.intervalMs());
        p.set(Plan.ROW_INTERVAL, Integer.MAX_VALUE);
        assertEquals(Plan.INTERVAL_MAX_MS, p.intervalMs(), "24 hours is the ceiling");

        p.set(Plan.ROW_FRAMES, 0);
        assertEquals(1, p.frames(), "a run of no frames is not a run");
        p.set(Plan.ROW_FRAMES, Plan.FRAMES_UNLIMITED);
        assertEquals(Plan.FRAMES_UNLIMITED, p.frames(), "unlimited survives clamping");
        p.set(Plan.ROW_FRAMES, 999_999);
        assertEquals(Plan.FRAMES_MAX, p.frames());

        p.set(Plan.ROW_DELAY, -5000);
        assertEquals(0, p.delayMs());

        p.set(Plan.ROW_SETTLE, 99);
        assertEquals(Plan.SETTLE_MS[Plan.SETTLE_MS.length - 1], p.settleMs());
        p.set(Plan.ROW_SETTLE, -1);
        assertEquals(Plan.SETTLE_MS[0], p.settleMs());

        // An on/off row holds 0 or 1, and the rule a caller can rely on is "nonzero means on" -- the
        // same rule the panel's own toggle uses (Steps.apply), so the two cannot disagree.
        p.set(Plan.ROW_SILENT, 7);
        assertEquals(1, p.get(Plan.ROW_SILENT));
        p.set(Plan.ROW_SILENT, 0);
        assertFalse(p.silentShutter());
    }

    @Test
    void theTotalTimeIsTheDelayPlusTheSpacesBetweenTheFrames() {
        Plan p = new Plan();
        p.set(Plan.ROW_INTERVAL, 2000);
        p.set(Plan.ROW_FRAMES, 300);
        p.set(Plan.ROW_DELAY, 10_000);

        assertEquals(10_000 + 299 * 2000, p.totalMs(), "300 frames have 299 gaps, not 300");

        p.set(Plan.ROW_FRAMES, Plan.FRAMES_UNLIMITED);
        assertEquals(Plan.UNLIMITED, p.totalMs(), "an endless run has no end to promise");
    }

    @Test
    void oneFrameIsJustTheDelay() {
        Plan p = new Plan();
        p.set(Plan.ROW_FRAMES, 1);
        p.set(Plan.ROW_INTERVAL, 60_000);
        p.set(Plan.ROW_DELAY, 5000);

        assertEquals(5000, p.totalMs());
    }

    @Test
    void seedingFromTheBodyMakesTheCameraTheBaseline() {
        Plan p = new Plan();
        assertFalse(p.silentShutter(), "the app's own default");

        // What the body reported at open. Without this, applying the plan was a one-way switch: the app
        // believed the body held its own default, so a setting it turned on could never be turned off.
        p.seed(Plan.ROW_SILENT, 1);
        p.seed(Plan.ROW_DRIVE, Plan.DRIVE_BRACKET3);

        assertTrue(p.silentShutter(), "the plan now says what the camera holds");
        assertEquals(Plan.DRIVE_BRACKET3, p.driveMode());
        assertFalse(p.dirty(), "and nothing is pending, because nothing was edited");
    }

    @Test
    void seedingIsClampedLikeEveryOtherWrite() {
        Plan p = new Plan();
        p.seed(Plan.ROW_SILENT, 42);
        assertEquals(1, p.get(Plan.ROW_SILENT));
        p.seed(Plan.ROW_DRIVE, 99);
        assertEquals(Plan.DRIVE_COUNT - 1, p.get(Plan.ROW_DRIVE));
    }

    @Test
    void anEditIsDirtyUntilTheCameraHoldsIt() {
        Plan p = new Plan();
        assertFalse(p.dirty());

        p.set(Plan.ROW_INTERVAL, 3000);
        assertTrue(p.dirty(), "the panel must be able to say an edit is not stored yet");
        assertTrue(p.dirty(Plan.ROW_INTERVAL));
        assertFalse(p.dirty(Plan.ROW_FRAMES), "and which row it was");

        p.commit();
        assertFalse(p.dirty(), "a stored plan is not dirty");
    }

    @Test
    void theDriveModeHasOneSpellingForTheCamera() {
        // Two spellings of one wire value is how a setting stops taking effect in one direction: this
        // mapping is used both when a run starts and when the body is put back on the way out.
        assertEquals("single", Plan.driveWire(Plan.DRIVE_SINGLE));
        assertEquals("burst", Plan.driveWire(Plan.DRIVE_BURST));
        assertEquals("bracket", Plan.driveWire(Plan.DRIVE_BRACKET3));
        assertEquals("single", Plan.driveWire(999), "an unknown mode falls back to the harmless one");
    }

    @Test
    void theExitRulePutsBackWhatTheCameraHolds() {
        Plan p = new Plan();
        p.set(Plan.ROW_INTERVAL, 2000);
        p.set(Plan.ROW_SILENT, 1);
        p.commit();                                      // the camera now holds these

        p.set(Plan.ROW_INTERVAL, 8000);
        p.set(Plan.ROW_SILENT, 0);
        p.set(Plan.ROW_AEL, 1);
        p.revert();

        // The exit rule is "set the live parameters to the STORED values", never to a launch snapshot.
        assertEquals(2000, p.intervalMs());
        assertTrue(p.silentShutter());
        assertFalse(p.autoExposureLock());
        assertFalse(p.dirty());
    }

    @Test
    void theExitRuleAfterSeedingRestoresTheBodyAndNotTheDefaults() {
        Plan p = new Plan();
        // What the body held when it was opened: silent shutter ON, bracketing. The app's own defaults
        // are the opposite of both, which is exactly the mistake the exit rule must not make.
        p.seed(Plan.ROW_SILENT, 1);
        p.seed(Plan.ROW_DRIVE, Plan.DRIVE_BRACKET3);

        p.set(Plan.ROW_SILENT, 0);
        p.set(Plan.ROW_DRIVE, Plan.DRIVE_SINGLE);
        assertTrue(p.dirty());

        p.revert();

        assertTrue(p.silentShutter(), "the exit rule puts back what the camera held, not what this app likes");
        assertEquals(Plan.DRIVE_BRACKET3, p.driveMode());
        assertFalse(p.dirty(), "and nothing is left pending");
    }
}
