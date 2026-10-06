package com.voxivoid.bettertimelapse;

/**
 * The camera's settings store, through {@code jni/jni.cpp}.
 *
 * <p>Four calls, and no more: the native side exposes exactly these, so nothing here can reach
 * beyond the settings store. Rules the rest of the app depends on:
 *
 * <ul>
 *   <li>{@link #read} and {@link #write} are size checked in native code against the slot's real
 *       size, so a wrong idea about a slot fails instead of writing a wrong-length value;</li>
 *   <li>a read-only slot comes back as {@link NativeException} with {@code READ_ONLY} in the
 *       message — {@link #ATTR_READ_ONLY} is how a caller asks beforehand;</li>
 *   <li>a slot the camera will not answer for throws; callers that only need a hint treat that as
 *       "unknown" rather than as an error.</li>
 * </ul>
 */
public final class NativeStore {

    private NativeStore() {}

    static {
        System.loadLibrary("bettertimelapse");
    }

    /** The read-only attribute bit in a slot's attribute word (Sony's {@code BACKUP_ATTR_READ_ONLY}). */
    public static final int ATTR_READ_ONLY = 1;

    /** Every slot's bytes, exactly as the store holds them. */
    public static native byte[] read(int id);

    /** Write one slot's bytes. The array length must equal the slot's size. */
    public static native void write(int id, byte[] data);

    /** A slot's attribute word, or a throw when the camera will not answer. */
    public static native int attr(int id);

    /** Flush pending writes to the store. Call only after at least one byte went in. */
    public static native void sync();
}
