package com.github.qwqfranzfox.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sony.scalar.hardware.avio.DisplayManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whether this body has a call that switches its display output, and how to make it.
 *
 * <p>This is a regression suite with a specific history. The first version of this app's display code looked
 * for {@code switchDisplayOutputTo(int)} and read the device ids as {@code Integer}s, because the method name
 * came from the old app's source and the old app compiled against a stub jar that no longer exists in this
 * repository. The body's class has {@code switchDisplayOutputTo(java.lang.String)} and {@code String}
 * constants, so every lookup missed, and the app told the user 本机身关不了屏 — on a body that has the call.
 *
 * <p>The stand-in in {@code test/com/sony/scalar/hardware/avio/} has the shape the stub's {@code javap} shows,
 * so these tests would have caught it: they assert against the real lookup path rather than against a
 * description of it. Nothing here proves the <em>body</em> behaves — only the camera can say that — but it
 * proves the app asks the right question.
 */
class DisplayApiTest {

    @BeforeEach
    void reset() {
        DisplayManager.reset();
    }

    @Test
    void aBodyWithTheStringCallIsSupported() {
        DisplayApi api = new DisplayApi();
        assertTrue(api.supported(), "the call is there, so this must not report unsupported: " + api.problem());
        assertEquals("", api.problem());
    }

    @Test
    void switchingOffAndOnUsesTheRealDeviceNames() {
        DisplayApi api = new DisplayApi();
        assertTrue(api.off(), api.problem());
        assertTrue(api.on(), api.problem());
        assertEquals(java.util.Arrays.asList("DEVICE_ID_NONE", "DEVICE_ID_PANEL"), DisplayManager.CALLS,
                "off is NONE and on is PANEL, spelled the way the body spells them");
        assertEquals("switchDisplayOutputTo(DEVICE_ID_PANEL)", api.working());
    }

    @Test
    void theGraphicsLayerIsTakenOffThePanelTooAndPutBackFirst() {
        // Measured on the camera: the output switch alone was accepted, reported success, and left the glass
        // lit showing the frame that had just been taken. So the app's own graphics output is controlled as
        // well -- off with the switch, and back on before the panel is handed to DEVICE_ID_PANEL again, so the
        // first thing the user sees is this app's screen rather than whatever the body had left there.
        Screen screen = new Screen();
        assertTrue(screen.off(), screen.problem());
        assertTrue(screen.on(), screen.problem());
        assertEquals(java.util.Arrays.asList(Boolean.FALSE, Boolean.TRUE), DisplayManager.GRAPHICS,
                "graphics off on the way out, on before the panel comes back");
    }

    @Test
    void aSwitchThatWorkedIsReportedWithWhatTheBodySaysIsActive() {
        // `active=` is what separates "the call was refused" from "the call was accepted and the panel is
        // still lit", which look identical on the screen and need different fixes.
        DisplayApi api = new DisplayApi();
        assertTrue(api.off(), api.problem());
        assertEquals("DEVICE_ID_NONE", api.activeDevice());
        assertTrue(api.on(), api.problem());
        assertEquals("DEVICE_ID_PANEL", api.activeDevice());
        assertEquals("DEVICE_ID_PANEL", api.activeDevices());
    }

    @Test
    void theDisplayObjectIsMadeOnceAndNotPerFrame() {
        // A run switches the output twice per frame, and upstream builds one DisplayManager for the life of the
        // app. Making a new one every two seconds for four hours is a camera resource held and dropped
        // thousands of times for no reason.
        DisplayApi api = new DisplayApi();
        api.off();
        api.on();
        api.off();
        api.on();
        assertEquals(1, DisplayManager.constructions, "one display object, however many switches");
    }

    @Test
    void aCallThatThrowsIsReportedAndNotClaimed() {
        DisplayManager.failWith = new IllegalStateException("device busy");
        DisplayApi api = new DisplayApi();
        assertTrue(api.supported(), "the method exists; refusing a value is a different answer");
        assertFalse(api.off(), "a call that threw did not switch anything");
        assertTrue(api.problem().contains("device busy"), api.problem());
        assertTrue(DisplayManager.CALLS.isEmpty(), "nothing was switched");
    }

    @Test
    void aCallThatSaysNoIsNotTakenForAYes() {
        // The real call is void, so this covers the other shape a body could have: a boolean that refuses the
        // value must not be recorded as having switched, which is the whole reason the return value is read.
        DisplayManager.refuse = Boolean.TRUE;
        DisplayApi api = new DisplayApi();
        assertFalse(api.off(), "false is not a switch");
        assertTrue(api.problem().contains("DEVICE_ID_NONE"), api.problem());
    }

    @Test
    void aClassWithoutTheCallSaysWhichShapesWereLookedFor() {
        // The panel shows this string when the row has to admit the feature is unavailable, so it has to name
        // what was tried rather than being a bare "no".
        DisplayApi api = new DisplayApi("java.lang.String");
        assertFalse(api.supported());
        assertTrue(api.problem().contains("switchDisplayOutputTo"), api.problem());
        assertTrue(api.problem().contains("String"), api.problem());
    }

    @Test
    void theReportNamesTheSignatureThatWasMissed() {
        // The report is what a camera can be asked that a guess cannot: this app read the wrong signature for a
        // whole round because the only description of the API was an old app's source. With this on the card,
        // the next question of this kind starts from the body's own answer.
        String report = new DisplayApi().apiReport();
        assertTrue(report.contains("switchDisplayOutputTo(String) -> boolean"), report);
        assertTrue(report.contains("DEVICE_ID_NONE=DEVICE_ID_NONE"), report);
        assertTrue(report.contains("DEVICE_ID_PANEL=DEVICE_ID_PANEL"), report);
    }

    @Test
    void theScreenKeepsItsOwnAnswerStraight() {
        // Screen is the state; DisplayApi is the reflection. A switch that failed must not leave the app
        // believing the panel is dark, because the wake path is what guarantees the panel comes back.
        Screen screen = new Screen();
        assertFalse(screen.isOff());
        assertTrue(screen.off());
        assertTrue(screen.isOff());
        assertTrue(screen.on());
        assertFalse(screen.isOff());

        DisplayManager.failWith = new IllegalStateException("no");
        assertFalse(screen.off(), "a refused switch leaves the panel lit and the app knowing it");
        assertFalse(screen.isOff());
    }
}
