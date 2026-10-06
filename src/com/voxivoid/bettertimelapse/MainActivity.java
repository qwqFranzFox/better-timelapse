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

    /** The watchdog period while a frame is in flight. */
    private static final long LAND_CHECK_MS = 400;

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

    /** How long a frame may take to appear on the card before it counts as lost. */
    private static final long LAND_TIMEOUT_MS = Frames.LAND_TIMEOUT_MS;

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

    /** Files on the card when the current frame was fired; the difference is the frame. */
    private int cardAtFire = Frames.UNKNOWN;
    private long firedAtMs;

    /** Attempts at the frame currently due; cleared by a call the camera accepted. */
    private int fireAttempts;

    /**
     * A frame the camera reported whose file the card has not accounted for yet.
     *
     * <p>One slot, because one frame is in flight at a time: the file it wrote is looked for again when
     * the next frame fires. A card slower than that is reported as unconfirmed, which is true.
     */
    private boolean pendingOpen;
    private int pendingAt = Frames.UNKNOWN;

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
        handler.removeCallbacks(onCheckLanding);
        handler.removeCallbacks(onRetryFire);
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
            card.refresh();
            pushDeviceStatus();
            // Which roots hold the camera's files, and how many each holds. Read once, kept on screen:
            // "the app cannot see the card" is a statement that needs the list behind it.
            panel.setRoots("\u5361\u6839 " + card.rootsReport());                              // 卡根
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
     * Hand one frame to the camera, then watch the card for it.
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
        card.refresh();
        int nowCount = card.count();
        // Settle the previous frame's open question first: its file has had a whole interval to appear,
        // which is longer than the window the watchdog can afford without holding up the next frame.
        // The immediate check below is made a fraction of a second after the shutter callback, when a
        // card that takes a second to write has not written anything yet — so "the count did not move"
        // there means "not yet", and this is where "yet" is answered.
        if (pendingOpen) {
            pendingOpen = false;
            if (Frames.frameArrived(pendingAt, nowCount, engine.plan())) engine.accountFor();
        }
        cardAtFire = nowCount;
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
        handler.removeCallbacks(onCheckLanding);
        handler.postDelayed(onCheckLanding, LAND_CHECK_MS);
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
     * The watchdog: how the card is consulted, and what happens when it cannot be.
     *
     * <p>The camera's shutter callback is the primary evidence that a frame was taken. The card count
     * is corroboration, and corroboration that is <em>missing</em> is not evidence of absence: this
     * app cannot always see the camera's files at all, and treating "the count did not move" as "the
     * frame was lost" told the user every single frame had failed to write while the frames were
     * arriving perfectly well. A missing measurement was read as a negative one.
     *
     * <p>So three outcomes, and they are different things:
     *
     * <ul>
     *   <li>the count moved — the frame is confirmed;</li>
     *   <li>the count is unavailable — the frame counts as taken and is reported as unconfirmed;</li>
     *   <li>the card went from readable to unreadable — a fault, because retrying cannot help.</li>
     * </ul>
     *
     * <p>Every outcome goes through {@link #settleFrame}, so the verdict and the numbers behind it are
     * written in one place. Splitting them meant the watchdog's verdict never reached the numbers line,
     * and the screen showed a stale one from the last shutter call instead.
     */
    private final Runnable onCheckLanding = new Runnable() {
        @Override
        public void run() {
            if (engine.state() != Engine.EXPOSING) return;      // the shutter callback got there first
            card.refresh();
            if (Frames.frameArrived(cardAtFire, card.count(), engine.plan())) {
                settleFrame(true);
                return;
            }
            if (cardAtFire == Frames.UNKNOWN || !card.readable()) {
                // The app cannot see the camera's files at all, so it has no evidence either way. That
                // is not a reason to fail the run: the frame counts as taken and unconfirmed, which is
                // what it is. Failing here told the user nothing except that the app gave up.
                settleFrame(false);
                return;
            }
            if (now() - firedAtMs >= LAND_TIMEOUT_MS) {
                // The file never showed up in the time a write takes. The camera did report the frame,
                // so it counts — as unconfirmed, which is what it is.
                settleFrame(false);
                return;
            }
            handler.postDelayed(this, LAND_CHECK_MS);
        }
    };

    /**
     * The shutter callback: the body says a frame was taken.
     *
     * <p>It does not advance the run on its own. The card is read first, so a camera that reports a
     * frame it never wrote cannot make the app count it.
     */
    private final SonyCamera.ShutterEvents onShutter = new SonyCamera.ShutterEvents() {
        @Override
        public void onShutter() {
            // The platform calls this from its own thread, and the engine, the card walk and the panel
            // all belong to the UI thread. Hopping across also serialises the callback against the
            // watchdog — the pending check is cancelled and the frame settled as one step — so the two
            // cannot both count the same frame.
            handler.post(onFrameTaken);
        }
    };

    /** The frame the camera reported, settled on the UI thread. */
    private final Runnable onFrameTaken = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(onCheckLanding);
            if (engine.state() != Engine.EXPOSING) return;      // the watchdog got there first
            // The camera reported the frame; that is enough to count it. The card is read to confirm,
            // never to contradict: a count this app cannot see is not a missing frame.
            card.refresh();
            settleFrame(Frames.frameArrived(cardAtFire, card.count(), engine.plan()));
        }
    };

    /**
     * Record the frame as landed and carry on: the engine decides what happens next.
     *
     * @param confirmed whether the card count corroborated the frame. Unconfirmed is not failed, and
     *                  the engine keeps the two apart so the panel can say "taken, not confirmed"
     *                  rather than claiming a loss it cannot see.
     */
    private void settleFrame(boolean confirmed) {
        // End the capture before anything else. `burstableTakePicture` holds the shutter open — the
        // name is literal — and the platform refuses the next call while one is in flight. This is the
        // one thing the old app got exactly right and this one left out: TimeLapse calls
        // `cancelTakePicture()` from its own shutter callback on every frame
        // (TimeLapse ShootActivity.java:364). Without it the first frame is taken, and every call after
        // it is refused — which is precisely the run that stopped after one photograph.
        camera.cancel();
        if (confirmed) {
            if (card.newestStamp() > 0) measuredFrameBytes = card.estimatedFrameBytes();
            engine.setLastFile(card.newestName());
            engine.step(Engine.EV_SHUTTER, now());
            pendingOpen = false;
        } else {
            engine.step(Engine.EV_UNVERIFIED, now());
            // Left open on purpose: the count is taken again when the next frame fires, and this frame
            // stops being "unconfirmed" the moment its file turns up.
            pendingOpen = true;
            pendingAt = cardAtFire;
        }
        // Raw numbers, not a verdict: how many files the card gained against what one frame of this
        // plan is expected to produce, and where the newest file on the card actually is. "One press,
        // N frames lost" is not diagnosable without them, and a count that never moves is either a
        // camera writing somewhere else or a walk looking in the wrong place — the path tells those two
        // apart, which no number of re-runs of the same build would have.
        card.refresh();
        panel.setRoots("\u5361\u6839 " + card.rootsReport() + "  " + newestLine());            // 卡根
        int gained = Frames.arrived(cardAtFire, card.count());
        panel.setNumbers("\u5361\u589e " + gained + " \u6587\u4ef6"
                + "  \u671f\u671b " + Frames.filesPerFrame(engine.plan())
                + "  \u843d\u76d8 " + engine.landed()
                + "  \u672a\u6838\u5b9e " + engine.unverified()
                + "  \u5361 " + (card.count() == Frames.UNKNOWN ? "?" : String.valueOf(card.count())));
        panel.invalidate();
        pump();
    }

    /**
     * The newest file on the card, and how long ago it was written.
     *
     * <p>This is the line that answers "is the camera writing at all": the name changes on every frame
     * and the age stays in seconds when it is. A name that does not move, or an age in minutes, means
     * the camera is writing somewhere this app is not looking — or not writing at all.
     */
    private String newestLine() {
        if (card.newestStamp() <= 0) return "\u6700\u65b0 \u65e0";                    // 最新 无
        // The path from DCIM down, not just the name: whether the camera files into a roll directory or
        // drops the file straight into DCIM is the difference between "this app was looking in the
        // wrong place" and "the camera writes somewhere else entirely", and only the path says which.
        String where = card.newestPath();
        int dcim = where.indexOf("/DCIM/");
        if (dcim >= 0) where = where.substring(dcim + 1);
        return "\u6700\u65b0 " + where + " "
                + Hud.age(System.currentTimeMillis() - card.newestStamp()) + "\u524d";  // 最新 <路径> <n>前
    }

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
        if (perFrame <= 0) perFrame = card.estimatedFrameBytes();
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
                    if (engine.state() == Engine.IDLE) applyPlanToCamera();
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
                    if (engine.state() == Engine.PAUSED) card.refresh();
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
            handler.removeCallbacks(onCheckLanding);
            handler.removeCallbacks(onRetryFire);
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
