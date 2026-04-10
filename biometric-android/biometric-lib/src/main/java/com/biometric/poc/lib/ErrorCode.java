package com.biometric.poc.lib;
//
public enum ErrorCode {

    // ── 생체인증 관련 ──────────────────────────
    BIOMETRIC_NONE_ENROLLED,
    // 기기에 얼굴인식 미등록 상태

    BIOMETRIC_HW_UNAVAILABLE,
    // 생체인식 하드웨어 사용 불가

    BIOMETRIC_AUTH_FAILED,
    // 안면인식 실패 (얼굴 불일치)

    BIOMETRIC_CANCELED,
    // 사용자 의도적 취소 (카운트 제외)

    // ── 서버 인증 응답 관련 ───────────────────
    SESSION_EXPIRED,
    // Challenge 세션 만료 (TTL 60초 초과)

    TIMESTAMP_OUT_OF_RANGE,
    // 기기-서버 시각 불일치 (±30초 초과)

    NONCE_REPLAY,
    // 동일 nonce 재사용 (재전송 공격 방어)

    INVALID_SIGNATURE,
    // ECDSA 서명 불일치 (공개키 미일치)

    MISSING_SIGNATURE,
    // ECDSA 서명 누락

    // ── 기기/계정 상태 관련 ───────────────────
    DEVICE_NOT_FOUND,
    // 서버에 기기 등록 정보 없음

    ACCOUNT_LOCKED,
    // 인증 실패 횟수 초과로 계정 잠금

    KEY_INVALIDATED,
    // 얼굴인식 변경으로 Keystore 키 무효화

    KEY_NOT_FOUND,
    // 로컬 Keystore에 키 없음

    ALREADY_REGISTERED,
    // 이미 등록된 기기 재등록 시도

    // ── 네트워크/시스템 관련 ─────────────────
    NETWORK_ERROR,
    // 네트워크 연결 오류

    UNKNOWN_ERROR
    // 분류되지 않은 기타 오류

//    ,ETC_ERROR
}
