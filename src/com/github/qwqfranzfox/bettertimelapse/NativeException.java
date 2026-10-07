package com.github.qwqfranzfox.bettertimelapse;

/** A failure reported by the settings-store driver, with the driver's own message. */
public class NativeException extends RuntimeException {
    public NativeException(String message) {
        super(message);
    }
}
