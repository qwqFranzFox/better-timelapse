package com.voxivoid.bettertimelapse;

/**
 * The shooting state machine, as a pure function: state plus event plus the time gives the next
 * single thing to do.
 *
 * <p>This is the piece the old app never had. Its whole run lived in one 465-line Activity as two
 * Handlers calling each other, sharing three mutable fields and a reassigned {@code AtomicInteger}
 * with the camera's own callback thread (TimeLapse {@code ShootActivity.java:57-116, 319-396};
 * docs/PAIN-POINTS.md D-06, D-11). Nothing about it could be checked without a camera, which is why
 * its interval arithmetic drifted, its bracket counter desynchronised, and its only stop button was
 * "press MENU and lose the run" (U-01, U-18).
 *
 * <p>Here the decisions are arithmetic and ordering. {@link #step} is called whenever something
 * happens — the wheel, the shutter callback, a timer — and it returns exactly one {@link Action}:
 * arm a timer, fire the shutter, or nothing. The shell executes that action against the real camera.
 * The whole of a several-hour run can therefore be simulated in a test in milliseconds, including
 * the cases nobody wants to reproduce at night: the card filling up, a frame landing early, the
 * camera falling behind the interval.
 *
 * <p>Every outcome is reported, never swallowed. A frame that failed becomes {@link FAULT} state with
 * a cause, because a run that silently reports frames it never took is the exact failure the old app
 * shipped (U-06).
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class Engine {

    // ---------------------------------------------------------------- states
    public static final int IDLE = 0, ARMED = 1, WAITING = 2, EXPOSING = 3, PAUSED = 4, DONE = 5, FAULT = 6;

    // ---------------------------------------------------------------- what happened
    public static final int EV_ENTER = 0, EV_TURN = 1, EV_MOVE = 2, EV_ADJUST = 3, EV_TRASH = 4,
            EV_HOLD_TRASH = 5, EV_SHUTTER = 6, EV_TICK = 7, EV_ACK = 8,
            /** The frame was handed to the camera but never appeared on the card. */
            EV_TIMEOUT = 9,
            /** The camera reported a frame and the card count could not corroborate it. */
            EV_UNVERIFIED = 10;

    // ---------------------------------------------------------------- what to do next
    public static final int ACT_NONE = 0, ACT_ARM_TIMER = 1, ACT_FIRE = 2;

    // ---------------------------------------------------------------- why a run stopped
    /**
     * Why a run stopped. {@code FAULT_CARD} is "the card cannot be read" and {@code FAULT_WRITE} is
     * "the card is there and the frames are not arriving" — different problems with different advice,
     * so they are not merged into one "card" failure the user cannot act on.
     */
    public static final int FAULT_NONE = 0, FAULT_CAMERA = 1, FAULT_CARD = 2, FAULT_WRITE = 3,
            FAULT_ABORTED = 4, FAULT_SHUTTER = 5;

    /** The states the panel's seven words map onto; kept in step with {@link Hud}. */
    public static int hudState(int state) {
        return state;
    }

    /**
     * The error budget: a frame that fails is retried once, and the run gives up only after this many
     * consecutive failures.
     *
     * <p>Recipe Lab ends its sample run on the first failure, which is right for a two-minute run and
     * wrong for an eight-hour one: a single slow card write should not throw away a night. Equally,
     * an infinite retry loop would sit there until dawn, so a genuinely broken path has to be
     * abandoned within seconds (docs/PLAN.md §4.2).
     */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** How many frame timestamps the drift measurement keeps: a full night at 1/10 s. */
    private static final int MAX_SHOTS = 20000;

    // ---------------------------------------------------------------- state
    private final Plan plan;
    private final Schedule schedule = new Schedule();
    private final long[] shotTimesMs = new long[MAX_SHOTS];

    private int state = IDLE;
    private int fault = FAULT_NONE;

    /** Frames handed to the camera, and frames seen to land. They differ when a write is lost. */
    private int fired;
    private int landed;

    /** How many times the frame currently due has been attempted, then how many in a row failed. */
    private int attemptsForCurrentFrame;
    private int consecutiveFailures;

    /** The last frame that landed, for the panel's "landed n / planned m". */
    private String lastFile = "";

    /** Why the last frame was judged lost, in the words of whatever reported it. */
    private String lastLoss = "";

    /**
     * Frames the camera reported that the card count could not corroborate.
     *
     * <p>Not a fault, and not hidden either. The camera writing its files somewhere this app cannot
     * read is a different thing from a frame going missing, and the honest answer is "taken, not
     * confirmed" rather than either "lost" or silence.
     */
    private int unverified;

    /**
     * Moments the run went past without taking a frame.
     *
     * <p>A frame whose moment is a whole interval in the past cannot be taken without landing on top of
     * the next one. Firing back to back to "catch up" is exactly what the old app did when the camera
     * fell behind — a 2 s time-lapse that quietly became "as fast as the card allows", with nothing on
     * screen to say so (docs/PAIN-POINTS.md U-18). The moment is dropped and counted instead, which
     * keeps the film's own timeline true: a gap where the camera was not ready, not a shifted sequence.
     */
    private int skipped;

    /** The moment that was skipped, and how late it was when it went by: for the session log. */
    private int lastSkipIndex = -1;
    private long lastSkipLateMs;

    public Engine(Plan plan) {
        this.plan = plan;
    }

    // ---------------------------------------------------------------- reading the state
    public int state() {
        return state;
    }

    public int fault() {
        return fault;
    }

    public boolean running() {
        return state == ARMED || state == WAITING || state == EXPOSING;
    }

    /**
     * Whether a run is on screen, paused or not.
     *
     * <p>Different from {@link #running()} on purpose: TRASH has to be able to resume a paused run, so
     * {@code running()} must stay false while paused. But a paused run still owns the camera and its plan,
     * and a row edited then would show a value the body does not hold — the old app's "sent, never
     * applied" lie (docs/PAIN-POINTS.md U-07) in a new place.
     */
    public boolean midRun() {
        return running() || state == PAUSED;
    }

    /** Frames handed to the camera. Compared against {@link #landed()} this is the honest count. */
    public int fired() {
        return fired;
    }

    public int landed() {
        return landed;
    }

    public int frameIndex() {
        return schedule.frameIndex();
    }

    public Schedule schedule() {
        return schedule;
    }

    public String lastFile() {
        return lastFile;
    }

    public long remainingMs(long nowMs) {
        return schedule.remainingMs(plan, nowMs);
    }

    public long elapsedMs(long nowMs) {
        return schedule.elapsedMs(nowMs);
    }

    /**
     * When the next frame is due, on the same clock as everything else, or {@link Schedule#NONE}.
     *
     * <p>Handed out for the one caller that has to act <em>before</em> a frame rather than at it: the panel
     * comes back on ahead of the moment so the body has a live preview when the shutter is asked for
     * ({@link Frames#panelWakeAtMs}). Absolute, not a wait: a wake-up computed from "how long is left" would
     * drift with every late frame, and its whole job is to be early.
     */
    public long nextDueMs() {
        return schedule.nextDueMs(plan);
    }

    public long meanIntervalMs() {
        return Schedule.meanInterval(shotTimesMs, landed);
    }

    /**
     * The frames' own times, with the number that are real, for the log's retrospective view of the run.
     *
     * <p>The same array {@link #meanIntervalMs()} measures and {@link #driftMs()} compares against the plan, so
     * the summary's film length and the panel's drift cannot disagree: they are two readings of one record. The
     * array is preallocated for a whole night, which is why the count travels with it.
     */
    public long[] shotTimesMs() {
        return shotTimesMs;
    }

    public long driftMs() {
        return Schedule.driftMs(meanIntervalMs(), plan);
    }

    /** The plan being run. The panel edits it only while idle. */
    public Plan plan() {
        return plan;
    }

    // ---------------------------------------------------------------- the one decision
    /**
     * Advance the machine. Returns one action for the shell to carry out.
     *
     * <p>Events: {@code EV_ENTER} is the centre button, {@code EV_TRASH} a short TRASH press,
     * {@code EV_HOLD_TRASH} the confirmed stop, {@code EV_SHUTTER} a shutter callback from the
     * camera, {@code EV_TICK} a timer firing, {@code EV_ACK} acknowledging a finished or faulted run.
     */
    public int step(int event, long nowMs) {
        switch (event) {
            case EV_ENTER:
                return onEnter(nowMs);
            case EV_TRASH:
                return onTrash(nowMs);
            case EV_HOLD_TRASH:
                return stop(FAULT_ABORTED, nowMs);
            case EV_SHUTTER:
                return onShutter(nowMs);
            case EV_TICK:
                return onTick(nowMs);            case EV_ACK:
                return onAck();
            case EV_TIMEOUT:
                return onFrameLost("no file", nowMs);
            case EV_UNVERIFIED:
                return onUnverified(nowMs);
            default:
                return ACT_NONE;   // value edits are the panel's, not the machine's
        }
    }

    private int onEnter(long nowMs) {
        switch (state) {
            case IDLE:
                // Three seconds of warning before the first frame, so whoever pressed the button can
                // get out of the picture. The old app went straight from Start to the first exposure
                // with no countdown at all (docs/PAIN-POINTS.md §4.2).
                schedule.arm(nowMs, plan);
                state = ARMED;
                return ACT_ARM_TIMER;
            case PAUSED:
                return resume(nowMs);
            case DONE:
            case FAULT:
                return onAck();
            default:
                return ACT_NONE;
        }
    }

    private int onTrash(long nowMs) {
        if (running()) {
            schedule.pause(nowMs);
            state = PAUSED;
            return ACT_NONE;
        }
        if (state == PAUSED) return resume(nowMs);
        return ACT_NONE;
    }

    private int resume(long nowMs) {
        schedule.resume(nowMs);
        // Rebase so the frames still to come keep their spacing from now, rather than firing a burst
        // of frames whose moments passed while the run was paused.
        schedule.rebase(nowMs, plan);
        state = WAITING;
        return ACT_ARM_TIMER;
    }

    /**
     * Stop the run. Confirmable by the caller: this is the action the old app did not have at all —
     * pressing MENU or TRASH there ended the app and lost everything with no question asked (U-01).
     */
    public int stop(int cause, long nowMs) {
        if (state == IDLE) return ACT_NONE;
        fault = cause;
        state = cause == FAULT_NONE ? DONE : FAULT;
        schedule.finish();
        return ACT_NONE;
    }

    private int onAck() {
        schedule.clear();
        state = IDLE;
        fault = FAULT_NONE;
        fired = 0;
        landed = 0;
        attemptsForCurrentFrame = 0;
        consecutiveFailures = 0;
        unverified = 0;
        skipped = 0;
        lastFile = "";
        lastLoss = "";
        return ACT_NONE;
    }

    private int onTick(long nowMs) {
        if (state == ARMED) {
            // The arming countdown is over: this is the first frame's due moment.
            state = WAITING;
            return ACT_ARM_TIMER;
        }
        // Anything but a waiting run ignores the clock, and PAUSED is the case that matters: the timer armed
        // before the user pressed TRASH arrives after the state changed, and a frame must never depend on the
        // shell having remembered to cancel it. A session log where a `pause` line is followed by another `shot`
        // is what sent me looking, and this guard is why no frame is taken there
        // (EngineTest.aPendingTimerCannotFireAFrameOnAPausedRun).
        if (state != WAITING) return ACT_NONE;

        long remaining = schedule.remainingMs(plan, nowMs);
        if (remaining == Schedule.NONE) {
            state = DONE;
            schedule.finish();
            return ACT_NONE;
        }
        if (remaining > 0) return ACT_ARM_TIMER;
        if (!Frames.stillFits(plan, remaining)) {
            // Too late to start this frame and still release before the next one. The moment is dropped,
            // and the frame after it is taken where it was always due, rather than sliding the rest of
            // the run behind a camera that could not keep up.
            skipped++;
            lastSkipIndex = schedule.frameIndex();
            lastSkipLateMs = -remaining;
            return advanceMoment();
        }
        state = EXPOSING;
        fired++;
        attemptsForCurrentFrame++;
        return ACT_FIRE;
    }

    /**
     * The camera says a frame landed. {@code file} is the name the camera gave it, or an empty string
     * when the body does not report one.
     */
    /**
     * The camera reported the frame; the card count could not confirm it.
     *
     * <p>The frame counts as taken — the camera's own callback is the evidence that it fired — but the
     * run remembers that it was not confirmed, and says so, because "this app cannot see the card" and
     * "the card is losing frames" need different answers.
     */
    private int onUnverified(long nowMs) {
        if (state != EXPOSING) return ACT_NONE;
        unverified++;
        return onShutter(nowMs);
    }

    private int onShutter(long nowMs) {
        if (state != EXPOSING) return ACT_NONE;
        landed++;
        consecutiveFailures = 0;
        if (landed < MAX_SHOTS) shotTimesMs[landed - 1] = nowMs;
        attemptsForCurrentFrame = 0;
        state = WAITING;

        int action = advanceMoment();
        if (plan.frames() != Plan.FRAMES_UNLIMITED && landed >= plan.frames()) {
            state = DONE;
            schedule.finish();
            return ACT_NONE;
        }
        return action;
    }

    /**
     * Move to the next moment, and end the run when that was the last one.
     *
     * <p>Every path that gives up on a frame — lost, refused, skipped — moves the timetable on without
     * taking a picture, and none of them used to notice that it had just gone past the final moment: the
     * run stayed in WAITING with no timer behind it, nothing changing on screen and nothing to press.
     * Five tests in {@code EngineTest} covered the happy paths and none of them covered this one; a
     * stuck camera screen is worse than a finished run.
     */
    private int advanceMoment() {
        schedule.advance();
        if (schedule.nextDueMs(plan) == Schedule.NONE) {
            state = DONE;
            schedule.finish();
            return ACT_NONE;
        }
        return ACT_ARM_TIMER;
    }

    /**
     * The camera took the frame, but nothing arrived on the card before the deadline.
     *
     * <p>The timetable does not move for a lost frame: the next frame is still due when it was always
     * due, so one slow write cannot drag the rest of the run out of phase. The lost frame is added to
     * {@code fired} but never to {@code landed}, and the difference is what the panel shows.
     */
    /** Frames the camera reported that the card count could not corroborate. */
    public int unverified() {
        return unverified;
    }

    /** Moments the run went past without a frame, because the camera was not free in time. */
    public int skipped() {
        return skipped;
    }

    /** The last moment that was skipped, counted from zero, or -1. */
    public int lastSkipIndex() {
        return lastSkipIndex;
    }

    /** How far past its own moment that frame was when it was given up on. */
    public long lastSkipLateMs() {
        return lastSkipLateMs;
    }

    /**
     * The card caught up: a frame that was reported but not confirmed has since been accounted for.
     *
     * <p>"Unconfirmed" is a statement about the last look, not a verdict. A write that takes longer
     * than the confirmation window is not a lost frame, and the count is taken again when the next
     * frame fires — so this comes back down when the file appears. Making it permanent meant a card
     * whose writes take two seconds reported every single frame as unconfirmed while every frame was
     * arriving perfectly well, which is the same mistake as the fault that started this: a missing
     * measurement read as a negative one.
     */
    public void accountFor() {
        if (unverified > 0) unverified--;
    }

    public int onFrameLost(String why, long nowMs) {
        if (state != EXPOSING) return ACT_NONE;
        lastLoss = why;
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            fault = FAULT_WRITE;
            state = FAULT;
            schedule.finish();
            return ACT_NONE;
        }
        attemptsForCurrentFrame = 0;
        state = WAITING;
        return advanceMoment();
    }

    /**
     * The platform refused the shutter call itself, retries and all.
     *
     * <p>Counted like a lost frame — a camera that will not take a frame is a broken run — but faulted
     * as {@link #FAULT_SHUTTER}, because "the body would not take the picture" and "the picture never
     * reached the card" send the user to different places. The first version of this reported every
     * failure that way as "3 frames were not written", which was a guess dressed as a diagnosis.
     */
    public int onCallFailed(String why, long nowMs) {
        if (state != EXPOSING) return ACT_NONE;
        lastLoss = why;
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            fault = FAULT_SHUTTER;
            state = FAULT;
            schedule.finish();
            return ACT_NONE;
        }
        attemptsForCurrentFrame = 0;
        state = WAITING;
        return advanceMoment();
    }

    /** Why the last frame was judged lost, in the words of whatever reported it. */
    public String lastLoss() {
        return lastLoss;
    }

    public void setLastFile(String name) {
        lastFile = name == null ? "" : name;
    }

    // ---------------------------------------------------------------- what the panel asks
    /** Whether a frame is worth attempting right now: not while paused, done, faulted or idle. */
    public boolean canFire() {
        return state == EXPOSING;
    }

    /** One line of plain language for the fault, or an empty string when there is none. */
    public String faultText() {
        switch (fault) {
            case FAULT_CAMERA:
                return "\u76f8\u673a\u4e0d\u53ef\u7528";                          // 相机不可用
            case FAULT_CARD:
                return "\u5b58\u50a8\u5361\u8bfb\u4e0d\u5230";                  // 存储卡读不到
            case FAULT_WRITE:
                return "\u8fde\u7eed " + MAX_CONSECUTIVE_FAILURES + " \u5f20\u672a\u5199\u5165";  // 连续 N 张未写入
            case FAULT_ABORTED:
                return "\u5df2\u624b\u52a8\u505c\u6b62";                          // 已手动停止
            case FAULT_SHUTTER:
                return "\u5feb\u95e8\u8c03\u7528\u5931\u8d25";                    // 快门调用失败
            default:
                return "";
        }
    }
}
