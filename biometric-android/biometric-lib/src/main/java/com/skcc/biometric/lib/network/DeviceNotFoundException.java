package com.skcc.biometric.lib.network;

public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException() {
        super("DEVICE_NOT_FOUND");
    }
}
