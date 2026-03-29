package com.biometric.poc.store;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.mapper.DeviceMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * ConcurrentHashMap 기반 {@link com.biometric.poc.lib.store.DeviceStoreImpl}을 MyBatis로 교체한 구현체.
 * 실서비스 전환 시 application.yml의 DB 연결정보만 Oracle로 교체하면 됨.
 */
@Component
public class MyBatisDeviceStoreImpl implements DeviceStore {

    private final DeviceMapper deviceMapper;

    public MyBatisDeviceStoreImpl(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /**
     * INSERT 전용. 동시 등록 시 DB UNIQUE 로 {@link org.springframework.dao.DataIntegrityViolationException}
     * 가 발생할 수 있으며, {@link com.biometric.poc.controller.DeviceController} 에서 409 로 매핑한다.
     */
    @Override
    public void save(DeviceInfo deviceInfo) {
        deviceInfo.setUpdatedAt(Instant.now());
        deviceMapper.insert(deviceInfo);
    }

    @Override
    public Optional<DeviceInfo> findByDeviceId(String deviceId) {
        DeviceInfo result = deviceMapper.selectByDeviceId(deviceId);
        return Optional.ofNullable(result);
    }

    @Override
    public boolean existsByDeviceId(String deviceId) {
        return deviceMapper.countByDeviceId(deviceId) > 0;
    }

    @Override
    public void updateStatus(String deviceId, DeviceStatus status) {
        deviceMapper.updateStatus(deviceId, status.name(), Instant.now());
    }

    @Override
    public void invalidateKey(String deviceId) {
        deviceMapper.updateKeyInvalidated(deviceId, Instant.now());
    }

    @Override
    public void updatePublicKey(String deviceId, String publicKeyBase64) {
        deviceMapper.updatePublicKey(deviceId, publicKeyBase64, Instant.now());
    }
}
