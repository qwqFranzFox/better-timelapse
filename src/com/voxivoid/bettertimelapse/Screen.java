package com.voxivoid.bettertimelapse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The camera's own display output: the one place this app switches the panel off and back on.
 *
 * <p>This exists because a long shoot has three problems with a lit panel. It is the largest power draw on
 * a body running from a battery for hours; a single fixed image on an LCD for four hours is what burn-in is
 * made of; and at night the screen is a light source pointed at the subject, which is the one thing a
 * time-lapse camera may not be. The old app had the setting — its {@code DOFF}, "每张之间关闭 LCD"
 * (docs/PAIN-POINTS.md §4.1) — and this project kept the row and marked it 尚未实现 until now, because a row
 * that claims something the app does not do is the same lie as a setting that is sent and never applied.
 *
 * <p>Like {@link SonyCamera}, everything here goes through reflection: the build has no Sony stub jar, so
 * {@code com.sony.scalar.hardware.avio.DisplayManager} cannot be imported. And like every other platform
 * answer in this app, a failure is <em>reported</em> rather than swallowed — {@link #problem()} carries the
 * reason and the panel shows it, because the interesting case is not "the screen went off" but "the screen
 * did not go off and nobody said why".
 *
 * <p>Nothing here decides anything: when the panel should be dark is {@link Frames#panelWakeAtMs}, and this
 * class only carries out the answer. It does not discover its own method names at runtime either — see
 * {@link #apiReport()}: the API surface is written to the card's device dump once, and the method names below
 * come from the old app that ran on this body, so the first run on a camera either works or says which name
 * it could not find.
 */
public final class Screen {

    /** The class the old app imported for this. */
    private static final String DISPLAY_MANAGER = "com.sony.scalar.hardware.avio.DisplayManager";

    /**
     * How the output is switched, in the order they are tried.
     *
     * <p>Both spellings are here on purpose: the old app's source compiled against a stub of this class and
     * the name it used is the one recorded in this project's notes, but a stub that never ran proves nothing
     * about the body. Trying two names costs one {@code NoSuchMethodException} on the way in and removes a
     * whole round of "the screen did not go off" that only a camera could explain.
     */
    private static final String[][] SWITCH_CALLS = {
            { "switchDisplayOutputTo", "int" },
            { "setDisplayOutput", "int" },
    };

    /** The output device that means "the panel", and the one that means "no output at all". */
    private static final String[] PANEL_CONSTANTS = { "DEVICE_ID_PANEL", "DEVICE_ID_LCD", "DEVICE_ID_MAIN" };
    private static final String[] OFF_CONSTANTS = { "DEVICE_ID_NONE", "DEVICE_ID_OFF" };

    private Object manager;
    private boolean looked;

    /** Why the panel could not be switched, or an empty string. */
    private String problem = "";

    /** Which method and constant actually worked, for the log: empty until one does. */
    private String working = "";

    private boolean off;

    /** Whether the panel is off right now, as far as this app knows. */
    public boolean isOff() {
        return off;
    }

    /** Why the last switch failed, or an empty string. */
    public String problem() {
        return problem;
    }

    /** The method and constant that worked, or an empty string. */
    public String working() {
        return working;
    }

    /**
     * Whether this body can switch its display output at all.
     *
     * <p>Answered without switching anything: the class has to exist and one of the two call shapes has to be
     * on it. Called once, at startup, so the row can say 本机身不可用 instead of pretending.
     */
    public boolean supported() {
        if (!looked) {
            looked = true;
            try {
                Class<?> kind = Class.forName(DISPLAY_MANAGER);
                StringBuilder why = new StringBuilder();
                for (String[] call : SWITCH_CALLS) {
                    if (hasMethod(kind, call[0])) return true;
                    why.append(call[0]).append(' ');
                }
                problem = DISPLAY_MANAGER + ": no " + why.toString().trim();
            } catch (Throwable t) {
                problem = shortName(t);
            }
        }
        return problem.length() == 0;
    }

    /**
     * Switch the panel off, if it is not already off.
     *
     * <p>Never between a frame's due moment and its shutter: the caller owes this class the quiet stretch of
     * the interval, and {@link Frames#panelWakeAtMs} is where that stretch is computed.
     *
     * @return whether the panel is off, which is the only answer worth acting on.
     */
    public boolean off() {
        if (off) return true;
        if (!open()) return false;
        if (switchTo(OFF_CONSTANTS)) {
            off = true;
            return true;
        }
        if (switchTo(PANEL_CONSTANTS)) {
            // Some bodies have no "nothing" output and leave the panel dark by switching the output to the
            // panel while it is asleep. Recorded, because it is a different mechanism from the other bodies'.
            off = true;
            return true;
        }
        return false;
    }

    /**
     * Switch the panel back on.
     *
     * <p>Always attempted, even when this class believes the panel is already on: a run that ends while the
     * bookkeeping is out of step must not leave the camera's screen black with the app in the foreground and
     * no key that explains it. Failing to come back is the one failure here that the user cannot work around.
     */
    public boolean on() {
        if (!open()) return false;
        if (!switchTo(PANEL_CONSTANTS)) return false;
        off = false;
        return true;
    }

    /**
     * The display API as this body has it, for the card's device dump.
     *
     * <p>One entry per method and one per candidate constant, with its value or the reason it is not there.
     * Short, because it is written to a file that has to stay readable, and written once, because the answer
     * cannot change while the app is open. This is the project's standing way of answering an API question —
     * ask the camera and put the answer where it can be retrieved (docs/DECISIONS.md A30) — rather than adding
     * a third guess at a name.
     */
    public String apiReport() {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> kind = Class.forName(DISPLAY_MANAGER);
            b.append(DISPLAY_MANAGER).append('\n');
            for (Method m : kind.getMethods()) {
                b.append("  method ").append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(simple(params[i]));
                }
                b.append(") -> ").append(simple(m.getReturnType())).append('\n');
            }
            b.append("  constants: ");
            Field[] fields = kind.getFields();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) b.append(' ');
                try {
                    b.append(fields[i].getName()).append('=').append(fields[i].get(null));
                } catch (Throwable t) {
                    b.append(fields[i].getName()).append("=?");
                }
            }
            b.append('\n');
        } catch (Throwable t) {
            b.append(DISPLAY_MANAGER).append(": ").append(shortName(t)).append('\n');
        }
        b.append("  tries ").append(working.length() > 0 ? working : "nothing yet");
        if (problem.length() > 0) b.append("  problem=").append(problem);
        return b.toString();
    }

    // ---------------------------------------------------------------- reflection, once
    private boolean open() {
        if (manager != null) return true;
        if (!supported()) return false;
        try {
            manager = Class.forName(DISPLAY_MANAGER).newInstance();
            return true;
        } catch (Throwable t) {
            problem = shortName(t);
            return false;
        }
    }

    /**
     * Try each constant against each known call shape until one of them answers true.
     *
     * <p>The return value is what decides, not the absence of an exception: a body that has the method and
     * refuses the value must not be recorded as having switched.
     */
    private boolean switchTo(String[] names) {
        try {
            Class<?> kind = Class.forName(DISPLAY_MANAGER);
            for (String[] call : SWITCH_CALLS) {
                Method method = method(kind, call[0]);
                if (method == null) continue;
                for (String name : names) {
                    Integer value = constant(kind, name);
                    if (value == null) continue;
                    Object result = method.invoke(manager, value);
                    if (result instanceof Boolean && !((Boolean) result)) continue;
                    working = call[0] + "(" + name + ")";
                    problem = "";
                    return true;
                }
            }
            problem = "no call took: " + join(names);
        } catch (Throwable t) {
            problem = shortName(t);
        }
        return false;
    }

    private static boolean hasMethod(Class<?> kind, String name) {
        return method(kind, name) != null;
    }

    /** The one-int shape of a call, by name, or {@code null}: the shape the old app used. */
    private static Method method(Class<?> kind, String name) {
        try {
            return kind.getMethod(name, int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A constant's value, or {@code null} when this body's class does not carry that name. */
    private static Integer constant(Class<?> kind, String name) {
        try {
            Object value = kind.getField(name).get(null);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String simple(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String join(String[] names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) b.append('/');
            b.append(names[i]);
        }
        return b.toString();
    }

    private static String shortName(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            Throwable cause = t.getCause();
            String message = cause.getMessage();
            return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        }
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.getClass().getSimpleName() : message;
    }
}
