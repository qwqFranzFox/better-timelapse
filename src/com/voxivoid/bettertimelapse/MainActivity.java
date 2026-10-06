package com.voxivoid.bettertimelapse;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * The shell: one Activity, one {@link PanelView}, one {@link Engine}, and the loop that connects them
 * to the camera.
 *
 * <p>Everything Android-shaped lives here, in {@link SonyCamera}, {@link Card} and {@link UiFont}:
 * read the colours, own the Handler, open the camera, count files, load the font. Every decision
 * lives in {@link Plan}, {@link Steps}, {@link Keys}, {@link Hud}, {@link Schedule}, {@link Engine}
 * and {@link Frames}, which are compiled and checked with no SDK at all. That split is what makes a
 * several-hour run testable without a camera, and it is the opposite of the old app, where the whole
 * run sat inside its Activity beside the UI and the camera (docs/PAIN-POINTS.md D-04, D-06).
 *
 * <p>Five rules this class exists to keep:
 *
 * <ol>
 *   <li><b>Nothing slow runs before the first frame.</b> Opening the camera and walking the card both
 *       cost seconds on a real body, and neither is needed to draw the panel. Doing them in
 *       {@code onResume} left the screen black until they finished — a bug the user sees and cannot
 *       explain, so they are queued behind the first draw instead.</li>
 *   <li><b>Both key-down and key-up are consumed.</b> The old app let MENU, TRASH and the shutter
 *       through to the firmware, so the camera's own menu could interrupt a running time-lapse while
 *       the app believed it was still shooting (docs/PAIN-POINTS.md U-01, U-04).</li>
 *   <li><b>One timer at a time.</b> Everything is driven by {@link #pump}, which cancels the pending
 *       callback before posting the next one, so two loops cannot run at once — the race the old
 *       app's pair of Handlers lived with.</li>
 *   <li><b>A frame is not a frame until it is on the card.</b> The shutter callback and a card
 *       watchdog both look at the card, and a frame that never appeared is reported lost rather than
 *       counted (docs/PAIN-POINTS.md U-06, U-16).</li>
 *   <li><b>The clock is monotonic.</b> {@code elapsedRealtime} does not jump when the camera syncs
 *       its clock, which is the second cause of the old app's drift (U-18).</li>
 * </ol>
 */
public class MainActivity extends Activity {

    /** How often the device status (battery, card) is re-read. Both change over minutes. */
    private static final long DEVICE_MS = 5000;

    /**
     * How many times a refused shutter call is retried, and how long between attempts.
     *
     * <p>The platform refuses {@code burstableTakePicture} while it is still busy with the previous
     * frame. That is transient, and it is not a lost frame: the first version counted the refusal as a
     * lost frame and stopped a run after the first photograph, over a camera that was merely still
     * writing. Retries are cheap; a thrown-away night is not.
     */
    private static final int FIRE_ATTEMPTS = 4;
    private static final long FIRE_RETRY_MS = 500;

    /** Fallback frame size for the card estimate, until one can be measured off the card. */
    private static final long FALLBACK_FRAME_BYTES = 6L * 1024 * 1024;

    private final Handler handler = new Handler();
    private final Keys.Hold trash = new Keys.Hold();
    private final Keys.Hold centre = new Keys.Hold();
    private final Keys.Hold menu = new Keys.Hold();
    private final SonyCamera camera = new SonyCamera();
    private final Card card = new Card();

    private Engine engine;
    private PanelView panel;

    /** Set once the startup work has been queued, so it happens on the first resume only. */
    private boolean started;

    /** When the current frame was handed to the camera, for the callback latency. */
    private long firedAtMs;

    /** Attempts at the frame currently due; cleared by a call the camera accepted. */
    private int fireAttempts;

    // ---------------------------------------------------------------- what the worker read
    /**
     * The card as of the last completed walk.
     *
     * <p>Published copies, not the {@code Card} object: the walk happens on a worker thread and this is
     * the hand-off, so nothing on the UI thread — including {@link #fire()} — ever reads a half-updated
     * Card. The count used at a frame's due moment is one of these, and being up to one interval old
     * is exactly what the outstanding-frames count is for.
     */
    private int cardCount = Frames.UNKNOWN;
    private boolean cardReadable;
    private String cardNewest = "";
    private long cardNewestStamp;
    private String cardNewestPath = "";
    private long cardNewestBytes;
    private String cardRoots = "";

    /** The count as of the previous walk: the difference between the two is what the card gained. */
    private int lastWalkCount = Frames.UNKNOWN;
    private int lastGained;

    /** True while a worker is walking the card, so two walks cannot read over each other. */
    private boolean walking;
    private long walkMs;

    /** Whether the card was ever readable this session, so losing it can be told from never having it. */
    private boolean sawCard;

    /** Frames the camera's own callback brought back, and the total time they took to arrive. */
    private int callbacks;
    private long callbackMsTotal;

    private long measuredFrameBytes;

    /**
     * When {@code onCreate} started, and whether the first frame has been drawn yet. The gap between
     * them is the black window the user sees, and it is measured rather than guessed: "the screen is
     * black for a while" is not something to fix by intuition when the fix is a reordering.
     */
    private long begunAtMs;
    private boolean firstFrameDrawn;

    /** A font failure found before the panel existed; shown as soon as there is one. */
    private String pendingFontError = "";

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new Engine(new Plan());
        // The font is loaded here, before the first frame, so the first frame is already the finished
        // thing. Drawing first and swapping the typeface in later meant every Chinese label appeared
        // as a box for a moment and then changed — worse than a few milliseconds of black, and quite
        // unnecessary: reading a 57 KB asset is not the slow part of starting up. The slow parts
        // (opening the camera, walking the card) still wait for the first frame.
        Typeface face = loadFont();
        panel = new PanelView(this, engine, face,
                color(R.color.background), color(R.color.text), color(R.color.dim),
                color(R.color.selected), color(R.color.accent), color(R.color.warn),
                color(R.color.good));
        panel.setOnFirstDraw(new Runnable() {
            @Override
            public void run() {
                onFirstFrame();
            }
        });
        setContentView(panel);
        if (pendingFontError.length() > 0) {
            panel.setFontNote("\u5b57\u4f53\u672a\u52a0\u8f7d");                            // 字体未加载
            panel.setFontError(pendingFontError);
        } else {
            panel.setFontNote("\u5b57\u4f53 " + UiFont.route());                                // 字体 <路径>
        }
        begunAtMs = now();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(onDevice);
        // The startup work is queued by onFirstFrame, not here: queueing it from onResume meant it ran
        // before anything had been drawn, which is the black window itself. A fallback covers the case
        // where the first draw never reports back.
        if (!started) {
            started = true;
            handler.postDelayed(onStartupFallback, 1500);
        }
    }

    /**
     * If the panel never reports a first draw, the slow work must still happen. It is better to open
     * the camera behind a black screen than never to open it at all.
     */
    private final Runnable onStartupFallback = new Runnable() {
        @Override
        public void run() {
            if (firstFrameDrawn) return;
            firstFrameDrawn = true;
            handler.post(onStartup);
        }
    };

    @Override
    protected void onPause() {
        handler.removeCallbacks(onTick);
        handler.removeCallbacks(onCallbackTimeout);
        handler.removeCallbacks(onRetryFire);
        handler.removeCallbacks(onVerdict);
        handler.removeCallbacks(onDevice);
        // Clear every hold: a release that lands while the app is not looking must not leave a key
        // stuck down, which is the failure that makes a camera app look broken.
        trash.reset();
        centre.reset();
        menu.reset();
        // The camera belongs to the camera when this app is not in front, and releasing it here is
        // also what makes the next open clean.
        camera.clearShutterListener();
        camera.cancel();
        camera.close();
        super.onPause();
    }

    /**
     * The first frame is on screen. Everything slow is queued from here, and the delay it took is
     * shown once, so a slow start is a number instead of a feeling.
     */
    private void onFirstFrame() {
        if (firstFrameDrawn) return;
        firstFrameDrawn = true;
        panel.setStartupMs(now() - begunAtMs);
        handler.post(onStartup);
    }

    /** Everything that costs time, queued behind the first frame. */
    private final Runnable onStartup = new Runnable() {
        @Override
        public void run() {
            openCamera();
            handler.post(onFirstCardRead);
        }
    };

    /**
     * Load the bundled font, after the first frame, and say which route worked.
     *
     * <p>Late on purpose: a font that takes seconds, or is rejected outright, must not be able to hold
     * the screen black. The panel has already drawn by the time this runs, and it redraws with the new
     * typeface when one arrives.
     */
    private Typeface loadFont() {
        Typeface face = UiFont.load(this);
        if (face == null) {
            // Boxes on screen, and the reason on screen with them. A silent catch here is what made
            // this unfixable from a photograph for two rounds. The reason waits for the panel to
            // exist, so it is stashed and applied once onCreate has built one.
            pendingFontError = UiFont.report() + "  " + UiFont.assetListing(this);
        }
        return face;
    }

    private final Runnable onFirstCardRead = new Runnable() {
        @Override
        public void run() {
            // The first walk is the baseline every later count is measured against, and it also goes
            // through the worker thread: one path for reading the card means the one place where the
            // Card object is touched is the same place every time.
            walkCard();
            pushDeviceStatus();
        }
    };

    /**
     * Open the camera, and say plainly what happened. A body that cannot be opened still gets a
     * working panel — in dry run — rather than a crash or a blank screen.
     */
    private void openCamera() {
        if (!camera.open()) {
            panel.setCameraNote("\u76f8\u673a\u672a\u6253\u5f00\uff1a" + camera.lastError()        // 相机未打开
                    + "  (\u6f14\u7ec3\u6a21\u5f0f)");                                              // (演练模式)
            panel.setDryRun(true);
            return;
        }
        camera.setShutterListener(onShutter);
        panel.setCameraNote(camera.capabilities().summary());
        applyPlanToCamera();
    }

    /**
     * Push the plan's camera-side options, and name every one that did not take.
     *
     * <p>Silent shutter in particular used to fail in complete silence: it travels through the
     * parameter modifier, and when the modifier could not be created every call through it returned
     * false while the row on screen still said the setting was on. A setting that did not apply is a
     * fact the user needs, not a return value to drop.
     */
    private void applyPlanToCamera() {
        Plan plan = engine.plan();
        StringBuilder failed = new StringBuilder();

        // The drive mode is applied whichever it is, not only SINGLE: a body left in a bracket or
        // burst mode takes several frames per press, which is exactly the kind of surprise the old app
        // shipped and this one must not.
        // Lower case: the camera reports "single", so that is the string it takes.
        String wanted = plan.driveMode() == Plan.DRIVE_BURST ? "burst"
                : plan.driveMode() == Plan.DRIVE_BRACKET3 ? "bracket" : "single";
        if (!camera.setDriveMode(wanted)) failed.append("\u9a71\u52a8 ");                    // 驱动

        boolean silentTook = !plan.silentShutter() || camera.setSilentShutter(true);
        if (!silentTook) failed.append("\u9759\u97f3 ");                                     // 静音

        if (plan.autoExposureLock() && !camera.setAutoExposureLock(true)) failed.append("AEL ");

        // What the body actually holds, read back after the writes above: the difference between "the
        // setting was applied" and "the setting was sent".
        panel.setNumbers(camera.settingsReport());

        String problem = camera.modifierProblem();
        if (problem.length() > 0) {
            // This one disables every setting above at once, so it goes on the evidence line.
            panel.setEvidence("\u53c2\u6570\u4fee\u9970\u5668\u4e0d\u53ef\u7528\uff1a" + problem);  // 参数修饰器不可用
        } else if (failed.length() > 0) {
            panel.setEvidence("\u672a\u751f\u6548\uff1a" + failed.toString().trim() + "\uff08"  // 未生效
                    + camera.modifierMethods().length() + " \u4e2a\u53ef\u7528\u65b9\u6cd5\uff09");  // 个可用方法
        }
        panel.setCameraNote(camera.capabilities().summary());
    }

    // ================================================================ the loop

    /**
     * Say so, before the run starts, when the plan asks for more than the camera can do.
     *
     * <p>The interval is a promise about the clock, and the settle delay is this plan's own estimate of
     * how long the body needs after a frame before it can take the next one. If the interval is shorter
     * than that, no amount of care in this app will produce it — the camera sets the pace, every frame
     * comes late, and the honest thing is to say it on screen before the shutter is pressed rather than
     * to let the user find out from a finished film (docs/PLAN.md §4.3). The app does not silently
     * stretch the interval to cover it: that would be the old app's failure, a number on screen that is
     * not the number being shot.
     */
    private void warnIfImpossible() {
        Plan plan = engine.plan();
        if (plan.intervalMs() >= plan.settleMs()) return;
        panel.setEvidence("\u95f4\u9694 " + Steps.shortMs(plan.intervalMs())                // 间隔
                + " \u6bd4\u7a33\u5b9a\u5ef6\u65f6 " + Steps.shortMs(plan.settleMs())        // 比稳定延时
                + " \u77ed\uff1a\u76f8\u673a\u53ef\u80fd\u8ddf\u4e0d\u4e0a");                 // 短：相机可能跟不上
    }

    /**
     * The only place the engine is stepped, and the only place timers are posted.
     *
     * <p>It carries out whatever single {@code ACT_*} the engine returns, and nothing else — which is
     * how a lost frame stops the run instead of being papered over.
     */
    private void pump() {
        int action = engine.step(Engine.EV_TICK, now());
        switch (action) {
            case Engine.ACT_ARM_TIMER:
                armTimer();
                break;
            case Engine.ACT_FIRE:
                fire();
                break;
            default:
                handler.removeCallbacks(onTick);
                break;
        }
        panel.invalidate();
    }

    /** Post the timer for the moment the next frame is due, cancelling whatever was pending. */
    private void armTimer() {
        handler.removeCallbacks(onTick);
        long remaining = engine.remainingMs(now());
        if (remaining == Schedule.NONE) return;
        handler.postDelayed(onTick, Math.max(0, remaining));
    }

    private final Runnable onTick = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };

    /**
     * Hand one frame to the camera.
     *
     * <p>Nothing slow happens in here, and that is the whole point of the way this is now arranged.
     * The frame's due moment is a fact about the clock, and everything between that moment and the
     * shutter call is time the frame is late by. A directory walk over a card holding a few hundred
     * files takes long enough to be visible in the finished film — two of them per frame, on the UI
     * thread, put the app's own bookkeeping in charge of the interval. The card is read on a worker
     * thread instead, after the frame's file has had time to appear, and the count used here is the
     * last one that came back.
     *
     * <p>A dry run takes the same path through the engine and never touches the camera, so the whole
     * panel and state machine can be exercised on a body whose camera will not open.
     */
    private void fire() {
        if (panel.isDryRun()) {
            engine.step(Engine.EV_SHUTTER, now());
            pump();
            return;
        }
        if (!engine.canFire()) return;
        firedAtMs = now();

        if (!camera.isOpen() && !camera.open()) {
            engine.stop(Engine.FAULT_CAMERA, now());
            panel.setCameraNote("\u76f8\u673a\u4e0d\u53ef\u7528\uff1a" + camera.lastError());   // 相机不可用
            panel.invalidate();
            return;
        }
        if (!camera.fire()) {
            // The call itself was refused. What the platform said goes on the evidence line, which
            // keeps the first failure it is told about: "快门调用失败" on its own is not a diagnosis,
            // and the fault that follows is not "the frame was not written" — this app knows nothing
            // about the card when the shutter never fired.
            String why = camera.lastError();
            panel.setEvidence("\u5feb\u95e8\u8c03\u7528\u5931\u8d25\uff1a" + why);       // 快门调用失败：
            fireAttempts++;
            if (fireAttempts < FIRE_ATTEMPTS) {
                // Close whatever the camera still thinks it is doing before asking again: a capture
                // left open is the usual reason for the refusal, and the retry is worth nothing if the
                // thing that caused it is still in flight.
                camera.cancel();
                handler.postDelayed(onRetryFire, FIRE_RETRY_MS);
                panel.invalidate();
                return;
            }
            fireAttempts = 0;
            panel.setCameraNote("\u5feb\u95e8\u8c03\u7528\u5931\u8d25\uff1a" + why);     // 快门调用失败：
            engine.onCallFailed(why, now());
            pump();
            return;
        }
        fireAttempts = 0;
        handler.removeCallbacks(onCallbackTimeout);
        handler.postDelayed(onCallbackTimeout, Frames.callbackTimeoutMs(engine.plan()));
    }

    /**
     * Another go at the frame the platform refused a moment ago.
     *
     * <p>Straight back into {@link #fire()}, which is safe because a refused call never advanced the
     * machine: the frame is still the frame that was due.
     */
    private final Runnable onRetryFire = new Runnable() {
        @Override
        public void run() {
            fire();
        }
    };

    /**
     * The camera never reported the frame.
     *
     * <p>It still counts as taken — the call was accepted, and the card is what decides whether
     * anything was written — but the machine has to let go of the frame, or the next one cannot be
     * fired at its own due moment and one missing callback would cost the run its cadence.
     */
    private final Runnable onCallbackTimeout = new Runnable() {
        @Override
        public void run() {
            if (engine.state() != Engine.EXPOSING) return;
            frameTaken(false);
        }
    };

    /**
     * The shutter callback: the body says a frame was taken.
     *
     * <p>It reports a frame, and that is all it is taken for. The card has the last word, later, on a
     * worker thread — a camera that reports a frame it never wrote cannot make the app claim it was
     * written.
     */
    private final SonyCamera.ShutterEvents onShutter = new SonyCamera.ShutterEvents() {
        @Override
        public void onShutter() {
            // The platform calls this from its own thread; the engine, the panel and the counters all
            // belong to the UI thread. Hopping across also serialises the callback against the timeout,
            // so the two cannot both count the same frame.
            handler.post(onFrameTaken);
        }
    };

    private final Runnable onFrameTaken = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(onCallbackTimeout);
            if (engine.state() != Engine.EXPOSING) return;      // the timeout got there first
            frameTaken(true);
        }
    };

    /**
     * End the frame: count it, arm the next one, and leave the card to answer later.
     *
     * <p>Two things happen in a fixed order, and both are about time. The capture is cancelled first,
     * because {@code burstableTakePicture} holds the shutter open until {@code cancelTakePicture}
     * closes it and the platform refuses the next call while one is in flight — the one thing the old
     * app got exactly right and this one left out (TimeLapse {@code ShootActivity.java:364}). Then the
     * next frame is armed <em>before</em> any bookkeeping, so whatever the panel or the card does next
     * cannot make the following frame late.
     *
     * @param reported whether the camera's own callback brought us here, rather than the timeout.
     */
    private void frameTaken(boolean reported) {
        camera.cancel();
        if (reported) {
            callbacks++;
            if (firedAtMs > 0) callbackMsTotal += now() - firedAtMs;
        }
        // Taken, and not yet accounted for on the card. This is the state every frame starts in: the
        // count that proves it is written comes back from the worker thread a good part of an interval
        // from now, because a file that is written after the shutter is not there the instant the
        // camera reports the frame.
        engine.step(Engine.EV_UNVERIFIED, now());
        pump();
        handler.removeCallbacks(onVerdict);
        handler.postDelayed(onVerdict, Frames.verdictDelayMs(engine.plan()));
    }

    // ================================================================ the card, off the UI thread

    /**
     * Walk the card on a worker thread and come back to the UI thread with the answer.
     *
     * <p>The walk is the app's only slow operation: a few hundred {@code stat} calls through the
     * camera's storage stack. On the UI thread it delays whatever is waiting behind it — including the
     * timer for the next frame — so it does not run there. Two walks cannot overlap; a card slow enough
     * to still be walking when the next one is due simply gets read less often, and the frames stay
     * unaccounted for until it answers, which is the truth about them.
     */
    private void walkCard() {
        if (walking) return;
        walking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long beganMs = now();
                card.refresh();
                walkMs = now() - beganMs;
                // The hand-off. Card is written by this thread and read by the UI thread only after
                // this post: the message queue is the memory barrier between the two.
                handler.post(onCardRead);
            }
        }, "card-walk").start();
    }

    private final Runnable onCardRead = new Runnable() {
        @Override
        public void run() {
            walking = false;
            // Publish what was read, so nothing outside this handler ever touches the Card object.
            cardCount = card.count();
            cardReadable = card.readable();
            cardNewest = card.newestName();
            cardNewestStamp = card.newestStamp();
            cardNewestPath = card.newestPath();
            cardNewestBytes = card.newestBytes();
            cardRoots = card.rootsReport();

            if (cardCount != Frames.UNKNOWN) sawCard = true;
            if (cardNewestBytes > 0) measuredFrameBytes = cardNewestBytes;
            if (!cardReadable && sawCard && engine.running()) {
                // Readable and then not: retrying cannot help, and the frames from here on have no
                // witness at all. This is the one card condition that stops a run (docs/DECISIONS.md
                // A18).
                engine.stop(Engine.FAULT_CARD, now());
                panel.setEvidence("\u5b58\u50a8\u5361\u8bfb\u4e0d\u5230\u4e86");              // 存储卡读不到了
            }
            accountForFrames();
            if (lastGained > 0) engine.setLastFile(cardNewest);
            pushCardLines();
            panel.invalidate();
        }
    };

    /**
     * Let the card answer for the frames it has not answered for yet.
     *
     * <p>The count moved by so many files, and each frame of this plan writes a known number of them,
     * so that many outstanding frames are accounted for. Nothing is ever un-accounted: a card that
     * gains nothing leaves the number where it is, which is what "this app cannot see your files"
     * should look like on screen.
     */
    private void accountForFrames() {
        if (cardCount == Frames.UNKNOWN) return;
        if (lastWalkCount == Frames.UNKNOWN) {
            lastWalkCount = cardCount;
            return;
        }
        int gained = cardCount - lastWalkCount;
        lastWalkCount = cardCount;
        lastGained = gained;
        int accounted = Frames.framesAccounted(gained, engine.plan());
        int open = engine.unverified();
        if (accounted > open) accounted = open;
        for (int i = 0; i < accounted; i++) engine.accountFor();
    }

    /** The card's own lines: where it was read, what it holds, and what it just did. */
    private void pushCardLines() {
        panel.setRoots("\u5361\u6839 " + (cardRoots.length() == 0 ? "\u65e0" : cardRoots)   // 卡根 / 无
                + "  " + newestLine());
        panel.setNumbers("\u5361\u589e " + lastGained + " \u6587\u4ef6"                     // 卡增
                + "  \u6838\u5b9e " + confirmed()                                           // 核实
                + "  \u843d\u76d8 " + engine.landed()                                       // 落盘
                + "  \u672a\u6838\u5b9e " + engine.unverified()                             // 未核实
                + "  \u5361 " + (cardCount == Frames.UNKNOWN ? "?" : String.valueOf(cardCount))
                + "  \u8bfb\u6570 " + walkMs + "ms"                                         // 读数
                + "  \u56de\u6267 " + callbacks + "/" + engine.landed()                      // 回执
                + (callbacks > 0 ? " +" + (callbackMsTotal / callbacks) + "ms" : ""));
    }

    /** Frames the card has answered for: taken, and no longer outstanding. */
    private int confirmed() {
        int open = engine.unverified();
        int landed = engine.landed();
        return landed > open ? landed - open : 0;
    }

    /**
     * The newest file on the card, and how long ago it was written.
     *
     * <p>This is the line that answers "is the camera writing at all": the name changes on every frame
     * and the age stays in seconds when it is. A name that does not move, or an age in minutes, means
     * the camera is writing somewhere this app is not looking — or not writing at all.
     */
    private String newestLine() {
        if (cardNewestStamp <= 0) return "\u6700\u65b0 \u65e0";                      // 最新 无
        // The path from DCIM down, not just the name: whether the camera files into a roll directory or
        // drops the file straight into DCIM is the difference between "this app was looking in the
        // wrong place" and "the camera writes somewhere else entirely", and only the path says which.
        String where = cardNewestPath;
        int dcim = where.indexOf("/DCIM/");
        if (dcim >= 0) where = where.substring(dcim + 1);
        return "\u6700\u65b0 " + where + " "
                + Hud.age(System.currentTimeMillis() - cardNewestStamp) + "\u524d";  // 最新 <路径> <n>前
    }

    /**
     * When the card is read for the frame just taken: once per frame, late in the interval.
     *
     * <p>Not immediately. The file is written after the shutter, so a walk taken the instant the camera
     * reports the frame finds nothing and would call a perfectly good frame unwritten — which is what
     * it did, on every frame, before this was moved.
     */
    private final Runnable onVerdict = new Runnable() {
        @Override
        public void run() {
            walkCard();
        }
    };

    // ================================================================ device status

    private final Runnable onDevice = new Runnable() {
        @Override
        public void run() {
            pushDeviceStatus();
            handler.postDelayed(this, DEVICE_MS);
        }
    };

    private void pushDeviceStatus() {
        long perFrame = measuredFrameBytes;
        if (perFrame <= 0) perFrame = cardNewestBytes;
        if (perFrame <= 0) perFrame = FALLBACK_FRAME_BYTES;
        panel.setDeviceStatus(Device.batteryPercent(this), Device.charging(this),
                Device.freeBytes(), perFrame);
    }

    private long now() {
        // Monotonic: it does not jump when the camera syncs its clock.
        return SystemClock.elapsedRealtime();
    }

    private int color(int res) {
        return getResources().getColor(res);
    }

    // ================================================================ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        int repeat = event.getRepeatCount();

        // Swallowed so the firmware never sees them: AEL, C1, DISP and PLAY are not bound, and letting
        // them through is how a camera's own menu interrupts a run.
        if (Keys.swallowed(scan)) return true;

        switch (scan) {
            case Keys.K_WHEEL_CW:
            case Keys.K_DIAL_CW:
                panel.turn(1);
                return true;
            case Keys.K_WHEEL_CCW:
            case Keys.K_DIAL_CCW:
                panel.turn(-1);
                return true;
            case Keys.K_UP:
                panel.move(-1);
                return true;
            case Keys.K_DOWN:
                panel.move(1);
                return true;
            case Keys.K_LEFT:
                panel.adjust(-1);
                return true;
            case Keys.K_RIGHT:
                panel.adjust(1);
                return true;
            case Keys.K_DEMO:
                // The zoom lever, unbound everywhere else, is the dry-run switch: the one way to see
                // the whole panel on a body whose camera will not open.
                if (repeat == 0) {
                    panel.setDryRun(!panel.isDryRun());
                    panel.setCameraNote(panel.isDryRun()
                            ? "\u6f14\u7ec3\u6a21\u5f0f\uff1a\u4e0d\u52a8\u76f8\u673a"                  // 演练模式：不动相机
                            : "\u6f14\u7ec3\u6a21\u5f0f\u5df2\u5173");                                  // 演练模式已关
                }
                return true;
            case Keys.K_ENTER:
                if (centre.down(repeat) == Keys.Hold.ARM) handler.postDelayed(onCentreHold, Keys.HOLD_MS);
                return true;
            case Keys.K_DELETE:
            case Keys.K_SK2:
                if (trash.down(repeat) == Keys.Hold.ARM) handler.postDelayed(onTrashHold, Keys.HOLD_MS);
                return true;
            case Keys.K_MENU:
            case Keys.K_SK1:
                if (menu.down(repeat) == Keys.Hold.ARM) handler.postDelayed(onMenuHold, Keys.HOLD_MS);
                return true;
            default:
                // Unknown keys are swallowed too. A run must never depend on what the firmware does
                // with a key this app does not model.
                return true;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        if (Keys.swallowed(scan)) return true;

        switch (scan) {
            case Keys.K_ENTER:
                handler.removeCallbacks(onCentreHold);
                if (centre.up() == Keys.Hold.SHORT) {
                    if (engine.state() == Engine.IDLE) {
                        applyPlanToCamera();
                        warnIfImpossible();
                    }
                    engine.step(Engine.EV_ENTER, now());
                    panel.invalidate();
                    pump();
                }
                return true;
            case Keys.K_DELETE:
            case Keys.K_SK2:
                handler.removeCallbacks(onTrashHold);
                if (trash.up() == Keys.Hold.SHORT) {
                    panel.trash(now());
                    if (engine.state() == Engine.PAUSED) walkCard();
                }
                return true;
            case Keys.K_MENU:
            case Keys.K_SK1:
                handler.removeCallbacks(onMenuHold);
                // MENU leaves the app; a hold is the app menu, still to come, and the fired hold
                // swallows the release so leaving and opening cannot both happen.
                if (menu.up() == Keys.Hold.SHORT) finish();
                return true;
            default:
                return true;
        }
    }

    /** Centre held: mark this frame. Not bound yet, and saying so beats pretending. */
    private final Runnable onCentreHold = new Runnable() {
        @Override
        public void run() {
            centre.fire();
            panel.setCameraNote("\u6807\u8bb0\uff1a\u5c1a\u672a\u5b9e\u73b0");   // 标记：尚未实现
        }
    };

    /**
     * TRASH held: stop the run.
     *
     * <p>The confirm prompt is not drawn yet, so the stop is reported rather than silent — the old app
     * had no way to stop at all: MENU or TRASH ended the app and lost the run with nothing asked
     * (docs/PAIN-POINTS.md U-01).
     */
    private final Runnable onTrashHold = new Runnable() {
        @Override
        public void run() {
            if (trash.fire() != Keys.Hold.HOLD) return;
            if (!engine.running()) return;
            engine.stop(Engine.FAULT_ABORTED, now());
            handler.removeCallbacks(onTick);
            handler.removeCallbacks(onCallbackTimeout);
            handler.removeCallbacks(onRetryFire);
            handler.removeCallbacks(onVerdict);
            panel.setCameraNote("\u5df2\u505c\u6b62\uff1a\u5df2\u62cd " + engine.landed() + " \u5f20");  // 已停止：已拍 N 张
            panel.invalidate();
        }
    };

    /** MENU held: the app menu, which does not exist yet. */
    private final Runnable onMenuHold = new Runnable() {
        @Override
        public void run() {
            menu.fire();
            panel.setCameraNote("\u83dc\u5355\uff1a\u5c1a\u672a\u5b9e\u73b0");   // 菜单：尚未实现
        }
    };
}
