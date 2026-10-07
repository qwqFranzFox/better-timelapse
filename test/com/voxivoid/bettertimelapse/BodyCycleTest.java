package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Learning what the body needs from the frames it refuses.
 *
 * <p>This is the answer to a question this platform will not answer: there is no exposure time to read
 * and no busy flag to ask, and the constant the app was built on is only right for short exposures. A
 * refusal is exact evidence — the body was still busy one interval after the last frame started — and a
 * lower bound is the honest shape for it.
 */
class BodyCycleTest {

    @Test
    void nothingIsClaimedBeforeAnythingIsRefused() {
        BodyCycle body = new BodyCycle();

        assertFalse(body.known());
        assertEquals(BodyCycle.UNKNOWN, body.lowerBoundMs());
        assertEquals(Frames.shortestIntervalMs(), body.shortestIntervalMs(),
                "with nothing measured, only the shutter's own floor is known");
    }

    @Test
    void aRefusalOneIntervalAfterAFrameProvesTheIntervalIsTooShort() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(5000);                        // the 5 s interval the plan asked for was not enough

        assertTrue(body.known());
        assertEquals(5000, body.lowerBoundMs());
        assertEquals(5400, body.shortestIntervalMs(), "the proven busy period plus the margin");
        assertEquals(1, body.refusals());
        assertEquals(1, body.samples());
    }

    @Test
    void theLongestBusyPeriodWins() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(3000);
        body.accepted(4000);
        body.refused(12_000);                      // a ten-second exposure, on the next frame

        assertEquals(8000, body.lowerBoundMs(), "measured from the frame the body had just started");

        body.accepted(20_000);
        body.refused(21_000);
        assertEquals(8000, body.lowerBoundMs(), "a shorter busy period does not unlearn the longer one");
    }

    @Test
    void aRefusalWithNoFrameBeforeItMeasuresNothing() {
        BodyCycle body = new BodyCycle();
        body.refused(1000);

        assertEquals(1, body.refusals());
        assertEquals(0, body.samples(), "there is nothing to measure against");
        assertFalse(body.known());
    }

    @Test
    void onlyARefusalAfterAFrameCounts() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(2000);                        // measured
        body.accepted(3000);
        body.refused(5000);                        // measured: 2000 after the frame at 3000
        body.accepted(7000);

        assertEquals(2, body.samples());
        assertEquals(2000, body.lowerBoundMs());
    }

    @Test
    void aThirdOfASecondOfBusyIsNotWorthReporting() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(300);

        // It is a real measurement, and the honest floor it implies is still the shutter's own constant:
        // a body that was busy for 300 ms has not raised the interval anybody should be offered.
        assertEquals(300, body.lowerBoundMs());
        assertEquals(Frames.shortestIntervalMs(), body.shortestIntervalMs());
    }

    @Test
    void reopeningTheCameraForgetsWhatWasLearned() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(9000);
        assertTrue(body.known());

        body.reset();

        assertFalse(body.known(), "a different exposure or card may need something else entirely");
        assertEquals(0, body.refusals());
        assertEquals(0, body.samples());
    }

    @Test
    void theShortestIntervalNeverDropsBelowTheShuttersOwnFloor() {
        BodyCycle body = new BodyCycle();
        body.accepted(0);
        body.refused(100);

        assertTrue(body.shortestIntervalMs() >= Frames.shortestIntervalMs());
    }
}
