package com.voxivoid.bettertimelapse;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;

/**
 * The two numbers a night shoot actually turns on: how much battery is left, and how many more
 * frames the card can hold. Both come from the platform, neither is a decision, so this class only
 * reads them and hands them to {@link Hud} to phrase.
 *
 * <p>Reflection is not needed here — {@code BatteryManager} and {@code StatFs} are public platform
 * APIs that exist on Android 2.3 — but every value is still allowed to be unknown, because guessing
 * a battery level is worse than admitting the camera would not say.
 *
 * <p>{@code StatFs} is used with {@code getAvailableBlocks()} rather than
 * {@code getAvailableBytes()}: the latter only arrived in API 18 and this app runs on API 10.
 */
public final class Device {

    private Device() {}

    /** Battery charge as a percentage, or {@code UNKNOWN} when the platform will not say. */
    public static final int UNKNOWN = -1;

    public static int batteryPercent(Context context) {
        try {
            Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) return UNKNOWN;
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, UNKNOWN);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, UNKNOWN);
            if (level < 0 || scale <= 0) return UNKNOWN;
            return Math.round(level * 100f / scale);
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /** Whether the camera is on external power. Meaningless while {@link #batteryPercent} is unknown. */
    public static boolean charging(Context context) {
        try {
            Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) return false;
            int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plugged != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Free space on the card that the camera writes to, in bytes, or {@code UNKNOWN}. This is where
     * a time-lapse actually fails, and the old app never looked: it counted shutter callbacks and
     * reported frames that were never written (docs/PAIN-POINTS.md U-06, U-16).
     */
    public static long freeBytes() {
        return freeBytes(null);
    }

    /**
     * The same, for the tree the app is actually reading.
     *
     * <p>The camera may write to a root this app only found by looking (see {@code Card}), and asking the
     * platform about a different one is how a screen ends up reporting room on a card the run is not using.
     * A null path means "wherever this app may write".
     */
    public static long freeBytes(String path) {
        try {
            String where = path;
            if (where == null) {
                File dir = Environment.getExternalStorageDirectory();
                if (dir == null) return UNKNOWN;
                where = dir.getPath();
            }
            StatFs stat = new StatFs(where);
            return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}
