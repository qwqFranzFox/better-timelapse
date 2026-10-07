package com.github.qwqfranzfox.bettertimelapse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Whether this body has a call that switches its display output, and how to make it.
 *
 * <p>Split out of {@link Screen} because the answer to "does this body have the call" was wrong once already,
 * and being wrong cost a build and a round trip to the camera:
 *
 * <pre>
 *   the guess      switchDisplayOutputTo(int), and the device ids read as Integers
 *   the body       public void switchDisplayOutputTo(java.lang.String)
 *                  public static final String DEVICE_ID_PANEL = "DEVICE_ID_PANEL"
 *                  public static final String DEVICE_ID_NONE  = "DEVICE_ID_NONE"
 * </pre>
 *
 * <p>An {@code int.class} lookup against that class throws {@code NoSuchMethodException}, and reading a
 * {@code String} constant as an {@code Integer} yields nothing, so the app reported 本机身关不了屏 on a body
 * that has the call. The name had come from the old app's source, which compiled against a stub jar that no
 * longer exists here — and a stub is not a body. That is why this class exists as its own tested thing: it
 * tries every shape it knows, reads each constant as whatever type the field actually holds, and takes the
 * <em>return value</em> as the answer rather than the absence of an exception.
 *
 * <p>Pure Java and no Android: {@code Class.forName} and {@code getMethod} are the JDK's, so this class lives
 * in the tested core and its behaviour is asserted against a stand-in for the camera's class
 * ({@code test/com/sony/scalar/hardware/avio/DisplayManager.java}) rather than reasoned about. Nothing here
 * decides anything about timing — that is {@link Frames#panelWakeAtMs}.
 */
public final class DisplayApi {

    /** What the old app imported, and the class this app looks for on the body. */
    public static final String DISPLAY_MANAGER = "com.sony.scalar.hardware.avio.DisplayManager";

    /**
     * The shapes of the call, in the order they are tried.
     *
     * <p>{@code (String)} first because that is what the body has — the stub's {@code javap} line is
     * {@code public void switchDisplayOutputTo(java.lang.String)}, one parameter, void return, with no
     * {@code int} overload at all. The two {@code int} shapes after it are what this app first believed, and
     * keeping them costs one exception on the way in: an API described two ways in two places is exactly the
     * situation this class exists to be robust about.
     */
    private static final Object[][] SHAPES = {
            { "switchDisplayOutputTo", String.class },
            { "switchDisplayOutputTo", int.class },
            { "setDisplayOutput", String.class },
            { "setDisplayOutput", int.class },
    };

    /** The output that means "the panel", and the one that means "no output at all". */
    private static final String[] PANEL = { "DEVICE_ID_PANEL" };
    private static final String[] OFF = { "DEVICE_ID_NONE" };

    private final String className;
    private Class<?> kind;
    private Object instance;
    private boolean looked;

    private String problem = "";
    private String working = "";

    public DisplayApi() {
        this(DISPLAY_MANAGER);
    }

    /** The same, against a named class, so a test can point it at a stand-in. */
    public DisplayApi(String className) {
        this.className = className;
    }

    /** Why the panel cannot be switched, or an empty string. */
    public String problem() {
        return problem;
    }

    /** {@code method(value)} of the call that worked, or an empty string. */
    public String working() {
        return working;
    }

    /**
     * Whether the display can be switched at all, asked without switching anything.
     *
     * <p>A body that cannot says nothing more than {@code false}: {@link #problem()} carries which shapes were
     * looked for, which is the string the panel shows when the row has to admit it is unavailable.
     */
    public boolean supported() {
        if (!looked) {
            looked = true;
            kind = null;
            problem = "";
            try {
                kind = Class.forName(className);
            } catch (Throwable t) {
                problem = shortName(t);
                return false;
            }
            StringBuilder tried = new StringBuilder();
            for (Object[] shape : SHAPES) {
                if (method(shape) != null) return true;
                if (tried.length() > 0) tried.append(' ');
                tried.append(shape[0]).append('(').append(simple((Class<?>) shape[1])).append(')');
            }
            problem = "no " + tried;
        }
        return problem.length() == 0;
    }

    /**
     * Put the output on {@code device}, trying every shape until one answers.
     *
     * <p>The instance is made once and kept: upstream creates one {@code DisplayManager} for the life of the
     * app, its constructor is not free, and a run switches the output twice per frame. A body whose constructor
     * needs something it cannot have comes back as a {@link #problem()} naming the throwable.
     */
    public boolean switchTo(String[] names) {
        if (!supported()) return false;
        if (!open()) return false;
        for (Object[] shape : SHAPES) {
            Method method = method(shape);
            if (method == null) continue;
            boolean wantsString = shape[1] == String.class;
            for (String name : names) {
                Object value = constant(name, wantsString);
                if (value == null) continue;
                try {
                    Object result = method.invoke(instance, value);
                    // A void call is this API's actual shape, so coming back without throwing is as much as the
                    // platform will say. A call that returns a boolean says more, and is believed.
                    if (result instanceof Boolean && !((Boolean) result)) continue;
                    working = shape[0] + "(" + name + ")";
                    problem = "";
                    return true;
                } catch (Throwable t) {
                    problem = shortName(t);
                }
            }
        }
        if (problem.length() == 0) problem = "no call took: " + join(names);
        return false;
    }

    /** The same, for the two devices this app uses: the panel, and nothing. */
    public boolean off() {
        return switchTo(OFF);
    }

    public boolean on() {
        return switchTo(PANEL);
    }

    /**
     * Take this application's own graphics off the panel, without switching the output.
     *
     * <p>The blunt instrument, and the second thing an output switch turns out not to be enough for: on the
     * camera this was measured on, {@code switchDisplayOutputTo(DEVICE_ID_NONE)} was accepted, reported success,
     * and left the screen showing the frame that had just been taken — the app's own drawing was gone, so the
     * switch had done something, but the panel was still lit and still displaying. So both are used: the output
     * switch says which device the body should drive, and this says whether this app's layer is drawn on it.
     *
     * @param visible {@code false} to stop drawing the application's graphics output, {@code true} to resume.
     */
    public boolean graphicsOutput(boolean visible) {
        if (!supported() || !open()) return false;
        try {
            Object result = kind.getMethod("controlGraphicsOutputAll", boolean.class)
                    .invoke(instance, visible);
            if (result instanceof Boolean && !((Boolean) result)) return false;
            return true;
        } catch (Throwable t) {
            problem = shortName(t);
            return false;
        }
    }

    /**
     * What the body says its active display output is, or an empty string when it will not say.
     *
     * <p>Read <em>before</em> a switch and not after it: the question it answers is "what was the output until
     * now", and a read taken after the call came back empty on this body often enough to be visible in the log
     * as an {@code on} line with no {@code active=} on it. Before the call it is a fact; after the call it is a
     * race with the firmware.
     */
    public String activeDevice() {
        if (!supported() || !open()) return "";
        try {
            Object device = kind.getMethod("getActiveDevice").invoke(instance);
            return device instanceof String ? (String) device : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Whether the panel is on the list of devices this body reports as attached.
     *
     * <p>The blunt question behind a switch that does not appear to do anything: if the panel is not in
     * {@code getActiveDevices()}, the app is switching an output the body is not driving, and no amount of
     * repeating the call will darken the glass.
     */
    public String activeDevices() {
        if (!supported() || !open()) return "";
        try {
            Object devices = kind.getMethod("getActiveDevices").invoke(instance);
            return devices instanceof String[] ? join((String[]) devices, ",") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Let the display object go.
     *
     * <p>Upstream does this on the way out of the app ({@code releaseDisplayStatusListener()} and
     * {@code finish()}, from the PMCADemo the old app vendored), and an app that keeps a display handle across
     * a whole night is holding a camera resource for no reason. Called from {@code onPause}, and resilient: a
     * body that will not let go is reported and then left alone.
     */
    public void release() {
        if (instance == null) return;
        Object held = instance;
        instance = null;
        try {
            held.getClass().getMethod("releaseDisplayStatusListener").invoke(held);
        } catch (Throwable t) {
            // No listener was registered, so there is nothing to release: not worth a word.
        }
        try {
            held.getClass().getMethod("finish").invoke(held);
        } catch (Throwable t) {
            problem = shortName(t);
        }
    }

    /**
     * The class's whole surface, as text for the card's device dump.
     *
     * <p>This is the report that would have prevented the {@code int}-versus-{@code String} round: the real
     * signature, on the card, written before anyone guessed at a method name.
     */
    public String apiReport() {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> type = kind != null ? kind : Class.forName(className);
            b.append(className).append('\n');
            for (Method m : type.getMethods()) {
                b.append("  method ").append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(simple(params[i]));
                }
                b.append(") -> ").append(simple(m.getReturnType())).append('\n');
            }
            b.append("  constants:");
            for (Field f : type.getFields()) {
                b.append(' ').append(f.getName()).append('=');
                try {
                    b.append(f.get(null));
                } catch (Throwable t) {
                    b.append('?');
                }
            }
            b.append('\n');
        } catch (Throwable t) {
            b.append(className).append(": ").append(shortName(t)).append('\n');
        }
        b.append("  tries ").append(working.length() > 0 ? working : "nothing yet");
        if (problem.length() > 0) b.append("  problem=").append(problem);
        return b.toString();
    }

    // ---------------------------------------------------------------- reflection
    /** The display object, made once. The class is not a singleton and has no static accessor. */
    private boolean open() {
        if (instance != null) return true;
        try {
            instance = kind.newInstance();
            return true;
        } catch (Throwable t) {
            problem = shortName(t);
            return false;
        }
    }

    private Method method(Object[] shape) {
        if (kind == null) return null;
        try {
            return kind.getMethod((String) shape[0], (Class<?>) shape[1]);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A constant's value in the shape this call wants, or {@code null} when the body has no such name.
     *
     * <p>Read as whatever the field actually holds rather than as the type this class expected. The device ids
     * are {@code String}s, and reading them as {@code Integer}s is what made the first version report a body
     * that has the call as a body that cannot switch its display.
     */
    private Object constant(String name, boolean wantsString) {
        try {
            Object value = kind.getField(name).get(null);
            if (value == null) return null;
            if (wantsString) return value instanceof String ? value : value.toString();
            return value instanceof Number ? ((Number) value).intValue() : null;
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
        return join(names, "/");
    }

    private static String join(String[] names, String between) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) b.append(between);
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
