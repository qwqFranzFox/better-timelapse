package com.github.qwqfranzfox.bettertimelapse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The order a borrowed camera is given back in.
 *
 * <p>This is a regression suite for a bug that shipped twice and that no test could see, because the order lived
 * in shell code: the teardown cleared the camera handle's field and then called {@code stopPreview()}, which
 * begins with {@code if (camera == null) return}. The step ran, did nothing, and read as if it had worked. The
 * session log said {@code preview=running} on every clean exit, which was the teardown reporting itself
 * honestly.
 *
 * <p>These are assertions about the order rather than about the code, which is the point: the code cannot be
 * run here, so what it must respect is written down where it can be.
 */
class TeardownTest {

    @Test
    void thePreviewStopsBeforeTheHandleIsGivenUp() {
        // The failing case, stated positively: a step that needs the camera must come before the camera is
        // released and before the app forgets it ever had one.
        assertTrue(Teardown.before(Teardown.PREVIEW, Teardown.HANDLE), "preview before handle");
        assertTrue(Teardown.before(Teardown.PREVIEW, Teardown.FORGET), "preview before forget");
        assertTrue(Teardown.before(Teardown.CAPTURE, Teardown.HANDLE), "capture before handle");
        assertTrue(Teardown.before(Teardown.CAPTURE, Teardown.FORGET), "capture before forget");
        assertTrue(Teardown.before(Teardown.HANDLE, Teardown.FORGET), "handle before forget");
    }

    @Test
    void nothingUsesTheCameraAfterItIsForgotten() {
        // The general shape of both failures: every step that touches the camera has to be behind FORGET in the
        // sequence, and nothing may be behind it at all.
        for (String step : Teardown.steps()) {
            if (step.equals(Teardown.FORGET)) continue;
            assertTrue(Teardown.before(step, Teardown.FORGET), step + " must come before forget");
        }
        assertEquals(Teardown.steps().length - 1, Teardown.indexOf(Teardown.FORGET),
                "forget is the last step, by definition");
    }

    @Test
    void theOrderIsTheDocumentedOne() {
        // Spelled out so that a reordering has to be a deliberate edit to this list rather than a side effect
        // of moving a line inside a method nobody can run.
        assertArrayEquals(new String[] { "preview", "capture", "settings", "handle", "forget" },
                Teardown.steps());
        assertEquals(-1, Teardown.indexOf("something-else"));
        assertFalse(Teardown.before("something-else", Teardown.PREVIEW));
        assertFalse(Teardown.before(Teardown.PREVIEW, Teardown.PREVIEW), "a step is not before itself");
    }
}
