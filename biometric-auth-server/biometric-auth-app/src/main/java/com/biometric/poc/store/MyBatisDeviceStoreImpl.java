package com.biometric.poc.store;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.mapper.DeviceMapper;
import com.biometric.poc.mapper.NonceMapper;
import com.biometric.poc.mapper.SessionMapper;
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
    private final NonceMapper nonceMapper;
    private final SessionMapper sessionMapper;

    public MyBatisDeviceStoreImpl(
            DeviceMapper deviceMapper,
            NonceMapper nonceMapper,
            SessionMapper sessionMapper) {
        this.deviceMapper = deviceMapper;
        this.nonceMapper = nonceMapper;
        this.sessionMapper = sessionMapper;
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

    @Override
    public void reRegister(DeviceInfo deviceInfo) {
        if (deviceInfo.getUpdatedAt() == null) {
            deviceInfo.setUpdatedAt(Instant.now());
        }
        deviceMapper.reRegister(deviceInfo);
    }

    @Override
    public void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt) {
        deviceMapper.renewKey(deviceId, newPublicKeyBase64, updatedAt);
    }

    // [중요] 삭제 순서 준수 필수
    // BIOMETRIC_NONCE → BIOMETRIC_SESSION → BIOMETRIC_DEVICE
    // 순서 변경 시 FK 제약 위반 오류 발생
    // Oracle 실서비스 전환 시에도 동일하게 적용
    @Override
    public void delete(String deviceId) {
        // ① BIOMETRIC_NONCE 삭제 (FK 없음, 먼저 삭제)
        nonceMapper.deleteByDeviceId(deviceId);
        // ② BIOMETRIC_SESSION 삭제 (DEVICE_ID FK)
        sessionMapper.deleteByDeviceId(deviceId);
        // ③ BIOMETRIC_DEVICE 삭제
        deviceMapper.deleteByDeviceId(deviceId);
    }
}
