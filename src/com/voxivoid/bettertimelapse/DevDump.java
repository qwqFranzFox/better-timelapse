package com.voxivoid.bettertimelapse;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A file on the card that answers questions the panel cannot.
 *
 * <p>The question this exists for: <em>can this app read the shutter speed?</em> The minimum interval wants
 * the exposure in it — a ten-second exposure and a two-second interval cannot both be honoured — and the
 * honest way to find out whether the camera will say is to ask it rather than to reason about it.
 *
 * <p>The first version wrote to one path, the card root, and the camera refused it: on this platform an app
 * creating a directory next to {@code DCIM} is not something to count on. So every plausible place is tried
 * in order, from the platform's own app directory on the card (which exists for exactly this) through the
 * camera's own tree to the app's private storage — and which one worked, or why none did, is reported rather
 * than swallowed. A file nobody can retrieve is worth less than knowing that it could not be written.
 *
 * <p>{@link #attempts()} exists for the failure case: on this camera there is no adb and no way to pull an
 * app's private files, so when nothing on the card takes the file the panel has to say why, in one line.
 *
 * <p>Deliberately not a log: overwritten on every launch, so it describes the camera in front of the user
 * rather than the history of the app. The session log (docs/PLAN.md §5.2) is a different file with a
 * different job.
 */
public final class DevDump {

    /** The name, in every location it is tried under. */
    public static final String NAME = "bettertimelapse-device.txt";

    private DevDump() {}

    /** The path that worked, or an empty string. */
    private static String written = "";

    /** One line per distinct failure reason, for the panel. */
    private static String attempts = "";

    /** Whether the file landed somewhere the user can actually get at it. */
    private static boolean onCard;

    /**
     * Write the report to the first place that takes it.
     *
     * @return the path written, or an empty string with {@link #attempts()} saying what was tried.
     */
    public static String write(Context context, String body) {
        written = "";
        attempts = "";
        onCard = false;
        List<String> reasons = new ArrayList<String>();

        List<File> places = places(context);
        for (int i = 0; i < places.size(); i++) {
            File file = places.get(i);
            File dir = file.getParentFile();
            // Best effort: a directory that cannot be made is not reported on its own, because the write
            // below fails for the same reason and with a better message.
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            if (writeTo(file, body)) {
                written = file.getPath();
                onCard = !isInternal(context, file);
                return written;
            }
            reasons.add(shortReason(lastProblem));
        }
        attempts = summarise(reasons);
        return "";
    }

    /** The path that worked, or an empty string. */
    public static String written() {
        return written;
    }

    /** Whether the file is somewhere a card reader or a USB cable can reach. */
    public static boolean onCard() {
        return onCard;
    }

    /** What was tried and why it failed, as {@code Permission denied (4) Read-only file system (2)}. */
    public static String attempts() {
        return attempts;
    }

    /**
     * The facts that decide whether a write can work at all, in one line, for the panel.
     *
     * <p>Written for a specific surprise: the write failed on every path, and the conclusion drawn from that
     * — "this body does not let apps write to the card" — was wrong. The old TimeLapse app's own log
     * directory is sitting at the card root, created by {@code mkdirs()} from a PMCA app with exactly the
     * permissions this app declares. So the reason is something else, and a reason that is only ever guessed
     * at is not a diagnosis.
     *
     * <p>{@code canWrite()} on the directories that matter, because the answer may well be "the card is
     * writable and this app is not allowed to touch it" or "the card is mounted read-only right now" — two
     * different problems with two different fixes.
     */
    public static String probe(Context context) {
        StringBuilder b = new StringBuilder();
        b.append("\u6743\u9650 ").append(hasWritePermission(context) ? "\u6709" : "\u65e0");   // 权限 有/无
        b.append("  \u72b6\u6001 ").append(storageState());                                 // 状态 <...>
        b.append("  /mnt/sdcard\u53ef\u5199 ").append(writable("/mnt/sdcard"));               // 可写
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) b.append("  ext\u53ef\u5199 ").append(writable(ext.getPath()));
        } catch (Throwable t) {
            b.append("  ext=?");
        }
        return b.toString();
    }

    private static boolean hasWritePermission(Context context) {
        try {
            return context.checkCallingOrSelfPermission(
                    "android.permission.WRITE_EXTERNAL_STORAGE") == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String storageState() {
        try {
            String state = Environment.getExternalStorageState();
            return state == null ? "?" : state;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean writable(String path) {
        try {
            return new File(path).canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the places, in order

    /**
     * Where to try, best first.
     *
     * <p>The platform's own directory for this app on the card comes first: it exists for this purpose and
     * the platform creates it. Then the camera's own tree — {@code DCIM}, where files are demonstrably
     * written, and written as a file rather than a directory so nothing has to be created. Then the two
     * spellings of the card root, then private storage, which always works and cannot be retrieved.
     */
    private static List<File> places(Context context) {
        List<File> out = new ArrayList<File>();
        try {
            File appDir = context.getExternalFilesDir(null);
            if (appDir != null) out.add(new File(appDir, NAME));
        } catch (Throwable t) {
            // A platform without the call simply does not offer this place.
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                out.add(new File(new File(ext, "DCIM"), NAME));
                out.add(new File(ext, NAME));
                out.add(new File(new File(ext, "bettertimelapse"), NAME));
            }
        } catch (Throwable t) {
            // Fall through to the fixed spellings below.
        }
        out.add(new File("/mnt/sdcard/DCIM", NAME));
        out.add(new File("/mnt/sdcard", NAME));
        try {
            File internal = context.getFilesDir();
            if (internal != null) out.add(new File(internal, NAME));
        } catch (Throwable t) {
            // Nothing left; the attempts line will say so.
        }
        return dedupe(out);
    }

    /** Two spellings of one place would otherwise be reported twice. */
    private static List<File> dedupe(List<File> in) {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < in.size(); i++) {
            String path = in.get(i).getPath();
            boolean seen = false;
            for (int j = 0; j < out.size(); j++) {
                if (out.get(j).getPath().equals(path)) seen = true;
            }
            if (!seen) out.add(in.get(i));
        }
        return out;
    }

    private static boolean isInternal(Context context, File file) {
        try {
            File internal = context.getFilesDir();
            return internal != null && file.getPath().startsWith(internal.getPath());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the write itself

    private static String lastProblem = "";

    private static boolean writeTo(File file, String body) {
        lastProblem = "";
        try {
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(body.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            String message = t.getMessage();
            lastProblem = message == null || message.length() == 0
                    ? t.getClass().getSimpleName() : message;
            return false;
        }
    }

    /** The interesting half of a Java IO message: "open failed: EACCES (Permission denied)" is noise. */
    private static String shortReason(String message) {
        if (message == null || message.length() == 0) return "failed";
        int bracket = message.indexOf('(');
        if (bracket > 0 && message.endsWith(")")) {
            return message.substring(bracket + 1, message.length() - 1);
        }
        return message;
    }

    /** {@code reason (n) reason (n)}, commonest first, which is all that fits on one panel line. */
    private static String summarise(List<String> reasons) {
        List<String> distinct = new ArrayList<String>();
        List<Integer> counts = new ArrayList<Integer>();
        for (int i = 0; i < reasons.size(); i++) {
            String reason = reasons.get(i);
            int at = distinct.indexOf(reason);
            if (at < 0) {
                distinct.add(reason);
                counts.add(Integer.valueOf(1));
            } else {
                counts.set(at, Integer.valueOf(counts.get(at).intValue() + 1));
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < distinct.size(); i++) {
            if (b.length() > 0) b.append("  ");
            b.append(distinct.get(i)).append(" (").append(counts.get(i)).append(')');
        }
        return b.length() == 0 ? "nothing to try" : b.toString();
    }
}
