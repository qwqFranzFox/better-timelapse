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

    private long measuredFrameBytes;

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new Engine(new Plan());
        // No font yet: the panel draws its first frame with whatever the camera has, so a font that
        // is slow, rejected or absent can never hold the screen black. The bundled font is loaded on
        // its own turn, right after that first frame, and the panel redraws when it arrives.
        panel = new PanelView(this, engine, null,
                color(R.color.background), color(R.color.text), color(R.color.dim),
                color(R.color.selected), color(R.color.accent), color(R.color.warn),
                color(R.color.good));
        setContentView(panel);
        // A font that did not load means boxes on screen. Saying so beats a silent catch: it is the
        // difference between "the app cannot draw Chinese" and "the app is broken".
        // Which font route worked, or why none did. One short line that separates "the app cannot
        // draw Chinese" from "the app is broken", and costs nothing when all is well.
        if (UiFont.route().length() == 0) {
            panel.setCameraNote("\u5b57\u4f53\u672a\u52a0\u8f7d\uff1a" + UiFont.report()   // 字体未加载
                    + "  " + UiFont.assetListing(this));
        } else {
            panel.setFontNote("\u5b57\u4f53 " + UiFont.route());                                // 字体 <路径>
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(onDevice);
        if (!started) {
            started = true;
            // Behind the first draw: the panel appears immediately, then the camera is opened and the
            // card is read. Neither is needed to draw, and both can take seconds.
            panel.post(onStartup);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(onTick);
        handler.removeCallbacks(onCheckLanding);
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

    /** Everything that costs time, queued behind the first frame. */
    private final Runnable onStartup = new Runnable() {
        @Override
        public void run() {
            loadFont();
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
    private void loadFont() {
        Typeface face = UiFont.load(this);
        if (face != null) {
            panel.setTypeface(face);
            panel.setFontNote("\u5b57\u4f53 " + UiFont.route());                                // 字体 <路径>
        } else {
            // Boxes on screen, and the reason on screen with them. A silent catch here is what made
            // this unfixable from a photograph for two rounds.
            panel.setFontNote("\u5b57\u4f53\u672a\u52a0\u8f7d");                            // 字体未加载
            panel.setCameraNote(UiFont.report() + "  " + UiFont.assetListing(this));
        }
    }

    private final Runnable onFirstCardRead = new Runnable() {
        @Override
        public void run() {
            card.refresh();
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

    /** Push the plan's camera-side options. Each may fail, and each failure is said out loud. */
    private void applyPlanToCamera() {
        Plan plan = engine.plan();
        StringBuilder failed = new StringBuilder();
        if (plan.driveMode() == Plan.DRIVE_SINGLE && !camera.setDriveMode("SINGLE")) failed.append("drive ");
        if (plan.silentShutter() && !camera.setSilentShutter(true)) failed.append("silent ");
        if (plan.autoExposureLock() && !camera.setAutoExposureLock(true)) failed.append("ael ");
        if (failed.length() > 0) {
            panel.setCameraNote("\u672c\u673a\u8eab\u4e0d\u652f\u6301\uff1a" + failed.toString().trim());  // 本机身不支持
        }
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
        cardAtFire = card.count();
        firedAtMs = now();

        if (!camera.isOpen() && !camera.open()) {
            engine.stop(Engine.FAULT_CAMERA, now());
            panel.setCameraNote("\u76f8\u673a\u4e0d\u53ef\u7528\uff1a" + camera.lastError());   // 相机不可用
            panel.invalidate();
            return;
        }
        if (!camera.fire()) {
            panel.setCameraNote("\u5feb\u95e8\u8c03\u7528\u5931\u8d25\uff1a" + camera.lastError());  // 快门调用失败
            engine.onFrameLost("call failed", now());
            pump();
            return;
        }
        handler.removeCallbacks(onCheckLanding);
        handler.postDelayed(onCheckLanding, LAND_CHECK_MS);
    }

    /**
     * The watchdog. It asks the card, not the camera, whether the frame arrived — the distinction the
     * old app never made.
     */
    private final Runnable onCheckLanding = new Runnable() {
        @Override
        public void run() {
            if (engine.state() != Engine.EXPOSING) return;      // the shutter callback got there first
            card.refresh();
            if (Frames.frameArrived(cardAtFire, card.count(), engine.plan())) {
                settleFrame();
                return;
            }
            if (!card.readable()) {
                // A card that was readable when the frame was fired and is not now is a card fault,
                // not a slow write: retrying will not help.
                engine.stop(Engine.FAULT_CARD, now());
                panel.invalidate();
                return;
            }
            if (now() - firedAtMs >= LAND_TIMEOUT_MS) {
                panel.setCameraNote("\u5361\u4e0a\u672a\u51fa\u73b0\u65b0\u6587\u4ef6");       // 卡上未出现新文件
                engine.onFrameLost("no file after " + LAND_TIMEOUT_MS + "ms", now());
                pump();
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
            handler.removeCallbacks(onCheckLanding);
            card.refresh();
            if (card.readable() && !Frames.frameArrived(cardAtFire, card.count(), engine.plan())) {
                // The camera says yes and the card says nothing yet: give the write a moment before
                // believing either of them.
                handler.postDelayed(onCheckLanding, LAND_CHECK_MS);
                return;
            }
            settleFrame();
        }
    };

    /** Record the frame as landed and carry on: the engine decides what happens next. */
    private void settleFrame() {
        if (card.newestStamp() > 0) measuredFrameBytes = card.estimatedFrameBytes();
        engine.setLastFile(card.newestName());
        engine.step(Engine.EV_SHUTTER, now());
        panel.invalidate();
        pump();
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
