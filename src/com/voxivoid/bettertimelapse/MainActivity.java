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
public class MainActivity extends Activity implements android.view.SurfaceHolder.Callback {

    /** How often the device status (battery, card) is re-read. Both change over minutes. */
    private static final long DEVICE_MS = 5000;

    /** The wait before the first retry of a refused press, and the longest that wait may grow to. */
    private static final long FIRE_RETRY_MS = 500, FIRE_RETRY_MAX_MS = 4000;

    /**
     * The most attempts one frame gets before it is counted lost.
     *
     * <p>Twelve, with the wait doubling from 0.5 s to 4 s: about thirty-five seconds of trying. That is
     * long enough to ride out a long exposure on an interval that can afford it — a body that needs twelve
     * seconds will be asked five or six times, not five hundred — and short enough that a body which is
     * genuinely stuck ends the run rather than holding a dark screen until dawn.
     */
    private static final int FIRE_ATTEMPTS_MAX = 12;

    /** Fallback frame size for the card estimate, until one can be measured off the card. */
    private static final long FALLBACK_FRAME_BYTES = 6L * 1024 * 1024;

    /**
     * The preview surface, behind the panel and never seen.
     *
     * <p>This app draws its whole interface on a Canvas, and a capture on this platform needs a live
     * preview stream — Recipe Lab keeps one for the same reason. It sits behind the opaque panel, so it
     * costs nothing to look at and everything to leave out.
     */
    private android.view.SurfaceView preview;

    private final Handler handler = new Handler();
    private final Keys.Hold trash = new Keys.Hold();
    private final Keys.Hold centre = new Keys.Hold();
    private final Keys.Hold menu = new Keys.Hold();
    private final Keys.Hold down = new Keys.Hold();
    private final Keys.Hold up = new Keys.Hold();
    private final SonyCamera camera = new SonyCamera();
    private final Card card = new Card();

    /** The camera's display output, for the 关屏 row. The only thing in this app that touches the LCD. */
    private final Screen screen = new Screen();

    private Engine engine;
    private PanelView panel;

    /** Set once the startup work has been queued, so it happens on the first resume only. */
    private boolean started;

    /** The user's own silent-shutter answer, while rehearsal is borrowing the row. */
    private int silentBeforeRehearsal;

    /** Whether the user answered it themselves while rehearsal was on: their answer wins on the way out. */
    private boolean silentChosenInRehearsal;

    /** Whether a run was in progress when the app was switched away from. */
    private boolean runInterrupted;

    /** Whether this run has already been told that most of its frames are not reaching the card. */
    private boolean shortfallSaid;

    /** The press window the current frame was given, and the body's callback for it, for the log. */
    private long pressWindowUsedMs;

    /** How late the current frame was against its own moment, and which moment that was. */
    private long lateAtFireMs;
    private int lateMomentIndex;
    private long lastCallbackMs = Frames.UNKNOWN;

    /** When the current run started, on the monotonic clock: every {@code at=} in the log is relative to it. */
    private long runStartMs;

    /** How much of the run's own history the log has already been told. */
    private int loggedSkipped;
    private int loggedState = Engine.IDLE;
    private boolean summaryLogged;

    /** Whether this app is the one holding the exposure lock. AEL cannot be read back, so it is tracked. */
    private boolean aelLockedByUs;

    /**
     * How long the body needs for a frame, learned from the frames it refused (docs/DECISIONS.md A27).
     *
     * <p>It replaces a constant that was measured once with a short exposure: a ten-second exposure is
     * refused on every attempt, and that refusal is the body saying how long it needs.
     */
    private final BodyCycle body = new BodyCycle();

    /** The longest the body's own shutter callback has taken, or 0 when it has never arrived. */
    private long callbackHintMs;

    /**
     * What a frame costs the body, read from the camera rather than assumed.
     *
     * <p>{@link #exposureMs} is the shutter speed the body is set to ({@code shutter-speed-nume / -deno}),
     * and {@link #longExposureNr} doubles it when the body takes its dark frame afterwards. Together they are
     * the fact the minimum interval has been guessing at since the first version — and on this body they
     * were four seconds and on, which is eight seconds per frame against a five-second plan.
     */
    private long exposureMs = Frames.UNKNOWN;
    private boolean longExposureNr;

    /** Attempts at the frame currently due, and the wait before the next one. */
    private long retryDelayMs = FIRE_RETRY_MS;

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
    private String cardRoot = "";

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

    /** How long the first frame took to draw, for the log's first lines. */
    private long startupMs;

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
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        preview = new android.view.SurfaceView(this);
        preview.getHolder().setType(android.view.SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        preview.getHolder().addCallback(this);
        root.addView(preview, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(panel, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setPlanListener(new PanelView.PlanListener() {
            @Override
            public void planEdited(int row) {
                onPlanEdited(row);
            }
        });
        setContentView(root);
        if (pendingFontError.length() > 0) {
            // A font failure is the one boot diagnostic that stays on the screen: every label is a box until
            // it is fixed, and the user cannot read anything else to find out why.
            panel.setFontError(pendingFontError);
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
        } else {
            // Coming back into the foreground a second time: the camera was released on the way out.
            handler.post(onReturn);
        }
    }

    /**
     * Back in front of the camera.
     *
     * <p>Nothing used to happen here, and two things were wrong because of it. A run that was in progress
     * when the app was switched away from could not continue — the camera had been released, the shutter
     * callback was gone and every timer had been cancelled — so the engine sat mid-frame with nothing
     * driving it: a frozen countdown no key could move. It ends now, and says how much it got. And the
     * camera has to be opened and registered again, or the next run shoots without a shutter callback and
     * without a single setting pushed to the body.
     */
    private final Runnable onReturn = new Runnable() {
        @Override
        public void run() {
            if (runInterrupted) {
                runInterrupted = false;
                int got = engine.landed();
                log(LogLines.fault("interrupted", got, engine.skipped(), "app_paused"));
                engine.stop(Engine.FAULT_NONE, now());
                panel.setEvidence("\u88ab\u6253\u65ad\uff1a\u5df2\u62cd " + got + " \u5f20");  // 被打断：已拍 N 张
            }
            if (!camera.isOpen()) openCamera();
            panel.invalidate();
        }
    };

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
        handler.removeCallbacks(onRelease);
        handler.removeCallbacks(onRetryFire);
        handler.removeCallbacks(onVerdict);
        handler.removeCallbacks(onDevice);
        handler.removeCallbacks(onTickRedraw);
        // The panel comes back on the way out, always. The camera's screen belongs to whoever is holding it
        // next, and an app that leaves it dark — with no key of its own left to press — has handed the user a
        // camera that looks broken. This happens even when a run is being interrupted, where the wake is the
        // only part of the state that is not about this app.
        wakePanel();
        handler.removeCallbacks(onPanelOff);
        handler.removeCallbacks(onPanelOffAsk);
        handler.removeCallbacks(onPanelWake);
        // Clear every hold: a release that lands while the app is not looking must not leave a key
        // stuck down, which is the failure that makes a camera app look broken.
        trash.reset();
        centre.reset();
        menu.reset();
        // The camera belongs to the camera when this app is not in front, and releasing it here is
        // also what makes the next open clean.
        // Remembered for onReturn: the run cannot survive the camera being released, and a run that
        // silently stops being driven is worse than one that ends and says so.
        runInterrupted = engine.midRun();
        // Leaving for good? Then the camera goes back the way it was found (docs/DECISIONS.md A10), and the
        // exit is written down: "the app was left mid-something" is a claim a log has to be able to support,
        // and the camera asking to repair its card data after some sessions is what it is there to explain.
        boolean finishing = isFinishing();
        if (finishing) restoreCameraSettings();
        camera.clearShutterListener();
        // No `release()` here any more: it means "let go of the shutter and start the preview again", which is
        // the opposite of what leaving is. `close()` stops the stream, cancels a capture that is still open and
        // then releases the handle, in that order.
        camera.close();
        if (finishing) {
            // Written after the teardown, not before it: the line exists to say what state the camera was left
            // in, and the previous version asked before doing anything, so it reported `preview=running` on
            // every exit -- which was true at the time and useless as evidence.
            log(LogLines.exit(engine.midRun(), engine.landed(), camera.previewing()));
        }
        super.onPause();
    }

    /**
     * The first frame is on screen. Everything slow is queued from here, and the delay it took is
     * shown once, so a slow start is a number instead of a feeling.
     */
    private void onFirstFrame() {
        if (firstFrameDrawn) return;
        firstFrameDrawn = true;
        startupMs = now() - begunAtMs;
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
     * What the camera says about itself, as text.
     *
     * <p>Written to the card when the card will take it, and readable on the panel at any time (hold MENU):
     * the answers are several screens long, this camera has no adb, and it will not let an app write where a
     * computer can read. A photograph of a screen is a poor channel, but it is the one that works.
     */

    /**
     * Try the dump again, if it never reached the card.
     *
     * <p>The old TimeLapse app logged into a directory of its own at the card root, from a PMCA app with the
     * same permissions this one declares — so the path is not the problem and neither is the manifest, which
     * leaves <em>when</em>. Its log was written while a shoot was running, seconds after the app started;
     * this one wrote two seconds after launch, before it had read the card even once. So the attempts are
     * spread out: at open, after the first successful card walk, and when a run begins. Bounded, because a
     * diagnostic that keeps retrying forever is its own kind of noise.
     */

    /** How much of the session log the panel's reader shows. */
    private static final int LOG_TAIL_LINES = 80;

    /** How many times the device dump is tried when the card will not take it. */
    private static final int DUMP_ATTEMPTS_MAX = 4;

    /**
     * Put the body back the way this app found it.
     *
     * <p>The exit rule, promised in docs/DECISIONS.md A10 and never implemented until now: the app sets
     * live parameters while it runs — silent shutter, drive mode, exposure lock — and until this existed
     * it left them there, so what the camera did after the app closed depended on what the last run of the
     * app had asked for. The values pushed back are the ones read off the body when it was opened
     * ({@link #seedPlanFromCamera}), not a snapshot of a previous launch and not this app's defaults.
     *
     * <p>Only rows that are actually different are written, and AEL only if this app is the one that
     * locked it: a body that would not say what it held must not be "corrected" by a guess.
     */
    private void restoreCameraSettings() {
        if (!camera.isOpen()) return;
        // AUTO REVIEW belongs to the body too: detach the control this app installed so the next person gets
        // the camera's own setting back rather than this app's silence.
        camera.releasePictureReview();
        Plan plan = engine.plan();
        int silent = plan.stored(Plan.ROW_SILENT);
        if (plan.get(Plan.ROW_SILENT) != silent) camera.setSilentShutter(silent != 0);
        int drive = plan.stored(Plan.ROW_DRIVE);
        if (plan.get(Plan.ROW_DRIVE) != drive) camera.setDriveMode(Plan.driveWire(drive));
        if (aelLockedByUs) {
            camera.setAutoExposureLock(false);
            aelLockedByUs = false;
        }
        plan.revert();
    }

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
        log("camera " + camera.capabilities().summary(camera.previewing()).replace(' ', '_'));
        // Whether this body can switch its display output is the one question the 关屏 row cannot answer for
        // itself, and it is asked here — once, without switching anything — so the row can say so on screen.
        boolean switchable = screen.supported();
        panel.setPanelSwitchable(switchable);
        log("screen display=" + (switchable ? "switchable" : "no")
                + (screen.problem().length() > 0 ? " problem=" + LogLines.text(screen.problem(), "?") : ""));
        // The body's own picture review, silenced: it shows the frame just taken on the LCD between shots, which
        // for a night run is a photograph flashing at the user and light pointed at the subject. Installed here
        // rather than at a run's start so a session log says what the body accepted even when no run follows.
        boolean quiet = camera.suppressPictureReview();
        log("review " + (quiet ? "silenced=" + LogLines.text(camera.reviewMode(), "?")
                : "not-silenced problem=" + LogLines.text(camera.reviewProblem(), "?")));
        // A different exposure, image quality or card changes what the body needs, and the measurement
        // belongs to the body's state rather than to the app.
        body.reset();
        callbackHintMs = 0;
        seedPlanFromCamera();
        applyPlanToCamera();
    }

    /**
     * Take the body's own answers as the plan's baseline before anything is pushed to it.
     *
     * <p>The rows this app can read back are the silent shutter and the drive mode; for those, "what the
     * camera holds" was until now simply a copy of this app's defaults, which made the panel's 未存 marker
     * and the exit rule meaningless and turned applying the plan into a one-way switch — a setting this app
     * turned on could never be turned off, because the app believed the body already held its default
     * (docs/PAIN-POINTS.md U-07, in a new place).
     */
    private void seedPlanFromCamera() {
        int silent = camera.silentShutterHeld();
        if (silent != Frames.UNKNOWN) engine.plan().seed(Plan.ROW_SILENT, silent);
        int drive = camera.driveModeHeld();
        if (drive != Frames.UNKNOWN) engine.plan().seed(Plan.ROW_DRIVE, drive);
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
        if (!camera.setDriveMode(Plan.driveWire(plan.driveMode()))) failed.append("\u9a71\u52a8 ");  // 驱动

        // Both directions, every time. Sending only the "on" value left the body holding whatever the
        // previous run had set while the row on screen said otherwise — the lie this project exists to
        // avoid. The silent shutter is verifiable (setParameterKey reads the key back), so a failure is
        // reported whichever way it was asked to go.
        if (!camera.setSilentShutter(plan.silentShutter())) failed.append("\u9759\u97f3 ");     // 静音

        // AEL is applied in both directions too, but only the "on" direction is reported: the modifier
        // route cannot prove the off one, and a line that complains about a setting nobody asked for
        // teaches its user to stop reading it.
        boolean aelWanted = plan.autoExposureLock();
        if (aelWanted && camera.setAutoExposureLock(true)) {
            // Remembered so the exit rule can undo exactly this and nothing else: AEL has no read-back, so
            // an app that unlocks on the way out "to be safe" would be clearing a lock the user set with
            // the camera's own AEL button.
            aelLockedByUs = true;
        } else if (aelWanted) {
            failed.append("AEL ");
        } else if (aelLockedByUs && camera.setAutoExposureLock(false)) {
            aelLockedByUs = false;
        }

        // What the body actually holds, read back after the writes above: the difference between "the
        // setting was applied" and "the setting was sent". A file fact now, not a screen line.
        log("settings " + camera.settingsReport().replace(' ', '_'));

        String problem = camera.modifierProblem();
        if (problem.length() > 0) {
            // This one disables every setting above at once, so it goes on the evidence line.
            panel.setEvidence("\u53c2\u6570\u4fee\u9970\u5668\u4e0d\u53ef\u7528\uff1a" + problem);  // 参数修饰器不可用
        } else if (failed.length() > 0) {
            panel.setEvidence("\u672a\u751f\u6548\uff1a" + failed.toString().trim());  // 未生效：
        }
        panel.setCameraNote(camera.capabilities().summary(camera.previewing()));
    }

    /**
     * A row changed on the panel.
     *
     * <p>The interval is the one edit that has to be handled rather than stored. The timetable is
     * absolute — frame n is due at {@code start + n × interval} — so re-gridding every frame still to
     * come from a value the user just turned would move the next frame by minutes, not by one detent.
     * Rebasing puts the grid back on "now" with the new spacing, which is what the user meant by turning
     * the wheel, and the elapsed clock keeps the time already shot (docs/DECISIONS.md A19).
     */
    private void onPlanEdited(int row) {
        if (row == Plan.ROW_SILENT && panel.isDryRun()) silentChosenInRehearsal = true;
        boolean rebased = row == Plan.ROW_INTERVAL && engine.midRun();
        if (rebased) {
            engine.schedule().rebase(now(), engine.plan());
        }
        // Logged either way: turning the interval during a run moves the next frame's moment to now, and a log
        // that stays silent about it cannot explain a frame that fires long after the run started.
        startOverride = false;
        handler.removeCallbacks(onOverrideExpiry);
        log(LogLines.planEdit(row, Hud.rowValue(engine.plan(), row), rebased));
        if (rebased) pump();                            // re-arm for the new spacing
    }

    // ================================================================ the preview surface

    @Override
    public void surfaceCreated(android.view.SurfaceHolder holder) {
        camera.attachPreview(holder);
    }

    @Override
    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
        // The surface is created once and never resized: the panel is the whole screen.
    }

    @Override
    public void surfaceDestroyed(android.view.SurfaceHolder holder) {
        // Nothing to do: onPause releases the camera, which is what owns the stream.
    }

    // ================================================================ the loop

    /**
     * The header of a run, in the log.
     *
     * <p>Written before the first frame so that a run which dies half way is still identifiable: what the body
     * said its frames cost, what the plan asked for, and what the camera looked like at the start. Everything
     * after it is one line per event, appended, so the last line of a file that ends abruptly is the last
     * thing that happened.
     */
    private void logRunStart() {
        Plan plan = engine.plan();
        Session.open(this, LogLines.header());
        // One append for the header rather than eleven: the log is opened, written and closed per line so that
        // a run which dies loses at most its last line, and that rule is about frames, not about a header.
        Session.line(this, LogLines.schema()
                + "\n" + LogLines.body(SonyCamera.property("model.name"), SonyCamera.property("version.platform"),
                        exposureMs, longExposureNr, occupancyMs(),
                        Math.max(body.shortestIntervalMs(plan),
                                Frames.shortestIntervalMs(plan, exposureMs, longExposureNr)))
                + "\n" + LogLines.plan(plan));
        // Where the frames are going, and the count every later walk is measured against.
        //
        // This waits for the first walk when the run was started before that walk came back, which is what
        // happens when the user presses the centre key within the second after the panel first draws. The
        // session log of a 300-frame run says so: `start dir=? count=? free=20374MB bat=71%` for a run whose
        // next line reports a card it can count perfectly well. Neither of those two numbers is a fact about
        // the camera at that instant — there is no such fact yet — and the file is supposed to hold facts.
        if (cardRoot.length() == 0 || cardCount == Frames.UNKNOWN) {
            startLinePending = true;
            startBattery = Device.batteryPercent(this);
        } else {
            logStartLine();
        }
        // The run's own tally, from before its first frame. Kept apart from the walk-to-walk count the verdicts
        // use: the baseline of THIS run, not wherever the last one left the counter.
        cardBaseline = cardCount;
        Session.line(this, "app " + appIdentity()
                + "\n" + "boot firstframe=" + LogLines.ms(startupMs)
                        + " font=" + LogLines.text(UiFont.route(), "?")
                + "\n" + "paths log=" + LogLines.text(Session.path(), "-"));
        if (Session.broken()) {
            // The one thing about the log the user has to know while standing at the camera.
            panel.setLogNote("\u65e5\u5fd7\u5199\u4e0d\u8fdb\uff1a"                     // 日志写不进：
                    + LogLines.text(Session.problem(), "?"));
        }
    }

    /**
     * Which build wrote this log line, straight from the installed package.
     *
     * <p>Not vanity: three rounds of this feature were diagnosed from logs whose build could only be inferred
     * from which fields happened to be present, and one of those inferences was wrong. A file that is the only
     * instrument for a four-hour run has to say what wrote it. The version name carries the commit hash and
     * whether the tree was dirty, so one line identifies the exact source.
     */
    private String appIdentity() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "version=" + LogLines.text(info.versionName, "?")
                    + " code=" + info.versionCode;
        } catch (Throwable t) {
            // A package the platform will not describe is not worth failing a run over.
            return "version=?";
        }
    }

    /**
     * The {@code start} line, now that there is a card to put in it.
     *
     * <p>{@code count} here is the number the run's own accounting treats as its baseline
     * ({@link #lastWalkCount}), which is the same number only when a walk has already come back. Taken at the
     * instant of the run start it is the count of an in-flight walk, and the first verdict would then appear
     * to have gained a frame nothing took.
     */
    private void logStartLine() {
        int baseline = lastWalkCount == Frames.UNKNOWN ? cardCount : lastWalkCount;
        cardBaseline = baseline;
        log(LogLines.start(cardRoot, baseline, Device.freeBytes(
                cardRoot.length() == 0 ? null : cardRoot), startBattery));
    }

    /** Whether the {@code start} line is waiting for the first card walk to come back. */
    private boolean startLinePending;

    /**
     * The card's count when the run began, for the one tally that cannot be mis-attributed.
     *
     * <p>Every verdict compares the card against the previous walk, and a walk can report the same count
     * twice — which loses one frame's credit for good. The count from before the first frame to after the last
     * cannot drift that way, so it is kept separately and put next to the ledger's own number in the summary.
     */
    private int cardBaseline = Frames.UNKNOWN;

    /** What the battery said when the run started: read then, because the line is written later. */
    private int startBattery = Device.UNKNOWN;

    /**
     * Say so, before the run starts, when the plan asks for more than the camera can do.
     *
     * <p>The interval is a promise about the clock, and the shutter itself takes time: the press and the
     * release cannot overlap, and on this body that pair wants {@link Frames#PRESS_MS} — measured on
     * this hardware by Recipe Lab, not guessed. Anything shorter than that cannot be shot here, and the
     * honest thing is to say so before the shutter is pressed rather than to let the user find out from
     * a finished film (docs/PLAN.md §4.3). What this app does <em>not</em> do is stretch the interval
     * quietly to cover it: that is the old app's failure, a number on screen that is not the number
     * being shot.
     */
    /**
     * What is wrong with this plan, or an empty string when nothing is.
     *
     * <p>Two constraints, and the longer one wins: what the shutter and the body need between frames, and what
     * the exposure plus — with noise reduction on — its dark frame occupies the camera for.
     */
    /**
     * Hold the start back once, and say why.
     *
     * <p>The alternative was to forbid the value, and the session log is the argument against it: a run at a
     * 3 s interval lost four frames of five, and its {@code late=} was 2147/1278/1820/909 ms -- the app was
     * blocking its own timer with parameter reads, not being held up by the camera. With that fixed the
     * lateness is 7 ms. And the shortest interval is not a fact of the body alone: part of it is the settle
     * row, which the user chose. Refusing on a number like that would refuse runs that work.
     *
     * <p>So the first press explains and asks; the second goes ahead and the log records that it was decided
     * rather than stumbled into. Any row edit, or ten seconds, takes the question away.
     *
     * @return true when the run may start.
     */
    private boolean startAllowed(String problem, Frames.Preflight preflight) {
        if (problem.length() == 0) {
            log(LogLines.preflight(engine.plan().intervalMs(), preflight.shortestMs, exposureMs,
                    longExposureNr, "ok", "ok"));
            return true;
        }
        if (startOverride) {
            startOverride = false;
            log(LogLines.preflight(engine.plan().intervalMs(), preflight.shortestMs, exposureMs,
                    longExposureNr, "override", problem));
            return true;
        }
        startOverride = true;
        handler.removeCallbacks(onOverrideExpiry);
        handler.postDelayed(onOverrideExpiry, OVERRIDE_MS);
        panel.setEvidence(problem + "  \u518d\u6309\u4e2d\u5fc3\u4ecd\u7167\u62cd");       // 再按中心仍照拍
        log(LogLines.preflight(engine.plan().intervalMs(), preflight.shortestMs, exposureMs,
                longExposureNr, "refused", problem));
        panel.invalidate();
        return false;
    }

    /** Whether a start the preflight refused is waiting for a second press. */
    private boolean startOverride;

    /** How long the question stands before it goes away on its own. */
    private static final long OVERRIDE_MS = 10_000;

    private final Runnable onOverrideExpiry = new Runnable() {
        @Override
        public void run() {
            if (!startOverride) return;
            startOverride = false;
            panel.clearEvidence();
            panel.invalidate();
        }
    };

    /**
     * The only place the engine is stepped, and the only place timers are posted.
     *
     * <p>It carries out whatever single {@code ACT_*} the engine returns, and nothing else — which is
     * how a lost frame stops the run instead of being papered over.
     */
    private void pump() {
        int action = engine.step(Engine.EV_TICK, now());
        logTransitions();
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
        checkShortfall();
    }

    /**
     * Say it, once per run, when most of what was taken never reached the card.
     *
     * <p>This is the alarm the photograph asked for: seventeen frames taken, six accounted for, and the
     * panel showing only the two numbers in small dim text. The old app would have reported seventeen and
     * nothing else (docs/PAIN-POINTS.md U-06). Once per run, because a line that keeps re-announcing itself
     * is a line the user stops reading.
     */
    private void checkShortfall() {
        if (shortfallSaid || !engine.midRun()) return;
        int taken = engine.landed();
        int accounted = confirmed();
        if (!Hud.worthSaying(taken, accounted)) return;
        shortfallSaid = true;
        panel.setEvidence(Hud.shortfallLine(taken, accounted));
        log(LogLines.shortfall(taken, accounted));
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
        // How late this frame is against its own moment — read here, because after the press the schedule has
        // moved on; and the moment's own number, so the log can be lined up with the skips around it.
        long remaining = engine.remainingMs(firedAtMs);
        lateAtFireMs = remaining == Schedule.NONE ? 0 : Math.max(0, -remaining);
        lateMomentIndex = engine.frameIndex() + 1;

        if (!camera.isOpen() && !camera.open()) {
            engine.stop(Engine.FAULT_CAMERA, now());
            panel.setCameraNote("\u76f8\u673a\u4e0d\u53ef\u7528\uff1a" + camera.lastError());   // 相机不可用
            panel.invalidate();
            return;
        }
        if (!camera.fire()) {
            // The call itself was refused, and a refusal is a measurement: the body was still busy at this
            // instant, which is at least one interval after the previous frame started. That is the honest
            // way to learn what this body needs — no exposure time to read, no busy flag to ask, and a
            // constant that is wrong the moment somebody sets a long exposure (docs/DECISIONS.md A27).
            long refusedAtMs = now();
            body.refused(refusedAtMs);
            String why = camera.lastError();
            if (fireAttempts == 0) {
                log(LogLines.refused(engine.fired(), runStartMs(), 1, body.lowerBoundMs(), why));
            }
            panel.setEvidence(body.known()
                    ? "\u5feb\u95e8\u88ab\u62d2\uff1a\u6bcf\u5f20\u81f3\u5c11 "                                  // 快门被拒：每张至少
                            + Steps.shortMs((int) body.lowerBoundMs()) + "\uff0c\u95f4\u9694\u592a\u77ed"     // N，间隔太短
                    : "\u5feb\u95e8\u8c03\u7528\u5931\u8d25\uff1a" + why);                            // 快门调用失败：
            fireAttempts++;
            // Retried while the frame still fits: a body that is busy for longer than the constant the app
            // was built around is exactly the case worth waiting out, and giving up after 1.5 s of trying
            // (which is what this used to do) throws away the frame instead.
            if (fireAttempts < FIRE_ATTEMPTS_MAX && Frames.stillFits(engine.plan(), engine.remainingMs(now()))) {
                // Let go before asking again: a shutter still held is the usual reason for a refusal, and a
                // retry is worth nothing while the thing that caused it is still in flight.
                camera.release();
                handler.postDelayed(onRetryFire, retryDelayMs);
                // Backing off, because a body that needs twelve seconds is not going to be ready in 500 ms
                // and there is no point asking it five hundred times.
                retryDelayMs = Math.min(retryDelayMs * 2, FIRE_RETRY_MAX_MS);
                panel.invalidate();
                return;
            }
            fireAttempts = 0;
            retryDelayMs = FIRE_RETRY_MS;
            panel.setCameraNote("\u5feb\u95e8\u8c03\u7528\u5931\u8d25\uff1a" + why);     // 快门调用失败：
            engine.onCallFailed(why, now());
            pump();
            return;
        }
        fireAttempts = 0;
        retryDelayMs = FIRE_RETRY_MS;
        body.accepted(firedAtMs);
        // The shutter is held, not just pressed: the release is what ends the capture and what the next
        // frame waits for. It is scheduled here, against the frame's own moment, so the press window is
        // as long as this body wants it to be and no longer (docs/DECISIONS.md A20).
        handler.removeCallbacks(onRelease);
        // The longer of the two signals: what the body says its exposure is, and how long its own callback
        // has taken when it sends one. Releasing before the exposure ends risks cutting the frame off.
        panelOffAsks = 0;                       // a new frame: its dark window is armed when it finishes
        pressWindowUsedMs = Frames.pressWindowMs(engine.plan(), Math.max(exposureMs, callbackHintMs));
        handler.postDelayed(onRelease, pressWindowUsedMs);
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
     * Let go of the shutter, and count the frame.
     *
     * <p>This is the frame's end, and it is a timer rather than a callback on purpose. The platform's
     * own idiom is a press and a release ({@code takePicture} / {@code cancelTakePicture}), and a plain
     * {@code takePicture} reports nothing back at all — so the moment the capture has had its time is
     * the honest end of the frame, not the arrival of a callback that may never come. A callback that
     * does arrive is counted, and never waited for.
     */
    private final Runnable onRelease = new Runnable() {
        @Override
        public void run() {
            camera.release();
            frameTaken();
        }
    };

    /**
     * The shutter callback, when the body offers one.
     *
     * <p>Statistics, not a verdict: it says the body noticed a shutter, which is worth counting (the
     * panel shows {@code 回执 n/m}) but is not what decides anything. The card decides, later, on a
     * worker thread.
     */
    private final SonyCamera.ShutterEvents onShutter = new SonyCamera.ShutterEvents() {
        @Override
        public void onShutter() {
            // The platform calls this from its own thread; the counters belong to the UI thread.
            handler.post(onFrameReported);
        }
    };

    private final Runnable onFrameReported = new Runnable() {
        @Override
        public void run() {
            if (engine.state() != Engine.EXPOSING) return;      // already released
            callbacks++;
            if (firedAtMs > 0) {
                long took = now() - firedAtMs;
                lastCallbackMs = took;
                callbackMsTotal += took;
                // The longest one seen, not the mean: a callback that arrives after ten seconds is the body
                // saying its exposure took ten seconds, and the release has to wait for that.
                if (took > callbackHintMs) callbackHintMs = took;
            }
            frameSettled();
        }
    };

    /**
     * End the frame: count it, arm the next one, and leave the card to answer later.
     *
     * <p>The next frame is armed <em>before</em> any bookkeeping, so whatever the panel or the card does
     * next cannot make the following frame late.
     */
    private void frameTaken() {
        // Two index spaces, named: presses are what the app did, moments are what the timetable asked for.
        // The first real log mixed them, and a reader could not tell 4 skips and 1 shot from 5 frames.
        log(LogLines.shot(engine.fired(), lateMomentIndex, runStartMs(),
                lateAtFireMs, pressWindowUsedMs, lastCallbackMs));
        // Taken, and not yet accounted for on the card. This is the state every frame starts in: the
        // count that proves it is written comes back from the worker thread a good part of an interval
        // from now, because a file that is written after the shutter is not there the instant the
        // camera reports the frame.
        engine.step(Engine.EV_UNVERIFIED, now());
        pump();
        if (!engine.plan().cardCheck()) {
            // 卡上核对 is off: no read, and the shutter callback is the whole verdict. The frame is accounted
            // for where it is already known to have happened -- `EV_UNVERIFIED` above moved the engine on, and
            // this closes the ledger for it -- so `核实` stops meaning "a file exists" and starts meaning "the
            // body said it took the frame", which is exactly what the row says it does.
            accountFromCallback();
        }
        handler.removeCallbacks(onVerdict);
        if (engine.plan().cardCheck()) handler.postDelayed(onVerdict, Frames.verdictDelayMs(engine.plan()));
        // The frame is over, so this is the stretch in which the panel has nothing to show. A timer, never a
        // call: switching the display output is a platform call with a surface behind it, and nothing slow may
        // sit between a frame's due moment and its shutter (docs/DECISIONS.md A19).
        frameSettled();
    }

    /**
     * A frame has finished, by whichever of its two endings came first.
     *
     * <p>A frame ends twice: when the release timer fires, and when the body reports the shot through its
     * shutter callback. Either can be first — the callback arrived 276–540 ms after the press in the logs, the
     * release at 2500 ms — and both call this.
     *
     * <p>It exists because the panel switch was first armed only at the release, and the engine was still
     * {@code EXPOSING} at that instant when its own callback had not come back yet. The gate refuses anything
     * that is not a waiting run, so the off was never scheduled at all, and a session log came home with the
     * row saying 每张之后关屏 and not one {@code panel} line in it: the feature reported nothing, did nothing,
     * and looked exactly like a body that cannot switch its display. Nothing here may run while a frame is in
     * flight; that is the invariant, and it is what the flag keeps.
     */
    private void frameSettled() {
        armPanelOff();
        armPanelWake();
    }

    /**
     * Close the ledger for a frame the body has reported, with no card involved.
     *
     * <p>This is the old app's accounting and it is worth naming as such: the app believes the body, and
     * nothing can contradict it. What it costs is the thing this project was built for — a run that takes
     * seventeen frames and keeps six can no longer be seen to have done so (docs/PAIN-FACTS: A9, A28, A40) —
     * which is why it is a row on the panel and not a decision taken here.
     */
    private void accountFromCallback() {
        if (engine.unverified() <= 0) return;
        engine.accountFor();
        if (engine.landed() > 0) {
            log(LogLines.verdict(engine.fired(), Frames.UNKNOWN, 0, true, 0) + " look=callback");
        }
    }

    // ================================================================ the panel between frames

    /**
     * Darken the panel for the wait, and bring it back before the next frame.
     *
     * <p>Three conditions, and each of them is a reason not to do it rather than a detail: the row has to ask
     * for it, the body has to be able to switch its display output, and the plan has to leave room for the
     * switches without eating into the frame (all three in {@link Frames#panelStaysOff}). When any of them is
     * false the panel simply stays lit, which is what a camera did before this feature existed.
     */
    private void armPanelOff() {
        handler.removeCallbacks(onPanelOff);
        handler.removeCallbacks(onPanelOffAsk);
        String why = panelRefusal();
        if (why.length() > 0) {
            // A refusal here is not a decision to leave the panel lit, it is a "not yet". The body reports a
            // frame's ending through a callback that arrives while the engine still counts the frame as in
            // flight -- measured on the camera, twice in one run:
            //
            //     panel action=arm why=in-flight at=+0.578s
            //     panel action=arm why=in-flight at=+5.204s
            //
            // and a feature that gives up on the first "not yet" did nothing at all while its row claimed
            // otherwise. So it asks again, briefly: the frame's own release is at due+2500ms and this can only
            // run in the quiet stretch, so a few attempts a few hundred milliseconds apart are spent well
            // inside the interval.
            if (why.equals("in-flight") && panelOffAsks < PANEL_OFF_ASKS_MAX) {
                panelOffAsks++;
                handler.postDelayed(onPanelOffAsk, PANEL_OFF_ASK_MS);
            } else if (!why.equals("in-flight")) {
                log(LogLines.panelWhy("arm", why, runStartMs()));
            }
            return;
        }
        panelOffAsks = 0;
        handler.postDelayed(onPanelOff, Frames.panelOffDelayMs(engine.plan()));
    }

    /** How many times a frame-in-flight refusal is asked again, and how far apart. */
    private static final int PANEL_OFF_ASKS_MAX = 6;
    private static final long PANEL_OFF_ASK_MS = 250;

    /** How many of those have been used for the frame currently finishing. */
    private int panelOffAsks;

    private final Runnable onPanelOffAsk = new Runnable() {
        @Override
        public void run() {
            armPanelOff();
        }
    };

    /**
     * Bring the panel back before the next frame's moment.
     *
     * <p>Armed against the frame's own due moment and not as a fixed wait, so a frame answered late cannot
     * push its own wake-up late with it — the failure that matters here is a panel still dark when the shutter
     * fires. With no next frame there is no deadline, so the panel comes back at once: a finished run must not
     * leave a black screen and a user who cannot read the count.
     */
    private void armPanelWake() {
        handler.removeCallbacks(onPanelWake);
        if (!panelShouldDarken()) return;
        long due = engine.nextDueMs();
        long wakeAt = Frames.panelWakeAtMs(engine.plan(), now(), due);
        if (wakeAt == Schedule.NONE) {
            // No next frame to come back for. The wake is still what happens -- a finished run must not leave a
            // black screen -- but the log says why the arm had nothing to pair with.
            log(LogLines.panelWhy("wake", "no-frame", runStartMs()));
            wakePanel();
            return;
        }
        handler.postDelayed(onPanelWake, Math.max(0, wakeAt - now()));
    }

    /** Whether the panel may be darkened at all right now. */
    private boolean panelShouldDarken() {
        return panelRefusal().length() == 0;
    }

    /**
     * Why the panel may not be darkened, or an empty string when it may.
     *
     * <p>One function so that the answer and the reason for it cannot disagree, and so that the reason can be
     * written to the log. Every condition here has been the whole bug at least once: the row switched off, a
     * body without the call, an interval with no room for two switches, a frame in flight, a run that is not a
     * run. When this feature does nothing, this string is the answer to "which one was it" — and finding that
     * out from a camera has cost four rounds so far.
     */
    private String panelRefusal() {
        if (!engine.plan().panelOff()) return "row-off";
        if (!screen.supported()) return "unsupported";
        if (!Frames.panelStaysOff(engine.plan())) return "no-room";
        if (screen.isOff()) return "";                      // already dark: only the wake matters
        if (engine.state() != Engine.ARMED && engine.state() != Engine.WAITING) return "in-flight";
        return "";
    }

    private final Runnable onPanelOff = new Runnable() {
        @Override
        public void run() {
            // The gate is the engine's state, never the card: with the check off there is no walk to wait for,
            // and with it on the walk has its own timer. Both darken once the frame has finished -- which is the
            // condition the user described from outside the app, as the screen waiting for the file.
            String late = panelRefusal();
            if (late.length() > 0) {
                // Refused at the last moment, which is a different fact from refused when it was armed: the
                // frame arrived early, or the run was paused while the timer was in the air.
                log(LogLines.panelWhy("off", late, runStartMs()));
                return;
            }
            if (!screen.off()) {
                // A screen that will not switch off is worth saying once, on the evidence line, with the reason
                // — and never worth retrying per frame.
                panel.setEvidence("\u5173\u5c4f\u5931\u8d25\uff1a" + LogLines.text(screen.problem(), "?"));
                panel.invalidate();
                return;
            }
            // The preview stream stops *after* the screen is off, never before, so that stopping it and
            // restarting it are a pair: the old app stops the stream and then switches the output
            // (ShootActivity:67-68), and this app had never stopped a preview outside onPause. Doing it in the
            // other order is what broke a run -- a preview stopped for a panel that never went dark is a camera
            // that will not take another frame, and the log of that run has two shots in it and then a pause.
            // The stream is NOT stopped here any more. It was, for one round, on the theory that a running
            // stream was what kept the panel lit -- and that theory was wrong: the panel-off worked before the
            // stop was added and after it, so stopping bought nothing that was ever measured, while paying for
            // it with a stop/restart pair every interval (about 1440 of them in four hours at 10s). The stream
            // now stays up for the whole run, which is what the old app did: it stopped the preview only for
            // its own display-off, and this is the same display-off.
            log(LogLines.panel("off", screen.working(), screen.wasActiveDevice(), screen.problem()));
        }
    };

    private final Runnable onPanelWake = new Runnable() {
        @Override
        public void run() {
            wakePanel();
        }
    };

    /**
     * Light the panel again, if this app is the one that darkened it.
     *
     * <p>Called from every path that ends the dark window: the frame coming due, a pause, a stop, a fault, a
     * key the user pressed, and the app going to the background. It is deliberately safe to call when the panel
     * was never off, because the cost of a redundant switch is nothing and the cost of a missed one is a camera
     * whose screen is black in the user's hands.
     */
    private void wakePanel() {
        handler.removeCallbacks(onPanelOff);
        handler.removeCallbacks(onPanelOffAsk);
        if (screen.isOff()) {
            if (screen.on()) log(LogLines.panel("on", screen.working(), screen.wasActiveDevice(), screen.problem()));
        }
        // The preview comes back whenever a panel is about to be looked at, and never only when this class
        // believes the screen was dark. Switching a display output can leave the stream stopped, and this body
        // will not take a photograph without a live one -- a failure that arrives as `BurstableTakePicture
        // failed`, with nothing in it mentioning the panel (docs/DECISIONS.md A20). Restarting a preview that
        // never stopped costs nothing worth measuring; not restarting one that did costs the rest of the run.
        boolean wasDown = !camera.previewing();
        camera.resumePreview();
        if (wasDown) {
            log(LogLines.preview(camera.previewing() ? "start" : "start-failed",
                    camera.previewing() ? "before-frame" : camera.previewProblem()));
        }
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
        look(false);
    }

    /**
     * The verdict's look at the card: one directory, not the whole tree.
     *
     * <p>The full walk is what answers "how many images does this card hold", and it costs a few thousand
     * filesystem calls through the camera's storage stack. A verdict only needs to know whether a file
     * appeared. So the cheap look runs on every verdict and the full walk runs when it has said no a few
     * times in a row -- a handful of frames at most -- which is the case it exists for: a card that has
     * stopped growing, or a camera that has moved to a new roll directory.
     */
    private void pollCard() {
        look(true);
    }

    /** How many cheap looks in a row are allowed before the full walk is owed. */
    private static final int POLLS_BEFORE_WALK = 3;

    /** How many have been spent. Reset by any full walk and by any look that found something. */
    private int pollsSinceWalk;

    private void look(final boolean cheap) {
        if (walking) return;
        walking = true;
        final boolean full = !cheap || pollsSinceWalk >= POLLS_BEFORE_WALK;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long beganMs = now();
                boolean found = false;
                if (full) {
                    card.refresh();
                } else {
                    found = card.poll();
                }
                walkCheap = !full;
                walkFound = found;
                walkMs = now() - beganMs;
                // The hand-off. Card is written by this thread and read by the UI thread only after
                // this post: the message queue is the memory barrier between the two.
                handler.post(onCardRead);
            }
        }, "card-walk").start();
    }

    /** Whether the look that just came back was the cheap one, and whether it found a new file. */
    private boolean walkCheap;
    private boolean walkFound;

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
            // The root report is a walk of every candidate root, and on this body all of them resolve to
            // the same tree — five directory walks per frame for a diagnostic that only changes when the
            // root does. It is taken again when that happens, and not otherwise.
            String rootNow = card.rootInUse();
            if (!rootNow.equals(cardRoot)) cardRoot = rootNow;

            if (cardCount != Frames.UNKNOWN) sawCard = true;
            if (cardNewestBytes > 0) measuredFrameBytes = cardNewestBytes;
            if (!cardReadable && sawCard && engine.running()) {
                // Readable and then not: retrying cannot help, and the frames from here on have no
                // witness at all. This is the one card condition that stops a run (docs/DECISIONS.md
                // A18).
                log(LogLines.fault("card", engine.landed(), engine.skipped(), "unreadable"));
                engine.stop(Engine.FAULT_CARD, now());
                panel.setEvidence("\u5b58\u50a8\u5361\u8bfb\u4e0d\u5230\u4e86");              // 存储卡读不到了
            }
            accountForFrames();
            if (engine.state() == Engine.DONE || engine.state() == Engine.FAULT
                    || engine.state() == Engine.PAUSED) {
                // Nothing more can be taken: what the card's total gain can still clear, it clears now, so the
                // block ends with the counts the camera itself supports rather than with the ledger's doubt.
                int late = unattributedCardGain();
                for (int i = 0; i < late; i++) engine.accountFor();
            }
            if (lastGained > 0) engine.setLastFile(cardNewest);
            if (startLinePending && cardRoot.length() > 0 && cardCount != Frames.UNKNOWN) {
                // The run header was written before this walk came back, so the line waits for the card it is
                // about, and the baseline in it is the one this walk just established. Before accountForFrames.
                startLinePending = false;
                logStartLine();
            }
            logCard();
            logSummary();
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
        // A cheap look that found a file earned another cheap look; one that found nothing is what the full
        // walk is for, so the budget is only reset by a full walk or by a hit.
        if (!walkCheap || gained > 0) pollsSinceWalk = 0;
        else pollsSinceWalk++;
        int accounted = Frames.framesAccounted(gained, engine.plan());
        int open = engine.unverified();
        if (accounted > open) accounted = open;
        for (int i = 0; i < accounted; i++) engine.accountFor();
        if (engine.landed() > 0) {
            log(LogLines.verdict(engine.fired(), cardCount, gained, accounted > 0, walkMs)
                    + " look=" + (walkCheap ? "poll" : "walk"));
        }
    }

    /**
     * What the card did, to the log.
     *
     * <p>This was five lines of small text at the bottom of the screen: the root, the newest file and its
     * age, the count, what the walk cost, which frames the card had answered for, how many callbacks the body
     * sent. Every one of those numbers was worth having while they were being hunted down, and every one of
     * them belongs in a file that a reader can be run over rather than in the corner of a screen the user is
     * standing in front of. What the screen keeps is the progress line, which already says 核实 and 未核实 and
     * 跳过 in the place those numbers are actually about.
     */
    private void logCard() {
        Session.line(this, "card root=" + LogLines.text(cardRoot, "?")
                + " count=" + LogLines.count(cardCount)
                + " newest=" + LogLines.text(cardNewest, "-")
                + " gained=" + lastGained
                + " walk=" + LogLines.ms(walkMs)
                + " callbacks=" + callbacks + "/" + engine.landed()
                + (callbacks > 0 ? " cb=" + LogLines.ms(callbackMsTotal / callbacks) : "")
                + " measured=" + LogLines.ms(body.lowerBoundMs()));
    }

    /** Frames the card has answered for: taken, and no longer outstanding. */
    private int confirmed() {
        int open = engine.unverified();
        int landed = engine.landed();
        return landed > open ? landed - open : 0;
    }

    /**
     * Frames the card's total gain says arrived and the ledger never credited, never more than what is
     * outstanding.
     *
     * <p>Only asked once the run cannot advance. A run's own tally is the files the card gained from before
     * the first frame to after the last, and a run of 20 frames came home with that tally reading
     * {@code 647 → 667}: twenty files for twenty frames, against a ledger of {@code landed=20 verified=19
     * missed=1}. The one missing credit was movable, because the card had said so itself — but only in total:
     * one walk reported {@code count=667} twice ({@code gain=0} after a {@code gain=1}), and a walk-to-walk
     * delta has no way to tell a repeated count from a frame that never arrived. The distance between the two
     * tallies does, and it is the two numbers the user cares about — how many frames were taken, and how many
     * files the card gained — so it is the honest thing to close on.
     */
    private int unattributedCardGain() {
        int gain = cardGain();
        if (gain == Frames.UNKNOWN) return 0;
        int unattributed = gain - confirmed();
        if (unattributed <= 0) return 0;
        int outstanding = engine.unverified();
        return Math.min(unattributed, outstanding);
    }

    /**
     * Frames the card can still answer for, over and over: about six seconds of walks at the verdict cadence.
     *
     * <p>The walk is what closes a run's ledger, so it has to be asked more than twice — the 20-frame run's
     * last two walks both reported the same count. But a card that will never answer for a frame again (the
     * app cannot see that part of the card at all) must not turn "shut down cleanly" into a loop, so the
     * attempts are bounded and the run reports what it has.
     */
    private static final int FINAL_WALK_ATTEMPTS = 3;

    /** How many of those have been used on this pause or this run's end. */
    private int finalWalks;

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
            pollCard();
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

    /**
     * The panel's own heartbeat, for the things on it that are about the clock.
     *
     * <p>Everything on the panel used to change only when something happened -- a frame, a key, a card walk --
     * and for a status line of text that was right. A countdown is not text: a number of seconds remaining is a
     * claim about now, and a claim about now drawn only every five seconds is wrong for four of them. The user
     * saw exactly that: the bar and the number disagreed with the clock, because both were a snapshot taken up
     * to five seconds earlier.
     *
     * <p>So while a run is on, and only then, the panel is redrawn four times a second. The work is one canvas
     * redraw of a screen with a handful of text on it; what it buys is that the two things on that screen which
     * describe time describe it now.
     */
    private static final long TICK_MS = 250;

    private final Runnable onTickRedraw = new Runnable() {
        @Override
        public void run() {
            if (!engine.midRun()) return;
            panel.invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void pushDeviceStatus() {
        // Battery and card space are platform calls and always safe; the camera is not always safe to ask.
        if (quietMoment()) refreshExposure();
        long perFrame = measuredFrameBytes;
        if (perFrame <= 0) perFrame = cardNewestBytes;
        if (perFrame <= 0) perFrame = FALLBACK_FRAME_BYTES;
        panel.setDeviceStatus(Device.batteryPercent(this), Device.charging(this),
                Device.freeBytes(cardRoot.length() == 0 ? null : cardRoot), perFrame);
    }

    /**
     * Ask the body how long its frames are, and remember the answer.
     *
     * <p>One read of the parameter set, not four: see {@link SonyCamera.Parameters}. The read is measured and
     * logged, because a session log showed a frame skipped for being two seconds late and the suspect is
     * exactly this call.
     *
     * <p>And it never happens near a frame's own moment. Five seconds is the interval because nothing here
     * changes quickly, and a status line that is a second stale costs nothing while a frame that is a second
     * late costs a hole in the film.
     */
    private void refreshExposure() {
        if (!camera.isOpen()) return;
        SonyCamera.Parameters read = camera.readParameters();
        if (read.exposureMs != Frames.UNKNOWN) exposureMs = read.exposureMs;
        longExposureNr = read.longExposureNr;
        if (read.readMs > PARAMETER_READ_WORTH_SAYING_MS) {
            log(LogLines.parameters(read.readMs));
        }
    }

    /** A parameter read slower than this goes in the log: it is the cost that explains a late frame. */
    private static final long PARAMETER_READ_WORTH_SAYING_MS = 200;

    /**
     * Whether the camera may be asked anything right now.
     *
     * <p>Reading the parameters means copying 298 of them, and the first real log has a frame that was
     * skipped for being 2147 ms late because a read landed on the moment it was due. So the app keeps its
     * hands off the camera for the last stretch before a frame and while one is in flight: status that is a
     * little stale is worth more than a frame that is late.
     */
    private boolean quietMoment() {
        if (!camera.isOpen()) return false;
        if (engine.state() == Engine.EXPOSING) return false;
        long remaining = engine.remainingMs(now());
        return remaining == Schedule.NONE || remaining > QUIET_MS;
    }

    /** How long before a frame's moment the app stops asking the camera anything. */
    private static final long QUIET_MS = 1500;

    /** How long one frame occupies the body: the exposure, doubled when it takes a dark frame too. */
    private long occupancyMs() {
        return Frames.occupancyMs(exposureMs, longExposureNr);
    }

    /**
     * The things the engine decided on its own, into the log: moments it skipped, and the end of the run.
     *
     * <p>These happen inside the engine rather than in answer to a key, so the shell only learns about them by
     * looking — and a run whose log says nothing about the eleven moments it skipped is a run whose holes
     * cannot be explained afterwards.
     */
    private void logTransitions() {
        while (loggedSkipped < engine.skipped()) {
            loggedSkipped++;
            log(LogLines.skip(engine.lastSkipIndex(), runStartMs(), engine.lastSkipLateMs(),
                    engine.plan().intervalMs()));
        }
        int state = engine.state();
        if (state == loggedState) return;
        if (state == Engine.FAULT) {
            log(LogLines.fault(LogLines.faultName(engine.fault()), engine.landed(), engine.skipped(),
                    engine.lastLoss().length() > 0 ? engine.lastLoss() : engine.faultText()));
        } else if (state == Engine.DONE) {
            log(LogLines.end("done", engine.landed(), confirmed(), engine.skipped(), engine.unverified()));
        }
        loggedState = state;
    }

    /**
     * The run's account of itself, once there is nothing left to wait for.
     *
     * <p>The {@code END} line is written the moment the run ends, which can be a second and a half before the
     * card has been asked about its last frame — the first real log said {@code END … verified=0 pending=1}
     * directly above {@code verdict … result=ok}. This is the line that waits, and it is the one worth reading
     * afterwards.
     *
     * <p>A paused run is counted too, and it is the reason {@code outcome=} exists: the session log of a run
     * stopped with TRASH ended at {@code pause at=+13.176s} and nothing else, so a night's shoot carried home
     * that way read exactly like a crash. The count waits for the same thing a finished run's does — the card
     * answering for the last frame — because one taken at the pause would report a frame fewer than the run
     * took. Written once per pause: resuming clears it.
     */
    private void logSummary() {
        if (summaryLogged) return;
        boolean paused = engine.state() == Engine.PAUSED;
        if (!paused && engine.midRun()) return;
        if (!paused && engine.state() != Engine.DONE && engine.state() != Engine.FAULT) return;
        if (engine.unverified() > 0) {
            // The ledger is not closed yet: the card has not answered for the last frame, so a count taken now
            // would report it missing. The walk started here is the one that settles it — or asks for one more,
            // up to the bounded number of them. Without this, a finished 20-frame run was summarised at
            // `verified=19 missed=1` while the card's own count (647 → 667) and 20 of 20 shutter callbacks said
            // nothing was missing.
            if (finalWalks++ < FINAL_WALK_ATTEMPTS) {
                handler.removeCallbacks(onVerdict);
                handler.postDelayed(onVerdict, Frames.verdictDelayMs(engine.plan()));
                return;
            }
        }
        summaryLogged = true;
        String outcome = paused ? "paused" : (engine.state() == Engine.DONE ? "done" : "fault");
        log(LogLines.summary(outcome, engine.landed(), confirmed(), engine.skipped(),
                engine.unverified(), runStartMs(), cardGain(),
                engine.shotTimesMs(), engine.landed()));
    }

    /**
     * Files the card gained over the whole run, or {@link Frames#UNKNOWN} when there is no count to compare
     * against. Not {@code lastGained} totalled up: the baseline and the latest count, nothing in between,
     * because in between is where a repeated count loses a frame's credit.
     */
    private int cardGain() {
        if (cardBaseline == Frames.UNKNOWN || cardCount == Frames.UNKNOWN) return Frames.UNKNOWN;
        return cardCount - cardBaseline;
    }

    /**
     * One line to the session log on the card.
     *
     * <p>The screen shows the run; the file shows the machine. Everything that used to be printed in small
     * dim text at the bottom of the panel — the card's counts, the walk's cost, the callbacks, the keys, the
     * font route, the startup time — goes here instead, where it is read as a file and not photographed.
     *
     * <p>Never between a frame's due moment and its shutter: the timing budget is the whole point of the rest
     * of this class, and a log write is I/O like any other.
     */
    private void log(String line) {
        Session.line(this, line);
    }

    /** Milliseconds since the current run started, for the log's {@code at=} fields. */
    private long runStartMs() {
        return runStartMs == 0 ? 0 : now() - runStartMs;
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

        // While a text is on screen the keys belong to it: the wheel and the arrows turn pages, anything else
        // closes the reader rather than acting on a run the user cannot see.
        if (panel.reading()) {
            switch (scan) {
                case Keys.K_WHEEL_CW:
                case Keys.K_DIAL_CW:
                case Keys.K_RIGHT:
                case Keys.K_DOWN:
                    if (repeat == 0) panel.turnPage(1);
                    return true;
                case Keys.K_WHEEL_CCW:
                case Keys.K_DIAL_CCW:
                case Keys.K_LEFT:
                case Keys.K_UP:
                    if (repeat == 0) panel.turnPage(-1);
                    return true;
                default:
                    panel.closeText();
                    return true;
            }
        }

        // Swallowed so the firmware never sees them: AEL, C1, DISP and PLAY are not bound, and letting
        // them through is how a camera's own menu interrupts a run.
        if (Keys.swallowed(scan)) return true;

        // Any key brings the panel back, before anything is done with it: a user who cannot see the screen
        // cannot see what their key did, and the next thing they will try is another key. Here rather than in
        // each branch, so that no key can be missed — including the ones this app ignores on purpose.
        if (screen.isOff()) wakePanel();

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
                if (up.down(repeat) == Keys.Hold.ARM) handler.postDelayed(onUpHold, Keys.HOLD_MS);
                if (repeat == 0) panel.move(-1);
                return true;
            case Keys.K_DOWN:
                // Held, the down key switches the rehearsal mode on and off. The zoom lever was bound to
                // it first and this body never delivers that key to an app — a mode nobody can reach is
                // a mode that does not exist, and one with a working camera cannot test the timetable
                // without it.
                if (down.down(repeat) == Keys.Hold.ARM) handler.postDelayed(onDownHold, Keys.HOLD_MS);
                if (repeat == 0) panel.move(1);
                return true;
            case Keys.K_LEFT:
                panel.adjust(-1);
                return true;
            case Keys.K_RIGHT:
                panel.adjust(1);
                return true;
            case Keys.K_DEMO:
                // The zoom lever, kept as a second way in. It is a camera control, and the body does not
                // pass it to the app — this is here for a body that does. The down key is the one that
                // works (see onDownHold).
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
            case Keys.K_UP:
                handler.removeCallbacks(onUpHold);
                up.up();
                return true;
            case Keys.K_DOWN:
                handler.removeCallbacks(onDownHold);
                down.up();
                return true;
            case Keys.K_ENTER:
                handler.removeCallbacks(onCentreHold);
                if (centre.up() == Keys.Hold.SHORT) {
                    if (engine.state() == Engine.IDLE) {
                        // The evidence line is about THIS run: it kept the first thing it was ever told, so a
                        // warning that had been fixed stayed on screen through every later run. Photographed
                        // on the camera: 间隔 3s 短于本机身所需的 2.9s, with the interval row reading 3 s.
                        panel.clearEvidence();
                        startOverride = false;
                        handler.removeCallbacks(onOverrideExpiry);
                        shortfallSaid = false;
                                    refreshExposure();
                        applyPlanToCamera();
                        // Held back once when the plan asks for more than this body has: the reason goes on the
                        // screen, the second press goes ahead, and the file records which of the two it was.
                        Frames.Preflight preflight = Frames.preflight(engine.plan(), exposureMs,
                                longExposureNr, body.shortestIntervalMs(engine.plan()));
                        if (!startAllowed(Hud.preflightProblem(engine.plan(), preflight), preflight)) return true;
                        loggedSkipped = 0;
                        loggedState = Engine.IDLE;
                        summaryLogged = false;
                        finalWalks = 0;
                        cardBaseline = cardCount;
                        // The planning is over and the panel is about to be needed for a whole run: if it was
                        // dark, this is where it comes back, before the timetable is armed.
                        wakePanel();
                        // The run's clock and the timetable's origin are the same instant, and both are set
                        // here — before anything slow. They used to be ten lines apart with a log header, a
                        // device dump and a card walk between them, and the session log of a 5-frame run says
                        // what that cost: every frame's `at=` and `late=` agree that the timetable began at
                        // +2.561s while `run start` was written at +0.046s. A timetable that begins 2.5
                        // seconds after the run it belongs to makes the first frame's `late=` a 2.5-second lie
                        // and hands the countdown that much less time. See docs/DECISIONS.md A39.
                        runStartMs = now();
                        handler.removeCallbacks(onTickRedraw);
                        handler.postDelayed(onTickRedraw, TICK_MS);
                        engine.step(Engine.EV_ENTER, runStartMs);
                        // Arm the first frame's timer now, too: the work below is file I/O, and with a plan
                        // whose delay is zero the first moment is due at once.
                        pump();
                        logRunStart();
                    } else {
                        engine.step(Engine.EV_ENTER, now());
                    }
                    log(LogLines.runStart(runStartMs()));
                    panel.invalidate();
                    pump();
                }
                return true;
            case Keys.K_DELETE:
            case Keys.K_SK2:
                handler.removeCallbacks(onTrashHold);
                if (trash.up() == Keys.Hold.SHORT) {
                    int before = engine.state();
                    panel.trash(now());                 // steps the engine: pause, resume, or nothing
                    int after = engine.state();
                    if (after == Engine.PAUSED && before != Engine.PAUSED) {
                        log(LogLines.pause(runStartMs(), "trash", Device.batteryPercent(this)));
                        // Once per pause, and again the next time: the count is the card's, so it is taken
                        // after the walk this starts. See logSummary.
                        summaryLogged = false;
                        finalWalks = 0;
                        walkCard();
                    } else if (before == Engine.PAUSED && after != Engine.PAUSED) {
                        log(LogLines.resume(runStartMs()));
                        summaryLogged = false;
                        finalWalks = 0;
                        // The engine has just rebased the timetable to "now" and asked for its timer to be
                        // armed -- and this used to be the end of it. Nothing drove the engine after the
                        // resume, so it sat in WAITING with no timer behind it and no frame ever came: the
                        // user pressed TRASH twice and got a camera that had stopped, showing the word for
                        // "waiting". The pause path pumps through its card walk; this one has to say so.
                        pump();
                    }
                }
                return true;
            case Keys.K_MENU:
            case Keys.K_SK1:
                handler.removeCallbacks(onMenuHold);
                // MENU still leaves the app -- from a screen where there is nothing to lose. During a run
                // it ends the run instead, cleanly, and the panel says how many frames it got: the old
                // app ended the app *and* the run on one press of this key, with nothing asked and nothing
                // reported (docs/PAIN-POINTS.md U-01). Losing a night's frames to a mis-keyed button is
                // the one outcome this app must not have, and the way to not have it is to make the key
                // unable to do it.
                if (menu.up() == Keys.Hold.SHORT) {
                    if (engine.midRun()) {
                        engine.stop(Engine.FAULT_NONE, now());
                        // The run is over, so the panel comes back now rather than on the next frame's wake-up:
                        // a stopped run whose screen is dark is a camera the user cannot read the count off.
                        wakePanel();
                        handler.removeCallbacks(onTick);
                        handler.removeCallbacks(onRelease);
                        handler.removeCallbacks(onRetryFire);
                        handler.removeCallbacks(onVerdict);
                        panel.invalidate();
                    } else {
                        finish();
                    }
                }
                return true;
            default:
                return true;
        }
    }

    /**
     * Down held: the rehearsal mode.
     *
     * <p>Only from idle: a mode that can change under a running shoot is a mode that can ruin one. In
     * rehearsal the engine, the timetable and the panel all run exactly as they do for real, and the
     * shutter is never touched — which is the only way to tell "my scheduling is wrong" from "the camera
     * is slower than the interval", and the two need completely different fixes.
     */
    private final Runnable onDownHold = new Runnable() {
        @Override
        public void run() {
            if (down.fire() != Keys.Hold.HOLD) return;
            if (engine.running()) return;
            boolean rehearsal = !panel.isDryRun();
            panel.setDryRun(rehearsal);
            // A rehearsal that sounds like a shoot is not a rehearsal: the shutter is the one thing
            // everyone in the room hears, and a rehearsal is exactly when nobody expects it. Silent
            // shutter is measured working on this body, so rehearsal switches it on — as a default, not
            // a lock: the row stays editable, and leaving rehearsal puts the user's own answer back.
            if (rehearsal) {
                silentBeforeRehearsal = engine.plan().get(Plan.ROW_SILENT);
                silentChosenInRehearsal = false;
                engine.plan().set(Plan.ROW_SILENT, 1);
                if (camera.isOpen()) camera.setSilentShutter(true);
            } else if (!silentChosenInRehearsal) {
                // Only put the old value back if the user did not answer the question themselves while
                // rehearsal was on: silently undoing a setting the user just made is its own defect.
                engine.plan().set(Plan.ROW_SILENT, silentBeforeRehearsal);
            }
            panel.setCameraNote(rehearsal
                    ? "\u6f14\u7ec3\u6a21\u5f0f\uff1a\u4e0d\u52a8\u76f8\u673a\u3001\u9759\u97f3\u5feb\u95e8\u5f00"  // 演练模式：不动相机、静音快门开
                    : "\u6f14\u7ec3\u6a21\u5f0f\u5df2\u5173");                                     // 演练模式已关
            panel.invalidate();
        }
    };

    /**
     * Up held: how much of the panel is on screen.
     *
     * <p>Full, then one line, then nothing but the legend. The panel has drawn all three modes since it
     * was written and no key could reach two of them: a long exposure does not want a lit screen in front
     * of it, and the mode that fixes that was unreachable code. Unlike the rehearsal switch, this one
     * works during a run — that is when it is wanted.
     */
    private final Runnable onUpHold = new Runnable() {
        @Override
        public void run() {
            if (up.fire() != Keys.Hold.HOLD) return;
            panel.cyclePanel();
        }
    };

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
            // A body that loses the release makes a short press look like a hold, and the difference here
            // is a stopped run. The key itself is asked: still down means the hold was meant, and no answer
            // means the same thing. A release that went missing means the press was short, and the short
            // action is carried out now instead of the stop.
            if (!Keys.trashHoldActs(camera.keyDown(Keys.K_DELETE))) {
                panel.trash(now());
                if (engine.state() == Engine.PAUSED) walkCard();
                return;
            }
            if (!engine.midRun()) return;
            log(LogLines.fault("aborted", engine.landed(), engine.skipped(), "hold_trash"));
            engine.stop(Engine.FAULT_ABORTED, now());
            handler.removeCallbacks(onTick);
            handler.removeCallbacks(onRelease);
            handler.removeCallbacks(onRetryFire);
            handler.removeCallbacks(onVerdict);
            panel.setCameraNote("\u5df2\u505c\u6b62\uff1a\u5df2\u62cd " + engine.landed() + " \u5f20");  // 已停止：已拍 N 张
            panel.invalidate();
        }
    };

    /**
     * MENU held: the text reader.
     *
     * <p>The app menu is still to come (docs/PLAN.md §4.3) and this is where its most useful page already
     * is: what the camera says about itself, a screenful at a time, so a photograph can carry it off a
     * device that has no adb and will not let an app write to the card. Wheel or arrows turn the pages;
     * any other key closes it.
     */
    private final Runnable onMenuHold = new Runnable() {
        @Override
        public void run() {
            if (menu.fire() != Keys.Hold.HOLD) return;
            if (!engine.midRun()) {
                // The session log, which is the one record this project kept when it threw the rest of its
                // diagnostics away: on a camera that will not let a computer at its app's files, this is how a
                // run is read without pulling the card.
                String tail = Session.tail(LOG_TAIL_LINES);
                panel.showText("[the session log, last " + LOG_TAIL_LINES + " lines]\n"
                        + LogLines.text(Session.path(), "(no log)") + "\n"
                        + (tail.length() == 0 ? "(nothing written yet)" : tail));
            }
        }
    };
}
