package com.voxivoid.bettertimelapse;

import android.os.Environment;

import java.io.File;
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

    private static final String[] IMAGE_SUFFIXES = { ".jpg", ".jpeg", ".arw", ".png" };

    /** Extension of the newest image found, so the panel can say what is being written. */
    private long newestStamp;
    private String newestName = "";
    private long newestBytes;
    private int count = Frames.UNKNOWN;
    private boolean readable;

    /**
     * Read the card. Call it before a run and again after each frame; it is a directory walk, not a
     * camera call, so it works while the camera is busy.
     */
    public void refresh() {
        File dcim = new File(Environment.getExternalStorageDirectory(), DCIM);
        if (!dcim.isDirectory()) {
            // No DCIM directory is not the same as an empty one: the card may be absent, and the
            // difference decides whether a missing frame is a card problem or a shutter problem.
            readable = dcim.exists();
            count = Frames.UNKNOWN;
            newestName = "";
            newestStamp = 0;
            newestBytes = 0;
            return;
        }
        readable = true;
        int found = 0;
        long stamp = 0;
        String name = "";
        File[] rolls = dcim.listFiles();
        if (rolls == null) {
            readable = false;
            count = Frames.UNKNOWN;
            return;
        }
        for (File roll : rolls) {
            if (!roll.isDirectory()) continue;
            File[] files = roll.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !isImage(f.getName())) continue;
                found++;
                long modified = f.lastModified();
                if (modified >= stamp) {
                    stamp = modified;
                    name = f.getName();
                    newestBytes = f.length();
                }
            }
        }
        count = found;
        newestStamp = stamp;
        newestName = name;
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
