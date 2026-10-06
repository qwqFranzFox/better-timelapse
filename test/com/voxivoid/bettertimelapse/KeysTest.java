package com.voxivoid.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The keys: which one is which, which are swallowed, and the press-or-hold machine behind TRASH, MENU
 * and the rehearsal switch.
 *
 * <p>A time-lapse app on a camera with five usable keys lives or dies on this: the old app ended a run
 * and lost it when TRASH or MENU was touched at all (docs/PAIN-POINTS.md U-01), and there was no way to
 * tell a press from a hold because nothing modelled the difference.
 */
class KeysTest {

    @Test
    void trashAndMenuAreFoundOnBothGenerations() {
        assertTrue(Keys.isTrash(Keys.K_DELETE));
        assertTrue(Keys.isTrash(Keys.K_SK2), "the NEX bodies print the second soft key where the others print TRASH");
        assertFalse(Keys.isTrash(Keys.K_SK1));

        assertTrue(Keys.isMenu(Keys.K_MENU));
        assertTrue(Keys.isMenu(Keys.K_SK1));
        assertFalse(Keys.isMenu(Keys.K_SK2));
    }

    @Test
    void theKeysTheAppDoesNotBindAreStillSwallowed() {
        // Each of these does something on the camera that would interrupt a shoot: AEL locks exposure,
        // DISP changes the display, PLAY leaves the app's screen entirely.
        assertTrue(Keys.swallowed(Keys.K_AEL));
        assertTrue(Keys.swallowed(Keys.K_C1));
        assertTrue(Keys.swallowed(Keys.K_DISP));
        assertTrue(Keys.swallowed(Keys.K_PLAY));

        assertFalse(Keys.swallowed(Keys.K_ENTER), "the centre button is the app's own");
        assertFalse(Keys.swallowed(Keys.K_WHEEL_CW));
    }

    @Test
    void aBodyThatLosesTheReleaseDoesNotGetToStopTheRun() {
        // The probe answered "the key is already up", so the press was short however long the timer ran.
        assertFalse(Keys.trashHoldActs(Boolean.FALSE));
        // Still down: the hold was meant.
        assertTrue(Keys.trashHoldActs(Boolean.TRUE));
        // No answer from the body: treat it as a hold, which at worst asks a question.
        assertTrue(Keys.trashHoldActs(null));
    }

    @Test
    void aPressThatIsReleasedEarlyIsShort() {
        Keys.Hold h = new Keys.Hold();

        assertEquals(Keys.Hold.ARM, h.down(0));
        assertEquals(Keys.Hold.SHORT, h.up());
        assertEquals(Keys.Hold.NONE, h.up(), "one release is one action");
    }

    @Test
    void aHoldFiresOnceAndSwallowsTheRelease() {
        Keys.Hold h = new Keys.Hold();

        assertEquals(Keys.Hold.ARM, h.down(0));
        assertEquals(Keys.Hold.HOLD, h.fire(), "the timer the shell posted says the hold happened");
        assertEquals(Keys.Hold.NONE, h.up(), "and the release is not also a short press");
        assertEquals(Keys.Hold.NONE, h.fire(), "the hold fires once");
    }

    @Test
    void keyRepeatDoesNotStartASecondPress() {
        Keys.Hold h = new Keys.Hold();

        assertEquals(Keys.Hold.ARM, h.down(0));
        assertEquals(Keys.Hold.IGNORE, h.down(1), "the firmware's repeat is not a new press");
        assertEquals(Keys.Hold.SHORT, h.up());
    }

    @Test
    void aResetReleaseAfterAPauseDoesNotLeaveAKeyStuckDown() {
        Keys.Hold h = new Keys.Hold();
        assertEquals(Keys.Hold.ARM, h.down(0));

        h.reset();                                       // what onPause does to every key it tracks

        assertEquals(Keys.Hold.NONE, h.up(), "the release of a key nobody is holding");
        assertEquals(Keys.Hold.ARM, h.down(0), "and the key works again next time");
    }

    @Test
    void everyNamedScanCodeIsDistinct() {
        int[] codes = { Keys.K_UP, Keys.K_DOWN, Keys.K_LEFT, Keys.K_RIGHT, Keys.K_ENTER, Keys.K_MENU,
                Keys.K_DELETE, Keys.K_SK1, Keys.K_SK2, Keys.K_WHEEL_CW, Keys.K_WHEEL_CCW,
                Keys.K_DIAL_CW, Keys.K_DIAL_CCW, Keys.K_AEL, Keys.K_C1, Keys.K_DISP, Keys.K_PLAY,
                Keys.K_FN, Keys.K_S1, Keys.K_S2, Keys.K_ZOOM_T, Keys.K_ZOOM_W, Keys.K_MOVIE };

        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertFalse(codes[i] == codes[j],
                        "two names share scan code " + codes[i] + ": a key would do two things at once");
            }
        }
    }
}
