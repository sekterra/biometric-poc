package com.biometric.poc.lib.store;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;

import java.time.Instant;
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

    /**
     * KEY_INVALIDATED 상태 기기의 공개키·사용자·등록 시각 재등록.
     *
     * <p>TODO: [실서비스] MyBatis 구현에서 WHERE STATUS = 'KEY_INVALIDATED' 조건·감사 로그 검토
     */
    void reRegister(DeviceInfo deviceInfo);

    /**
     * 공개키 갱신 및 {@link DeviceStatus#ACTIVE} 유지(PoC: in-memory / MyBatis UPDATE).
     *
     * <p>실서비스: {@code UPDATE BIOMETRIC_DEVICE SET PUBLIC_KEY_B64 = ?, STATUS = 'ACTIVE', UPDATED_AT =
     * ? WHERE DEVICE_ID = ?}
     */
    void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt);

    /**
     * 기기 등록 정보 완전 삭제 (사용자 변경 시 사용).
     *
     * <p>실서비스: {@code DELETE FROM BIOMETRIC_DEVICE WHERE DEVICE_ID = ?}
     */
    void delete(String deviceId);
}
