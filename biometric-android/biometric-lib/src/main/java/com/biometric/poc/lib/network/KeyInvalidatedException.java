package com.biometric.poc.lib.network;

public class KeyInvalidatedException extends RuntimeException {

    public KeyInvalidatedException() {
        super("KEY_INVALIDATED");
    }
}
