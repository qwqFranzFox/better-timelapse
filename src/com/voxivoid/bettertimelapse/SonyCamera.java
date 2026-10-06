package com.voxivoid.bettertimelapse;

import java.lang.reflect.Method;

/**
 * The one place the app touches Sony's classes.
 *
 * <p>The build has no Sony stub jar, so a direct import of {@code CameraEx} would not compile. Every
 * call here goes through {@link Class#forName} and is wrapped in {@code catch (Throwable)}: off the
 * camera — in {@code tools/test.sh}, or on a body whose firmware lacks a method — the app degrades
 * and says so instead of throwing. A reflection failure is a normal branch, not an error path.
 *
 * <p>Nothing here decides anything. Timing lives in {@link Engine} and {@link Schedule}; this class
 * only holds {@code CameraEx} and turns its quirks into a small, honest interface. Every method
 * reports what actually happened: {@code false} from {@link #fire()} means nothing was handed to the
 * camera, which is what stops the app from counting frames it never took — the failure the old app
 * shipped (docs/PAIN-POINTS.md U-06, U-16).
 *
 * <p>What was measured on a real body and what was only read from the old app's source is recorded in
 * {@code docs/PLATFORM-FACTS.md} §4. Method names on the parameter modifier come from the sources
 * that project compiled against; until {@link #capabilities()} has been run on a camera, they are
 * unverified there.
 */
public final class SonyCamera {

    private static final String CAMERA_EX = "com.sony.scalar.hardware.CameraEx";
    private static final String MODIFIER = "com.sony.scalar.hardware.CameraEx$ParametersModifier";
    private static final String PROPS = "com.sony.scalar.sysutil.ScalarProperties";

    private Object cameraEx;
    private android.hardware.Camera camera;
    private Object modifier;

    /**
     * Why the parameter modifier could not be created; empty when it was.
     *
     * <p>Every camera-side setting the app makes — silent shutter, drive mode, exposure lock — travels
     * through this one object. When {@code createParametersModifier} fails, every one of those calls
     * returns false and the settings do nothing, which is indistinguishable from "the body does not
     * support it" unless the reason is kept. The first version of this class dropped it in a bare
     * catch, so silent shutter silently did nothing.
     */
    private String modifierProblem = "";
    private Object shutterListener;
    private String lastError = "";

    // ---------------------------------------------------------------- capabilities, asked not guessed
    /**
     * What this body reports about itself. Every field is optional: {@code null} means the camera
     * would not say, and the panel shows that instead of assuming.
     */
    public static final class Capabilities {
        public String model = "";
        public String platform = "";
        public Boolean silentShutter;
        public Boolean driveMode;
        public Boolean autoExposureLock;
        public Boolean burstTakePicture;

        /** Why the parameter modifier is missing, or empty. */
        public String modifierProblem = "";

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(model.length() > 0 ? model : "?");
            if (platform.length() > 0) b.append(" / ").append(platform);
            b.append("  silent=").append(tri(silentShutter));
            b.append(" drive=").append(tri(driveMode));
            b.append(" ael=").append(tri(autoExposureLock));
            b.append(" burst=").append(tri(burstTakePicture));
            if (modifierProblem.length() > 0) b.append(" modifier=").append(modifierProblem);
            return b.toString();
        }

        private static String tri(Boolean b) {
            return b == null ? "?" : (b ? "yes" : "no");
        }
    }

    // ---------------------------------------------------------------- open / close
    /**
     * Open the camera. Returns false and records a reason when the body has no {@code CameraEx} or
     * refuses to open — the caller runs without a camera rather than crashing.
     */
    public boolean open() {
        try {
            Class<?> cx = Class.forName(CAMERA_EX);
            Class<?> options = Class.forName(CAMERA_EX + "$OpenOptions");
            Object instance = cx.getMethod("open", int.class, options).invoke(null, 0, null);
            if (instance == null) {
                lastError = "CameraEx.open returned null";
                return false;
            }
            cameraEx = instance;
            camera = (android.hardware.Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
            if (camera == null) {
                lastError = "getNormalCamera returned null";
                close();
                return false;
            }
            modifier = createModifier();
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = shortName(t);
            cameraEx = null;
            camera = null;
            return false;
        }
    }

    /** Release the camera. Safe to call twice, and safe after a failed {@link #open()}. */
    public void close() {
        Object instance = cameraEx;
        cameraEx = null;
        camera = null;
        modifier = null;
        if (instance == null) return;
        try {
            instance.getClass().getMethod("release").invoke(instance);
        } catch (Throwable t) {
            // Nothing useful to do at this point, and throwing here would mask the real error.
        }
    }

    public boolean isOpen() {
        return camera != null;
    }

    /** Why the last {@link #open()} failed; empty when it did not. */
    public String lastError() {
        return lastError;
    }

    /** The platform camera, for the parts of the app that set live parameters. */
    public android.hardware.Camera raw() {
        return camera;
    }

    // ---------------------------------------------------------------- the shutter
    /**
     * Take one frame. Returns false when the call itself failed, which is the only honest signal the
     * platform gives: {@code burstableTakePicture} returns void, so a frame that the camera accepts
     * and then drops on the floor is caught later by {@link Frames}, not here.
     */
    public boolean fire() {
        if (cameraEx == null) return false;
        try {
            cameraEx.getClass().getMethod("burstableTakePicture").invoke(cameraEx);
            return true;
        } catch (Throwable t) {
            lastError = shortName(t);
            return false;
        }
    }

    /** Abandon the frame in flight. Called on the way out of a shot and on release. */
    public void cancel() {
        if (cameraEx == null) return;
        try {
            cameraEx.getClass().getMethod("cancelTakePicture").invoke(cameraEx);
        } catch (Throwable t) {
            // A body without the call keeps the frame; nothing to report.
        }
    }

    /**
     * Register the shutter callback. The listener is an object whose single method the firmware
     * calls with {@code (int, CameraEx)}; {@link #invokeShutterListener} reflects it back out.
     *
     * <p>Implemented through a dynamic proxy rather than a compiled interface, because implementing
     * {@code CameraEx$ShutterListener} directly is exactly the Sony import this class exists to
     * avoid.
     */
    public boolean setShutterListener(final ShutterEvents events) {
        if (cameraEx == null) return false;
        try {
            Class<?> listenerClass = Class.forName(CAMERA_EX + "$ShutterListener");
            shutterListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(), new Class<?>[] { listenerClass },
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onShutter".equals(method.getName())) events.onShutter();
                            return null;
                        }
                    });
            cameraEx.getClass().getMethod("setShutterListener", listenerClass).invoke(cameraEx, shutterListener);
            return true;
        } catch (Throwable t) {
            shutterListener = null;
            lastError = shortName(t);
            return false;
        }
    }

    public void clearShutterListener() {
        shutterListener = null;
        if (cameraEx == null) return;
        try {
            Class<?> listenerClass = Class.forName(CAMERA_EX + "$ShutterListener");
            cameraEx.getClass().getMethod("setShutterListener", listenerClass).invoke(cameraEx, (Object) null);
        } catch (Throwable t) {
            // Best effort on the way out.
        }
    }

    /** What the shell wants to happen when the body says a frame was taken. */
    public interface ShutterEvents {
        void onShutter();
    }

    // ---------------------------------------------------------------- live parameters
    private Object createModifier() {
        modifierProblem = "";
        if (camera == null) {
            modifierProblem = "no camera";
            return null;
        }
        // The names and signatures here come from the old app's build files, not from this body. A
        // reflection lookup that fails says only "not that one", so the real signature list is kept
        // and shown: guessing again is how silent shutter stayed broken for a round.
        String[] names = { "createParametersModifier", "createParameterModifier", "getParametersModifier" };
        for (int i = 0; i < names.length; i++) {
            Object made = tryModifier(names[i], new Class<?>[] { android.hardware.Camera.Parameters.class },
                    new Object[] { paramsOf() });
            if (made != null) return made;
        }
        for (int i = 0; i < names.length; i++) {
            Object made = tryModifier(names[i], new Class<?>[] { android.hardware.Camera.class },
                    new Object[] { camera });
            if (made != null) return made;
        }
        for (int i = 0; i < names.length; i++) {
            Object made = tryModifier(names[i], new Class<?>[0], new Object[0]);
            if (made != null) return made;
        }
        // Nothing worked. Record what the class actually offers, so the next round is not a guess.
        modifierProblem = "no factory matched; CameraEx methods: " + cameraExMethods();
        return null;
    }

    private android.hardware.Camera.Parameters paramsOf() {
        try {
            return camera.getParameters();
        } catch (Throwable t) {
            return null;
        }
    }

    private Object tryModifier(String name, Class<?>[] types, Object[] args) {
        try {
            Object made = cameraEx.getClass().getMethod(name, types).invoke(cameraEx, args);
            if (made == null) {
                modifierProblem = name + " returned null";
                return null;
            }
            return made;
        } catch (Throwable t) {
            return null;   // try the next shape
        }
    }

    /**
     * Every method the body's CameraEx actually declares, in order, so a missing call can be replaced
     * by the one that exists instead of by another guess.
     */
    public String cameraExMethods() {
        if (cameraEx == null) return "(no camera)";
        StringBuilder b = new StringBuilder();
        try {
            java.lang.reflect.Method[] methods = cameraEx.getClass().getMethods();
            java.util.Arrays.sort(methods, new java.util.Comparator<java.lang.reflect.Method>() {
                @Override
                public int compare(java.lang.reflect.Method a, java.lang.reflect.Method c) {
                    return a.getName().compareTo(c.getName());
                }
            });
            for (java.lang.reflect.Method m : methods) {
                if (m.getDeclaringClass() == Object.class) continue;
                b.append(m.getName()).append('(');
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(p[i].getSimpleName());
                }
                b.append(") ");
            }
        } catch (Throwable t) {
            b.append("(listing failed: ").append(shortName(t)).append(')');
        }
        return b.toString();
    }


    /** Why the parameter modifier is unavailable; empty when it is. */
    public String modifierProblem() {
        return modifierProblem;
    }

    /**
     * Push the modifier's changes into the live parameters, then into the camera. The old app built
     * the modifier and then called {@code setParameters} unconditionally, which is why a body that
     * rejected one setting still looked configured (TimeLapse {@code ShootActivity.java:213}).
     */
    private void applyModifier(Object mod) {
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            mod.getClass().getMethod("applyParameters", android.hardware.Camera.Parameters.class).invoke(mod, params);
            camera.setParameters(params);
        } catch (Throwable t) {
            lastError = shortName(t);
            modifierProblem = "applyParameters/setParameters: " + lastError;
        }
    }

    /** Ask the body what it supports, once. Every answer is allowed to be unknown. */
    public Capabilities capabilities() {
        Capabilities caps = new Capabilities();
        caps.model = property("model.name");
        caps.platform = property("version.platform");
        if (modifier != null) {
            caps.silentShutter = supports("isSupportedSilentShutterMode");
            caps.driveMode = supports("isSupportedDriveMode");
            caps.autoExposureLock = supports("isSupportedAutoExposureLock");
        }
        caps.burstTakePicture = hasMethod(cameraEx, "burstableTakePicture");
        caps.modifierProblem = modifierProblem;
        return caps;
    }

    /**
     * Every method this body's parameter modifier actually has, lower-cased, and the field names it
     * exposes. The app reaches the camera by reflection with no stub jar to compile against, so the
     * only trustworthy list of what exists is the one the camera hands back. Guessing method names
     * from the old app's build files is how "silent shutter" ends up doing nothing.
     */
    public String modifierMethods() {
        if (modifier == null) {
            return modifierProblem.length() > 0
                    ? "(no modifier: " + modifierProblem + ")"
                    : "(no modifier)";
        }
        StringBuilder b = new StringBuilder();
        try {
            java.lang.reflect.Method[] methods = modifier.getClass().getMethods();
            java.util.Arrays.sort(methods, new java.util.Comparator<java.lang.reflect.Method>() {
                @Override
                public int compare(java.lang.reflect.Method a, java.lang.reflect.Method c) {
                    return a.getName().compareTo(c.getName());
                }
            });
            for (java.lang.reflect.Method m : methods) {
                if (m.getDeclaringClass() == Object.class) continue;
                if (b.length() > 0) b.append(' ');
                b.append(m.getName());
            }
            b.append(" || fields:");
            for (java.lang.reflect.Field f : modifier.getClass().getFields()) {
                b.append(' ').append(f.getName());
            }
        } catch (Throwable t) {
            b.append("(listing failed: ").append(shortName(t)).append(')');
        }
        return b.toString();
    }

    private Boolean supports(String method) {
        try {
            Object v = modifier.getClass().getMethod(method).invoke(modifier);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasMethod(Object target, String name) {
        if (target == null) return false;
        try {
            target.getClass().getMethod(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- drive mode and options
    /**
     * Set the drive mode by the name the camera menus use. Returns false when the body has no such
     * call or refuses the value, so the caller can tell the user rather than leaving them with a
     * setting that silently did not apply.
     */
    public boolean setDriveMode(String mode) {
        return callModifier("setDriveMode", new Class<?>[] { String.class }, new Object[] { mode });
    }

    /** Silent shutter, where the body has it. */
    public boolean setSilentShutter(boolean on) {
        return callModifier("setSilentShutterMode", new Class<?>[] { boolean.class },
                new Object[] { Boolean.valueOf(on) });
    }

    /** Lock the exposure after the first frame, for consistency across a long run. */
    public boolean setAutoExposureLock(boolean on) {
        if (modifier == null) return false;
        try {
            Object constant = modifier.getClass().getField(on ? "AE_LOCK_ON" : "AE_LOCK_OFF").get(null);
            return callModifier("setAutoExposureLock", new Class<?>[] { constant.getClass() },
                    new Object[] { constant });
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean callModifier(String name, Class<?>[] types, Object[] args) {
        if (modifier == null) return false;
        try {
            modifier.getClass().getMethod(name, types).invoke(modifier, args);
            applyModifier(modifier);
            return true;
        } catch (Throwable t) {
            lastError = shortName(t);
            return false;
        }
    }

    // ---------------------------------------------------------------- what the body says about itself
    /** A {@code ScalarProperties} string, or {@code ""}. */
    public static String property(String key) {
        try {
            Object v = Class.forName(PROPS).getMethod("getString", String.class).invoke(null, key);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** The body's model name, or {@code ""}. {@code Build.MODEL} is {@code ScalarA} everywhere. */
    public static String model() {
        return property("model.name");
    }

    /** The platform version, or {@code ""}. */
    public static String platform() {
        return property("version.platform");
    }

    /** A short, readable form of a reflection failure: the class name, and the message if there is one. */
    private static String shortName(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        // InvocationTargetException wraps the real cause, and its own message says nothing.
        if (t.getCause() != null) {
            message = t.getCause().getMessage();
            name = t.getCause().getClass().getSimpleName();
        }
        return message == null || message.length() == 0 ? name : name + ": " + message;
    }
}
