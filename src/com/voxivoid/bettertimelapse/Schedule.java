package com.voxivoid.bettertimelapse;

/**
 * When each frame is due, as an absolute timetable rather than a chain of waits.
 *
 * <p>Frame <em>n</em> is due at {@code delay + n × interval}, measured from the moment the run was
 * armed. The old app instead re-armed a timer <em>after</em> each capture
 * (TimeLapse {@code ShootActivity.java:297}), so every frame's exposure, card write and review time
 * was added to the next wait. Over a few hundred frames the phase walked away from what the user
 * asked for, and when the camera fell behind the interval the code silently gave up waiting and
 * fired back to back — a "2 s time-lapse" quietly became "as fast as the card allows"
 * (docs/PAIN-POINTS.md U-18).
 *
 * <p>With an absolute timetable, drift cannot accumulate: frame n is late or it is not, and "late" is
 * a fact the panel can show instead of a failure the app hides.
 *
 * <p>Time is a {@code long} of milliseconds from an arbitrary origin. The shell feeds it
 * {@link System#nanoTime} divided by a million, never {@code currentTimeMillis}: a wall clock can
 * jump when the camera syncs it, and a timetable that jumps is worse than a late frame.
 * {@link #NONE} means "no such frame" — an unlimited run, or one that is already finished.
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class Schedule {

    /** No such moment. */
    public static final long NONE = Long.MIN_VALUE;

    private long startMs = NONE;
    private long resumeMs = NONE;
    private int frameIndex;
    private long pausedForMs;
    private boolean finished;

    /** Arm a run: frame {@code n} is due at {@code now + delay + n × interval}. */
    public void arm(long nowMs, Plan plan) {
        startMs = nowMs;
        resumeMs = nowMs;
        frameIndex = 0;
        pausedForMs = 0;
        finished = false;
    }

    /** Back to "nothing scheduled". A finished or faulted run does this. */
    public void clear() {
        startMs = NONE;
        resumeMs = NONE;
        frameIndex = 0;
        pausedForMs = 0;
        finished = false;
    }

    public boolean armed() {
        return startMs != NONE;
    }

    public boolean finished() {
        return finished;
    }

    public int frameIndex() {
        return frameIndex;
    }

    /**
     * When the next frame is due, in the same clock {@code arm} was given.
     *
     * <p>Frame {@code n} (counting from zero) is due at {@code delay + n × interval}, so the first
     * frame lands after the initial delay and the run ends when the last one is taken — at
     * {@code delay + (n - 1) × interval}, which is what {@link Plan#totalMs} promises the user.
     */
    public long nextDueMs(Plan plan) {
        if (!armed() || finished) return NONE;
        if (frameIndex >= plan.frames() && plan.frames() != Plan.FRAMES_UNLIMITED) return NONE;
        return startMs + plan.delayMs() + (long) frameIndex * plan.intervalMs();
    }

    /** How long until the next frame is due; negative means it is already late. */
    public long remainingMs(Plan plan, long nowMs) {
        long due = nextDueMs(plan);
        return due == NONE ? NONE : due - nowMs;
    }

    /** The frame that has just been handed to the camera is now the next one. */
    public void advance() {
        frameIndex++;
    }

    /**
     * Give up on the current frame because the camera could not take it. The timetable does not
     * move: the frame is due at the same instant it always was, so a failure cannot drag the rest of
     * the run out of phase. The caller decides, from {@link #remainingMs}, whether there is still
     * time to retry it.
     */
    public void skipCurrent() {
        frameIndex++;
    }

    /** Suspend the clock: the run keeps its phase relative to when it resumes, not to wall time. */
    public void pause(long nowMs) {
        resumeMs = nowMs;
    }

    public void resume(long nowMs) {
        if (resumeMs != NONE) pausedForMs += nowMs - resumeMs;
        resumeMs = NONE;
    }

    /** Total time spent paused, so "elapsed" means time the run was actually running. */
    public long pausedForMs() {
        return pausedForMs;
    }

    /** Running time so far, with pauses subtracted. */
    public long elapsedMs(long nowMs) {
        if (!armed()) return 0;
        long end = resumeMs == NONE ? nowMs : resumeMs;
        return Math.max(0, end - startMs - pausedForMs);
    }

    /**
     * The timetable restated for a run that is starting now, keeping the frame the next one due.
     *
     * <p>Two uses: a pause rebases so the remaining frames keep their spacing, and a session that was
     * interrupted rebases to "now" so it can carry on rather than replaying a schedule that expired
     * while the app was away.
     */
    public void rebase(long nowMs, Plan plan) {
        if (!armed()) return;
        startMs = nowMs - plan.delayMs() - (long) frameIndex * plan.intervalMs();
        resumeMs = NONE;
        pausedForMs = 0;
    }

    /** Mark the run as over: no frame is due again. */
    public void finish() {
        finished = true;
    }

    // ---------------------------------------------------------------- measuring what happened
    /**
     * The mean interval actually achieved, in milliseconds, from the timestamps of the frames that
     * were taken. Measured, not promised: the panel shows this next to the interval asked for, which
     * is how a user sees that the camera is behind instead of discovering it in the finished film.
     *
     * <p>Returns {@code 0} until there are two frames to measure between.
     */
    public static long meanInterval(long[] shotTimesMs, int count) {
        if (count < 2) return 0;
        long span = shotTimesMs[count - 1] - shotTimesMs[0];
        return span / (count - 1);
    }

    /** Mean minus wanted: positive means the run is slower than asked for. */
    public static long driftMs(long meanIntervalMs, Plan plan) {
        return meanIntervalMs == 0 ? 0 : meanIntervalMs - plan.intervalMs();
    }
}
