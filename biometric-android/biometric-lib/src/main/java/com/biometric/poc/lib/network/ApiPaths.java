package com.biometric.poc.lib.network;

/**
 * 인증 서버 API 엔드포인트 경로 상수 모음.
 *
 * <p>baseUrl 은 {@link AuthApiClient} 생성자에서 주입받으며,
 * 실제 요청 URL 은 {@code baseUrl + ApiPaths.XXX} 형태로 조합됩니다.
 *
 * <p>TODO: [실서비스] 운영 서버 URL/경로 변경 시 이 파일만 수정.
 */
public final class ApiPaths {

    private ApiPaths() {}

    // =========================================================================
    // /api/device — 기기 관리
    // =========================================================================

    /** 기기에 매핑된 사용자 ID·상태 조회 (GET) */
    public static final String DEVICE_USER_ID    = "/api/device/user-id";

    /** 기기 등록 (POST) */
    public static final String DEVICE_REGISTER   = "/api/device/register";

    /** 기기 등록 삭제 (DELETE) */
    public static final String DEVICE_UNREGISTER = "/api/device/unregister";

    /** 계정 잠금 해제 (PUT) */
    public static final String DEVICE_UNLOCK     = "/api/device/unlock";

    /** 기기 키 상태 업데이트 — KEY_INVALIDATED 표시 (PUT) */
    public static final String DEVICE_UPDATE_KEY = "/api/device/update-key";

    /** 공개키 갱신 (PUT) */
    public static final String DEVICE_RENEW_KEY  = "/api/device/renew-key";

    // =========================================================================
    // /api/auth — 인증 플로우
    // =========================================================================

    /** 챌린지 발급 (POST) */
    public static final String AUTH_CHALLENGE    = "/api/auth/challenge";

    /** 서명 검증 후 토큰 발급 (POST) */
    public static final String AUTH_TOKEN        = "/api/auth/token";

    /** 계정 잠금 요청 (POST) */
    public static final String AUTH_ACCOUNT_LOCK = "/api/auth/account-lock";

    // =========================================================================
    // /api/policy — 정책
    // =========================================================================

    /** 실패 정책 조회 (GET) */
    public static final String POLICY_FAILURE_CONFIG = "/api/policy/failure-config";
}
