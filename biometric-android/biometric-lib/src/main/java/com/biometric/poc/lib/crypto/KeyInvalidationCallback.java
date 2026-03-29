package com.biometric.poc.lib.crypto;

public interface KeyInvalidationCallback {

    void onInvalidated();

    void onError(String message);
}
