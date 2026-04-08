package com.biometric.poc.lib;

/**
 * PoC 공통 상수. 서버 {@code FailurePolicyConfig} 와 맞출 때는 서버 값을 우선합니다.
 *
 * <p>TODO: [실서비스] 정책·잠금 수치는 원격 설정 또는 서버 단일 소스로 통일.
 */
public final class BiometricLibConstants {

    private BiometricLibConstants() {}

    // =========================================================================
    // 저장소
    // =========================================================================

    /** Android Keystore: 생체 인증 후 키 사용 허용 시간(초) — EcKeyManager.generateKeyPair */
    public static final int KEY_AUTH_VALIDITY_SECONDS = 10;

    /**
     * EncryptedSharedPreferences 파일명 — {@link com.biometric.poc.lib.storage.TokenStorage}
     *
     * <p>TODO: [실서비스] 실MIS앱 패키지명 기반으로 교체
     * <p>주의: 파일명 변경 시 기존 데이터 접근 불가 — 앱 데이터 삭제 후 재등록 필요
     */
    public static final String PREFS_NAME = "com.biometric.poc.lib.prefs";

    // =========================================================================
    // 보안 임계값
    // =========================================================================

    /**
     * 데모 UI에 표시하는 "N회 실패 시 잠금" 안내용 기본값. 실제 잠금 임계는 서버 정책·{@link
     * com.biometric.poc.lib.policy.FailurePolicyManager} 가 따름.
     */
    public static final int MAX_FAILURE_COUNT_FOR_UI = 5;

    /**
     * 로컬 잠금 표시용 기본(초). 서버 {@code lockout_seconds} 가 있으면 그 값이 우선.
     *
     * <p>TODO: [실서비스] 로컬 전용 상수 제거하고 정책 API만 사용 검토.
     */
    public static final int LOCAL_LOCKOUT_SECONDS = 30;

    /**
     * SESSION_EXPIRED 발생 시 자동 재시도 최대 횟수.
     *
     * <p>TODO: [실서비스] 서버 정책 API로 통일 검토.
     */
    public static final int MAX_SESSION_RETRY = 2;

    /**
     * INVALID_SIGNATURE 연속 발생 시 자동 키 재발급을 트리거하는 임계 횟수 (CASE6).
     *
     * <p>TODO: [실서비스] 서버 정책 API로 통일 검토.
     */
    public static final int INVALID_SIGNATURE_RENEWAL_THRESHOLD = 3;

    // =========================================================================
    // 암호화 파라미터
    // =========================================================================

    /**
     * 클라이언트 nonce 생성 시 사용할 바이트 수 (128비트).
     * Hex 인코딩 후 길이: NONCE_BYTE_SIZE * 2 문자.
     */
    public static final int NONCE_BYTE_SIZE = 16;

    /**
     * EncryptedSharedPreferences 암호화 스키마 (참조용 주석).
     * <ul>
     *   <li>MasterKey: AES256_GCM</li>
     *   <li>키 암호화: AES256_SIV</li>
     *   <li>값 암호화: AES256_GCM</li>
     * </ul>
     * TODO: [실서비스] 스키마 변경 시 기존 데이터 마이그레이션 필요.
     */
    @SuppressWarnings("unused")
    public static final String CRYPTO_SCHEMA_NOTE =
            "MasterKey=AES256_GCM / PrefKey=AES256_SIV / PrefValue=AES256_GCM";

    // =========================================================================
    // UI 전환 딜레이 (ms)
    // =========================================================================

    /** 화면 전환 짧은 딜레이 (1초) — 등록 완료 후 로그인 유도 등. */
    public static final int UI_REDIRECT_DELAY_MS = 1000;

    /** 화면 전환 중간 딜레이 (1.5초) — 등록 성공 후 다음 화면 이동. */
    public static final int UI_REDIRECT_DELAY_MEDIUM_MS = 1500;

    /** 화면 전환 긴 딜레이 (2초) — 기기 상태 확인 후 자동 이동 등. */
    public static final int UI_REDIRECT_DELAY_LONG_MS = 2000;

    // =========================================================================
    // 토큰 표시
    // =========================================================================

    /**
     * 서버 응답에 expires_in 필드가 없을 때 사용하는 토큰 만료 기본값 (초).
     * 기본 30분(1800초).
     *
     * <p>TODO: [실서비스] 서버가 항상 expires_in을 내려주도록 보장하면 이 상수 불필요.
     */
    public static final int TOKEN_EXPIRES_IN_DEFAULT_SEC = 1800;

    /** 메인 화면에서 access_token 미리보기 최대 문자 수. */
    public static final int TOKEN_PREVIEW_MAX_CHARS = 20;
}
