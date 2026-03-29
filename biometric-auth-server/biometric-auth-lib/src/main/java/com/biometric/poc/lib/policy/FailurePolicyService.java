package com.biometric.poc.lib.policy;

import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.FailurePolicyConfig;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.FailurePolicyStore;

public class FailurePolicyService {

    private final FailurePolicyStore failurePolicyStore;
    private final DeviceStore deviceStore;

    public FailurePolicyService(FailurePolicyStore failurePolicyStore, DeviceStore deviceStore) {
        this.failurePolicyStore = failurePolicyStore;
        this.deviceStore = deviceStore;
    }

    public FailurePolicyConfig getPolicy(String deviceId) {
        return failurePolicyStore.getPolicy(deviceId);
    }

    public void lockAccount(String deviceId) {
        deviceStore.updateStatus(deviceId, DeviceStatus.LOCKED);
    }

    public boolean isLocked(String deviceId) {
        return deviceStore
                .findByDeviceId(deviceId)
                .map(d -> d.getStatus() == DeviceStatus.LOCKED)
                .orElse(false);
    }

    public boolean isKeyInvalidated(String deviceId) {
        return deviceStore
                .findByDeviceId(deviceId)
                .map(d -> d.getStatus() == DeviceStatus.KEY_INVALIDATED)
                .orElse(false);
    }
}
