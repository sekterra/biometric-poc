package com.biometric.poc.lib.auth;

import android.content.Context;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Log;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.crypto.KeyNotFoundException;
import com.biometric.poc.lib.network.AccountLockedException;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.network.DeviceNotFoundException;
import com.biometric.poc.lib.network.KeyInvalidatedException;
import com.biometric.poc.lib.network.TokenVerificationException;
import com.biometric.poc.lib.policy.FailurePolicyManager;
import com.biometric.poc.lib.storage.TokenStorage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 생체 인증 후 챌린지·서명·토큰 발급까지 오케스트레이션.
 *
 * <p>흐름: (백그라운드) 정책 로드 → 챌린지 요청 → (UI) {@link BiometricPrompt}를 CryptoObject 없이 표시 →
 * 인증 성공 후 윈도(예: 10초) 내 {@link EcKeyManager#signPayload(byte[])} → 토큰 요청.
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
     * KEY_INVALIDATED 다이얼로그에서 사용자가 확인을 누른 뒤 호출.
     * challenge 재요청 없이 바로 키 재발급 → BiometricPrompt 재실행.
     */
    public void startRenewal(FragmentActivity activity, AuthCallback callback) {
        Log.d(TAG, "startRenewal 호출 → KeyRenewalHandler 시작");
        String deviceId = tokenStorage.getDeviceId();
        String userId = tokenStorage.getUserId();
        keyRenewalHandler.renewAndRetry(activity, deviceId, userId, callback);
    }

    public void authenticate(FragmentActivity activity, AuthCallback callback) {
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
            activity.runOnUiThread(callback::onNotRegistered);
        } catch (AccountLockedException e) {
            activity.runOnUiThread(callback::onAccountLocked);
        } catch (KeyInvalidatedException e) {
            // 서버 409 KEY_INVALIDATED → 사용자 확인 후 갱신
            // Dialog 표시를 위해 onError로 전달 (LoginActivity에서 showKeyInvalidatedDialog 처리)
            Log.w(TAG, "서버 KEY_INVALIDATED 감지 → 사용자 확인 필요");
            activity.runOnUiThread(() -> callback.onError(ErrorCode.KEY_INVALIDATED));
        } catch (Exception e) {
            Log.e(TAG, "authenticate 실패", e);
            activity.runOnUiThread(() -> callback.onError(ErrorCode.NETWORK_ERROR));
        }
    }

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

                                                AuthApiClient.TokenRequest tokenReq =
                                                        new AuthApiClient.TokenRequest();
                                                tokenReq.sessionId = challengeResponse.sessionId;
                                                tokenReq.deviceId = deviceId;
                                                tokenReq.userId = userId;
                                                tokenReq.ecSignature = ecSignature;
                                                tokenReq.clientNonce = clientNonce;
                                                tokenReq.timestamp = timestamp;

                                                AuthApiClient.TokenResponse tokenResponse =
                                                        authApiClient.requestToken(tokenReq);
                                                tokenStorage.saveTokens(
                                                        tokenResponse.accessToken,
                                                        tokenResponse.refreshToken);
                                                failurePolicyManager.reset();
                                                invalidSignatureCount = 0;
                                                Log.d(TAG, "로그인 성공 → invalidSignatureCount 초기화");
                                                sessionRetryCount = 0;
                                                Log.d(TAG, "로그인 성공 → sessionRetryCount 초기화");
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
                                    activity.runOnUiThread(() -> callback.onLockedOut(sec));
                                } else {
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

    public interface AuthCallback {

        void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse);

        void onNotRegistered();

        void onLockedOut(int remainingSeconds);

        void onRetry(int failureCount);

        void onAccountLocked();

        /**
         * SESSION_EXPIRED 자동 재시도 중 상태 알림.
         *
         * @param retryCount 현재 재시도 횟수 (1부터 시작)
         * @param maxRetry   최대 재시도 횟수
         */
        void onSessionRetrying(int retryCount, int maxRetry);

        /** onNotEnrolled() 제거 — onError(ErrorCode.BIOMETRIC_NONE_ENROLLED)로 통합 */
        void onError(ErrorCode errorCode);
    }
}
