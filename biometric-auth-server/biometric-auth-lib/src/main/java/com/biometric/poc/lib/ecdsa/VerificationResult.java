package com.biometric.poc.lib.ecdsa;

public enum VerificationResult {
    SUCCESS,
    SESSION_EXPIRED,
    TIMESTAMP_OUT_OF_RANGE,
    NONCE_REPLAY,
    MISSING_SIGNATURE,
    INVALID_SIGNATURE
}
