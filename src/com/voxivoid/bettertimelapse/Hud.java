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
        switch (row) {
            case Plan.ROW_PANEL_OFF:
                return "\u6bcf\u5f20\u4e4b\u540e\u5173\u5c4f";        // 每张之后关屏
            case Plan.ROW_SILENT:
                return plan.silentShutter()
                        ? "\u672c\u673a\u8eab\u5df2\u62a5\u544a\u652f\u6301"   // 本机身已报告支持
                        : "\u6bcf\u5f20\u4e0d\u52a8\u673a\u68b0\u5feb\u95e8";   // 每张不动机械快门
            case Plan.ROW_DRIVE:
                return "\u4e0d\u652f\u6301\u7684\u53d6\u503c\u4e0d\u8fdb\u5faa\u73af"; // 不支持的取值不进循环
            case Plan.ROW_AEL:
                return "\u7b2c\u4e00\u5f20\u4e4b\u540e\u9501\u66dd\u5149";   // 第一张之后锁曝光
            default:
                return "";
        }
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

    /** The progress line: landed frames against the plan, and how long the run has been going. */
    public static String progressLine(int landed, int planned, int unverified, long elapsedMs) {
        String of;
        if (planned == Plan.FRAMES_UNLIMITED) {
            of = landed + FRAMES_UNIT;                                   // 张
        } else {
            of = landed + " / " + planned + FRAMES_UNIT;
        }
        String tail = "   \u5df2\u7528 " + clock(elapsedMs, false);          // 已用
        if (unverified > 0) {
            // Taken, not confirmed. Saying it is the difference between "the app cannot see this card"
            // and "the app is losing frames", and the two need different answers.
            tail += "   \u672a\u6838\u5b9e " + unverified + FRAMES_UNIT;    // 未核实 N 张
        }
        return "\u5df2\u843d\u76d8 " + of + tail;
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

    /** The row that says what the keys do. Hold actions are left out on purpose. */
    public static String legend() {
        return "\u2190\u2192 \u5927\u8c03   "                      // ←→ 大调
                + "\u6eda\u8f6e \u5fae\u8c03   "                  // 滚轮 微调
                + "\u2191\u2193 \u6362\u884c   "                  // ↑↓ 换行
                + "\u4e2d\u5fc3 \u5f00\u59cb   "                  // 中心 开始
                + "TRASH \u6682\u505c";                          // TRASH 暂停
    }

    /** Shown once under the legend while the demo key is what drives the screen. */
    public static String demoLegend() {
        return "\u6f14\u793a\uff1a\u53d8\u7126\u6746\u5207\u6362\u72b6\u6001";   // 演示：变焦杆切换状态
    }
}
