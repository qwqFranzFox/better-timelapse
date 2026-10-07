package com.voxivoid.bettertimelapse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * The shooting screen, drawn on one Canvas.
 *
 * <p>Canvas rather than widgets, for the reason the old app demonstrates: it drove Android's focus
 * machinery on a device with no touch screen and no focus indicator, so the wheel was consumed while
 * appearing to do nothing, and one dial event was fed to three sliders at once
 * (docs/PAIN-POINTS.md U-12, D-11). Here the selected row is a field in this class and is drawn
 * inverted with an arrow, so what the wheel will change is never in doubt.
 *
 * <p><b>Nothing is sized in dp.</b> Every dimension is derived from the view's measured width and
 * height in {@link #onSizeChanged}, so the same code fills a 480x320 panel and a 640x480 one without
 * clipping. The old app used fixed dp widths and left out a ScrollView, which is how a layout gets
 * cut off on a body nobody tested (U-20).
 *
 * <p>It owns no state machine. {@link Engine} decides, {@link Schedule} keeps the time, and this
 * class draws what they say and forwards key events into them — which is the split the old app got
 * wrong by putting the whole run inside its Activity (docs/PAIN-POINTS.md D-04, D-06).
 *
 * <p>Only this class, {@link MainActivity}, {@link SonyCamera} and {@link Card} know Android exists.
 * Every string and every number drawn here comes from {@link Hud}, {@link Plan}, {@link Steps},
 * {@link Engine} and {@link Frames}, which are compiled and checked without {@code android.jar}.
 */
public final class PanelView extends View {

    /**
     * How much of the screen the panel occupies: three steps of the same thing, which is light in front
     * of a long exposure.
     *
     * <ul>
     *   <li>{@link #PANEL_FULL} — the state, every row, the progress block, the legend and the
     *       diagnostics. The screen for editing and for reading what happened.</li>
     *   <li>{@link #PANEL_LABEL} — one line, centred and small: the state, the countdown, how many frames
     *       are taken. For a shoot where the screen ends up in the frame but you still want to glance at
     *       it.</li>
     *   <li>{@link #PANEL_HIDDEN} — the state word alone, top left, dim. For a night frame where the
     *       screen must not be a light source. The state is never one of the things dropped: a screen that
     *       will not say whether the run is still going is the failure this app exists to avoid.</li>
     * </ul>
     */
    public static final int PANEL_FULL = 0, PANEL_LABEL = 1, PANEL_HIDDEN = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** The whole run lives here; this view only draws it. */
    private Engine engine;

    // ---- palette, mirrored from res/values/colors.xml by MainActivity ----
    private final int colBackground, colText, colDim, colSelected, colAccent, colWarn, colGood;

    // ---- what the screen is showing ----
    private int row = Plan.ROW_INTERVAL;
    private int panelMode = PANEL_FULL;

    /**
     * Dry run: the engine runs for real, the shutter does not. It exists so the whole panel can be
     * reviewed with no camera attached, and so a body whose camera refuses to open can still be
     * driven through every state. The shell decides what "fire" means.
     */
    private boolean dryRun;

    // ---- what the shell knows that the engine does not ----
    private String cameraNote = "";

    /**
     * The diagnostics band, and all that is left on it.
     *
     * <p>A screen collects debug text the way a desk collects paper. 卡增/核实/落盘/读数/回执/机身, the card
     * root, the newest file, the font route, the startup time: every one of them earned its place while the
     * thing it measured was broken, and every one of them is in the session log on the card now, where it can
     * be read as a file rather than photographed. What stays is what the person at the camera has to act on:
     * the first thing that went wrong with this run, why the labels are boxes if they are, and whether the
     * run is being recorded at all.
     */
    private String fontError = "";
    private String evidence = "";
    private String logNote = "";

    /**
     * A page of text to read on the panel, or null.
     *
     * <p>For the answers that do not fit anywhere: this body reports 298 live parameters, the filter that
     * matters matches dozens, and the file they were written to cannot be read back off this camera. A
     * photograph of a screen is a poor channel, but it is the only one there is — so the panel can show the
     * text a page at a time, with the state line always visible above it and the wheel turning the pages.
     */
    private String[] pageLines;
    private int page;

    /** Called once, the first time a frame is actually drawn. */
    private Runnable onFirstDraw;
    private boolean drewOnce;

    private int batteryPercent = -1;
    private boolean charging;
    private long cardFreeBytes = -1;
    private long bytesPerFrame;

    // ---- geometry, computed in onSizeChanged ----
    private float padding, rowHeight, nameWidth, hintWidth, statusHeight, smallSize, bigSize,
            titleSize, valueSize, legendHeight, notesHeight, progressHeight;
    private int rowsVisible;

    public PanelView(Context context, Engine engine, Typeface typeface, int background, int text,
                     int dim, int selected, int accent, int warn, int good) {
        super(context);
        this.engine = engine;
        this.colBackground = background;
        this.colText = text;
        this.colDim = dim;
        this.colSelected = selected;
        this.colAccent = accent;
        this.colWarn = warn;
        this.colGood = good;
        // The bundled Sarasa subset: the firmware font has no CJK, so without this every Chinese
        // label is a tofu box. A null typeface means the asset failed to load, and the panel still
        // draws — in boxes, but it draws.
        if (typeface != null) paint.setTypeface(typeface);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ================================================================ input

    /**
     * The wheel and the top dial: the smallest step, because sweeping a wheel is the fast, imprecise
     * motion and it must never overshoot a value the user cares about.
     */
    public void turn(int direction) {
        if (panelMode == PANEL_HIDDEN) panelMode = PANEL_FULL;
        edit(true, direction);
    }

    /** Up and down move between rows, as they do in Recipe Lab. */
    public void move(int direction) {
        row = ((row + direction) % Plan.ROW_COUNT + Plan.ROW_COUNT) % Plan.ROW_COUNT;
        invalidate();
    }

    /** Left and right, deliberate presses, carry the big jumps. */
    public void adjust(int direction) {
        edit(false, direction);
    }

    /**
     * One edit, and the two rules that decide whether it is allowed.
     *
     * <p>A camera-side row is refused while a run is on screen, because it cannot take effect until the
     * next one starts; the row says why instead of moving. Every other row is a number the timetable
     * uses, and changing it mid-run is meant to work — the shell rebases the run so the new value applies
     * from the next frame rather than retroactively moving every frame after it.
     */
    private void edit(boolean wheel, int direction) {
        if (engine.midRun() && Hud.cameraRow(row)) {
            invalidate();
            return;
        }
        Plan plan = engine.plan();
        int before = plan.get(row);
        plan.set(row, Steps.apply(row, before, wheel, direction));
        if (plan.get(row) != before && planListener != null) planListener.planEdited(row);
        invalidate();
    }

    /** Told when a row actually changed, so the shell can make the change take effect. */
    public interface PlanListener {
        void planEdited(int row);
    }

    private PlanListener planListener;

    public void setPlanListener(PlanListener listener) {
        planListener = listener;
    }

    /** Show a text, a screenful at a time. Null or empty closes the reader. */
    public void showText(String text) {
        if (text == null || text.length() == 0) {
            pageLines = null;
        } else {
            pageLines = text.split("\n");
            page = 0;
        }
        invalidate();
    }

    public void closeText() {
        pageLines = null;
        invalidate();
    }

    public boolean reading() {
        return pageLines != null && pageLines.length > 0;
    }

    /** Turn a page: forward for a positive direction, wrapping at both ends. */
    public void turnPage(int direction) {
        if (!reading()) return;
        int count = pageCount();
        page = ((page + direction) % count + count) % count;
        invalidate();
    }

    public int pageNumber() {
        return page + 1;
    }

    public int pageCount() {
        int perPage = linesPerPage();
        return Math.max(1, (pageLines.length + perPage - 1) / perPage);
    }

    /** How many lines a page holds: the rows' band, at the notes' text size. */
    private int linesPerPage() {
        if (smallSize <= 0f) return 8;
        int fits = (int) Math.floor((rowHeight * rowsVisible) / lineHeight());
        return Math.max(4, Math.min(16, fits));
    }

    /** The reader's own drawing: the rows' band, one page of text, line by line. */
    private void drawText(Canvas canvas, float w, float top, float height) {
        paint.setTextSize(smallSize);
        paint.setColor(colText);
        int perPage = linesPerPage();
        int first = page * perPage;
        float y = top + lineHeight();
        for (int i = 0; i < perPage && first + i < pageLines.length; i++) {
            canvas.drawText(clip(pageLines[first + i], w - padding * 2f), padding, y, paint);
            y += lineHeight();
            if (y > top + height) break;
        }
        paint.setColor(colAccent);
        String mark = "\u9875 " + pageNumber() + " / " + pageCount();                       // 页 n / m
        canvas.drawText(mark, padding, top + height - paint.descent(), paint);
        paint.setColor(colDim);
    }

    /** TRASH: cycle how much of the panel is on screen. */
    public void cyclePanel() {
        panelMode = (panelMode + 1) % 3;
        invalidate();
    }

    /** TRASH short press: pause and resume, without leaving the run. */
    public void trash(long nowMs) {
        engine.step(Engine.EV_TRASH, nowMs);
        invalidate();
    }

    /** Centre: arm the run, resume it, or acknowledge a finished one. */
    public void enter(long nowMs) {
        engine.step(Engine.EV_ENTER, nowMs);
        invalidate();
    }

    /** The engine is asking for a timer; the shell posts it and calls {@link #tick} when it fires. */
    public void tick(long nowMs) {
        engine.step(Engine.EV_TICK, nowMs);
        invalidate();
    }

    /** A frame landed, as the camera's own callback reports it. */
    public void shutter(long nowMs) {
        engine.step(Engine.EV_SHUTTER, nowMs);
        invalidate();
    }

    /** A frame never appeared on the card. */
    public void frameLost(String why, long nowMs) {
        engine.onFrameLost(why, nowMs);
        invalidate();
    }

    public Engine engine() {
        return engine;
    }

    /** Dry run: the engine runs, the shutter does not. */
    public void setDryRun(boolean on) {
        dryRun = on;
        invalidate();
    }

    public boolean isDryRun() {
        return dryRun;
    }

    /** Told once, when the first frame has been drawn. */
    public void setOnFirstDraw(Runnable callback) {
        onFirstDraw = callback;
    }

    /**
     * The first thing that went wrong, kept on its own line for the rest of the session.
     *
     * <p>Every diagnostic this app has written so far has been overwritten by the next message the
     * shell sent — which is how a reflection failure that disabled silent shutter stayed invisible
     * while the settings row said it was applied. Evidence that scrolls away is not evidence.
     */
    /** Forget the evidence: it belongs to one run, and a warning that has been fixed is a lie. */
    public void clearEvidence() {
        evidence = "";
    }

    public void setEvidence(String message) {
        if (evidence.length() > 0) return;      // the first failure is the interesting one
        evidence = message == null ? "" : message;
        invalidate();
    }

    /**
     * That the session log is not being written.
     *
     * <p>One line, and only when it is true: a run nobody is recording is something the user has to know
     * before the run, not after it.
     */
    public void setLogNote(String message) {
        logNote = message == null ? "" : message;
        invalidate();
    }

    /** A font failure, drawn on its own line. Empty means the font is fine. */
    public void setFontError(String message) {
        fontError = message == null ? "" : message;
        invalidate();
    }

    /**
     * Swap the typeface in, after the first frame. Used when the bundled font arrives late: the panel
     * never waits for a font, it just redraws with one when it has one.
     */
    public void setTypeface(Typeface typeface) {
        if (typeface != null) paint.setTypeface(typeface);
        invalidate();
    }

    /** Free text the shell wants on the status line, such as a missing camera or a failed probe. */
    public void setCameraNote(String note) {
        cameraNote = note == null ? "" : note;
        invalidate();
    }

    /**
     * The two numbers that decide whether a night shoot survives: battery and card space. Pushed in
     * by the shell, which is the only thing here that can ask the platform.
     */
    public void setDeviceStatus(int batteryPercent, boolean charging, long cardFreeBytes,
                                long bytesPerFrame) {
        this.batteryPercent = batteryPercent;
        this.charging = charging;
        this.cardFreeBytes = cardFreeBytes;
        this.bytesPerFrame = bytesPerFrame;
        invalidate();
    }

    // ================================================================ geometry

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidate();
    }

    /**
     * Work out every dimension from the view's real size.
     *
     * <p>Called from {@code onDraw}, not from {@code onSizeChanged}, because part of the layout
     * depends on the current state: the progress block is only there once a run has figures to show,
     * and the rows take the space it leaves. Computing this once at size-change time meant the rows
     * and the progress block overlapped the moment the state changed without the window changing.
     */
    /**
     * Work out the bands, once, so that nothing is drawn over anything.
     *
     * <p>The diagnostics under the progress block used to be drawn into "whatever is left", which was
     * nothing: with eight rows, a status line, a progress block and a legend, the space they needed was
     * already spent, and the stack was drawn straight through the last two rows (photographed twice — 卡上
     * 写不进 over the AEL row, 参数键 over the 关屏 row). They now have their own band, sized to the lines
     * they actually need, and the rows get what remains.
     */
    private void layout(float w, float h) {
        // Measure with the text size the last frame ended on; the first frame simply reserves three lines.
        int noteLines = noteLines(w);
        layoutFor(w, h, noteLines * lineHeight());
    }

    private void layoutFor(float w, float h, float notes) {
        padding = w * 0.022f;
        float usable = h - padding * 2f;
        statusHeight = usable * 0.085f;
        legendHeight = usable * 0.085f;
        // Never more than a third of the screen: the rows are the panel, and a diagnostic that squeezes them
        // out has stopped being a diagnostic. Past that the stack drops its least important lines instead.
        notesHeight = Math.min(notes, usable * 0.30f);
        boolean running = engine.state() != Engine.IDLE;
        progressHeight = usable * (running ? 0.20f : 0.08f);
        float rowsSpace = usable - statusHeight - progressHeight - legendHeight - notesHeight;
        rowsVisible = Plan.ROW_COUNT;
        // Divide by at least 1: a text size derived from a zero or negative row height is a crash on
        // the one panel nobody measured. The rows squeezing together is the better failure.
        rowHeight = Math.max(1f, rowsSpace) / rowsVisible;
        // Set after the rows are known, because the note measurement above depends on it next frame.
        float small = rowHeight * 0.40f;
        smallSize = small;
        titleSize = rowHeight * 0.46f;
        valueSize = rowHeight * 0.52f;
        bigSize = statusHeight * 0.62f;
        // The name column is fixed so values line up; the hint column takes the rest, and a long hint
        // is clipped rather than allowed to run off the panel.
        nameWidth = w * 0.22f;
        hintWidth = w * 0.36f;
    }

    // ================================================================ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(colBackground);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        layout(w, h);

        // Tell the shell once, so the slow work can be queued behind a frame that exists.
        if (!drewOnce) {
            drewOnce = true;
            if (onFirstDraw != null) onFirstDraw.run();
        }

        float y = padding;

        if (panelMode == PANEL_LABEL) {
            // One line and nothing else. This mode used to draw the top status bar *and* the centred line,
            // which put the same sentence on the screen twice — photographed from the camera, twice.
            drawStatusOnly(canvas, w, h);
            return;
        }
        if (panelMode == PANEL_HIDDEN) {
            drawStateWordOnly(canvas, w, y, statusHeight);
            return;
        }

        drawStatus(canvas, w, y, statusHeight);
        y += statusHeight;

        if (reading()) {
            drawText(canvas, w, y, rowHeight * rowsVisible + progressHeight);
        } else {
            y = drawRows(canvas, w, y, rowHeight * rowsVisible);
        }

        // The diagnostics live under the progress block and grow upwards from the legend, so the two used
        // to be drawn through each other the moment there were three of them: the photograph shows 卡根 and
        // 已拍 on the same baseline, and the measured line under the interval warning. The notes get a fixed
        // budget and the progress block gets what is left; past the budget the least important line is
        // dropped rather than overprinted.
        drawProgress(canvas, w, y, progressHeight);
        // The legend sits at the bottom, the diagnostics stack upwards from it into their own band, and the
        // band's top is the limit: past it a line would be drawn over the rows.
        drawLegend(canvas, w, h, padding + statusHeight
                + rowHeight * rowsVisible + progressHeight);
    }

    /** The label-only state: the state line and the plan, centred, on black. */
    /**
     * The state word alone, dim, top left: the whole of the hidden mode.
     *
     * <p>No countdown, no legend, no diagnostics, no font note — the point of this mode is that the screen
     * is not a light source in the frame. What is kept is the one thing a camera app may never hide: which
     * state the run is in.
     */
    private void drawStateWordOnly(Canvas canvas, float w, float top, float height) {
        paint.setTextSize(bigSize);
        paint.setColor(engine.state() == Engine.FAULT ? colWarn : colDim);
        canvas.drawText(Hud.stateName(engine.state()), padding,
                baselineIn(top, height, paint), paint);
    }

    private void drawStatusOnly(Canvas canvas, float w, float h) {
        paint.setTextSize(bigSize);
        paint.setFakeBoldText(true);
        int state = engine.state();
        paint.setColor(state == Engine.FAULT ? colWarn : (state == Engine.DONE ? colGood : colText));
        String name = Hud.stateName(state);
        float y = h / 2f;
        float x = padding;
        canvas.drawText(name, x, y, paint);
        x += paint.measureText(name) + padding * 2f;
        paint.setFakeBoldText(false);

        paint.setTextSize(smallSize);
        paint.setColor(colDim);
        long next = engine.remainingMs(now());
        if (next != Schedule.NONE && next >= 0) {
            String count = Hud.countdown(next);
            canvas.drawText(count, x, y, paint);
            x += paint.measureText(count) + padding * 2f;
        }
        // What follows depends on whether there is a run to report on: the plan while idle, the count
        // while it is going. The plan summary is the answer to "what did I ask for", which is not the
        // question anyone has while a shoot is running.
        boolean over = engine.state() == Engine.IDLE || engine.state() == Engine.DONE
                || engine.state() == Engine.FAULT;
        String rest = over
                ? Hud.statusLine(engine.plan(), batteryPercent, charging, cardFreeBytes, bytesPerFrame)
                : Hud.labelLine(engine.landed(), engine.plan().frames(), engine.skipped(),
                        engine.unverified());
        if (x + paint.measureText(rest) <= w - padding) {
            canvas.drawText(rest, x, y, paint);
        }
    }

    /** One line: state name, plain-language state, countdown, and the plan summary. */
    private void drawStatus(Canvas canvas, float w, float top, float height) {
        paint.setTextSize(bigSize);
        paint.setFakeBoldText(true);
        int state = engine.state();
        paint.setColor(state == Engine.FAULT ? colWarn : (state == Engine.DONE ? colGood : colText));
        float baseline = baselineIn(top, height, paint);
        String name = Hud.stateName(state);
        canvas.drawText(name, padding, baseline, paint);
        float used = padding + paint.measureText(name) + padding;

        // The countdown sits next on a narrow panel, further right when there is room.
        long nextInMs = engine.remainingMs(now());
        if (nextInMs != Schedule.NONE && engine.running()) {
            String count = Hud.countdown(nextInMs);
            float countWidth = paint.measureText(count);
            float x = Math.max(used, w - padding - countWidth - w * 0.24f);
            paint.setColor(state == Engine.EXPOSING ? colAccent : colText);
            canvas.drawText(count, x, baseline, paint);
            used = x + countWidth + padding * 2f;
        }
        paint.setFakeBoldText(false);

        // Whatever room is left carries the plan summary, then the plain-language state, then the
        // camera note. Dropping the least important line is better than drawing over another one.
        paint.setTextSize(smallSize);
        String[] tail = { Hud.statusLine(engine.plan(), batteryPercent, charging, cardFreeBytes, bytesPerFrame),
                Hud.statePlain(state),
                cameraNote,
                dryRun ? Hud.demoLegend() : "" };
        float x = used;
        for (int i = 0; i < tail.length; i++) {
            String text = tail[i];
            if (text.length() == 0) continue;
            float width = paint.measureText(text);
            if (x + width > w - padding) break;
            paint.setColor(i == 3 ? colAccent : colDim);
            canvas.drawText(text, x, baseline, paint);
            x += width + padding * 2f;
        }
    }

    /** The settings rows: name, value, and — on the selected row — what a detent is worth. */
    private float drawRows(Canvas canvas, float w, float top, float height) {
        float rh = height / rowsVisible;
        for (int i = 0; i < rowsVisible; i++) {
            float rowTop = top + i * rh;
            boolean selected = i == row;
            if (selected) {
                paint.setColor(colSelected);
                canvas.drawRect(padding * 0.5f, rowTop, w - padding * 0.5f, rowTop + rh, paint);
            }
            float baseline = baselineIn(rowTop, rh, paint);

            paint.setTextSize(titleSize);
            paint.setColor(selected ? colAccent : colText);
            if (selected) {
                // A drawn triangle, not a glyph: Sarasa Gothic has no U+25B8, and a font that
                // lacks a character draws a tofu box. Shapes the panel needs are drawn.
                float h = titleSize * 0.30f, cy = rowTop + rh / 2f;
                path.reset();
                path.moveTo(padding + h * 0.6f, cy - h);
                path.lineTo(padding + h * 1.6f, cy);
                path.lineTo(padding + h * 0.6f, cy + h);
                path.close();
                canvas.drawPath(path, paint);
            }
            canvas.drawText(Hud.rowLabel(i), padding * 2.2f, baseline, paint);

            String value = Hud.rowValue(engine.plan(), i);
            paint.setTextSize(valueSize);
            paint.setColor(engine.plan().dirty(i) ? colAccent : colText);
            canvas.drawText(value, padding * 2.2f + nameWidth, baseline, paint);

            String detail = Hud.rowDetail(engine.plan(), i,
                    engine.midRun() && Hud.cameraRow(i));
            String hint = detail.length() > 0 ? detail : Steps.hint(i, engine.plan().get(i));
            float hintX = w - padding - hintWidth;
            if (hintX > padding * 2.2f + nameWidth + w * 0.20f) {
                paint.setTextSize(smallSize);
                paint.setColor(colDim);
                canvas.drawText(clip(hint, hintWidth), hintX, baseline, paint);
            }
        }
        return top + height;
    }

    /** Frames the card has answered for: taken, and no longer outstanding. */
    private int accounted() {
        int open = engine.unverified();
        int landed = engine.landed();
        return landed > open ? landed - open : 0;
    }

    /** The progress block, and the measured line that is the only honest source of drift. */
    private void drawProgress(Canvas canvas, float w, float top, float height) {
        if (engine.state() == Engine.IDLE) return;
        float barHeight = Math.max(6f, height * 0.18f);
        float barTop = top + height * 0.12f;
        float full = w - padding * 2f;

        if (engine.state() == Engine.FAULT) {
            paint.setTextSize(bigSize * 0.80f);
            paint.setColor(colWarn);
            String why = engine.faultText();
            canvas.drawText(clip(why.length() > 0 ? why : Hud.statePlain(engine.state()), full),
                    padding, baselineIn(top, height * 0.32f, paint), paint);
            // What the run did achieve, and what the last refusal said. A faulted run is exactly when
            // these two lines matter most, and the first version of this branch returned here and hid
            // both of them: the screen said a run had failed and nothing about what it had done.
            paint.setTextSize(smallSize);
            paint.setColor(colText);
            String second = Hud.progressLine(engine.landed(), engine.plan().frames(),
                    accounted(), engine.unverified(), engine.skipped(), now());
            if (engine.lastLoss().length() > 0) second = second + "   " + engine.lastLoss();
            canvas.drawText(clip(second, full), padding,
                    baselineIn(top + height * 0.40f, height * 0.46f, paint), paint);
            return;
        }

        paint.setColor(colSelected);
        canvas.drawRect(padding, barTop, padding + full, barTop + barHeight, paint);
        int planned = engine.plan().frames();
        if (planned > 0 && planned != Plan.FRAMES_UNLIMITED) {
            float ratio = Math.min(1f, engine.landed() / (float) planned);
            paint.setColor(engine.state() == Engine.DONE ? colGood : colAccent);
            canvas.drawRect(padding, barTop, padding + full * ratio, barTop + barHeight, paint);
        }

        paint.setTextSize(smallSize);
        paint.setColor(colText);
        canvas.drawText(Hud.progressLine(engine.landed(), planned, accounted(),
                        engine.unverified(), engine.skipped(), now()),
                padding, baselineIn(barTop + barHeight, height * 0.42f, paint), paint);

        if (engine.meanIntervalMs() > 0) {
            // The measured line, not a promise: this is where a user sees that the camera is behind
            // the interval instead of finding out from the finished film (docs/PAIN-POINTS.md U-18).
            long left = engine.remainingMs(now());
            paint.setColor(colDim);
            canvas.drawText(clip(Hud.measuredLine(engine.meanIntervalMs(), engine.plan().intervalMs(), left), full),
                    padding, baselineIn(barTop + barHeight + height * 0.36f, height * 0.42f, paint), paint);
        }
    }

    /** One line of small text, from its top to the next line's top. */
    private float lineHeight() {
        return smallSize - paint.ascent() + paint.descent();
    }

    /**
     * How many lines the diagnostics need at the current text size.
     *
     * <p>Measured, not guessed: reserving a fixed block either wastes the rows' height or fails to hold the
     * lines, and the failure mode of the second is text on top of text.
     */
    private int noteLines(float w) {
        float width = w - padding * 2f;
        int lines = 0;
        if (fontError.length() > 0) lines += wrappedLines(fontError, width, 2);
        if (evidence.length() > 0) lines += wrappedLines(evidence, width, 3);
        if (logNote.length() > 0) lines += wrappedLines(logNote, width, 2);
        return Math.max(1, lines);
    }

    /**
     * The key legend, and the one thing that must never be a surprise: the run's own state.
     *
     * @param limit the topmost baseline the stack may reach: anything above it would be drawn over the
     *              progress block.
     */
    private void drawLegend(Canvas canvas, float w, float h, float limit) {
        paint.setTextSize(smallSize);
        paint.setColor(colDim);
        String legend = Hud.legend();
        // Subtract the descent: the legend sits on the last line of the screen, and a baseline at
        // exactly h - padding puts its descenders off the bottom edge.
        float y = h - padding - paint.descent();
        canvas.drawText(clip(legend, w - padding * 2f), padding, y, paint);

        // Where the legend ends, so that the note on the right of this row can be kept off it. The check
        // used to be against the left edge of the screen alone, and once the legend grew the font note was
        // drawn straight through it — photographed, twice, from the camera.
        float legendEnd = padding + Math.min(paint.measureText(legend), w - padding * 2f);
        String note = "";
        float noteWidth = 0f;
        // The dirty marker shares this row too, so it is measured before the decision.
        float markWidth = engine.plan().dirty() ? paint.measureText("\u672a\u5b58") + padding * 2f : 0f;  // 未存
        boolean noteInline = note.length() > 0
                && w - padding - markWidth - noteWidth > legendEnd + padding * 2f;

        // The font failure gets its own line above the legend: it is the answer to "why is every
        // label a box", and a message that can be clipped or overwritten is a message that wastes a
        // round trip to the camera.
        float lineHeight = paint.descent() - paint.ascent();
        float lines = 1f;
        if (note.length() > 0 && !noteInline) {
            // No room beside the legend: it goes on the stack instead of on top of something.
            lines += 1f;
            paint.setColor(colDim);
            canvas.drawText(clip(note, w - padding * 2f), padding, y - lineHeight, paint);
        }
        if (fontError.length() > 0) {
            paint.setColor(colWarn);
            canvas.drawText(clip(fontError, w - padding * 2f), padding, y - lineHeight * lines, paint);
            lines += 1f;
            paint.setColor(colDim);
        }
        // Wrapped, not clipped. A single clipped line threw away most of the method list the one time
        // the list was the whole point — diagnostics that do not fit are diagnostics that do not exist.
        float wrapWidth = w - padding * 2f;
        // Drawn from the bottom up, most important first: the first line that would reach above the budget
        // is left off. A diagnostic drawn on top of another one is not a diagnostic.
        if (evidence.length() > 0 && fits(y, lineHeight, lines + 1, limit)) {
            paint.setColor(colWarn);
            lines += wrap(canvas, evidence, padding, y - lineHeight * lines, wrapWidth, 3);
            paint.setColor(colDim);
        }
        if (logNote.length() > 0 && fits(y, lineHeight, lines + 1, limit)) {
            paint.setColor(colWarn);
            wrap(canvas, logNote, padding, y - lineHeight * lines, wrapWidth, 2);
        }

        // Two markers share this row, so they are laid out from the right edge inwards rather than
        // both claiming the corner and drawing over each other.
        float cursor = w - padding;

        // The dirty marker: an edit that has not been stored yet. The old app had no such notion at
        // all, and the exit rule exists precisely because of it.
        if (engine.plan().dirty()) {
            String mark = "\u672a\u5b58";   // 未存
            float width = paint.measureText(mark);
            cursor -= width;
            paint.setColor(colAccent);
            canvas.drawText(mark, cursor, y, paint);
            cursor -= padding * 2f;
        }

        // Which route the bundled font was loaded by, and how long the first frame took. Small, dim,
        // and the only way to tell either from a photograph of the screen.
        if (noteInline) {
            paint.setColor(colDim);
            canvas.drawText(note, cursor - noteWidth, y, paint);
        }
    }

    // ================================================================ helpers

    /**
     * The baseline that centres text vertically inside a band, using the font's real metrics.
     *
     * <p>{@code ascent()} and {@code descent()} rather than {@code getFontMetrics()}: the latter only
     * arrived in API 29, and this app runs on API 10.
     */
    /**
     * Where the line starting at {@code start} ends, given the width.
     *
     * <p>One place decides this, so that measuring a text and drawing it cannot disagree — which is how the
     * notes ended up reserving a different number of lines from the number they took.
     */
    private int lineBreak(String text, int start, float width) {
        int end = start;
        while (end < text.length() && paint.measureText(text, start, end + 1) <= width) end++;
        if (end == start) end = Math.min(text.length(), start + 1);
        if (end < text.length()) {
            // Prefer to break at a space so the method names stay readable.
            int space = text.lastIndexOf(' ', end);
            if (space > start) end = space;
        }
        return end;
    }

    /** Lines a text needs when wrapped, without drawing it. */
    private int wrappedLines(String text, float width, int maxLines) {
        if (smallSize <= 0f) return Math.min(1, maxLines);
        float size = paint.getTextSize();
        paint.setTextSize(smallSize);
        int used = 0;
        int start = 0;
        while (start < text.length() && used < maxLines) {
            int end = lineBreak(text, start, width);
            start = end < text.length() ? end + 1 : text.length();
            used++;
        }
        paint.setTextSize(size);
        return Math.max(1, used);
    }

    /** Whether one more line at {@code lines + 1} still sits below {@code limit}. */
    private boolean fits(float legendBaseline, float lineHeight, float lines, float limit) {
        return legendBaseline - lineHeight * lines >= limit - lineHeight * 0.5f;
    }

    private float baselineIn(float top, float height, Paint p) {
        float ascent = p.ascent(), descent = p.descent();
        return top + (height - (descent - ascent)) / 2f - ascent;
    }

    /**
     * Draw text wrapped across at most {@code maxLines} lines, and return how many were used.
     *
     * @return the number of lines drawn
     */
    private float wrap(Canvas canvas, String text, float x, float baseline, float width, int maxLines) {
        float lineHeight = paint.descent() - paint.ascent();
        int start = 0, drawn = 0;
        while (start < text.length() && drawn < maxLines) {
            int breakAt = lineBreak(text, start, width);
            String piece = text.substring(start, breakAt);
            if (drawn == maxLines - 1 && breakAt < text.length()) piece = piece + "\u2026";   // …
            canvas.drawText(piece, x, baseline, paint);
            baseline -= lineHeight;
            drawn++;
            start = breakAt < text.length() ? breakAt + 1 : text.length();
        }
        return drawn;
    }

    /** Cut a string to fit a width, with an ellipsis. Drawing over the next column is worse. */
    /**
     * The monotonic clock, in milliseconds. {@code elapsedRealtime} rather than
     * {@code currentTimeMillis}: the camera syncs its clock, and a timetable that jumps when it does
     * is worse than a late frame (docs/PAIN-POINTS.md U-18, cause 2).
     */
    private long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private String clip(String text, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "\u2026";
        float ellipsisWidth = paint.measureText(ellipsis);
        int n = text.length();
        while (n > 1 && paint.measureText(text, 0, n) + ellipsisWidth > maxWidth) n--;
        return text.substring(0, n) + ellipsis;
    }

    public int panelMode() {
        return panelMode;
    }
}
