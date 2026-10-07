package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The naming rule this card appears to have, which is what a file written there has to obey.
 *
 * <p>Six writes came back ENOENT for long names in directories that exist and are writable, and every name
 * the card already holds is eight-dot-three. The names this app writes are checked against that here, so a
 * later edit that lengthens one for readability fails a test instead of failing on a camera.
 */
class CardNamesTest {

    @Test
    void theNamesTheCardAlreadyHoldsFit() {
        assertTrue(CardNames.fitsEightDotThree("TIMELPS"));
        assertTrue(CardNames.fitsEightDotThree("LOG.TXT"));
        assertTrue(CardNames.fitsEightDotThree("APPSTORE.log"));
        assertTrue(CardNames.fitsEightDotThree("TWEAKLOG.txt"));
        assertTrue(CardNames.fitsEightDotThree("PMCADEMO"));
        assertTrue(CardNames.fitsEightDotThree("DSC00507.ARW"));
        assertTrue(CardNames.fitsEightDotThree("10561007"));
        assertTrue(CardNames.fitsEightDotThree("DCIM"));
    }

    @Test
    void theNamesThisAppWasUsingDoNot() {
        assertFalse(CardNames.fitsEightDotThree("bettertimelapse-device.txt"));
        assertFalse(CardNames.fitsEightDotThree("bettertimelapse"));
    }

    @Test
    void theLimitsAreTheLimitsAndNotSomethingNearerThem() {
        assertTrue(CardNames.fitsEightDotThree("ABCDEFGH.TXT"), "eight and three are allowed");
        assertTrue(CardNames.fitsEightDotThree("ABCDEFGH"), "no extension at all is allowed");
        assertTrue(CardNames.fitsEightDotThree("A."), "an empty extension is allowed");

        assertFalse(CardNames.fitsEightDotThree("ABCDEFGHI"), "nine is not eight");
        assertFalse(CardNames.fitsEightDotThree("A.TXTX"), "four is not three");
        assertFalse(CardNames.fitsEightDotThree("A.B.TXT"), "one dot, not two");
        assertFalse(CardNames.fitsEightDotThree(".TXT"), "a name needs a base");
        assertFalse(CardNames.fitsEightDotThree(""));
        assertFalse(CardNames.fitsEightDotThree(null));
    }
}
