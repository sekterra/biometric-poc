package com.biometric.poc.lib.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceInfo {

    private String deviceId;
    private String userId;
    /** ECDSA 공개키 (X.509 Base64) */
    private String publicKeyBase64;
    private Instant enrolledAt;
    /** DB 동기화·감사용 마지막 변경 시각 */
    private Instant updatedAt;
    private DeviceStatus status;
}
