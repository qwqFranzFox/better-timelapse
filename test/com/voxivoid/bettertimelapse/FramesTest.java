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
    void theShortestIntervalIsThePressWindowPlusTheMargin() {
        assertEquals(2900, Frames.shortestIntervalMs());
        assertTrue(Frames.shortestIntervalMs() > Frames.PRESS_MS);
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
}
