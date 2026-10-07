package com.voxivoid.bettertimelapse;

import java.util.Locale;

/**
 * Every string the panel draws, and every piece of time arithmetic behind them.
 *
 * <p>Centralised for one reason: the old app computed the same "how long will this take" number in
 * two places and the two disagreed by a factor of three under bracketing, because only one of them
 * accounted for pictures-per-shot (TimeLapse {@code SettingsActivity.java:357} against
 * {@code ShootActivity.java:424}; docs/PAIN-POINTS.md U-10, D-18). Here a duration is formatted in
 * exactly one method and the plan's own {@link Plan#totalMs} is the only source of the number.
 *
 * <p>Chinese is the display language; the strings live in this file rather than in Android resources
 * because {@code tools/test.sh} must be able to compile and check them with no SDK, and because
 * API 10's locale handling is not something to rely on. The text tables move to one table per
 * language (the Recipe Lab shape) when a second language is actually wanted.
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class Hud {

    private Hud() {}

    // ---------------------------------------------------------------- the seven states
    public static final int IDLE = 0, ARMED = 1, WAITING = 2, EXPOSING = 3, PAUSED = 4, DONE = 5, FAULT = 6;

    private static final String[] STATE_NAMES = {
            "IDLE", "ARMED", "WAITING", "EXPOSING", "PAUSED", "DONE", "FAULT",
    };

    /** One line of plain language per state. The old app had no state at all, only a count. */
    private static final String[] STATE_PLAIN = {
            "\u5f85\u673a\uff1a\u6309\u4e2d\u5fc3\u5f00\u59cb",                  // 待机：按中心开始
            "\u5012\u8ba1\u65f6\uff1a\u9a6c\u4e0a\u5f00\u62cd",                  // 倒计时：马上开拍
            "\u7b49\u5f85\u4e0b\u4e00\u5f20",                                     // 等待下一张
            "\u6b63\u5728\u66dd\u5149",                                           // 正在曝光
            "\u5df2\u6682\u505c\uff1aTRASH \u7ee7\u7eed",                        // 已暂停：TRASH 继续
            "\u5df2\u5b8c\u6210",                                                 // 已完成
            "\u51fa\u9519\uff1a\u5df2\u505c\u6b62",                              // 出错：已停止
    };

    public static String stateName(int state) {
        return STATE_NAMES[inRange(state) ? state : IDLE];
    }

    public static String statePlain(int state) {
        return STATE_PLAIN[inRange(state) ? state : IDLE];
    }

    private static boolean inRange(int state) {
        return state >= 0 && state < STATE_NAMES.length;
    }

    // ---------------------------------------------------------------- the settings rows
    private static final String[] ROW_LABELS = {
            "\u95f4\u9694",           // 间隔
            "\u5f20\u6570",           // 张数
            "\u521d\u59cb\u5ef6\u65f6", // 初始延时
            "\u7a33\u5b9a\u5ef6\u65f6", // 稳定延时
            "\u9759\u97f3\u5feb\u95e8", // 静音快门
            "\u9a71\u52a8\u6a21\u5f0f", // 驱动模式
            "AEL",
            "\u5173\u5c4f",           // 关屏
            "\u5361\u4e0a\u6838\u5bf9",   // 卡上核对
    };

    public static String rowLabel(int row) {
        return ROW_LABELS[row];
    }

    private static final String ON = "\u5f00";   // 开
    private static final String OFF = "\u5173";  // 关
    private static final String UNLIMITED = "\u4e0d\u9650";  // 不限
    private static final String FRAMES_UNIT = " \u5f20";     // 张

    private static final String[] DRIVE_NAMES = { "SINGLE", "BURST", "BRACKET3" };

    public static String driveName(int mode) {
        return DRIVE_NAMES[mode >= 0 && mode < DRIVE_NAMES.length ? mode : 0];
    }

    /** The value as the panel prints it, units included. */
    public static String rowValue(Plan plan, int row) {
        switch (row) {
            case Plan.ROW_INTERVAL:
                // The same notation the step hint uses, so one row never shows a value one way and
                // its own increments another.
                return Steps.shortMs(plan.intervalMs());
            case Plan.ROW_FRAMES:
                return plan.frames() == Plan.FRAMES_UNLIMITED
                        ? UNLIMITED
                        : plan.frames() + FRAMES_UNIT;
            case Plan.ROW_DELAY:
                return clock(plan.delayMs(), true);
            case Plan.ROW_SETTLE:
                return Steps.shortMs(plan.settleMs());
            case Plan.ROW_SILENT:
                return plan.silentShutter() ? ON : OFF;
            case Plan.ROW_DRIVE:
                return driveName(plan.driveMode());
            case Plan.ROW_AEL:
                return plan.autoExposureLock() ? ON : OFF;
            case Plan.ROW_PANEL_OFF:
                return plan.panelOff() ? ON : OFF;
            case Plan.ROW_CARD_CHECK:
                return plan.cardCheck() ? ON : OFF;
            default:
                return "";
        }
    }

    /**
     * The row's own explanation, shown on the selected row. This is where the old app left five
     * two-to-four letter abbreviations unexplained on screen (U-05), and where "which detent is
     * worth what" becomes readable before the wheel is touched (U-08).
     */
    public static String rowDetail(Plan plan, int row) {
        return rowDetail(plan, row, false);
    }

    /**
     * The same, for a row the run has locked.
     *
     * <p>A camera-side row takes effect when a run starts, so editing one mid-run would leave the panel
     * showing a setting the body does not hold. The row says so instead of accepting the edit: a number
     * the user cannot trust is worse than a key that does nothing.
     */
    public static String rowDetail(Plan plan, int row, boolean locked) {
        return rowDetail(plan, row, locked, true);
    }

    /**
     * The same, told whether this body can switch its display output at all.
     *
     * <p>One row's text depends on something only the camera can answer, which is why the "关屏" row was marked
     * 尚未实现 for three milestones rather than guessed at. The answer travels as an argument because the row is
     * drawn by a class that must not talk to Sony's classes: the shell finds out, the text says so.
     */
    public static String rowDetail(Plan plan, int row, boolean locked, boolean panelSwitchable) {
        if (locked && cameraRow(row)) {
            return "\u8fd0\u884c\u4e2d\u4e0d\u6539\u673a\u8eab\u8bbe\u7f6e";   // 运行中不改机身设置
        }
        switch (row) {
            case Plan.ROW_PANEL_OFF:
                // Three answers, and only the first is a promise: the camera can do it and the plan has room for
                // it; the setting is on but this body has no display call; the setting is on but the interval is
                // too short to fit the two switches without eating the frame. The last two used to say 每张之后
                // 关屏 as if they worked, which is the same lie as a setting that is sent and never applied.
                if (!plan.panelOff()) return "\u5c4f\u5e55\u4e00\u76f4\u4eae\u7740";           // 屏幕一直亮着
                if (!panelSwitchable) return "\u672c\u673a\u8eab\u5173\u4e0d\u4e86\u5c4f";    // 本机身关不了屏
                if (!Frames.panelStaysOff(plan)) {
                    return "\u95f4\u9694\u592a\u77ed\uff0c\u5173\u4e0d\u4e86\u5c4f";            // 间隔太短，关不了屏
                }
                return "\u6bcf\u5f20\u4e4b\u540e\u5173\u5c4f";                                 // 每张之后关屏
            case Plan.ROW_CARD_CHECK:
                // The honest consequence of the value, because the difference is one the user will notice in a
                // finished night rather than in the panel: with the check on, "核实" is a statement about files
                // that exist; with it off, every frame the shutter reported counts as taken and nothing can
                // contradict that. That is the old app's behaviour, and it is a choice rather than a bug only
                // when it is written down where the choice is made.
                return plan.cardCheck()
                        ? "\u6bcf\u5f20\u8bfb\u5361\u6838\u5bf9"                    // 每张读卡核对
                        : "\u53ea\u8ba1\u5feb\u95e8\u56de\u62a5";                  // 只计快门回报
            case Plan.ROW_SILENT:
                // These two were the wrong way round: "每张不动机械快门" is what the row does when the
                // silent shutter is ON, and it was being shown when it was off. What the user needs is the
                // consequence of the value in front of them, not a fact about the body's capabilities --
                // those are on the capability line.
                return plan.silentShutter()
                        ? "\u7535\u5b50\u5feb\u95e8\uff0c\u65e0\u58f0"              // 电子快门，无声
                        : "\u673a\u68b0\u5feb\u95e8\uff0c\u6709\u58f0";              // 机械快门，有声
            case Plan.ROW_DRIVE:
                return "\u4e0d\u652f\u6301\u7684\u53d6\u503c\u4e0d\u8fdb\u5faa\u73af"; // 不支持的取值不进循环
            case Plan.ROW_AEL:
                return "\u7b2c\u4e00\u5f20\u4e4b\u540e\u9501\u66dd\u5149";   // 第一张之后锁曝光
            default:
                return "";
        }
    }

    /**
     * Rows that are pushed to the camera when a run starts, and therefore cannot mean anything new until
     * the next one: the silent shutter, the drive mode and the exposure lock.
     */
    public static boolean cameraRow(int row) {
        return row == Plan.ROW_SILENT || row == Plan.ROW_DRIVE || row == Plan.ROW_AEL;
    }

    // ---------------------------------------------------------------- the status bar
    /**
     * The whole plan in one line: how long it runs and how many frames that is. Shown above the
     * values so the user can see the consequence of an edit without doing the arithmetic, which is
     * the thing the old app never showed at all (U-21).
     */
    public static String planSummary(Plan plan) {
        long total = plan.totalMs();
        String span = total == Plan.UNLIMITED
                ? UNLIMITED
                : "\u7ea6 " + clock(total, false);                            // 约
        String frames = plan.frames() == Plan.FRAMES_UNLIMITED
                ? UNLIMITED
                : plan.frames() + FRAMES_UNIT;
        return span + "  \u00b7  " + frames + "  \u00b7  "                  // ·
                + "\u6bcf " + Steps.shortMs(plan.intervalMs());             // 每
    }

    /**
     * Battery as the status bar prints it: {@code BAT 78%}, or {@code 电源} on external power, or
     * nothing at all when the camera will not say. An unknown battery is left out rather than shown
     * as a guess — the old app printed a percentage it had not verified.
     */
    public static String batteryText(int percent, boolean charging) {
        if (percent < 0) return "";
        return (charging ? "\u7535\u6e90 " : "BAT ") + percent + "%";   // 电源
    }

    /**
     * How many more frames the card has room for, from the free bytes and the plan's own interval.
     * The estimate is honestly a floor: it assumes a frame costs what the last one did, which is why
     * it is rounded down and prefixed with a tilde.
     */
    public static String cardText(long freeBytes, long bytesPerFrame) {
        if (freeBytes < 0) return "";
        if (bytesPerFrame <= 0) {
            // No frame size to divide by, so report the space itself rather than inventing a count.
            return "\u5361 " + megabytes(freeBytes) + "MB";                              // 卡
        }
        long frames = freeBytes / bytesPerFrame;
        // Rounded down, and to a whole number of thousands: "10.9k" would be three digits of
        // precision on a guess. A floor is the honest direction — the card runs out earlier, not
        // later, than the number on screen.
        String count = frames >= 10000 ? (frames / 1000) + "k" : String.valueOf(frames);
        return "\u5361 \u7ea6 " + count + FRAMES_UNIT;                                  // 卡 约 … 张
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * The whole status line: what the plan will do, and what the body has left to do it with.
     *
     * <p>All of it on one line on purpose. The old app showed the battery only after a run had
     * started, and never showed a plan summary at all, so the consequence of an edit was invisible
     * until it was too late (docs/PAIN-POINTS.md U-21).
     */
    public static String statusLine(Plan plan, int batteryPercent, boolean charging, long freeBytes,
                                    long bytesPerFrame) {
        StringBuilder b = new StringBuilder(planSummary(plan));
        String battery = batteryText(batteryPercent, charging);
        if (battery.length() > 0) b.append("   ").append(battery);
        String card = cardText(freeBytes, bytesPerFrame);
        if (card.length() > 0) b.append("   ").append(card);
        return b.toString();
    }

    /** Countdown to the next frame, as {@code 03:12.4} or {@code 1:02:03}. */
    public static String countdown(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        if (totalSeconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
        }
        long tenths = (ms % 1000) / 100;
        return String.format(Locale.US, "%02d:%02d.%d",
                totalSeconds / 60, totalSeconds % 60, tenths);
    }

    /** A duration as {@code 00:02.0} when it is a per-frame interval, {@code 00:00:00} otherwise. */
    public static String clockSeconds(long ms) {
        long totalSeconds = ms / 1000;
        long tenths = (ms % 1000) / 100;
        return String.format(Locale.US, "%02d:%02d.%d",
                totalSeconds / 60, totalSeconds % 60, tenths);
    }

    /** A duration as {@code hh:mm:ss}, or {@code mm:ss} when {@code compact} and under an hour. */
    public static String clock(long ms, boolean compact) {
        if (ms == Plan.UNLIMITED) return UNLIMITED;
        long s = ms / 1000;
        if (compact && s < 3600) {
            return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
        }
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * How much of the wait to the next frame has gone by, from {@code 0} to {@code 1}.
     *
     * <p>The single-line panel draws this as a bar, and the bar is drawn as a filled rectangle rather than
     * from characters. That is not a style choice: the panel uses a CJK font, in which {@code #} and {@code .}
     * are not the same width -- so a character bar is ragged, and the first version of it was. Geometry has no
     * such problem.
     *
     * <p>The fraction is here rather than in {@code PanelView} because this is the part that can be wrong (an
     * interval of zero, a frame already late, a negative remaining time) and {@code PanelView} cannot be run off
     * the camera. The panel does nothing but multiply two numbers by it.
     *
     * @param remainingMs how long until the next frame, or {@link Schedule#NONE} when none is due.
     * @param intervalMs the whole wait the fraction is of.
     * @return {@code 0} before anything has elapsed, {@code 1} when the moment is here or past, and
     *     {@link Frames#UNKNOWN} when there is no bar to draw at all.
     */
    public static float countdownFraction(long remainingMs, long intervalMs) {
        if (remainingMs == Schedule.NONE || intervalMs <= 0) return Frames.UNKNOWN;
        if (remainingMs <= 0) return 1f;                       // the moment is here, or it is late
        if (remainingMs >= intervalMs) return 0f;              // the wait has not started
        return (float) (intervalMs - remainingMs) / (float) intervalMs;
    }

    /**
     * How far through the whole run this is, from {@code 0} to {@code 1}.
     *
     * <p>The bar under the countdown used to be the wait to the next frame, which answers "is it close" and
     * nothing else. What a person standing at a camera for two hours wants is the other question: how much of
     * this is left. So the bar is the run's own progress, frames taken against frames planned.
     *
     * <p>An unlimited run has no such thing, and inventing one would be the kind of number this project exists
     * to avoid: {@link Frames#UNKNOWN} comes back and the panel falls back to the wait it can measure.
     *
     * @param landed frames taken.
     * @param planned frames the plan asks for, or {@link Plan#FRAMES_UNLIMITED}.
     * @return {@code 0}..{@code 1}, or {@link Frames#UNKNOWN} when there is no total to be a fraction of.
     */
    public static float totalFraction(int landed, int planned) {
        if (planned == Plan.FRAMES_UNLIMITED || planned <= 0) return Frames.UNKNOWN;
        if (landed <= 0) return 0f;
        if (landed >= planned) return 1f;
        return (float) landed / (float) planned;
    }

    /**
     * How long the anti-burn-in drift takes to go up and come back.
     *
     * <p>Ten seconds rather than the minute it started at, at the user's request. A minute of travel is invisible
     * by design, which is what it should be on a real shoot -- but it is also invisible to the person checking
     * whether the feature works at all. Ten seconds makes the movement something that can be looked at, and the
     * travel is one bar height each way; the value can go back up once it has been seen to work
     * (docs/DECISIONS.md A55).
     */
    public static final long BURN_IN_PERIOD_MS = 10_000;

    /**
     * How long the minimal state's drift takes: slower than the one-line mode's, for the same reason.
     *
     * <p>That mode draws one word and nothing else. There is less on screen to move, and a word that slides
     * about is easier to notice than a line of text breathing, so it gets a longer round trip.
     */
    public static final long MINIMAL_DRIFT_PERIOD_MS = 45_000;

    /**
     * Where to draw a fixed piece of screen so that it does not sit in the same place for hours.
     *
     * <p>A progress bar is the worst case for a panel: the same few hundred pixels lit, in the same rows, for a
     * whole night. The panel already has display modes for this reason, but the bar is the one thing drawn for
     * the entire run in every mode that shows it.
     *
     * <p>So it drifts: a triangle wave, up and then down, over {@link #BURN_IN_PERIOD_MS}. Triangle rather than
     * sine because it is arithmetic that can be checked instead of a transcendental function that has to be
     * trusted -- and the movement is not what anyone is looking at.
     *
     * @param nowMs a monotonic clock reading.
     * @param amplitudePx how far to either side of the resting place, in canvas units.
     * @return an offset in {@code [-amplitudePx, +amplitudePx]}.
     */
    public static float burnInShift(long nowMs, float amplitudePx) {
        return burnInShift(nowMs, amplitudePx, BURN_IN_PERIOD_MS);
    }

    /**
     * The same, over a period of the caller's choosing.
     *
     * <p>The minimal state draws one word and nothing else, so there is less on screen to move and a moving word
     * is easier to notice than a line of text breathing: it gets a slower round trip than the one-line mode.
     */
    public static float burnInShift(long nowMs, float amplitudePx, long periodMs) {
        if (amplitudePx <= 0f) return 0f;
        long period = periodMs > 0 ? periodMs : BURN_IN_PERIOD_MS;
        long phase = ((nowMs % period) + period) % period;          // a negative clock still lands in range
        float half = period / 2f;
        float t = phase < half ? phase / half : (period - phase) / half;   // up, then back down
        return (t * 2f - 1f) * amplitudePx;
    }

    /** The progress line: frames taken against the plan, and how long the run has been going. */
    public static String progressLine(int landed, int planned, int accounted, int unverified,
            int skipped, long elapsedMs) {
        String of;
        if (planned == Plan.FRAMES_UNLIMITED) {
            of = landed + FRAMES_UNIT;                                   // 张
        } else {
            of = landed + " / " + planned + FRAMES_UNIT;
        }
        // 已拍, not 已落盘: taken is what this app knows at this point. Whether each one reached the
        // card is what the tail of this line is about, and a label that claims more than the app can
        // see is the habit this whole project exists to avoid.
        // What the card has actually answered for, next to what was taken: the two numbers that matter,
        // side by side, where the run's own progress is reported. A run that takes seventeen frames and
        // keeps six says so here rather than looking like seventeen.
        String tail = "   \u6838\u5b9e " + accounted + "   \u5df2\u7528 "               // 核实 N  已用
                + clock(elapsedMs, false);
        if (unverified > 0) {
            // Taken, not accounted for on the card. Saying it is the difference between "the app cannot
            // see this card" and "the app is losing frames", and the two need different answers.
            tail += "   \u672a\u6838\u5b9e " + unverified + FRAMES_UNIT;    // 未核实 N 张
        }
        if (skipped > 0) {
            // A moment that went by with no frame in it: the camera was not free in time. Silence here
            // is what let the old app call a run complete while its timeline had holes in it.
            tail += "   \u8df3\u8fc7 " + skipped + FRAMES_UNIT;                // 跳过 N 张
        }
        return "\u5df2\u62cd " + of + tail;
    }

    /** How long ago, in the shortest unit that says it: {@code 7s}, {@code 3min}, {@code 2h}. */
    public static String age(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "min";
        return (s / 3600) + "h";
    }

    /**
     * The alarm for a run whose frames are not reaching the card.
     *
     * <p>Measured on this body: at a 3 s interval it took seventeen frames and the card kept six — the app
     * knew both numbers and said neither. The old app reported the seventeen. In-flight writes make a small
     * gap normal, so this only speaks when most of the run is missing.
     */
    public static String shortfallLine(int taken, int accounted) {
        return "\u5361\u4e0a\u53ea\u5bf9\u4e0a " + accounted + " / " + taken         // 卡上只对上 N / M
                + " \u5f20\uff1a\u95f4\u9694\u592a\u77ed\u6216\u673a\u8eab\u5fd9";      // 张：间隔太短或机身忙
    }

    /** Whether a shortfall is bad enough to interrupt the user with: fewer than half, and enough frames. */
    public static boolean worthSaying(int taken, int accounted) {
        return taken >= 5 && accounted * 2 < taken;
    }

    /**
     * What to say about a plan that does not fit, or an empty string when it does.
     *
     * <p>The words are here and the arithmetic is in {@link Frames}: the sentence has to name the thing the
     * user can change — their shutter speed with the body's own noise reduction, or a floor that refusals
     * measured rather than this app guessed.
     */
    public static String preflightProblem(Plan plan, Frames.Preflight preflight) {
        if (preflight.fits()) return "";
        StringBuilder b = new StringBuilder();
        b.append("\u95f4\u9694 ").append(Steps.shortMs(plan.intervalMs()))                 // 间隔 <n>
                .append(" \u77ed\u4e8e\u672c\u673a\u8eab\u6240\u9700\u7684 ")              // 短于本机身所需的
                .append(Steps.shortMs((int) preflight.shortestMs));
        if (preflight.reason == Frames.EXPOSURE) {
            b.append("\uff08\u66dd\u5149 ").append(Steps.shortMs((int) preflight.exposureMs));   // （曝光 <n>
            if (preflight.longExposureNr) b.append(" + \u964d\u566a");                          // + 降噪
            b.append("\uff09");                                                              // ）
        } else if (preflight.reason == Frames.MEASURED) {
            b.append("\uff08\u5b9e\u6d4b\uff09");                                          // （实测）
        }
        b.append("\uff1a\u76f8\u673a\u4f1a\u6765\u4e0d\u53ca");                       // ：相机会来不及
        return b.toString();
    }

    /**
     * The one line the dimmed label mode shows while a run is on.
     *
     * <p>Not the plan summary: with the screen reduced to a single line the question is never "what did I
     * ask for" but "is it working", and the answer is the count, the holes where the camera was not ready,
     * and the frames the card has not answered for yet.
     */
    public static String labelLine(int landed, int planned, int skipped, int unverified) {
        String of = planned == Plan.FRAMES_UNLIMITED
                ? landed + FRAMES_UNIT
                : landed + " / " + planned + FRAMES_UNIT;
        String line = "\u5df2\u62cd " + of;                                          // 已拍
        if (skipped > 0) line += "   \u8df3\u8fc7 " + skipped + FRAMES_UNIT;         // 跳过 N 张
        if (unverified > 0) line += "   \u672a\u6838\u5b9e " + unverified + FRAMES_UNIT;  // 未核实 N 张
        return line;
    }

    /**
     * The measured line: the mean interval actually achieved and the drift per frame, against the
     * interval asked for. The old app showed a theoretical number that was wrong by a factor of
     * three and never compared it against what happened (U-10, U-18). A drift that is shown can be
     * acted on; a drift that is hidden turns into a ruined sequence.
     */
    public static String measuredLine(long meanIntervalMs, long wantedIntervalMs, long remainingMs) {
        StringBuilder b = new StringBuilder();
        b.append("\u5b9e\u6d4b\u95f4\u9694 ").append(clockSeconds(meanIntervalMs));   // 实测间隔
        long drift = meanIntervalMs - wantedIntervalMs;
        if (drift != 0) {
            b.append("   \u6f02\u79fb ").append(drift > 0 ? "+" : "-")               // 漂移
                    .append(clockSeconds(Math.abs(drift))).append("/\u5f20");        // /张
        }
        if (remainingMs >= 0) {
            b.append("   \u9884\u8ba1 ").append(clock(remainingMs, false))            // 预计
                    .append("\u540e\u7ed3\u675f");                                    // 后结束
        }
        return b.toString();
    }

    /**
     * The row that says what the keys do. Hold actions are left out on purpose — "它是给需要的人准备的，
     * 不是邀请" (docs/PLAN.md §4.1) — and they were added back once, which is how the legend grew long
     * enough to be drawn through the font note beside it. Held keys are announced where they take effect
     * instead: the rehearsal note when it is switched on, the panel mode as the screen changes.
     */
    public static String legend() {
        return "\u2190\u2192 \u5927\u8c03   "                      // ←→ 大调
                + "\u6eda\u8f6e \u5fae\u8c03   "                  // 滚轮 微调
                + "\u2191\u2193 \u6362\u884c   "                  // ↑↓ 换行
                + "\u4e2d\u5fc3 \u5f00\u59cb   "                  // 中心 开始
                + "TRASH \u6682\u505c   "                          // TRASH 暂停
                + "C1 \u5c4f\u663e";                              // C1 屏显
    }

    /** Shown once under the legend while the demo key is what drives the screen. */
    public static String demoLegend() {
        return "\u6f14\u7ec3\uff1a\u53ea\u8dd1\u65f6\u523b\u8868\uff0c\u4e0d\u52a8\u76f8\u673a";   // 演练：只跑时刻表，不动相机
    }
}
