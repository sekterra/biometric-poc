package com.skcc.biometric.lib.auth;

import android.content.Context;
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
import com.skcc.biometric.lib.storage.TokenStorage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * CASE6 (INVALID_SIGNATURE), CASE10 (KEY_INVALIDATED) 공통 처리.
 *
 * <p>흐름: 키 재발급 → 서버 공개키 갱신 → Challenge 재요청 → BiometricPrompt 재실행.
 *
 * <p>TODO: [실서비스] 키 재발급 이력 서버 로그 기록 추가
 */
public class KeyRenewalHandler {

    private static final String TAG = "KeyRenewalHandler";

    // BiometricPrompt 표시 문자열 — biometric-lib은 R.string 접근 불가이므로 상수로 관리
    // TODO: [실서비스] 앱 레이어에서 PromptInfo를 주입하는 구조로 개선 검토
    private static final String PROMPT_TITLE_RENEWAL    = "안면 인식 로그인";
    private static final String PROMPT_SUBTITLE_RENEWAL = "보안키가 갱신되었습니다. 다시 인증해주세요.";

    /** 생성 비용이 큰 SecureRandom을 1회만 초기화해 재사용 (thread-safe). */
    private final SecureRandom secureRandom = new SecureRandom();

    private final Context context;
    private final EcKeyManager ecKeyManager;
    private final AuthApiClient authApiClient;
    private final TokenStorage tokenStorage;
    private final ExecutorService executor;

    public KeyRenewalHandler(
            Context context,
            EcKeyManager ecKeyManager,
            AuthApiClient authApiClient,
            TokenStorage tokenStorage,
            ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.ecKeyManager = ecKeyManager;
        this.authApiClient = authApiClient;
        this.tokenStorage = tokenStorage;
        this.executor = executor;
    }

    public void renewAndRetry(
            FragmentActivity activity,
            String deviceId,
            String userId,
            BiometricAuthManager.AuthCallback originalCallback) {

        // TODO: [실서비스] device_id 마스킹 처리 필요
        Log.w(TAG, "키 재발급 시작 deviceId=" + deviceId);

        executor.submit(() -> {
            try {
                // ① 기존 키 삭제
                try {
                    ecKeyManager.deleteKeyPair();
                } catch (Exception ignored) {
                }
                Log.d(TAG, "기존 키 삭제 완료");

                // ② 새 EC 키쌍 생성
                ecKeyManager.generateKeyPair();
                Log.d(TAG, "새 EC 키쌍 생성 완료");

                // ③ 새 공개키 추출
                String newPublicKey = ecKeyManager.getPublicKeyBase64();

                // ④ 서버 공개키 갱신
                authApiClient.renewKey(deviceId, newPublicKey);
                Log.d(TAG, "서버 공개키 갱신 완료");

                // ⑤ 새 Challenge 요청
                String clientNonce = generateNonce();
                long timestamp = System.currentTimeMillis();

                AuthApiClient.ChallengeRequest challengeReq = new AuthApiClient.ChallengeRequest();
                challengeReq.deviceId = deviceId;
                challengeReq.userId = userId;
                challengeReq.clientNonce = clientNonce;
                challengeReq.timestamp = timestamp;

                AuthApiClient.ChallengeResponse challenge = authApiClient.getChallenge(challengeReq);
                Log.d(TAG, "새 Challenge 발급 완료");

                // ⑥ payload 구성
                byte[] payload = buildPayload(
                        challenge.serverChallenge, clientNonce, deviceId, timestamp);

                // ⑦ BiometricPrompt 재실행 (UI 스레드)
                activity.runOnUiThread(() -> {
                    Log.d(TAG, "BiometricPrompt 재실행");
                    showBiometricPromptForRenewal(
                            activity, payload, challenge,
                            deviceId, userId, clientNonce, timestamp,
                            originalCallback);
                });

            } catch (DeviceNotFoundException e) {
                Log.e(TAG, "키 재발급 실패: 기기 미등록", e);
                activity.runOnUiThread(
                        () -> originalCallback.onError(ErrorCode.DEVICE_NOT_FOUND));

            } catch (AccountLockedException e) {
                Log.e(TAG, "키 재발급 실패: 계정 잠금", e);
                activity.runOnUiThread(originalCallback::onAccountLocked);

            } catch (Exception e) {
                Log.e(TAG, "키 재발급 실패: 알 수 없는 오류", e);
                activity.runOnUiThread(
                        () -> originalCallback.onError(ErrorCode.NETWORK_ERROR));
            }
        });
    }

    private void showBiometricPromptForRenewal(
            FragmentActivity activity,
            byte[] payload,
            AuthApiClient.ChallengeResponse challenge,
            String deviceId,
            String userId,
            String clientNonce,
            long timestamp,
            BiometricAuthManager.AuthCallback callback) {

        Executor mainExecutor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt.PromptInfo promptInfo =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(PROMPT_TITLE_RENEWAL)
                        .setSubtitle(PROMPT_SUBTITLE_RENEWAL)
                        .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK
                                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();

        BiometricPrompt biometricPrompt = new BiometricPrompt(
                activity,
                mainExecutor,
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        executor.submit(() -> {
                            try {
                                String ecSignature = ecKeyManager.signPayload(payload);

                                AuthApiClient.TokenRequest tokenReq =
                                        new AuthApiClient.TokenRequest();
                                tokenReq.sessionId = challenge.sessionId;
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

                                Log.d(TAG, "키 재발급 후 로그인 성공");
                                activity.runOnUiThread(
                                        () -> callback.onSuccess(userId, tokenResponse));

                            } catch (KeyNotFoundException e) {
                                Log.e(TAG, "키 재발급 후 서명 실패: 로컬 키 없음", e);
                                activity.runOnUiThread(
                                        () -> callback.onError(ErrorCode.KEY_NOT_FOUND));
                            } catch (Exception e) {
                                Log.e(TAG, "키 재발급 후 토큰 발급 실패", e);
                                activity.runOnUiThread(
                                        () -> callback.onError(ErrorCode.UNKNOWN_ERROR));
                            }
                        });
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        activity.runOnUiThread(
                                () -> callback.onError(ErrorCode.BIOMETRIC_AUTH_FAILED));
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, CharSequence errString) {
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

    private String generateNonce() {
        byte[] bytes = new byte[BiometricLibConstants.NONCE_BYTE_SIZE];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] buildPayload(
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
}
