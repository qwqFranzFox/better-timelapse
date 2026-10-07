package com.voxivoid.bettertimelapse;

/**
 * The camera's own display output, as this app uses it: panel off, panel on, and what happened.
 *
 * <p>This exists because a long shoot has two problems with a lit panel: a single fixed image on an LCD for
 * four hours is what burn-in is made of, and at night the screen is a light source pointed at the subject,
 * which is the one thing a time-lapse camera may not be. The old app had the setting — its {@code DOFF},
 * "Turn the display off between each shot" — and this project kept the row and marked it 尚未实现 until now,
 * because a row that claims something the app does not do is the same lie as a setting that is sent and never
 * applied.
 *
 * <p>It does <em>not</em> exist to save power, and the row must not pretend otherwise: the upstream author
 * removed this feature once and put it back with the note that it "didn't have any impact on the battery life"
 * (docs/PLATFORM-FACTS.md §4). The reasons that survive are the panel and the darkness.
 *
 * <p>Everything about <em>whether the body can do it and how</em> lives in {@link DisplayApi}, which is pure
 * Java and tested against a stand-in for the camera's class. This class is the part that holds state across a
 * run and nothing else — and it holds the lesson of the first version: it read the device ids as
 * {@code Integer}s and looked for {@code switchDisplayOutputTo(int)}, so it reported 本机身关不了屏 on a body
 * that has the call. The name had come from the old app's source, which compiled against a stub jar that no
 * longer exists here — and a stub is not a body.
 */
public final class Screen {

    private final DisplayApi api = new DisplayApi();

    private boolean off;

    /** Whether the panel is off right now, as far as this app knows. */
    public boolean isOff() {
        return off;
    }

    /** Why the panel cannot be switched or would not switch, or an empty string. */
    public String problem() {
        return api.problem();
    }

    /** The call that worked, as {@code method(DEVICE)}, or an empty string. */
    public String working() {
        return api.working();
    }

    /** Whether this app's own graphics are being drawn on the panel. */
    public boolean graphicsOutput(boolean visible) {
        return api.graphicsOutput(visible);
    }

    /** What the body says its active output is right now. */
    public String activeDevice() {
        return api.activeDevice();
    }

    /**
     * What the body said its active output was immediately before the last switch.
     *
     * <p>Read at the moment it is a fact rather than a race: asked after a switch, this body has answered with
     * nothing often enough to show up in the session log as an {@code on} line with no {@code active=} on it.
     */
    public String wasActiveDevice() {
        return wasActive;
    }

    private String wasActive = "";

    /** Every device the body reports as attached, for the case where the panel is not among them. */
    public String activeDevices() {
        return api.activeDevices();
    }

    /** Whether this body can switch its display output at all. Asked without switching anything. */
    public boolean supported() {
        return api.supported();
    }

    /**
     * Switch the panel off, if it is not already off.
     *
     * <p>Never between a frame's due moment and its shutter: the caller owes this class the quiet stretch of the
     * interval, and {@link Frames#panelWakeAtMs} is where that stretch is computed.
     *
     * @return whether the panel is off, which is the only answer worth acting on.
     */
    public boolean off() {
        if (off) return true;
        wasActive = api.activeDevice();
        // Both, in this order, because either alone was measured not to be enough on this body: the output
        // switch says which device the body should drive, and the graphics call says whether this app's own
        // layer is drawn there. The graphics call is allowed to fail — a body without it still gets the switch
        // — but a switch that fails is a failure, because then nothing has happened at all.
        boolean switched = api.off();
        boolean graphics = api.graphicsOutput(false);
        if (!switched && !graphics) return false;
        off = true;
        return true;
    }

    /**
     * Switch the panel back on.
     *
     * <p>Always attempted, even when this class believes the panel is already lit: a run that ends while the
     * bookkeeping is out of step must not leave the camera's screen black with the app in the foreground and no
     * key that explains it. Failing to come back is the one failure here the user cannot work around.
     */
    public boolean on() {
        wasActive = api.activeDevice();
        // The graphics come back first: the app's layer keeps rendering either way, and showing a frame of the
        // panel is a better first thing to see than whatever the body had on the glass.
        api.graphicsOutput(true);
        if (!api.on()) return false;
        off = false;
        return true;
    }

    /** Let the camera's display object go, on the way out of the app. */
    public void release() {
        api.release();
    }

    /** The body's display API as text, for the card's device dump. */
    public String apiReport() {
        return api.apiReport();
    }
}
