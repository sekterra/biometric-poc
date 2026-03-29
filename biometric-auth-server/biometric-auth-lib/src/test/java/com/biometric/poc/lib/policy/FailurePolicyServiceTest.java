package com.biometric.poc.lib.policy;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStoreImpl;
import com.biometric.poc.lib.store.FailurePolicyStoreImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePolicyServiceTest {

    private DeviceStoreImpl deviceStore;
    private FailurePolicyService failurePolicyService;

    @BeforeEach
    void setUp() {
        deviceStore = new DeviceStoreImpl();
        FailurePolicyStoreImpl failurePolicyStore = new FailurePolicyStoreImpl();
        failurePolicyService = new FailurePolicyService(failurePolicyStore, deviceStore);
    }

    @Test
    void lockAccount_thenIsLocked_returnsTrue() {
        String deviceId = "dev-lock-1";
        deviceStore.save(
                DeviceInfo.builder()
                        .deviceId(deviceId)
                        .userId("USER01")
                        .publicKeyBase64("cHVibGljS2V5")
                        .enrolledAt(Instant.parse("2024-01-01T00:00:00Z"))
                        .updatedAt(Instant.parse("2024-01-01T00:00:00Z"))
                        .status(DeviceStatus.ACTIVE)
                        .build());

        failurePolicyService.lockAccount(deviceId);

        assertTrue(failurePolicyService.isLocked(deviceId));
    }

    @Test
    void isKeyInvalidated_afterInvalidateKey_returnsTrue() {
        String deviceId = "dev-inv-1";
        deviceStore.save(
                DeviceInfo.builder()
                        .deviceId(deviceId)
                        .userId("USER01")
                        .publicKeyBase64("cHVibGljS2V5")
                        .enrolledAt(Instant.parse("2024-01-01T00:00:00Z"))
                        .updatedAt(Instant.parse("2024-01-01T00:00:00Z"))
                        .status(DeviceStatus.ACTIVE)
                        .build());

        deviceStore.invalidateKey(deviceId);

        assertTrue(failurePolicyService.isKeyInvalidated(deviceId));
    }
}
