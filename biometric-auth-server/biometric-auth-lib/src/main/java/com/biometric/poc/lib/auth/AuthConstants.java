package com.biometric.poc.lib.auth;

/** 인증·세션·nonce 관련 PoC 기본값. 실서비스에서는 설정 외부화 권장. */
public final class AuthConstants {

    private AuthConstants() {}

    /** 챌린지 세션 만료 (초) — schema.sql BIOMETRIC_SESSION.EXPIRE_AT 와 일치 */
    public static final int SESSION_TTL_SECONDS = 60;

    /** 토큰 요청 시각 허용 오차 (ms) */
    public static final long TIMESTAMP_TOLERANCE_MS = 30_000L;

    /** Nonce 레코드 만료 (분) — markUsed 시 USED_AT + N분 */
    public static final int NONCE_TTL_MINUTES = 5;

    /** {@link com.biometric.poc.lib.model.FailurePolicyConfig} 기본 잠금 시간(초) */
    public static final int DEFAULT_LOCKOUT_SECONDS = 30;
}
