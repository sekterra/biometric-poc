package com.skcc.biometric.lib.network;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("ACCOUNT_LOCKED");
    }
}
