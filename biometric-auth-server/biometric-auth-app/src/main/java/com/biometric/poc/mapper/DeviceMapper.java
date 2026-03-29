package com.biometric.poc.mapper;

import com.biometric.poc.lib.model.DeviceInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface DeviceMapper {

    void insert(DeviceInfo deviceInfo);

    DeviceInfo selectByDeviceId(String deviceId);

    int countByDeviceId(String deviceId);

    void updateStatus(
            @Param("deviceId") String deviceId,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt);

    void updateKeyInvalidated(@Param("deviceId") String deviceId, @Param("updatedAt") Instant updatedAt);

    /** [PoC/test-flow] 동일 device_id 재호출 시 서명 검증을 위해 공개키만 갱신 */
    void updatePublicKey(
            @Param("deviceId") String deviceId,
            @Param("publicKeyBase64") String publicKeyBase64,
            @Param("updatedAt") Instant updatedAt);
}
