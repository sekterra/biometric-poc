package com.biometric.poc.lib.store;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;

import java.util.Optional;

public interface DeviceStore {

    void save(DeviceInfo deviceInfo);

    Optional<DeviceInfo> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);

    void updateStatus(String deviceId, DeviceStatus status);

    /** status = KEY_INVALIDATED, publicKeyBase64 = null 로 업데이트 */
    void invalidateKey(String deviceId);

    /** 동일 device에 대해 공개키만 교체 (테스트/PoC용) */
    void updatePublicKey(String deviceId, String publicKeyBase64);
}
