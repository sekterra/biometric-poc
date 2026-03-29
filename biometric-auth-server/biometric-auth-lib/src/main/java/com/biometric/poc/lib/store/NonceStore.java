package com.biometric.poc.lib.store;

public interface NonceStore {

    boolean isUsed(String nonce);

    void markUsed(String nonce, String deviceId);
}
