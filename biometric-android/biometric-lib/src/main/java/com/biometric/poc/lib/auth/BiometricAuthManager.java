package com.biometric.poc.lib.auth;

import android.content.Context;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Log;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.crypto.KeyInvalidationCallback;
import com.biometric.poc.lib.crypto.KeyInvalidationHandler;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.policy.FailurePolicyManager;
import com.biometric.poc.lib.storage.TokenStorage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 생체 인증 후 챌린지·서명·토큰 발급까지 오케스트레이션.
 *
 * <p>흐름: (백그라운드) 정책 로드 → 챌린지 요청 → Keystore 서명 준비 → (UI) BiometricPrompt → (백그라운드)
 * 토큰 요청.
 */
public class BiometricAuthManager {

    private static final String TAG = "BiometricAuth";

    private final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("biometric-poc-io");
                        return t;
                    });

    @SuppressWarnings("unused")
    private final Context context;

    private final AuthApiClient authApiClient;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;
    private final FailurePolicyManager failurePolicyManager;
    private final KeyInvalidationHandler keyInvalidationHandler;

    public BiometricAuthManager(
            Context context,
            AuthApiClient authApiClient,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage,
            FailurePolicyManager failurePolicyManager,
            KeyInvalidationHandler keyInvalidationHandler) {
        this.context = context.getApplicationContext();
        this.authApiClient = authApiClient;
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
        this.failurePolicyManager = failurePolicyManager;
        this.keyInvalidationHandler = keyInvalidationHandler;
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

        ioExecutor.submit(() -> runPrepareAndShowPrompt(activity, callback));
    }

    private void runPrepareAndShowPrompt(FragmentActivity activity, AuthCallback callback) {
        try {
            String deviceId = tokenStorage.getDeviceId();
            String userId = tokenStorage.getUserId();
            if (deviceId == null || userId == null) {
                activity.runOnUiThread(() -> callback.onError("deviceId 또는 userId가 없습니다"));
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

            final BiometricPrompt.CryptoObject cryptoObject;
            try {
                cryptoObject = ecKeyManager.buildCryptoObject();
            } catch (KeyPermanentlyInvalidatedException e) {
                keyInvalidationHandler.handleInvalidation(
                        deviceId,
                        new KeyInvalidationCallback() {
                            @Override
                            public void onInvalidated() {
                                activity.runOnUiThread(callback::onKeyInvalidated);
                            }

                            @Override
                            public void onError(String message) {
                                activity.runOnUiThread(() -> callback.onError(message));
                            }
                        });
                return;
            }

            byte[] payload =
                    (challengeResponse.serverChallenge
                                    + ":"
                                    + clientNonce
                                    + ":"
                                    + deviceId
                                    + ":"
                                    + timestamp)
                            .getBytes(StandardCharsets.UTF_8);

            activity.runOnUiThread(
                    () ->
                            showBiometricPrompt(
                                    activity,
                                    cryptoObject,
                                    payload,
                                    challengeResponse,
                                    deviceId,
                                    userId,
                                    clientNonce,
                                    timestamp,
                                    callback));

        } catch (Exception e) {
            String msg =
                    e.getMessage() != null && !e.getMessage().isEmpty()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            activity.runOnUiThread(() -> callback.onError(msg));
        }
    }

    private void showBiometricPrompt(
            FragmentActivity activity,
            BiometricPrompt.CryptoObject cryptoObject,
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
                                        () ->
                                                runTokenExchange(
                                                        activity,
                                                        result,
                                                        payload,
                                                        challengeResponse,
                                                        deviceId,
                                                        userId,
                                                        clientNonce,
                                                        timestamp,
                                                        callback));
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
                                        () ->
                                                callback.onError(
                                                        errString != null
                                                                ? errString.toString()
                                                                : "Biometric error"));
                            }
                        });

        biometricPrompt.authenticate(promptInfo, cryptoObject);
    }

    private static BiometricPrompt.PromptInfo buildPromptInfo() {
        // TODO: [실서비스] DEVICE_CREDENTIAL 제거 여부 — PIN/패턴만으로 우회 가능한지 보안 정책 검토
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle("안면 인식 로그인")
                .setSubtitle("등록된 얼굴로 인증해주세요")
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
    }

    private void runTokenExchange(
            FragmentActivity activity,
            BiometricPrompt.AuthenticationResult result,
            byte[] payload,
            AuthApiClient.ChallengeResponse challengeResponse,
            String deviceId,
            String userId,
            String clientNonce,
            long timestamp,
            AuthCallback callback) {
        try {
            String ecSignature = ecKeyManager.sign(result.getCryptoObject(), payload);
            AuthApiClient.TokenRequest tokenReq = new AuthApiClient.TokenRequest();
            tokenReq.sessionId = challengeResponse.sessionId;
            tokenReq.deviceId = deviceId;
            tokenReq.userId = userId;
            tokenReq.ecSignature = ecSignature;
            tokenReq.clientNonce = clientNonce;
            tokenReq.timestamp = timestamp;

            AuthApiClient.TokenResponse tokenResponse = authApiClient.requestToken(tokenReq);
            tokenStorage.saveTokens(tokenResponse.accessToken, tokenResponse.refreshToken);
            failurePolicyManager.reset();
            activity.runOnUiThread(() -> callback.onSuccess(userId, tokenResponse));
        } catch (Exception e) {
            String msg =
                    e.getMessage() != null && !e.getMessage().isEmpty()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            activity.runOnUiThread(() -> callback.onError(msg));
        }
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

    private static String randomHexNonce16Bytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public interface AuthCallback {

        void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse);

        void onNotRegistered();

        void onLockedOut(int remainingSeconds);

        void onRetry(int failureCount);

        void onAccountLocked();

        void onKeyInvalidated();

        void onError(String message);
    }
}
