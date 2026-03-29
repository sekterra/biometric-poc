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
public class SessionData {

    private String sessionId;
    private String deviceId;
    private String userId;
    /** 32B random hex */
    private String serverChallengeHex;
    private String clientNonce;
    private long timestamp;
    /** 생성시각 + 60초 */
    private Instant expireAt;
    private boolean used;
    /** 세션 레코드 생성 시각 */
    private Instant createdAt;
}
