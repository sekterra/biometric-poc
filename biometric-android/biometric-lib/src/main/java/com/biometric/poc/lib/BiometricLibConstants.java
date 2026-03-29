package com.biometric.poc.lib;

/**
 * PoC 공통 상수. 서버 {@code FailurePolicyConfig} 와 맞출 때는 서버 값을 우선합니다.
 *
 * <p>TODO: [실서비스] 정책·잠금 수치는 원격 설정 또는 서버 단일 소스로 통일.
 */
public final class BiometricLibConstants {

    private BiometricLibConstants() {}

    /** Android Keystore: 생체 인증 후 키 사용 허용 시간(초) — EcKeyManager.generateKeyPair */
    public static final int KEY_AUTH_VALIDITY_SECONDS = 10;

    /** EncryptedSharedPreferences 파일명 — {@link com.biometric.poc.lib.storage.TokenStorage} */
    public static final String PREFS_NAME = "biometric_prefs";

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
}
