package com.github.qwqfranzfox.bettertimelapse;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The session log on the card: one line per event, appended, flushed and closed each time.
 *
 * <p>Written the way the one app known to have written anything on this card writes: eight-dot-three names
 * ({@code BTLPSE/LOG.TXT}), because everything on this card is eight-dot-three and a longer name comes back
 * as {@code ENOENT} — six failed attempts taught that (docs/DECISIONS.md A33).
 *
 * <p>Appended and closed per line rather than buffered, which is what docs/PLAN.md §5.2 asks for and what
 * makes the file useful in the case it exists for: a run that dies at hour three loses at most its last
 * line. The cost is one open/write/close per event, at most a few per frame, none of it between a frame's
 * due moment and its shutter.
 *
 * <p>Rotated rather than truncated: a night is a few hundred kilobytes and a card holds years, but a file
 * nobody can open is not a log. The old one is kept as {@code LOG.OLD} — also eight-dot-three.
 *
 * <p>A failure is never silent and never repeated: the first one is reported to the panel, and after that the
 * log stops trying, because an app that retries a failing write on every frame is a worse app.
 */
public final class Session {

    /** The directory and the file, in the spelling this card accepts. */
    public static final String DIR = "BTLPSE";
    public static final String NAME = "LOG.TXT";
    public static final String OLD = "LOG.OLD";

    /** Past this, the log is rotated at the next start: a few nights of frames. */
    public static final long MAX_BYTES = 512L * 1024L;

    private static String path = "";
    private static String problem = "";
    private static boolean broken;

    private Session() {}

    /** The file being written, or an empty string. */
    public static String path() {
        return path;
    }

    /** Why the last write failed, or an empty string. */
    public static String problem() {
        return problem;
    }

    /** Whether anything can be written at all: a log that cannot, says so once and stops trying. */
    public static boolean broken() {
        return broken;
    }

    /**
     * Open the log for this launch: rotate it if it has grown, and start it with a header.
     *
     * <p>The header goes in once per launch and never in the middle of the file. It used to be appended
     * whenever this was called, and it is called at every run start — so a run whose plan was applied before
     * the run header was written put {@code # better-timelapse session} between the camera block and the
     * {@code schema=} line it belongs to, which splits one boot in two for anything reading the file. The
     * header marks where the app was launched; the {@code schema=} line marks where a run begins, and a
     * session that runs five times has five of those under one header, which is the truth about it.
     *
     * @return the path, or an empty string with {@link #problem()} saying why.
     */
    public static String open(Context context, String header) {
        if (broken) return path;
        if (path.length() > 0) return path;             // already started this launch: the header is in
        File file = locate(context);
        if (file == null) {
            broken = true;
            if (problem.length() == 0) problem = "no writable place for " + DIR + "/" + NAME;
            return "";
        }
        path = file.getPath();
        rotate(file);
        append(context, header);
        return path;
    }

    /**
     * Append one line. Appends nothing when the log has already failed: the reason is on the panel and in the
     * one line that did get written, and repeating it per frame would fill a card with the same sentence.
     */
    public static void line(Context context, String text) {
        if (broken || text == null || text.length() == 0) return;
        if (path.length() == 0) {
            open(context, LogLines.header());
            if (path.length() == 0) return;
        }
        append(context, text);
    }

    // ---------------------------------------------------------------- the file itself

    private static void append(Context context, String text) {
        try {
            OutputStream out = new FileOutputStream(path, true);
            try {
                out.write((text + "\n").getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            broken = true;
            problem = describe(t);
        }
    }

    /** The first place that will take it, in the order this card has been shown to accept. */
    private static File locate(Context context) {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                File dir = new File(ext, DIR);
                if (!dir.isDirectory()) dir.mkdirs();
                if (dir.isDirectory()) return new File(dir, NAME);
                // No directory of our own could be made: the card root itself is worth a try, as a file
                // rather than a directory, which is the form that worked for the device dump.
                return new File(ext, NAME);
            }
        } catch (Throwable t) {
            problem = describe(t);
        }
        try {
            File dir = new File("/mnt/sdcard", DIR);
            if (!dir.isDirectory()) dir.mkdirs();
            if (dir.isDirectory()) return new File(dir, NAME);
        } catch (Throwable t) {
            problem = describe(t);
        }
        return null;
    }

    /** Keep the file to a size a card reader will open, and keep the night before it. */
    private static void rotate(File file) {
        try {
            if (file.length() <= MAX_BYTES) return;
            File older = new File(file.getParentFile(), OLD);
            if (older.exists()) older.delete();
            file.renameTo(older);
        } catch (Throwable t) {
            // A log that cannot be rotated is still a log; it just keeps growing.
        }
    }

    /** The last {@code lines} lines, for reading on the panel when the card cannot be pulled out. */
    public static String tail(int lines) {
        if (path.length() == 0) return "";
        try {
            File file = new File(path);
            long length = file.length();
            // Guessing backwards from the end beats reading a megabyte to show twenty lines.
            long from = Math.max(0L, length - lines * 160L);
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            try {
                raf.seek(from);
                byte[] buffer = new byte[(int) (length - from)];
                raf.readFully(buffer);
                String text = new String(buffer, "UTF-8");
                int firstBreak = from > 0 ? text.indexOf('\n') : -1;
                return firstBreak >= 0 && firstBreak + 1 < text.length() ? text.substring(firstBreak + 1) : text;
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.getClass().getSimpleName() : message;
    }
}
