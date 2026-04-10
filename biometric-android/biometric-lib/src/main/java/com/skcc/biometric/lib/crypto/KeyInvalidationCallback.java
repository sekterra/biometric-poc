package com.skcc.biometric.lib.crypto;

public interface KeyInvalidationCallback {

    void onInvalidated();

    void onError(String message);
}
