package com.voxivoid.bettertimelapse;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * A file on the card that answers questions the panel cannot.
 *
 * <p>The question this exists for, right now: <em>can this app read the shutter speed?</em> The app's
 * minimum interval wants the exposure in it — a ten-second exposure and a two-second interval cannot both
 * be honoured — and the honest way to find out whether the camera will tell us the exposure is to ask it,
 * not to reason about it. The live parameter API is the cheapest place to ask
 * ({@code Camera.Parameters.flatten()}), the camera's settings store is the other, and
 * {@link SonyCamera#parameterKeys()} has been filtering the parameter list for a key with
 * {@code shutter} in its name since the skeleton was written — with no caller, because there was nowhere
 * to put an answer several screens long.
 *
 * <p>So there is somewhere now: a text file, written next to the camera's own tree where a card reader or
 * a USB cable can reach it. The panel keeps showing the two or three facts that fit on it; this is for the
 * rest, and for the parts that only mean something to whoever is reading the whole thing.
 *
 * <p>Deliberately not a log: it is overwritten on every launch, so it always describes the camera in front
 * of the user rather than the history of the app. The session log (docs/PLAN.md §5.2) is a different file
 * with a different job.
 */
public final class DevDump {

    /** The folder on the card. Next to the camera's own tree, so a card reader finds it. */
    public static final String DIR = "bettertimelapse";

    /** The file. One per launch, overwritten. */
    public static final String NAME = "device.txt";

    private DevDump() {}

    /** Where the dump goes, or an empty string when the card cannot be reached at all. */
    public static String path() {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null) return "";
            return new File(new File(root, DIR), NAME).getPath();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Write the report. Returns the path written, or an empty string with {@link #problem()} saying why.
     *
     * <p>Nothing is thrown at the caller: a diagnostic that crashes the app it is diagnosing is worse than
     * no diagnostic, and the failure is reported on the panel instead.
     */
    public static String write(String body) {
        problem = "";
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null) {
                problem = "no external storage";
                return "";
            }
            File dir = new File(root, DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                problem = "cannot create " + dir.getPath();
                return "";
            }
            File file = new File(dir, NAME);
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(body.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return file.getPath();
        } catch (Throwable t) {
            problem = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return "";
        }
    }

    private static String problem = "";

    /** Why the last write failed, or an empty string. */
    public static String problem() {
        return problem;
    }
}
