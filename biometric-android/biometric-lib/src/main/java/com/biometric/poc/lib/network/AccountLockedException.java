package com.biometric.poc.lib.network;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("ACCOUNT_LOCKED");
    }
}
