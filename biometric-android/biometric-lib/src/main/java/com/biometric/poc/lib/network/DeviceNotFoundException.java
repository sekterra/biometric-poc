package com.biometric.poc.lib.network;

public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException() {
        super("DEVICE_NOT_FOUND");
    }
}
