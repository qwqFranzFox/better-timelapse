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

    /**
     * The preview surface, and whether the preview is running.
     *
     * <p>A capture on this platform needs a live preview stream, and this app draws its whole UI on a
     * Canvas with no preview at all. The stream is not for the user to look at — the panel covers it —
     * but the body will not take a photograph without one.
     */
    private android.view.SurfaceHolder holder;
    private boolean previewing;
    private String previewProblem = "";

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

        public String summary(boolean preview) {
            StringBuilder b = new StringBuilder();
            b.append(model.length() > 0 ? model : "?");
            if (platform.length() > 0) b.append(" / ").append(platform);
            b.append("  silent=").append(tri(silentShutter));
            b.append(" drive=").append(tri(driveMode));
            b.append(" ael=").append(tri(autoExposureLock));
            b.append(" burst=").append(tri(burstTakePicture));
            b.append(" preview=").append(preview ? "yes" : "no");
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
            // A surface that arrived before the camera was open is attached now; one that arrives later
            // comes in through attachPreview().
            if (holder != null) attachPreview(holder);
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
     * The press: hand the capture to the camera.
     *
     * <p>{@code Camera.takePicture} rather than {@code CameraEx.burstableTakePicture}, because that is
     * what the one app known to shoot frames on this body uses — Recipe Lab's sample run, 77 frames on
     * an ILCE-6001 with firmware 3.21 ({@code MainActivity.java:566}). The burstable call is refused
     * here ("BurstableTakePicture failed"), and the platform's own idiom is a press and a release:
     * {@code takePicture}, then a couple of seconds later {@code cancelTakePicture} — Recipe Lab calls
     * it "the shutter key's press / release, automated" ({@code DEVELOPMENT.md}).
     *
     * <p>All three callbacks are null on purpose: the camera writes the file itself, and this app does
     * not want the JPEG bytes — the card is how it knows whether the frame arrived.
     */
    public boolean fire() {
        if (camera == null) return false;
        try {
            camera.takePicture(null, null, null);
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = shortName(t);
            return false;
        }
    }

    /**
     * The release: {@code cancelTakePicture}, then the preview again.
     *
     * <p>Both halves matter. {@code takePicture} holds the shutter until it is cancelled, and a capture
     * on a plain Android camera leaves the preview stopped — "nothing is applied to a stopped preview"
     * (Recipe Lab {@code resumePreview}), so the next frame would be refused, or taken with whatever
     * settings were live before it started.
     */
    public void release() {
        if (cameraEx != null) {
            try {
                cameraEx.getClass().getMethod("cancelTakePicture").invoke(cameraEx);
            } catch (Throwable t) {
                // A body without the call keeps the frame; nothing to report.
            }
        }
        resumePreview();
    }

    /** Start the preview again. Silent: a body that restarts it by itself simply does not need this. */
    public void resumePreview() {
        if (camera == null || holder == null) return;
        try {
            camera.startPreview();
            previewing = true;
            previewProblem = "";
        } catch (Throwable t) {
            previewing = false;
            previewProblem = shortName(t);
        }
    }

    /**
     * Hand the camera a preview surface.
     *
     * <p>Called when the surface is created, which is usually before the camera is open, so the holder
     * is kept and the work is done whichever way round the two happen.
     */
    public boolean attachPreview(android.view.SurfaceHolder surface) {
        holder = surface;
        if (camera == null) return false;
        try {
            camera.setPreviewDisplay(surface);
            camera.startPreview();
            previewing = true;
            previewProblem = "";
            return true;
        } catch (Throwable t) {
            previewing = false;
            previewProblem = shortName(t);
            lastError = previewProblem;
            return false;
        }
    }

    /**
     * Whether a key is still physically down, straight from the camera, or {@code null} when it will not
     * say.
     *
     * <p>Needed because a body can lose the key-release event: a short press then looks exactly like a
     * hold, and on this app the difference between those two is a stopped run. {@code null} means "no
     * answer", and the caller treats that as a hold — the worst case is a question, not a lost shoot.
     */
    public Boolean keyDown(int scan) {
        try {
            Class<?> input = Class.forName("com.sony.scalar.sysutil.ScalarInput");
            Object status = input.getMethod("getKeyStatus", int.class).invoke(null, scan);
            if (status == null) return null;
            Object value = status.getClass().getField("status").get(status);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * What the body itself holds for the two rows this app can read back.
     *
     * <p>Used to seed the plan at open, so "wanted ≠ stored" and the exit rule are statements about the
     * camera rather than about this app's defaults. {@code Frames.UNKNOWN} means the body would not say.
     */
    public int silentShutterHeld() {
        String value = readKey("silent-shutter-mode");
        if (value == null) return Frames.UNKNOWN;
        String v = value.trim().toLowerCase(java.util.Locale.US);
        if ("true".equals(v) || "on".equals(v) || "1".equals(v)) return 1;
        if ("false".equals(v) || "off".equals(v) || "0".equals(v)) return 0;
        return Frames.UNKNOWN;
    }

    /** The body's own drive mode as a {@code Plan.DRIVE_*}, or {@code Frames.UNKNOWN}. */
    public int driveModeHeld() {
        String value = readKey("drive-mode");
        if (value == null) return Frames.UNKNOWN;
        String v = value.trim().toLowerCase(java.util.Locale.US);
        if (v.startsWith("single")) return Plan.DRIVE_SINGLE;
        if (v.startsWith("burst")) return Plan.DRIVE_BURST;
        if (v.startsWith("bracket")) return Plan.DRIVE_BRACKET3;
        return Frames.UNKNOWN;
    }

    /** Why the preview is not running, or empty. Without one, a capture is refused. */
    public String previewProblem() {
        return previewProblem;
    }

    public boolean previewing() {
        return previewing;
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
            // Deliberately no apply here: an untouched modifier has nothing to apply, and attempting
            // it marked the whole channel unavailable before a single setting had been made.
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
            // Only the names that could plausibly carry a parameter modifier, plus a count of the
            // rest. A full dump of every method would wrap past the screen and bury the answer.
            int others = 0;
            for (java.lang.reflect.Method m : methods) {
                if (m.getDeclaringClass() == Object.class) continue;
                String name = m.getName();
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.indexOf("param") < 0 && lower.indexOf("modif") < 0
                        && lower.indexOf("silent") < 0) {
                    others++;
                    continue;
                }
                b.append(name).append('(');
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(p[i].getSimpleName());
                }
                b.append(") ");
            }
            b.append("| ").append(others).append(" other methods");
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
    /**
     * Push a modified parameter set back into the camera.
     *
     * <p>The old app's build files suggested the modifier applies itself with
     * {@code applyParameters(Camera.Parameters)}. On this body that method does not exist — the call
     * raised {@code NoSuchMethodException} — and because every camera-side setting travels through
     * here, silent shutter, drive mode and exposure lock were all dead while their rows read as
     * applied. Three shapes are tried, and the one that worked is recorded rather than assumed.
     */
    private void applyModifier(Object mod) {
        if (applyModifierWith(mod, "applyParameters", new Class<?>[] { android.hardware.Camera.Parameters.class })) return;
        if (applyModifierWith(mod, "applyParameters", new Class<?>[] { android.hardware.Camera.class })) return;
        if (applyModifierWith(mod, "apply", new Class<?>[0])) return;
        modifierProblem = "no apply method worked; modifier methods: " + modifierMethods();
    }

    private boolean applyModifierWith(Object mod, String name, Class<?>[] types) {
        try {
            Object[] args = types.length == 0 ? new Object[0]
                    : new Object[] { types[0] == android.hardware.Camera.class ? camera : paramsOf() };
            Object result = mod.getClass().getMethod(name, types).invoke(mod, args);
            // A modifier that hands back a Parameters is telling us where the result went.
            if (result instanceof android.hardware.Camera.Parameters) {
                camera.setParameters((android.hardware.Camera.Parameters) result);
            }
            applyMethod = name + "/" + types.length;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Which apply shape worked, e.g. {@code applyParameters/1}; empty before one has. */
    private String applyMethod = "";

    public String applyMethod() {
        return applyMethod;
    }

    /**
     * Set a live camera parameter by the name the camera itself uses, straight into
     * {@code Camera.Parameters}. This is the route that does not depend on any Sony method name: the
     * camera declares its own parameter strings, and {@link #parameterKeys()} reads them back.
     */
    public boolean setParameterKey(String key, String value) {
        if (camera == null) return false;
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            params.set(key, value);
            camera.setParameters(params);
            // Write, then read back. Setting an unknown key does not throw, so "no exception" is not
            // "it took" — and a setting that silently did nothing is the bug this whole round is about.
            android.hardware.Camera.Parameters after = camera.getParameters();
            String read = after.get(key);
            return read != null && read.equalsIgnoreCase(value);
        } catch (Throwable t) {
            lastError = shortName(t);
            return false;
        }
    }

    /**
     * Try each key a body might use for the silent shutter, and report which one the camera accepted.
     * The name is the camera's, not the SDK's, so it is asked for rather than assumed.
     */
    /**
     * Ask the body, both ways, how the silent shutter is switched, and report which way answered.
     *
     * <p>Two routes exist and neither can be assumed: the camera's own parameter string (no Sony
     * method needed) and the Sony parameter modifier (no parameter name needed). On this body the
     * parameter key exists — it reads back {@code false} — but rejects every spelling of "on", so the
     * question is whether it is read-only, and whether the modifier can do what the key cannot.
     */
    public String probeSilentShutterKeys() {
        if (camera == null) return "(no camera)";
        String original = readKey("silent-shutter-mode");
        StringBuilder b = new StringBuilder();

        // Route 1: the camera's own parameter, every plausible spelling of "on".
        b.append("key:");
        String[] values = { "on", "true", "1" };
        boolean took = false;
        for (int i = 0; i < values.length; i++) {
            if (setParameterKey("silent-shutter-mode", values[i])) {
                b.append(values[i]).append("=OK");
                took = true;
                break;
            }
        }
        if (!took) b.append("=").append(original == null ? "absent" : "readonly");
        if (original != null) setParameterKeyRaw("silent-shutter-mode", original);

        // Route 2: the Sony modifier, if this body has one.
        b.append(" modifier:");
        if (modifier == null) {
            b.append("none(").append(modifierProblem.length() > 0 ? modifierProblem : "?").append(')');
        } else {
            boolean byBoolean = callModifierQuiet("setSilentShutterMode", new Class<?>[] { boolean.class },
                    new Object[] { Boolean.TRUE });
            boolean byObject = !byBoolean && callModifierQuiet("setSilentShutterMode",
                    new Class<?>[] { Boolean.class }, new Object[] { Boolean.TRUE });
            boolean byString = !byBoolean && !byObject && callModifierQuiet("setSilentShutterMode",
                    new Class<?>[] { String.class }, new Object[] { "on" });
            b.append(byBoolean ? "boolean=OK" : byObject ? "Boolean=OK" : byString ? "String=OK"
                    : "no method(" + modifierProblem + ")");
            if (byBoolean || byObject || byString) setSilentShutterQuiet(false);
        }

        b.append(" | drive:").append(readKey("drive-mode"));
        return b.toString();
    }

    /** Apply a modifier call, silently: for probing, where a failure is an answer and not an error. */
    private boolean callModifierQuiet(String name, Class<?>[] types, Object[] args) {
        try {
            modifier.getClass().getMethod(name, types).invoke(modifier, args);
            applyModifierQuiet();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyModifierQuiet() {
        String saved = modifierProblem;
        applyModifier(modifier);
        modifierProblem = saved;
    }

    private void setSilentShutterQuiet(boolean on) {
        callModifierQuiet("setSilentShutterMode", new Class<?>[] { boolean.class },
                new Object[] { Boolean.valueOf(on) });
    }


    /** Write without the read-back check, for putting a value back the way it was. */
    private boolean setParameterKeyRaw(String key, String value) {
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            params.set(key, value);
            camera.setParameters(params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether writing the key changes anything when it is read back. */
    private String isWritable(String key, String original) {
        if (original == null) return "unknown";
        String probe = "on".equalsIgnoreCase(original) ? "off" : "on";
        boolean took = setParameterKey(key, probe);
        setParameterKeyRaw(key, original);
        return took ? "yes" : "no";
    }


    /**
     * What the camera holds right now for the settings this app touches, read back from the body.
     *
     * <p>The successor to the probe: the probe asked which spelling the body accepts, this says
     * whether the writes that followed actually landed. A setting that was sent is not a setting that
     * took, and only a read-back tells the two apart.
     */
    public String settingsReport() {
        if (camera == null) return "(no camera)";
        StringBuilder b = new StringBuilder();
        String[] keys = { "silent-shutter-mode", "drive-mode" };
        for (int i = 0; i < keys.length; i++) {
            String v = readKey(keys[i]);
            b.append(keys[i]).append('=').append(v == null ? "absent" : v).append(i == 0 ? "  " : "");
        }
        return b.toString();
    }

    private String readKey(String key) {
        try {
            return camera.getParameters().get(key);
        } catch (Throwable t) {
            return null;
        }
    }


    /**
     * Every parameter name the body reports, from {@code Camera.Parameters.flatten()}. Guessing these
     * is what turned "silent shutter" into a dead setting: the names are the camera's, not the SDK's.
     */
    /**
     * Every live parameter, exactly as the camera reports it.
     *
     * <p>The unfiltered answer to "what will this body tell us about itself", for the device dump. The
     * filtered version below is what fits on a screen; a key nobody thought to look for only shows up here.
     */
    public String parametersFlat() {
        if (camera == null) return "(no camera)";
        try {
            String flat = camera.getParameters().flatten();
            if (flat == null) return "(no parameters)";
            // One per line: flatten() uses ';' and the values contain commas, so this is the readable form.
            return flat.replace(";", "\n");
        } catch (Throwable t) {
            return "(flatten failed: " + shortName(t) + ")";
        }
    }

    public String parameterKeys() {
        if (camera == null) return "(no camera)";
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            String flat = params.flatten();
            if (flat == null) return "(no parameters)";
            String[] lines = flat.split(";");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int eq = line.indexOf('=');
                String key = eq > 0 ? line.substring(0, eq) : line;
                String lower = key.toLowerCase(java.util.Locale.US);
                // The ones a time-lapse could need, plus anything Sony-flavoured.
                if (lower.indexOf("silent") >= 0 || lower.indexOf("drive") >= 0
                        || lower.indexOf("burst") >= 0 || lower.indexOf("bracket") >= 0
                        || lower.indexOf("shutter") >= 0 || lower.indexOf("focus") >= 0
                        || lower.indexOf("review") >= 0) {
                    b.append(key).append(' ');
                }
            }
            return b.length() == 0 ? "(no matching keys of " + lines.length + ")" : b.toString();
        } catch (Throwable t) {
            return "(flatten failed: " + shortName(t) + ")";
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
    /**
     * The drive mode, by the name the camera itself reports.
     *
     * <p>{@code drive-mode} reads back as {@code single} on this body, lower case: the values are the
     * camera's, and an upper-case constant from the SDK is a different string as far as it is
     * concerned.
     */
    public boolean setDriveMode(String mode) {
        String value = mode == null ? "" : mode.toLowerCase(java.util.Locale.US);
        if (setParameterKey("drive-mode", value)) return true;
        return callModifier("setDriveMode", new Class<?>[] { String.class }, new Object[] { mode });
    }


    /** Silent shutter, where the body has it. */
    /**
     * The silent shutter, by whichever route this body accepts.
     *
     * <p>The value came from the camera, not from a guess: asked to accept every plausible spelling,
     * it took {@code "true"} and rejected {@code "on"} — which is why the setting did nothing while
     * the code looked correct. The Sony modifier answers as well, so it is the second route rather
     * than the only one.
     */
    public boolean setSilentShutter(boolean on) {
        if (setParameterKey("silent-shutter-mode", on ? "true" : "false")) return true;
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
