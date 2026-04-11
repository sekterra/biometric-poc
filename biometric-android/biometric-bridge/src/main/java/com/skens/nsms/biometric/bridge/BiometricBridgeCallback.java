package com.skens.nsms.biometric.bridge;

/**
 * BiometricBridgeCallback
 * BiometricBridge 인증·등록·사용자변경 결과를 수신하는 콜백 인터페이스.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>AAR(biometric-lib) 타입을 일절 노출하지 않고 원시 타입(String, int)만 사용합니다.</li>
 *   <li>A2 앱은 biometric-lib를 import하지 않고 이 인터페이스만 구현하면 됩니다.</li>
 *   <li>모든 메서드는 UI 스레드에서 호출됩니다. UI 조작이 가능합니다.</li>
 * </ul>
 *
 * <p>CASE 매핑:
 * <ul>
 *   <li>CASE 1  — onLoginSuccess (인증 성공)</li>
 *   <li>CASE 2  — onRetry (재시도)</li>
 *   <li>CASE 3  — onSessionRetrying (세션 재시도 중)</li>
 *   <li>CASE 4  — onLockedOut (일시 잠금)</li>
 *   <li>CASE 7  — onNotRegistered (미등록)</li>
 *   <li>CASE 9  — onAccountLocked (계정 잠금)</li>
 *   <li>그 외   — onError (오류 코드 문자열 전달)</li>
 * </ul>
 */
public interface BiometricBridgeCallback {

    /**
     * CASE 1: 인증/등록/사용자변경 성공.
     *
     * <p>로그인 성공 시: userId, accessToken, expiresIn에 유효값 전달.
     * <p>등록/사용자변경 완료 시: accessToken="", expiresIn=0으로 전달 (완료 신호).
     *
     * <p>호출 스레드: UI 스레드
     *
     * @param userId      인증된 사용자 ID (등록/변경 완료 시에도 전달)
     * @param accessToken 발급된 액세스 토큰 (민감 정보 — 로컬 저장 시 암호화 필요)
     * @param expiresIn   토큰 유효 시간(초, 0이면 토큰 없음)
     */
    void onLoginSuccess(String userId, String accessToken, int expiresIn); // CASE 1

    /**
     * CASE 2: 안면인식 실패 — 잠금 전 재시도 가능 상태.
     * 화면에 실패 횟수 또는 잔여 시도 횟수를 표시할 때 활용합니다.
     *
     * <p>호출 스레드: UI 스레드
     *
     * @param failureCount 현재 누적 실패 횟수 (1부터 시작)
     */
    void onRetry(int failureCount); // CASE 2

    /**
     * CASE 3: SESSION_EXPIRED 자동 재시도 중 상태 알림.
     * 화면에 "재시도 중..." 메시지를 표시할 때 활용합니다.
     *
     * <p>호출 스레드: UI 스레드
     *
     * @param retryCount 현재 재시도 횟수 (1부터 시작)
     * @param maxRetry   최대 재시도 횟수 (BiometricLibConstants.MAX_SESSION_RETRY)
     */
    void onSessionRetrying(int retryCount, int maxRetry); // CASE 3

    /**
     * CASE 4: 인증 실패 횟수 초과로 일시 잠금.
     * CountDownTimer 등을 이용해 잠금 해제까지 남은 시간을 표시하는 데 활용합니다.
     *
     * <p>호출 스레드: UI 스레드
     *
     * @param remainingSeconds 잠금 해제까지 남은 초 (0이면 즉시 해제)
     */
    void onLockedOut(int remainingSeconds); // CASE 4

    /**
     * CASE 7: 기기 미등록 상태.
     * 등록 화면(RegisterActivity 등)으로 이동 처리가 필요합니다.
     *
     * <p>호출 스레드: UI 스레드
     */
    void onNotRegistered(); // CASE 7

    /**
     * CASE 9: 관리자 계정 잠금.
     * ID/PW 입력 영역을 표시하고 unlockWithIdPw() 호출 준비가 필요합니다.
     *
     * <p>호출 스레드: UI 스레드
     */
    void onAccountLocked(); // CASE 9

    /**
     * 오류 발생 (CASE 5, 6, 8, 10, 11 및 기타).
     *
     * <p>호출 스레드: UI 스레드
     *
     * <p>errorCode 값 목록 (biometric-lib ErrorCode enum 이름):
     * <ul>
     *   <li>BIOMETRIC_NONE_ENROLLED  — 기기에 안면인식이 등록되지 않음</li>
     *   <li>BIOMETRIC_HW_UNAVAILABLE — 생체인증 하드웨어 사용 불가</li>
     *   <li>KEY_INVALIDATED          — 생체정보 변경으로 Keystore 키 무효화 (CASE 10)</li>
     *   <li>DEVICE_NOT_FOUND         — 서버에 기기 등록 정보 없음</li>
     *   <li>TIMESTAMP_OUT_OF_RANGE   — 기기 시간 서버와 불일치</li>
     *   <li>MISSING_SIGNATURE        — 서명값 누락 (내부 오류)</li>
     *   <li>SESSION_EXPIRED          — 세션 만료 후 재시도 한계 초과 (CASE 11)</li>
     *   <li>NONCE_REPLAY             — 중복 요청 감지</li>
     *   <li>INVALID_SIGNATURE        — 서명 검증 실패 (CASE 6)</li>
     *   <li>KEY_NOT_FOUND            — 로컬 키 없음</li>
     *   <li>NETWORK_ERROR            — 네트워크 연결 오류</li>
     *   <li>UNKNOWN_ERROR            — 알 수 없는 오류</li>
     * </ul>
     *
     * @param errorCode ErrorCode enum의 name() 문자열
     */
    void onError(String errorCode); // CASE 5, 6, 8, 10, 11
}
