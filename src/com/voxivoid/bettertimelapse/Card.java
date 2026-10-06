package com.voxivoid.bettertimelapse;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Comparator;

/**
 * What is actually on the card.
 *
 * <p>This is the observation the old app never made. It incremented a counter right after calling a
 * method that returns {@code void} and never looked at the card at all
 * (TimeLapse {@code ShootActivity.java:299-301}), so a full card or a dropped frame produced a
 * screen reporting frames that do not exist (docs/PAIN-POINTS.md U-06, U-16).
 *
 * <p>The camera names the files, so the only thing worth counting is how many of them there are and
 * which one is newest. That is enough to tell "the frame landed" from "the shutter fired and nothing
 * was written", which is the distinction the whole run depends on.
 *
 * <p>All arithmetic on those counts lives in {@link Frames}, which has no Android in it. This class
 * is only the directory listing.
 */
public final class Card {

    /** The camera's own tree. Subdirectories are per-card and per-roll, so the walk is recursive. */
    private static final String DCIM = "DCIM";

    /**
     * Where the camera might keep its files.
     *
     * <p>{@code Environment.getExternalStorageDirectory()} answers where <em>this app</em> may write,
     * which on a camera is not necessarily where the camera writes. So every plausible root is checked
     * and the first one with a readable {@code DCIM} wins — first readable, not first with images: the
     * walk that follows knows how many images there are, and counting them here to choose between roots
     * would be that walk once per candidate.
     */
    private static final String[] ROOTS = {
            null,                       // Environment.getExternalStorageDirectory()
            "/mnt/sdcard",
            "/storage/sdcard0",
            "/sdcard",
            "/mnt/ext_card",
            "/storage/sdcard1",
    };

    private static final String[] IMAGE_SUFFIXES = { ".jpg", ".jpeg", ".arw", ".png" };

    /** Extension of the newest image found, so the panel can say what is being written. */
    private long newestStamp;
    private String newestName = "";
    private String newestPath = "";
    private long newestBytes;
    private int count = Frames.UNKNOWN;
    private boolean readable;

    /**
     * Accumulators for one walk.
     *
     * <p>Fields rather than a result object because the walk happens on one thread and returns four
     * numbers that are always wanted together; handing them back through an object would mean an
     * allocation on every frame of a night-long run.
     */
    private int walkCount;
    private long walkStamp;
    private String walkName = "";
    private String walkPath = "";
    private long walkBytes;

    /**
     * Read the card. Call it before a run and again after each frame; it is a directory walk, not a
     * camera call, so it works while the camera is busy.
     */
    /** The root that last produced images, or {@code null} when none has. */
    private String rootInUse;

    /** Every root that exists, and how many images each holds. For the diagnostic line. */
    public String rootsReport() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ROOTS.length; i++) {
            File root = root(i);
            if (root == null) continue;
            File dcim = new File(root, DCIM);
            if (!dcim.exists()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(root.getPath()).append('=').append(countImages(dcim));
        }
        return b.length() == 0 ? "no DCIM under any known root" : b.toString();
    }

    private static File root(int i) {
        if (ROOTS[i] == null) {
            try {
                return Environment.getExternalStorageDirectory();
            } catch (Throwable t) {
                return null;
            }
        }
        return new File(ROOTS[i]);
    }

    /** The root the images were found under; empty when none. */
    public String rootInUse() {
        return rootInUse == null ? "" : rootInUse;
    }

    public void refresh() {
        // Prefer the root that worked last time; otherwise look in all of them.
        File dcim = rootInUse == null ? null : new File(new File(rootInUse), DCIM);
        if (dcim == null || !dcim.isDirectory()) {
            dcim = null;
            rootInUse = null;
            for (int i = 0; i < ROOTS.length; i++) {
                File root = root(i);
                if (root == null) continue;
                File candidate = new File(root, DCIM);
                if (candidate.isDirectory()) {
                    dcim = candidate;
                    rootInUse = root.getPath();
                    break;
                }
            }
        }
        if (dcim == null) {
            // Nothing readable anywhere: this app cannot see the camera's files at all, which is a
            // different thing from a card that is empty or absent.
            readable = false;
            count = Frames.UNKNOWN;
            newestName = "";
            newestStamp = 0;
            newestBytes = 0;
            return;
        }
        readable = true;
        walkCount = 0;
        walkStamp = 0;
        walkName = "";
        walkPath = "";
        walkBytes = 0;
        walk(dcim);
        count = walkCount;
        newestStamp = walkStamp;
        newestName = walkName;
        newestPath = walkPath;
        newestBytes = walkBytes;
    }

    /** How many images the card holds, or {@link Frames#UNKNOWN}. */
    public int count() {
        return count;
    }

    /** Whether the card could be read at all. A card that goes from readable to not is a fault. */
    public boolean readable() {
        return readable;
    }

    /** The newest image's name, or an empty string. */
    public String newestName() {
        return newestName;
    }

    public long newestStamp() {
        return newestStamp;
    }

    /**
     * Where the newest image actually is, full path.
     *
     * <p>Not decoration: the whole question of whether this app can see the camera's files is answered
     * by that path, and a name alone does not say which directory the camera wrote into.
     */
    public String newestPath() {
        return newestPath;
    }

    /**
     * The newest file's size in bytes, recorded during {@link #refresh()} rather than by a second
     * walk of the tree: on a card holding thousands of files a second walk is the same cost again,
     * and this number is only ever wanted right after a refresh.
     */
    public long newestBytes() {
        return newestBytes;
    }

    /** Free bytes on the card, from the platform. */
    public static long freeBytes() {
        return Device.freeBytes();
    }

    /**
     * Count the images under a directory tree and remember the newest.
     *
     * <p>Recursive, and it really does descend: the first version looked only one level below
     * {@code DCIM} — assuming the camera files everything into its roll subdirectories, the way a
     * camera's card is usually laid out. On this body it does not hold: the tree held one image more
     * than the count did, the count therefore never moved when a frame was taken, and every frame of
     * the run was read as "not written". The layout of someone else's card is not something to assume,
     * and the cost of walking it is a handful of {@code listFiles} calls.
     */
    private void walk(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                walk(f);
                continue;
            }
            if (!f.isFile() || !isImage(f.getName())) continue;
            walkCount++;
            long modified = f.lastModified();
            if (modified >= walkStamp) {
                walkStamp = modified;
                walkName = f.getName();
                walkPath = f.getPath();
                walkBytes = f.length();
            }
        }
    }

    /** Images under a directory tree, for the root report. Does not disturb the walk accumulators. */
    private static int countImages(File dir) {
        int n = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory()) n += countImages(files[i]);
            else if (isImage(files[i].getName())) n++;
        }
        return n;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * A frame size estimate from the newest file, so "how many more will fit" is a measurement rather
     * than a guess. Returns {@code 0} when nothing has been read yet, and the caller falls back to a
     * default.
     */
    public long estimatedFrameBytes() {
        return newestBytes;
    }

    /** The per-roll directories, newest last, for the session log. */
    public String[] rolls() {
        File dcim = new File(Environment.getExternalStorageDirectory(), DCIM);
        File[] dirs = dcim.listFiles();
        if (dirs == null) return new String[0];
        String[] names = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) names[i] = dirs[i].getName();
        Arrays.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        });
        return names;
    }
}
