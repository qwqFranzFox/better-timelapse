package com.voxivoid.bettertimelapse;

import java.util.Locale;

/**
 * The lines of the session log, as text and nothing else.
 *
 * <p>The format is the one docs/PLAN.md §5.2 promised: one appended line per event, {@code key=value},
 * readable by a person and by a script, with the frame's own moment measured from the start of the run on
 * the monotonic clock. What is new here are the facts this app now knows that the plan could only hope for:
 * the exposure the body is set to, whether it takes a dark frame afterwards, how late each press was, which
 * window it was given, whether the card answered for it, and how long the card took to answer.
 *
 * <p>The screen is for the person standing at the camera; this is for whoever reads the run afterwards —
 * including the case the plan calls M7 and nobody has evidence for: a shoot that dies at hour three. A line
 * per frame costs a few hundred kilobytes a night and is the difference between "it stopped somewhere" and
 * "it stopped at frame 2141, eleven milliseconds late, with the card answering in 900 ms".
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}, so the
 * format is checked off the camera like every other decision in this project.
 */
public final class LogLines {

    private LogLines() {}

    /** The version of this format, so a later reader can tell what it is looking at. */
    public static final int SCHEMA = 1;

    public static String header() {
        return "# better-timelapse session";
    }

    public static String schema() {
        return "schema=" + SCHEMA;
    }

    /** What the body is and how long its frames are, before anything is shot. */
    public static String body(String model, String platform, long exposureMs, boolean longExposureNr,
            long occupancyMs, long shortestIntervalMs) {
        StringBuilder b = new StringBuilder("body");
        b.append(" model=").append(text(model, "?"));
        b.append(" platform=").append(text(platform, "?"));
        b.append(" exposure=").append(ms(exposureMs));
        b.append(" nr=").append(longExposureNr ? "on" : "off");
        b.append(" occupancy=").append(ms(occupancyMs));
        b.append(" shortest=").append(ms(shortestIntervalMs));
        return b.toString();
    }

    /** The plan this run is following, as the panel shows it. */
    public static String plan(Plan plan) {
        return "plan interval=" + ms(plan.intervalMs())
                + " frames=" + (plan.frames() == Plan.FRAMES_UNLIMITED ? "unlimited"
                        : String.valueOf(plan.frames()))
                + " delay=" + ms(plan.delayMs())
                + " settle=" + ms(plan.settleMs())
                + " drive=" + Plan.driveWire(plan.driveMode())
                + " silent=" + onOff(plan.silentShutter())
                + " ael=" + onOff(plan.autoExposureLock())
                + " paneloff=" + onOff(plan.panelOff());
    }

    /** Where the run is writing and what the camera looked like when it started. */
    public static String start(String root, int cardCount, long freeBytes, int batteryPercent) {
        return "start dir=" + text(root, "?")
                + " count=" + count(cardCount)
                + " free=" + bytes(freeBytes)
                + " bat=" + percent(batteryPercent);
    }

    /**
     * One frame, at the moment the shutter was pressed.
     *
     * @param lateMs how far past its own moment the frame was started: the number the timetable is judged by.
     * @param windowMs how long the shutter was held; {@code >= exposure} when the exposure was readable.
     * @param callbackMs the body's own shutter callback, or {@link Frames#UNKNOWN} when none came.
     */
    public static String shot(int press, int moment, long atMs, long lateMs, long windowMs, long callbackMs) {
        return "shot press=" + press + " moment=" + moment
                + " at=" + rel(atMs)
                + " late=" + ms(lateMs)
                + " window=" + ms(windowMs)
                + " cb=" + (callbackMs == Frames.UNKNOWN ? "-" : "+" + ms(callbackMs));
    }

    /** What the card said about a frame, once it had been read. */
    public static String verdict(int press, int cardCount, int gained, boolean confirmed, long walkMs) {
        return "verdict press=" + press
                + " card=" + count(cardCount)
                + " gain=" + gained
                + " result=" + (confirmed ? "ok" : "unverified")
                + " walk=" + ms(walkMs);
    }

    /** A moment that went by with no frame in it, because the camera was not free in time. */
    public static String skip(int moment, long atMs, long lateMs, long intervalMs) {
        return "skip moment=" + moment + " at=" + rel(atMs) + " late=" + ms(lateMs)
                + " interval=" + ms(intervalMs);
    }

    /** A press the camera refused, and what that proved about how long its frames take. */
    public static String refused(int press, long atMs, int attempt, long busyAtLeastMs, String error) {
        return "refused press=" + press + " at=" + rel(atMs) + " attempt=" + attempt
                + " busy>=" + ms(busyAtLeastMs)
                + " err=" + text(error, "?");
    }

    /** A run that ended because retries ran out. */
    public static String fault(String cause, int landed, int skipped, String detail) {
        return "fault cause=" + text(cause, "?") + " landed=" + landed + " skipped=" + skipped
                + " detail=" + text(detail, "-");
    }

    /**
     * The end of a run, however it ended.
     *
     * <p>{@code pending}, not {@code unverified}: a run can end while the card is still being asked about its
     * last frame, and the first real log said {@code END … verified=0 unverified=1} one line above
     * {@code verdict … result=ok}. The summary that follows the last verdict is where the final counts
     * belong.
     */
    public static String end(String outcome, int landed, int accounted, int skipped, int unverified) {
        return "END outcome=" + text(outcome, "?") + " landed=" + landed + " verified=" + accounted
                + " skipped=" + skipped + " pending=" + unverified;
    }

    /** The run's own account of itself, written once the last frame has been answered for. */
    public static String summary(int landed, int accounted, int skipped, int unverified, long elapsedMs) {
        return summary("done", landed, accounted, skipped, unverified, elapsedMs);
    }

    /**
     * The same line when the run stopped for a reason of its own.
     *
     * <p>{@code outcome} is why there is no more: {@code done} (the last frame was taken), {@code paused} (the
     * user stopped it and may start it again) or {@code fault}.
     *
     * <p>{@code paused} exists because a paused run used to end its block in the log with nothing but
     * {@code pause at=+13.176s reason=trash}: a four-hour run stopped on the camera and carried home read
     * exactly like a run that had crashed, with no count of what it got. The count is provisional and says so
     * by the word itself — {@code resume} continues it — while a reader of the finished film wants the number
     * and not the silence.
     */
    public static String summary(String outcome, int landed, int accounted, int skipped, int unverified,
            long elapsedMs) {
        return summary(outcome, landed, accounted, skipped, unverified, elapsedMs, Frames.UNKNOWN);
    }

    /**
     * The same line with the camera's own tally next to it.
     *
     * <p>{@code missed} and {@code card} answer two different questions and only one of them is about the
     * photographs. {@code missed} is this app's ledger: frames the card never answered for, one walk at a
     * time. {@code card} is the card itself: how many files it gained between the run's first count and the
     * last one. A 20-frame run came home saying {@code landed=20 verified=19 missed=1} while
     * {@code 647 → 667} says the card gained exactly 20 files for 20 frames and 20 of 20 shutter callbacks
     * arrived — nothing was lost, a walk simply reported the same count twice and one frame's credit went
     * missing. Walk-to-walk deltas can be mis-attributed that way; the cumulative count cannot.
     *
     * <p>So both go in, and neither is dressed up: with {@code card} present a reader can see at once whether
     * the ledger's doubt is real. {@code missed=1 card=+20} is a bookkeeping lag; {@code missed=11 card=+6} is
     * eleven photographs that do not exist. Only the second is a reason to go back to the camera.
     *
     * @param cardGain files the card gained over the whole run, or {@link Frames#UNKNOWN} when it could not be
     *     counted from before the first frame to after the last.
     */
    public static String summary(String outcome, int landed, int accounted, int skipped, int unverified,
            long elapsedMs, int cardGain) {
        StringBuilder b = new StringBuilder("summary outcome=").append(text(outcome, "done"));
        b.append(" landed=").append(landed);
        b.append(" verified=").append(accounted);
        b.append(" missed=").append(unverified);
        b.append(" card=").append(cardGain == Frames.UNKNOWN ? "?" : signed(cardGain));
        b.append(" skipped=").append(skipped);
        b.append(" elapsed=").append(rel(elapsedMs).substring(1));
        return b.toString();
    }

    /** A count that is allowed to say which way it moved: {@code +20}, {@code -3}, {@code 0}. */
    public static String signed(int value) {
        return (value > 0 ? "+" : "") + value;
    }

    /** The moment the timetable was armed: every {@code at=} after it is measured from here. */
    public static String runStart(long atMs) {
        return "run start at=" + rel(atMs);
    }

    /**
     * What the app said before the first frame, and what the user did about it.
     *
     * <p>{@code action} is one of {@code ok} (the plan fits), {@code refused} (the start was held back so the
     * user could see what would happen) or {@code override} (they pressed again and went ahead anyway). The
     * three are kept apart because the difference matters afterwards: a run full of holes that was started by
     * accident is a defect, and the same run started deliberately is a decision.
     */
    public static String preflight(long intervalMs, long shortestMs, long exposureMs, boolean nr,
            String action, String said) {
        return "preflight interval=" + ms(intervalMs) + " shortest=" + ms(shortestMs)
                + " exposure=" + ms(exposureMs) + " nr=" + (nr ? "on" : "off")
                + " action=" + text(action, "?") + " said=" + text(said, "ok");
    }

    /** A row the user turned, and whether that moved the timetable (docs/DECISIONS.md A22). */
    public static String planEdit(int row, String value, boolean rebased) {
        return "plan-edit row=" + rowName(row) + " value=" + text(value, "?")
                + " rebased=" + (rebased ? "yes" : "no");
    }

    /**
     * A row's name in the log's own language (docs/DECISIONS.md A13: the screen is Chinese, the file is not).
     *
     * <p>Which row was turned matters more than it looks: turning the interval mid-run rebases the timetable
     * from that moment (A22), and a log that does not say so leaves a frame firing 2.5 s after the run started
     * with nothing to explain it — which is exactly the first session log with a fixed formatter.
     */
    public static String rowName(int row) {
        switch (row) {
            case Plan.ROW_INTERVAL: return "interval";
            case Plan.ROW_FRAMES: return "frames";
            case Plan.ROW_DELAY: return "delay";
            case Plan.ROW_SETTLE: return "settle";
            case Plan.ROW_SILENT: return "silent";
            case Plan.ROW_DRIVE: return "drive";
            case Plan.ROW_AEL: return "ael";
            case Plan.ROW_PANEL_OFF: return "paneloff";
            default: return "row" + row;
        }
    }

    /** One reading of the whole parameter set, and what it cost: the body reports 298 of them. */
    public static String parameters(long tookMs) {
        return "parameters read=" + ms(tookMs);
    }

    /** Why a run stopped, in the words a log is read in. */
    public static String faultName(int fault) {
        switch (fault) {
            case Engine.FAULT_CAMERA: return "camera";
            case Engine.FAULT_CARD: return "card";
            case Engine.FAULT_WRITE: return "write";
            case Engine.FAULT_ABORTED: return "aborted";
            case Engine.FAULT_SHUTTER: return "shutter";
            default: return "none";
        }
    }

    public static String pause(long atMs, String reason, int batteryPercent) {
        return "pause at=" + rel(atMs) + " reason=" + text(reason, "?") + " bat=" + percent(batteryPercent);
    }

    public static String resume(long atMs) {
        return "resume at=" + rel(atMs);
    }

    /** The frames the card never answered for, said in the file the way the panel says it on screen. */
    public static String shortfall(int taken, int accounted) {
        return "shortfall taken=" + taken + " accounted=" + accounted;
    }

    /** Something the app could not do, recorded so that it is not only on a screen nobody photographed. */
    public static String problem(String what, String detail) {
        return "problem what=" + text(what, "?") + " detail=" + text(detail, "-");
    }

    // ---------------------------------------------------------------- formatting

    /** A moment in the run, as {@code +12.345s}: seconds with milliseconds, which is the scale in question. */
    public static String rel(long ms) {
        if (ms == Frames.UNKNOWN) return "?";
        long whole = ms / 1000;
        long millis = Math.abs(ms % 1000);
        return String.format(Locale.US, "%s%d.%03ds", ms < 0 ? "-" : "+", Math.abs(whole), millis);
    }

    /** A duration in milliseconds, or {@code ?} when the platform would not say. */
    public static String ms(long ms) {
        return ms == Frames.UNKNOWN ? "?" : ms + "ms";
    }

    public static String count(int count) {
        return count == Frames.UNKNOWN ? "?" : String.valueOf(count);
    }

    public static String percent(int percent) {
        return percent < 0 ? "?" : percent + "%";
    }

    /** Free space, in whole megabytes: the unit a card's room is actually talked about in. */
    public static String bytes(long bytes) {
        if (bytes < 0) return "?";
        return (bytes / (1024L * 1024L)) + "MB";
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    /** A value that must not be able to break the line it is on: a log is read by scripts too. */
    public static String text(String value, String fallback) {
        if (value == null || value.length() == 0) return fallback;
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
