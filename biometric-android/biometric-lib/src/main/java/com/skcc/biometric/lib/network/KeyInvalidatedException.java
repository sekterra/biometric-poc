package com.skcc.biometric.lib.network;

public class KeyInvalidatedException extends RuntimeException {

    public KeyInvalidatedException() {
        super("KEY_INVALIDATED");
    }
}
