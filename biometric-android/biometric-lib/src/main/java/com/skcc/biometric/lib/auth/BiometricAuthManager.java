package com.skcc.biometric.lib.auth;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Log;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.skcc.biometric.lib.BiometricLibConstants;
import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.crypto.KeyNotFoundException;
import com.skcc.biometric.lib.network.AccountLockedException;
import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.network.DeviceNotFoundException;
import com.skcc.biometric.lib.network.KeyInvalidatedException;
import com.skcc.biometric.lib.network.TokenVerificationException;
import com.skcc.biometric.lib.policy.FailurePolicyManager;
import com.skcc.biometric.lib.storage.TokenStorage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * BiometricAuthManager
 * 안면인식 인증 전체 플로우를 오케스트레이션하는 핵심 클래스.
 *
 * <p>책임:
 * <ol>
 *   <li>FailurePolicyManager를 통해 서버 실패 정책 로드 (최대 5분 캐시)</li>
 *   <li>로컬 잠금·미등록 여부 사전 확인</li>
 *   <li>챌린지 요청 (POST /api/auth/challenge) → UI 스레드에서 BiometricPrompt 표시</li>
 *   <li>인증 성공 후 ECDSA 서명 → 토큰 발급 (POST /api/auth/token)</li>
 *   <li>결과에 따라 CASE 1~11 분기 처리 및 AuthCallback 호출</li>
 * </ol>
 *
 * <p>CASE 정의:
 * <ul>
 *   <li>CASE 1  — 인증 + 토큰 발급 성공 → onSuccess()</li>
 *   <li>CASE 2  — 안면인식 실패, 재시도 가능 → onRetry()</li>
 *   <li>CASE 3  — SESSION_EXPIRED 자동 재시도 (최대 MAX_SESSION_RETRY회) → onSessionRetrying()</li>
 *   <li>CASE 4  — 로컬 일시 잠금 → onLockedOut(remainingSeconds)</li>
 *   <li>CASE 6  — INVALID_SIGNATURE 임계치 초과 → KeyRenewalHandler 위임</li>
 *   <li>CASE 7  — 기기 미등록 (서버 404) → onNotRegistered()</li>
 *   <li>CASE 9  — 계정 잠금 (실패 횟수 초과) → onAccountLocked()</li>
 *   <li>CASE 10 — KEY_INVALIDATED (서버 409 또는 KeyPermanentlyInvalidatedException) → onError(KEY_INVALIDATED)</li>
 *   <li>CASE 11 — SESSION_EXPIRED 재시도 한계 초과 → onError(SESSION_EXPIRED)</li>
 *   <li>CASE 12 — 사용자 변경 (UserChangeHandler 위임, BiometricBridge에서 진입)</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>authenticate() 호출 시 반드시 살아있는 FragmentActivity를 전달해야 함</li>
 *   <li>모든 AuthCallback 메서드는 UI 스레드에서 호출됨</li>
 *   <li>ioExecutor.shutdown() 호출 금지 — 외부(BiometricApplication)가 생명주기 관리</li>
 *   <li>CryptoObject 없이 BiometricPrompt 사용 — PoC 구조, 실서비스에서 강화 필요 (TODO 참고)</li>
 * </ul>
 */
public class BiometricAuthManager {

    private static final String TAG = "BiometricAuthManager";

    // BiometricPrompt 표시 문자열 — biometric-lib은 R.string 접근 불가이므로 상수로 관리
    // TODO: [실서비스] 앱 레이어에서 PromptInfo를 주입하는 구조로 개선 검토
    private static final String PROMPT_TITLE_AUTH    = "안면 인식 로그인";
    private static final String PROMPT_SUBTITLE_AUTH = "등록된 얼굴로 인증해주세요";

    /** 생성 비용이 큰 SecureRandom을 1회만 초기화해 재사용 (thread-safe). */
    private final SecureRandom secureRandom = new SecureRandom();

    /** BiometricManager 인스턴스 재사용 — canAuthenticate() 호출마다 생성 방지. */
    private final BiometricManager biometricManager;

    /** 외부(BiometricApplication)에서 주입받은 공유 executor — 직접 소유하지 않음. */
    private final ExecutorService ioExecutor;

    private final Context context;

    private final AuthApiClient authApiClient;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;
    private final FailurePolicyManager failurePolicyManager;
    private final KeyRenewalHandler keyRenewalHandler;

    /** INVALID_SIGNATURE 연속 수신 카운터 — {@link BiometricLibConstants#INVALID_SIGNATURE_RENEWAL_THRESHOLD} 이상 시 자동 키 재발급 (CASE6) */
    private int invalidSignatureCount = 0;

    /** SESSION_EXPIRED 자동 재시도 카운터 (CASE3) */
    private int sessionRetryCount = 0;

    public BiometricAuthManager(
            Context context,
            AuthApiClient authApiClient,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage,
            FailurePolicyManager failurePolicyManager,
            ExecutorService ioExecutor) {
        this.context = context.getApplicationContext();
        this.authApiClient = authApiClient;
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
        this.failurePolicyManager = failurePolicyManager;
        this.ioExecutor = ioExecutor;
        this.biometricManager = BiometricManager.from(this.context);
        this.keyRenewalHandler = new KeyRenewalHandler(
                context, ecKeyManager, authApiClient, tokenStorage, ioExecutor);
    }

    /**
     * KEY_INVALIDATED 다이얼로그에서 사용자가 확인을 누른 뒤 호출 (CASE 10).
     * 챌린지 재요청 없이 곧바로 키 재발급 → BiometricPrompt 재실행.
     *
     * @param activity 현재 활성 FragmentActivity (BiometricPrompt 표시용)
     * @param callback 재인증 결과를 수신할 콜백 — UI 스레드에서 호출됨
     */
    public void startRenewal(FragmentActivity activity, AuthCallback callback) {
        Log.d(TAG, "startRenewal 호출 → KeyRenewalHandler 시작");
        String deviceId = tokenStorage.getDeviceId();
        String userId = tokenStorage.getUserId();
        keyRenewalHandler.renewAndRetry(activity, deviceId, userId, callback);
    }

    /**
     * 안면인식 인증 플로우 진입점.
     *
     * <p>사전 조건을 확인한 뒤 ioExecutor 백그라운드 스레드에서
     * 정책 로드·챌린지 요청을 수행하고, UI 스레드에서 BiometricPrompt를 표시합니다.
     *
     * @param activity BiometricPrompt를 표시할 FragmentActivity (null 불가)
     * @param callback 인증 결과 수신 콜백 — 모든 메서드는 UI 스레드에서 호출됨
     *
     * <p>흐름:
     * <ol>
     *   <li>API 28 미만 → onError(BIOMETRIC_HW_UNAVAILABLE) 즉시 반환</li>
     *   <li>미등록 상태 → onNotRegistered() 즉시 반환 (CASE 7 사전 차단)</li>
     *   <li>로컬 잠금 중 → onLockedOut(remainingSeconds) 즉시 반환 (CASE 4 사전 차단)</li>
     *   <li>canAuthenticate() 실패 → onError 즉시 반환</li>
     *   <li>ioExecutor에서 runPrepareAndShowPrompt() 실행</li>
     * </ol>
     */
    public void authenticate(FragmentActivity activity, AuthCallback callback) {
        Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > authenticate : 인증 시작");
        // API 28(Android 9.0) 미만 기기 생체인증 미지원 처리
        // A2 minSdk 23 대응 — 런타임 체크
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "API 레벨 " + Build.VERSION.SDK_INT
                    + " → 생체인증 미지원 (API 28 이상 필요)");
            activity.runOnUiThread(() ->
                    callback.onError(ErrorCode.BIOMETRIC_HW_UNAVAILABLE));
            return;
        }

        if (!tokenStorage.isRegistered()) {
            activity.runOnUiThread(callback::onNotRegistered);
            return;
        }
        if (failurePolicyManager.isLocallyLocked()) {
            int remaining = failurePolicyManager.getLockRemainingSeconds();
            activity.runOnUiThread(() -> callback.onLockedOut(remaining));
            return;
        }

        int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        Log.d(TAG, "canAuthenticate(BIOMETRIC_WEAK) = " + canAuth);
        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            Log.d(TAG, "BIOMETRIC_ERROR_NONE_ENROLLED → onError(BIOMETRIC_NONE_ENROLLED)");
            activity.runOnUiThread(() -> callback.onError(ErrorCode.BIOMETRIC_NONE_ENROLLED));
            return;
        }
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.d(TAG, "canAuthenticate 실패 코드 → onError(BIOMETRIC_HW_UNAVAILABLE), code=" + canAuth);
            activity.runOnUiThread(() -> callback.onError(ErrorCode.BIOMETRIC_HW_UNAVAILABLE));
            return;
        }

        ioExecutor.submit(() -> runPrepareAndShowPrompt(activity, callback));
    }

    /**
     * 백그라운드 스레드에서 실행 — 정책 로드 → 챌린지 요청 → UI 스레드로 전달.
     *
     * <p>호출 스레드: ioExecutor (백그라운드)
     * <p>결과 전달: activity.runOnUiThread()를 통해 UI 스레드에서 showBiometricPrompt 또는 콜백 호출
     *
     * <p>예외 처리 분기:
     * <ul>
     *   <li>DeviceNotFoundException  → 로컬 등록 삭제 후 onNotRegistered() (CASE 7)</li>
     *   <li>AccountLockedException   → onAccountLocked() (CASE 9)</li>
     *   <li>KeyInvalidatedException  → onError(KEY_INVALIDATED) (CASE 10)</li>
     *   <li>그 외 네트워크 오류        → onError(NETWORK_ERROR)</li>
     * </ul>
     */
    private void runPrepareAndShowPrompt(FragmentActivity activity, AuthCallback callback) {
        String deviceId = null;
        String userId = null;
        try {
            deviceId = tokenStorage.getDeviceId();
            userId = tokenStorage.getUserId();
            if (deviceId == null || userId == null) {
                activity.runOnUiThread(() -> callback.onError(ErrorCode.UNKNOWN_ERROR));
                return;
            }

            failurePolicyManager.loadPolicyIfAbsent(authApiClient, deviceId);

            String clientNonce = randomHexNonce16Bytes();
            long timestamp = System.currentTimeMillis();

            AuthApiClient.ChallengeRequest challengeReq = new AuthApiClient.ChallengeRequest();
            challengeReq.deviceId = deviceId;
            challengeReq.userId = userId;
            challengeReq.clientNonce = clientNonce;
            challengeReq.timestamp = timestamp;

            AuthApiClient.ChallengeResponse challengeResponse = authApiClient.getChallenge(challengeReq);

            byte[] payload = buildPayloadBytes(
                    challengeResponse.serverChallenge, clientNonce, deviceId, timestamp);

            final String deviceIdForPrompt = deviceId;
            final String userIdForPrompt = userId;
            activity.runOnUiThread(
                    () ->
                            showBiometricPrompt(
                                    activity,
                                    payload,
                                    challengeResponse,
                                    deviceIdForPrompt,
                                    userIdForPrompt,
                                    clientNonce,
                                    timestamp,
                                    callback));

        } catch (DeviceNotFoundException e) {
            tokenStorage.clearRegistration();
            failurePolicyManager.invalidatePolicy();
            Log.w("BIOMETRIC_LIB", "[AUTH] CASE7 기기 미등록 onNotRegistered 호출");
            activity.runOnUiThread(callback::onNotRegistered);
        } catch (AccountLockedException e) {
            Log.w("BIOMETRIC_LIB", "[AUTH] CASE9 계정 잠금 onAccountLocked 호출");
            activity.runOnUiThread(callback::onAccountLocked);
        } catch (KeyInvalidatedException e) {
            // 서버 409 KEY_INVALIDATED → 사용자 확인 후 갱신
            // Dialog 표시를 위해 onError로 전달 (LoginActivity에서 showKeyInvalidatedDialog 처리)
            Log.w(TAG, "서버 KEY_INVALIDATED 감지 → 사용자 확인 필요");
            Log.w("BIOMETRIC_LIB", "[AUTH] CASE10 KEY_INVALIDATED 감지");
            activity.runOnUiThread(() -> callback.onError(ErrorCode.KEY_INVALIDATED));
        } catch (Exception e) {
            Log.e(TAG, "authenticate 실패", e);
            activity.runOnUiThread(() -> callback.onError(ErrorCode.NETWORK_ERROR));
        }
    }

    /**
     * UI 스레드에서 BiometricPrompt를 생성하고 표시합니다.
     *
     * <p>호출 스레드: UI 스레드 (activity.runOnUiThread()에서 호출됨)
     *
     * <p>인증 성공 시 ioExecutor 백그라운드 스레드에서 서명·토큰 요청을 수행하며,
     * 결과에 따라 CASE 1, 2, 3, 6, 9, 10, 11 콜백을 UI 스레드에서 호출합니다.
     *
     * @param payload           서명 대상 바이트 배열 (serverChallenge:clientNonce:deviceId:timestamp)
     * @param challengeResponse 서버 챌린지 응답 (sessionId, serverChallenge 포함)
     * @param deviceId          등록된 기기 ID
     * @param userId            인증 대상 사용자 ID
     * @param clientNonce       클라이언트 nonce (재전송 공격 방지)
     * @param timestamp         챌린지 요청 시각 (epoch ms)
     * @param callback          인증 결과 콜백 — UI 스레드에서 호출됨
     */
    private void showBiometricPrompt(
            FragmentActivity activity,
            byte[] payload,
            AuthApiClient.ChallengeResponse challengeResponse,
            String deviceId,
            String userId,
            String clientNonce,
            long timestamp,
            AuthCallback callback) {

        Executor mainExecutor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt.PromptInfo promptInfo = buildPromptInfo();

        BiometricPrompt biometricPrompt =
                new BiometricPrompt(
                        activity,
                        mainExecutor,
                        new BiometricPrompt.AuthenticationCallback() {

                            @Override
                            public void onAuthenticationSucceeded(
                                    BiometricPrompt.AuthenticationResult result) {
                                ioExecutor.submit(
                                        () -> {
                                            try {
                                                String ecSignature = ecKeyManager.signPayload(payload);
                                                Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > signPayload : 서명 완료");

                                                AuthApiClient.TokenRequest tokenReq =
                                                        new AuthApiClient.TokenRequest();
                                                tokenReq.sessionId = challengeResponse.sessionId;
                                                tokenReq.deviceId = deviceId;
                                                tokenReq.userId = userId;
                                                tokenReq.ecSignature = ecSignature;
                                                tokenReq.clientNonce = clientNonce;
                                                tokenReq.timestamp = timestamp;

                                                Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > requestToken : 토큰 요청 시작");
                                                AuthApiClient.TokenResponse tokenResponse =
                                                        authApiClient.requestToken(tokenReq);
                                                Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > requestToken : 토큰 수신 완료 expiresIn=" + tokenResponse.expiresIn);
                                                tokenStorage.saveTokens(
                                                        tokenResponse.accessToken,
                                                        tokenResponse.refreshToken);
                                                failurePolicyManager.reset();
                                                invalidSignatureCount = 0;
                                                Log.d(TAG, "로그인 성공 → invalidSignatureCount 초기화");
                                                sessionRetryCount = 0;
                                                Log.d(TAG, "로그인 성공 → sessionRetryCount 초기화");
                                                Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > onSuccess : userId=" + userId);
                                                activity.runOnUiThread(
                                                        () ->
                                                                callback.onSuccess(
                                                                        userId, tokenResponse));

                                            } catch (KeyNotFoundException e) {
                                                Log.w(TAG, "로컬 키 없음 → 키 재발급 시작");
                                                activity.runOnUiThread(
                                                        () -> keyRenewalHandler.renewAndRetry(
                                                                activity, deviceId, userId, callback));

                                            } catch (KeyPermanentlyInvalidatedException e) {
                                                Log.w(TAG, "KeyPermanentlyInvalidatedException 감지 → 키 재발급 시작");
                                                activity.runOnUiThread(
                                                        () -> keyRenewalHandler.renewAndRetry(
                                                                activity, deviceId, userId, callback));

                                            } catch (TokenVerificationException e) {
                                                Log.w(TAG, "token 검증 실패: " + e.getErrorCode());
                                                if ("INVALID_SIGNATURE".equals(e.getErrorCode())) {
                                                    invalidSignatureCount++;
                                                    Log.w(TAG, "INVALID_SIGNATURE 발생 횟수=" + invalidSignatureCount);
                                                    if (invalidSignatureCount >= BiometricLibConstants.INVALID_SIGNATURE_RENEWAL_THRESHOLD) {
                                                        invalidSignatureCount = 0;
                                                        Log.w(TAG, "INVALID_SIGNATURE " + BiometricLibConstants.INVALID_SIGNATURE_RENEWAL_THRESHOLD + "회 초과 → 키 재발급 시작");
                                                        Log.w("BIOMETRIC_LIB", "[AUTH] CASE6 INVALID_SIGNATURE 키 재발급 시작");
                                                        activity.runOnUiThread(
                                                                () -> keyRenewalHandler.renewAndRetry(
                                                                        activity, deviceId, userId, callback));
                                                    } else {
                                                        activity.runOnUiThread(
                                                                () -> callback.onError(ErrorCode.INVALID_SIGNATURE));
                                                    }
                                                } else if ("SESSION_EXPIRED".equals(e.getErrorCode())) {
                                                    // CASE3: 자동 재시도 (최대 BiometricLibConstants.MAX_SESSION_RETRY회)
                                                    if (sessionRetryCount < BiometricLibConstants.MAX_SESSION_RETRY) {
                                                        sessionRetryCount++;
                                                        Log.w(TAG, "SESSION_EXPIRED → 자동 재시도 "
                                                                + sessionRetryCount + "/" + BiometricLibConstants.MAX_SESSION_RETRY);
                                                        Log.w("BIOMETRIC_LIB", "[AUTH] CASE3 SESSION_EXPIRED 재시도 " + sessionRetryCount + "/" + BiometricLibConstants.MAX_SESSION_RETRY);
                                                        // 재시도 상태를 앱(LoginActivity)에 알림
                                                        final int currentRetry = sessionRetryCount;
                                                        activity.runOnUiThread(() ->
                                                                callback.onSessionRetrying(
                                                                        currentRetry, BiometricLibConstants.MAX_SESSION_RETRY));
                                                        ioExecutor.submit(() -> {
                                                            try {
                                                                String newClientNonce = randomHexNonce16Bytes();
                                                                long newTimestamp = System.currentTimeMillis();

                                                                AuthApiClient.ChallengeRequest newReq =
                                                                        new AuthApiClient.ChallengeRequest();
                                                                newReq.deviceId = deviceId;
                                                                newReq.userId = userId;
                                                                newReq.clientNonce = newClientNonce;
                                                                newReq.timestamp = newTimestamp;

                                                                AuthApiClient.ChallengeResponse newChallenge =
                                                                        authApiClient.getChallenge(newReq);
                                                                Log.d(TAG, "세션 재시도 Challenge 발급 완료");

                                                                byte[] newPayload = buildPayloadBytes(
                                                                        newChallenge.serverChallenge,
                                                                        newClientNonce, deviceId, newTimestamp);

                                                                activity.runOnUiThread(() ->
                                                                        showBiometricPrompt(
                                                                                activity, newPayload, newChallenge,
                                                                                deviceId, userId,
                                                                                newClientNonce, newTimestamp,
                                                                                callback));
                                                            } catch (Exception ex) {
                                                                Log.e(TAG, "세션 재시도 실패", ex);
                                                                sessionRetryCount = 0;
                                                                activity.runOnUiThread(() ->
                                                                        callback.onError(ErrorCode.SESSION_EXPIRED));
                                                            }
                                                        });
                                                    } else {
                                                        sessionRetryCount = 0;
                                                        Log.w(TAG, "SESSION_EXPIRED 재시도 횟수 초과 (" + BiometricLibConstants.MAX_SESSION_RETRY + "회)");
                                                        Log.e("BIOMETRIC_LIB", "[AUTH] CASE11 SESSION_EXPIRED 재시도 한계 초과");
                                                        activity.runOnUiThread(() ->
                                                                callback.onError(ErrorCode.SESSION_EXPIRED));
                                                    }

                                                } else {
                                                    ErrorCode errCode;
                                                    switch (e.getErrorCode()) {
                                                        case "TIMESTAMP_OUT_OF_RANGE":
                                                            errCode = ErrorCode.TIMESTAMP_OUT_OF_RANGE;
                                                            break;
                                                        case "NONCE_REPLAY":
                                                            errCode = ErrorCode.NONCE_REPLAY;
                                                            break;
                                                        case "MISSING_SIGNATURE":
                                                            errCode = ErrorCode.MISSING_SIGNATURE;
                                                            break;
                                                        default:
                                                            errCode = ErrorCode.UNKNOWN_ERROR;
                                                            break;
                                                    }
                                                    final ErrorCode finalErrCode = errCode;
                                                    activity.runOnUiThread(
                                                            () -> callback.onError(finalErrCode));
                                                }

                                            } catch (Exception e) {
                                                Log.e(TAG, "token 요청 실패", e);
                                                activity.runOnUiThread(
                                                        () -> callback.onError(ErrorCode.NETWORK_ERROR));
                                            }
                                        });
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                int count = failurePolicyManager.recordFailure();
                                if (failurePolicyManager.shouldRequestAccountLock()) {
                                    ioExecutor.submit(
                                            () ->
                                                    runAccountLock(
                                                            activity,
                                                            deviceId,
                                                            userId,
                                                            callback));
                                } else if (failurePolicyManager.isLocallyLocked()) {
                                    int sec = failurePolicyManager.getLockRemainingSeconds();
                                    Log.w("BIOMETRIC_LIB", "[AUTH] CASE4 일시잠금 remainingSeconds=" + sec);
                                    activity.runOnUiThread(() -> callback.onLockedOut(sec));
                                } else {
                                    Log.w("BIOMETRIC_LIB", "[AUTH] CASE2 재시도 failureCount=" + count);
                                    activity.runOnUiThread(() -> callback.onRetry(count));
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                if (errorCode == BiometricPrompt.ERROR_CANCELED
                                        || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                                    return;
                                }
                                activity.runOnUiThread(
                                        () -> callback.onError(ErrorCode.UNKNOWN_ERROR));
                            }
                        });

        Log.d("BIOMETRIC_LIB", "[AUTH] BiometricAuthManager > showPrompt : BiometricPrompt 표시");
        biometricPrompt.authenticate(promptInfo);
    }

    private static BiometricPrompt.PromptInfo buildPromptInfo() {
        // TODO: [실서비스] DEVICE_CREDENTIAL 제거 여부 — PIN/패턴만으로 우회 가능한지 보안 정책 검토
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle(PROMPT_TITLE_AUTH)
                .setSubtitle(PROMPT_SUBTITLE_AUTH)
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
    }

    /**
     * ECDSA 서명 대상 페이로드를 생성합니다.
     * 형식: "serverChallenge:clientNonce:deviceId:timestamp" (UTF-8 인코딩)
     *
     * <p>서버는 동일한 방식으로 페이로드를 재구성하여 서명을 검증합니다.
     *
     * @param serverChallenge 서버 챌린지 값 (재전송 공격 방지용 서버 nonce)
     * @param clientNonce     클라이언트 nonce (추가 엔트로피)
     * @param deviceId        기기 ID
     * @param timestamp       타임스탬프 (epoch ms, 서버의 TIMESTAMP_OUT_OF_RANGE 검증 기준)
     * @return UTF-8 인코딩된 서명 대상 바이트 배열
     */
    private static byte[] buildPayloadBytes(
            String serverChallenge,
            String clientNonce,
            String deviceId,
            long timestamp) {
        return new StringBuilder()
                .append(serverChallenge).append(':')
                .append(clientNonce).append(':')
                .append(deviceId).append(':')
                .append(timestamp)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 서버에 계정 잠금 요청을 전송하고 onAccountLocked()를 호출합니다 (CASE 9).
     *
     * <p>호출 스레드: ioExecutor (백그라운드)
     * <p>잠금 API 실패 시에도 로컬 정책을 무효화하고 onAccountLocked()를 호출합니다.
     */
    private void runAccountLock(
            FragmentActivity activity, String deviceId, String userId, AuthCallback callback) {
        try {
            authApiClient.lockAccount(deviceId, userId);
        } catch (Exception e) {
            Log.e(TAG, "lockAccount failed after threshold", e);
        }
        failurePolicyManager.invalidatePolicy();
        activity.runOnUiThread(callback::onAccountLocked);
    }

    private String randomHexNonce16Bytes() {
        byte[] bytes = new byte[BiometricLibConstants.NONCE_BYTE_SIZE];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public ExecutorService getIoExecutor() {
        return ioExecutor;
    }

    /**
     * executor는 BiometricApplication이 관리하므로 Activity에서 직접 종료하지 않음.
     * 앱 프로세스 종료 시 OS가 정리.
     */
    public void shutdown() {
        Log.d(TAG, "shutdown() 호출 — executor는 BiometricApplication이 관리");
    }

    /**
     * BiometricAuthManager 인증 결과 콜백 인터페이스.
     *
     * <p>모든 메서드는 UI 스레드에서 호출됩니다.
     * Activity/Fragment에서 구현할 때 UI 조작을 직접 수행할 수 있습니다.
     *
     * <p>BiometricBridge를 통해 사용하는 경우 이 인터페이스를 직접 구현하지 않아도 됩니다.
     * BiometricBridgeCallback(원시 타입 기반)을 구현하면 됩니다.
     */
    public interface AuthCallback {

        /**
         * CASE 1: 인증 + 토큰 발급 성공.
         *
         * @param userId        인증된 사용자 ID
         * @param tokenResponse 서버 발급 토큰 (accessToken, refreshToken, expiresIn 포함)
         */
        void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse);

        /** CASE 7: 기기 미등록 — 등록 화면으로 이동 처리 필요. */
        void onNotRegistered();

        /**
         * CASE 4: 로컬 일시 잠금 — 잠금 해제까지 대기 또는 카운트다운 표시 필요.
         *
         * @param remainingSeconds 잠금 해제까지 남은 초 (0 이상)
         */
        void onLockedOut(int remainingSeconds);

        /**
         * CASE 2: 안면인식 실패, 재시도 가능.
         *
         * @param failureCount 현재 누적 실패 횟수 (잠금 임계치 표시에 활용)
         */
        void onRetry(int failureCount);

        /** CASE 9: 관리자 계정 잠금 — ID/PW 입력 영역 표시 등 별도 처리 필요. */
        void onAccountLocked();

        /**
         * CASE 3: SESSION_EXPIRED 자동 재시도 중 상태 알림.
         * UI에 재시도 중 메시지를 표시할 때 활용.
         *
         * @param retryCount 현재 재시도 횟수 (1부터 시작)
         * @param maxRetry   최대 재시도 횟수 (BiometricLibConstants.MAX_SESSION_RETRY)
         */
        void onSessionRetrying(int retryCount, int maxRetry);

        /**
         * CASE 5/6/8/10/11: 오류 발생.
         * onNotEnrolled()는 이 메서드로 통합됨 (errorCode=BIOMETRIC_NONE_ENROLLED).
         *
         * @param errorCode 오류 코드 (ErrorCode enum 참조)
         */
        void onError(ErrorCode errorCode);
    }
}
