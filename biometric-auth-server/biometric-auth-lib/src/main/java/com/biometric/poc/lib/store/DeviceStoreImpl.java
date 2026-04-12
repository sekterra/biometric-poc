package com.biometric.poc.lib.store;

// PoC: ConcurrentHashMap 기반 in-memory 구현체
// 실서비스 전환 시 MyBatisDeviceStoreImpl로 교체할 것
// 인터페이스는 변경 없이 구현체만 교체

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceStoreImpl implements DeviceStore {

    private final ConcurrentHashMap<String, DeviceInfo> map = new ConcurrentHashMap<>();

    @Override
    public void save(DeviceInfo deviceInfo) {
        map.put(deviceInfo.getDeviceId(), deviceInfo);
    }

    @Override
    public Optional<DeviceInfo> findByDeviceId(String deviceId) {
        return Optional.ofNullable(map.get(deviceId));
    }

    @Override
    public boolean existsByDeviceId(String deviceId) {
        return map.containsKey(deviceId);
    }

    @Override
    public void updateStatus(String deviceId, DeviceStatus status) {
        map.computeIfPresent(deviceId, (k, v) -> {
            v.setStatus(status);
            v.setUpdatedAt(Instant.now());
            return v;
        });
    }

    @Override
    public void invalidateKey(String deviceId) {
        map.computeIfPresent(deviceId, (k, v) -> {
            v.setPublicKeyBase64(null);
            v.setStatus(DeviceStatus.KEY_INVALIDATED);
            v.setUpdatedAt(Instant.now());
            return v;
        });
    }

    @Override
    public void updatePublicKey(String deviceId, String publicKeyBase64) {
        map.computeIfPresent(deviceId, (k, v) -> {
            v.setPublicKeyBase64(publicKeyBase64);
            v.setUpdatedAt(Instant.now());
            return v;
        });
    }

    @Override
    public void reRegister(DeviceInfo deviceInfo) {
        map.put(deviceInfo.getDeviceId(), deviceInfo);
    }

    @Override
    public void delete(String deviceId) {
        map.remove(deviceId);
    }

    @Override
    public void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt) {
        DeviceInfo existing = map.get(deviceId);
        if (existing == null) {
            return;
        }
        map.put(
                deviceId,
                DeviceInfo.builder()
                        .deviceId(existing.getDeviceId())
                        .userId(existing.getUserId())
                        .publicKeyBase64(newPublicKeyBase64)
                        .status(DeviceStatus.ACTIVE)
                        .enrolledAt(existing.getEnrolledAt())
                        .updatedAt(updatedAt)
                        .build());
    }
}
