package com.biometric.poc.lib.challenge;

import com.biometric.poc.lib.auth.AuthConstants;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.SessionStore;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public class ChallengeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final SessionStore sessionStore;

    public ChallengeService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /** SecureRandom으로 32바이트 생성 후 hex 문자열(64자) */
    public String generateChallenge() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 챌린지·세션 ID를 생성하고 저장합니다.
     *
     * <p>흐름: 랜덤 챌린지(hex) + 세션 ID(UUID) 생성 → 만료 시각(now + TTL) 설정 → {@link
     * com.biometric.poc.lib.store.SessionStore#save} 저장.
     */
    public SessionData createSession(
            String deviceId, String userId, String clientNonce, long timestamp) {
        String serverChallengeHex = generateChallenge();
        String sessionId = generateSessionId();
        Instant now = Instant.now();
        Instant expireAt = now.plusSeconds(AuthConstants.SESSION_TTL_SECONDS);

        SessionData sessionData =
                SessionData.builder()
                        .sessionId(sessionId)
                        .deviceId(deviceId)
                        .userId(userId)
                        .serverChallengeHex(serverChallengeHex)
                        .clientNonce(clientNonce)
                        .timestamp(timestamp)
                        .expireAt(expireAt)
                        .used(false)
                        .createdAt(now)
                        .build();

        sessionStore.save(sessionData);
        return sessionData;
    }

    public SessionData validateSession(String sessionId) {
        return sessionStore
                .findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException("SESSION_EXPIRED"));
    }

    /** 서명 검증 성공 후 세션 일회용 처리 — SessionStore.markUsed 위임 */
    public void markSessionUsed(String sessionId) {
        sessionStore.markUsed(sessionId);
    }
}
