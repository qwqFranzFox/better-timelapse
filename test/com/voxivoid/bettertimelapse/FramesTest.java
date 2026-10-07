package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The numbers that decide when a frame is shot, when the card is read, and how much of the interval
 * the body is allowed to take.
 *
 * <p>All of these were wrong at least once on the camera, and every one of them is a number this
 * project can check without one.
 */
class FramesTest {

    private static Plan plan(int intervalMs, int frames) {
        Plan plan = new Plan();
        plan.set(Plan.ROW_INTERVAL, intervalMs);
        plan.set(Plan.ROW_FRAMES, frames);
        return plan;
    }

    @Test
    void bracketingWritesThreeFilesPerPress() {
        Plan single = plan(2000, 10);
        assertEquals(1, Frames.filesPerFrame(single));

        single.set(Plan.ROW_DRIVE, Plan.DRIVE_BRACKET3);
        assertEquals(3, Frames.filesPerFrame(single), "U-10: the old estimate was out by this factor");
    }

    @Test
    void aPlanThatFitsIsNotHeldBack() {
        // The bug this test exists for: a rename swallowed the "everything is fine" branch, so every plan was
        // refused with 间隔 X 短于本机身所需的 3.7s whatever X was, on a camera, because the decision lived in
        // the activity where nothing tests it.
        Plan p = plan(5000, 10);                                     // settle 1200 → floor 3700
        p.set(Plan.ROW_SETTLE, 1);

        Frames.Preflight fits = Frames.preflight(p, 50, true, 0);
        assertTrue(fits.fits(), "5 s is longer than the 3.7 s this plan needs");
        assertEquals("", Hud.preflightProblem(p, fits));

        p.set(Plan.ROW_INTERVAL, 3700);
        assertTrue(Frames.preflight(p, 50, true, 0).fits(), "exactly the floor is enough");

        p.set(Plan.ROW_INTERVAL, 3699);
        assertFalse(Frames.preflight(p, 50, true, 0).fits(), "one millisecond under is under");
    }

    @Test
    void thePreflightSaysWhichFloorThePlanIsUnder() {
        Plan p = plan(3000, 10);
        p.set(Plan.ROW_SETTLE, 0);                                   // floor 3300

        // Nothing measured, a short exposure: the shutter's own window is what binds.
        Frames.Preflight shutter = Frames.preflight(p, 50, false, 0);
        assertEquals(Frames.SHUTTER, shutter.reason);
        assertEquals(3300, shutter.shortestMs);

        // A four-second exposure with the body's dark frame is eight, which is the number to say out loud.
        Frames.Preflight exposure = Frames.preflight(p, 4000, true, 0);
        assertEquals(Frames.EXPOSURE, exposure.reason);
        assertEquals(8200, exposure.shortestMs);
        assertTrue(Hud.preflightProblem(p, exposure).contains("曝光 4s"), Hud.preflightProblem(p, exposure));
        assertTrue(Hud.preflightProblem(p, exposure).contains("降噪"), Hud.preflightProblem(p, exposure));

        // A refusal measured the body at six seconds: that beats both of the others and says so.
        Frames.Preflight measured = Frames.preflight(p, 50, false, 6000);
        assertEquals(Frames.MEASURED, measured.reason);
        assertEquals(6000, measured.shortestMs);
        assertTrue(Hud.preflightProblem(p, measured).contains("实测"), Hud.preflightProblem(p, measured));
    }

    @Test
    void aPlanThatFitsSaysNothingAtAll() {
        Plan p = plan(10_000, 10);
        assertEquals("", Hud.preflightProblem(p, Frames.preflight(p, 4000, true, 0)));
        assertEquals("", Hud.preflightProblem(p, Frames.preflight(p, Frames.UNKNOWN, false, 0)));
    }

    @Test
    void thePressWindowIsTheBodysOwnTimeWhenTheIntervalCanAffordIt() {
        assertEquals(Frames.PRESS_MS, Frames.pressWindowMs(plan(5000, 10)));
        assertEquals(Frames.PRESS_MS, Frames.pressWindowMs(plan(3000, 10)));
    }

    @Test
    void thePressWindowShrinksRatherThanRunningIntoTheNextFrame() {
        // 2 s interval: 1600 ms of press window leaves the 400 ms margin the next press needs.
        assertEquals(1600, Frames.pressWindowMs(plan(2000, 10)));
        // 1 s: the floor, because a press window of 600 ms is already a compromise.
        assertEquals(600, Frames.pressWindowMs(plan(1000, 10)));
    }

    @Test
    void aLongExposureLengthensThePressWindow() {
        Plan p = plan(30_000, 10);

        assertEquals(Frames.PRESS_MS, Frames.pressWindowMs(p, 0), "no callback seen: the constant rules");

        // A shutter callback that arrives ten seconds after the press is this body saying its exposure took
        // ten seconds. Releasing at 2.5 s would cut that exposure off mid-frame.
        assertEquals(10_200, Frames.pressWindowMs(p, 10_000));
    }

    @Test
    void thePressWindowNeverEatsTheNextFramesMoment() {
        Plan p = plan(4000, 10);                     // the body says ten seconds, the interval is four

        // The window is shortened to what the interval can afford rather than allowed to run past it: the
        // frame will be late or lost, and the preflight is where that gets said.
        assertEquals(3600, Frames.pressWindowMs(p, 10_000));
    }

    @Test
    void theExposureIsReadFromTheTwoParametersThatHoldIt() {
        // Measured on this body: shutter-speed-nume=4 shutter-speed-deno=1 in manual exposure.
        assertEquals(4000, Frames.exposureMs("4", "1"));
        assertEquals(16, Frames.exposureMs("1", "60"));
        assertEquals(500, Frames.exposureMs("1", "2"));
        assertEquals(30_000, Frames.exposureMs("30", "1"));

        assertEquals(Frames.UNKNOWN, Frames.exposureMs("1", "0"), "bulb has no length to read");
        assertEquals(Frames.UNKNOWN, Frames.exposureMs("0", "1"));
        assertEquals(Frames.UNKNOWN, Frames.exposureMs(null, "60"), "a body that will not say");
        assertEquals(Frames.UNKNOWN, Frames.exposureMs("", "60"));
        assertEquals(Frames.UNKNOWN, Frames.exposureMs("auto", "60"));
    }

    @Test
    void noiseReductionDoublesWhatAFrameCosts() {
        assertEquals(4000, Frames.occupancyMs(4000, false));
        assertEquals(8000, Frames.occupancyMs(4000, true),
                "a four-second exposure with long-exposure NR is eight seconds of camera");
        assertEquals(0, Frames.occupancyMs(Frames.UNKNOWN, true));
    }

    @Test
    void aLongExposureRaisesTheShortestInterval() {
        Plan p = plan(5000, 10);                                     // settle 1200 → floor 3700

        assertEquals(3700, Frames.shortestIntervalMs(p, Frames.UNKNOWN, false));
        assertEquals(3700, Frames.shortestIntervalMs(p, 2000, false), "a short exposure does not bind");
        assertEquals(4200, Frames.shortestIntervalMs(p, 4000, false), "four seconds plus the margin");
        assertEquals(8200, Frames.shortestIntervalMs(p, 4000, true),
                "and the dark frame puts it at eight: a five-second plan cannot do this");
    }

    @Test
    void theShortestIntervalIsThePressWindowPlusWhatTheBodyGetsToRecover() {
        Plan p = plan(5000, 10);
        // The settle row used to be a floor of its own and the constant under it was 400 ms, which made the
        // shortest interval 2.9 s — and the camera kept six frames out of seventeen at a 3 s interval.
        p.set(Plan.ROW_SETTLE, 1);                                   // 1200 ms, the default
        assertEquals(3700, Frames.shortestIntervalMs(p));

        p.set(Plan.ROW_SETTLE, 0);                                   // 800 ms, the smallest the row offers
        assertEquals(3300, Frames.shortestIntervalMs(p));

        // Whatever the row says, the floor never drops below the shutter's own window plus the margin the
        // next press needs after the release.
        for (int i = 0; i < Plan.SETTLE_MS.length; i++) {
            p.set(Plan.ROW_SETTLE, i);
            assertTrue(Frames.shortestIntervalMs(p) >= Frames.PRESS_MS + Frames.RELEASE_MARGIN_MS,
                    "settle index " + i);
        }
    }

    @Test
    void theCardIsReadLateEnoughThatTheFileHasLanded() {
        // The file is written after the shutter: reading the card the instant the frame is reported
        // found nothing on every frame, and called every one of them unwritten.
        assertTrue(Frames.verdictDelayMs(plan(5000, 10)) >= 2000);
        assertEquals(1500, Frames.verdictDelayMs(plan(2000, 10)), "three quarters of a 2 s interval");
        assertEquals(5000, Frames.verdictDelayMs(plan(60_000, 10)), "capped, so a long run still looks");
    }

    @Test
    void theSettleDelayIsAFloorOnWhenTheCardIsRead() {
        Plan p = plan(5000, 10);
        p.set(Plan.ROW_SETTLE, 3);                                   // 3000 ms
        assertEquals(3750, Frames.verdictDelayMs(p), "three quarters of 5 s is later anyway");
        p.set(Plan.ROW_SETTLE, 4);                                   // 5000 ms
        assertEquals(5000, Frames.verdictDelayMs(p), "the settle delay wins when it is longer");
    }

    @Test
    void aGainOfFilesAccountsForWholeFramesOnly() {
        Plan single = plan(2000, 10);
        assertEquals(0, Frames.framesAccounted(0, single));
        assertEquals(0, Frames.framesAccounted(-3, single), "a card swapped under the run can go back");
        assertEquals(1, Frames.framesAccounted(1, single));
        assertEquals(3, Frames.framesAccounted(3, single), "one file per frame, so three files is three");

        single.set(Plan.ROW_DRIVE, Plan.DRIVE_BRACKET3);
        assertEquals(0, Frames.framesAccounted(2, single), "two of three bracketed files is not a frame");
        assertEquals(2, Frames.framesAccounted(6, single));
    }

    @Test
    void arrivedNeverGoesBackwards() {
        assertEquals(4, Frames.arrived(10, 14));
        assertEquals(0, Frames.arrived(14, 10));
        assertEquals(0, Frames.arrived(Frames.UNKNOWN, 14));
        assertEquals(0, Frames.arrived(10, Frames.UNKNOWN));
    }

    @Test
    void roomForIsAFloorAndRefusesToGuess() {
        assertEquals(100, Frames.roomFor(1000, 10));
        assertEquals(Frames.UNKNOWN, Frames.roomFor(-1, 10), "no answer from the card is not zero room");
        assertEquals(Frames.UNKNOWN, Frames.roomFor(1000, 0));
    }

    @Test
    void aRunIsNotStartedOnACardThatCannotHoldIt() {
        assertTrue(Frames.enoughRoom(50, 300));
        assertTrue(Frames.enoughRoom(5, 5), "a five-frame plan needs five frames of room");
        assertFalse(Frames.enoughRoom(5, 300));
        assertTrue(Frames.enoughRoom(Frames.UNKNOWN, 300), "a card that will not say is not a refusal");
        assertFalse(Frames.enoughRoom(3, Plan.FRAMES_UNLIMITED), "an endless run needs a real floor");
    }

    @Test
    void thePanelIsDarkForTheWaitAndLitForTheFrame() {
        // 5s, this body's default and the interval every measurement in docs/PLATFORM-FACTS.md was taken at.
        // The panel comes back a quarter of the interval before each frame -- 1250ms, which covers the switch,
        // the surface re-attach and the preview the body needs before it will take a shutter -- and goes dark a
        // second after the frame, once the counter has been redrawn. So it is lit for 2.25s of every 5 and dark
        // for the other 2.75s.
        Plan plan = plan(5000, 10);
        assertEquals(700, Frames.panelLitMs(plan), "the lit stretch is the floor, not a quarter of the plan");
        assertEquals(1000, Frames.panelOffDelayMs(plan));
        assertTrue(Frames.panelStaysOff(plan));

        // A long interval is where the feature pays, and it is now nearly all dark: on this body's 10s interval
        // the panel is lit for 0.7s of every 10 -- measured on the camera, the old quarter-interval rule left it
        // lit for 2.5s before every frame, which is what the user saw and objected to.
        Plan slow = plan(10_000, 10);
        assertEquals(700, Frames.panelLitMs(slow));
        assertEquals(8300, slow.intervalMs() - Frames.panelOffDelayMs(slow) - Frames.panelLitMs(slow),
                "the dark stretch of a ten-second interval");
    }

    @Test
    void theShortestIntervalKeepsThePanelLitRatherThanFlashingIt() {
        // Two switches and a lit stretch have to fit between two frames, and what is left has to be a dark
        // window and not a flash: an interval that can only manage 300ms of darkness is a screen being blinked,
        // which draws attention to the camera rather than hiding it.
        assertFalse(Frames.panelStaysOff(plan(2000, 10)), "300ms of darkness is a flash, not a feature");
        assertEquals(Schedule.NONE, Frames.panelOffDelayMs(plan(2000, 10)), "nothing to switch off");
        // With the lit stretch pinned to 700ms, the dark window no longer grows with the interval -- what grows
        // is the wait, and the wait is exactly what should be dark. So what decides is whether the two windows
        // fit at all: 1000ms after the frame plus 700ms before the next one is 1700ms, and anything past that
        // gets the full 500ms of darkness.
        // Below 700ms of interval the lit stretch shrinks with the plan, so the dark window grows again; the
        // rule turns over at 2133ms, where the window is exactly the anti-flash floor of 600ms (twice the
        // 300ms the switch itself costs). Computed, not estimated: the first two versions of this test were
        // written from the arithmetic of an older rule and both were wrong.
        assertFalse(Frames.panelStaysOff(plan(2132, 10)), "less than the floor of darkness is a flash");
        assertTrue(Frames.panelStaysOff(plan(2133, 10)), "exactly the floor is still worth switching");
        // Past 2800ms the lit stretch is pinned at the floor, so the dark window is just the wait: on this
        // body's 10s interval the panel is lit for 0.7s and dark for 8.3s of every 10.
        assertEquals(8300, Frames.panelDarkMs(plan(10_000, 10)));
        assertEquals(0, Frames.panelDarkMs(plan(2132, 10)), "a plan that cannot darken gets no window");
        // The wake still has an answer everywhere: a plan with no dark stretch is a plan whose panel is simply
        // on, and the wake is what guarantees that.
        // At 2s the lit stretch is the quarter (500ms), not the 700ms floor, so the wake is at due-500.
        assertEquals(1500, Frames.panelWakeAtMs(plan(2000, 10), 0, 2000));
        // This body's own floor -- the shortest interval it will shoot at all -- has room to spare.
        assertTrue(Frames.panelStaysOff(plan(3700, 10)));
    }

    @Test
    void thePanelComesBackBeforeTheFrameAndNotAfterIt() {
        // The wake is an instant, not a wait: a frame answered 2 seconds late must not push its own wake-up
        // late with it, because the failure this exists to prevent is a panel still dark when the shutter is
        // asked for. now=0, frame due at 5000, lit stretch 1250 -> on at 3750.
        assertEquals(4300, Frames.panelWakeAtMs(plan(5000, 10), 0, 5000),
                "5000 - 700: the panel is lit for the floor, not for a quarter of the interval");
        assertEquals(9300, Frames.panelWakeAtMs(plan(5000, 10), 5000, 10_000));

        // Inside the lit window already: wake where we stand rather than in the past.
        assertEquals(4000, Frames.panelWakeAtMs(plan(5000, 10), 4000, 4600));
        // No next frame: no deadline, and the caller wakes the panel at once rather than never.
        assertEquals(Schedule.NONE, Frames.panelWakeAtMs(plan(5000, 10), 0, Schedule.NONE));
    }
}
