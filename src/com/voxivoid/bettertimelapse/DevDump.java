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
                // Recorded even on success. The first version returned here without setting it, so when the
                // file landed in private storage the panel said 卡上写不进（）-- empty parentheses, the exact
                // place the reasons should have been. The reasons a card refused are the answer to why the
                // file is somewhere useless, so they belong on the panel whatever happened after them.
                attempts = summarise(reasons);
                return written;
            }
            reasons.add(label(context, file) + "=" + shortReason(lastProblem));
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
        // The two facts that decide whether a path can be created at all, and the canonical spelling of the
        // root: six writes came back ENOENT for paths whose parents are demonstrably there and listable.
        b.append("  \u89c4\u8303 ").append(canonicalRoot());                                 // 规范 <路径>
        b.append("  \u5361\u6839\u5b58\u5728 ").append(exists(environmentPath("")));        // 卡根存在
        b.append("  DCIM\u5b58\u5728 ").append(exists(environmentPath("DCIM")));              // DCIM存在
        b.append("  /mnt/sdcard\u53ef\u5199 ").append(writable("/mnt/sdcard"));               // 可写
        // The directory the old TimeLapse app created here and logged into. If this app may not write inside
        // it either, the difference is not the path and not the directory's owner; if it may, then where a
        // write goes is part of the answer and the dump should use a directory that already exists.
        b.append("  TIMELPS\u53ef\u5199 ").append(writable(environmentPath("TIMELPS")));      // 可写
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

    /**
     * The process's uid and supplementary groups, read from {@code /proc/self/status}.
     *
     * <p>Reported because the old TimeLapse app writes into a directory of its own at this card's root on
     * this camera, with the same permissions declared, while every create this app attempts comes back
     * ENOENT. When the same operation succeeds for one app and fails for another on the same path, what is
     * left is who is asking: on this storage layer that is decided by uid and group membership (sdcard_rw is
     * 1015, media_rw 1023), and neither is visible from the Java API.
     */
    private static String identity() {
        String uid = "?", groups = "?";
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/proc/self/status")));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length > 0) uid = parts[0];
                } else if (line.startsWith("Groups:")) {
                    groups = line.substring(7).trim().replace(' ', ',');
                }
            }
        } catch (Throwable t) {
            groups = "?";
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable t) {
                // Nothing to do about a failed close on a file that is already read.
            }
        }
        return "uid " + uid + " groups " + groups;
    }

    /** The card root as the filesystem resolves it, which is not always how it was spelled. */
    private static String canonicalRoot() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext == null) return "?";
            String canonical = ext.getCanonicalPath();
            return canonical == null || canonical.length() == 0 ? "?" : canonical;
        } catch (Throwable t) {
            return "!(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static boolean exists(String path) {
        try {
            return path != null && path.length() > 0 && new File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A child of the card root, or an empty string when there is no card root to speak of. */
    private static String environmentPath(String child) {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext == null) return "";
            return new File(ext, child).getPath();
        } catch (Throwable t) {
            return "";
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
        // The canonical spelling of the card root, when it is a different one. The reports come back with
        // ENOENT -- "no such file or directory" -- for paths whose parents demonstrably exist and are
        // readable, which is exactly what a path that only *looks* right produces: a symlink chain that
        // resolves somewhere the app may not create anything.
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                String canonical = ext.getCanonicalPath();
                if (!canonical.equals(ext.getPath())) {
                    out.add(new File(new File(canonical, "DCIM"), NAME));
                    out.add(new File(canonical, NAME));
                }
            }
        } catch (Throwable t) {
            // A root that will not resolve is itself the answer, and the probe reports it.
        }
        out.add(new File("/mnt/sdcard/DCIM", NAME));
        out.add(new File("/mnt/sdcard", NAME));
        // The directory the old TimeLapse app made at the card root and logged into: on this body that is
        // the one place an app is known to have written successfully, so it is worth a try.
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) out.add(new File(new File(ext, "TIMELPS"), NAME));
        } catch (Throwable t) {
            // Nothing to add.
        }
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
        if (attempt(file, body, false)) return true;
        String truncated = lastProblem;
        // Append as well, because the one log an app is known to have written on this card was written that
        // way (TimeLapse's LOG.TXT, FileWriter(file, true)) and "create" and "open an existing file" are not
        // the same operation on some storage layers.
        if (attempt(file, body, true)) {
            lastProblem = "";
            return true;
        }
        lastProblem = truncated.equals(lastProblem) ? truncated : truncated + "/" + lastProblem;
        return false;
    }

    private static boolean attempt(File file, String body, boolean append) {
        try {
            OutputStream out = new FileOutputStream(file, append);
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

    /**
     * The interesting half of a Java IO message, as the name a systems person reads.
     *
     * <p>{@code No such file or directory} and {@code ENOENT} are the same fact, and the second one fits on
     * a line next to five others. Which five matters: the paths are the other half of the answer, and the
     * first version of this line reported only the reasons.
     */
    private static String shortReason(String message) {
        if (message == null || message.length() == 0) return "failed";
        String m = message.toLowerCase(java.util.Locale.US);
        if (m.indexOf("no such file") >= 0) return "ENOENT";
        if (m.indexOf("permission denied") >= 0) return "EACCES";
        if (m.indexOf("read-only") >= 0) return "EROFS";
        if (m.indexOf("no space") >= 0) return "ENOSPC";
        if (m.indexOf("is a directory") >= 0) return "EISDIR";
        if (m.indexOf("not a directory") >= 0) return "ENOTDIR";
        int bracket = message.indexOf('(');
        if (bracket > 0 && message.endsWith(")")) {
            return message.substring(bracket + 1, message.length() - 1);
        }
        return message;
    }

    /** The directory an attempt went into, named the way it reads on the card. */
    private static String label(Context context, File file) {
        String parent = file.getParent();
        if (parent == null) return "?";
        String root = "";
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) root = ext.getPath();
        } catch (Throwable t) {
            // No card root to strip; the full path is the label.
        }
        if (root.length() > 0 && parent.startsWith(root)) {
            String rest = parent.substring(root.length());
            return rest.length() == 0 ? "/" : rest.replace('\\', '/');
        }
        int slash = parent.lastIndexOf('/');
        return slash >= 0 ? parent.substring(slash + 1) + "/" : parent;
    }

    /** One entry per failed place, which is what the panel has room for. */
    private static String summarise(List<String> reasons) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < reasons.size() && i < ATTEMPTS_SHOWN; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(reasons.get(i));
        }
        if (reasons.size() > ATTEMPTS_SHOWN) b.append(" +").append(reasons.size() - ATTEMPTS_SHOWN);
        return b.length() == 0 ? "nothing to try" : b.toString();
    }

    private static final int ATTEMPTS_SHOWN = 6;
}
