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
 * KeyRenewalHandler
 * CASE 6(INVALID_SIGNATURE 임계치 초과), CASE 10(KEY_INVALIDATED) 공통 키 재발급 처리 클래스.
 *
 * <p>책임:
 * <ol>
 *   <li>기존 EC 키쌍 삭제 (EcKeyManager 위임)</li>
 *   <li>새 EC 키쌍 생성 (EcKeyManager 위임)</li>
 *   <li>서버 공개키 갱신 (PUT /api/device/renew-key)</li>
 *   <li>새 챌린지 요청 → BiometricPrompt 재실행</li>
 *   <li>재인증 후 토큰 발급 → 원래 AuthCallback으로 결과 전달</li>
 * </ol>
 *
 * <p>주의사항:
 * <ul>
 *   <li>renewAndRetry()는 반드시 UI 스레드에서 호출해야 함 (내부적으로 executor에 위임)</li>
 *   <li>키 재발급 완료 후 BiometricPrompt를 다시 표시하므로 Activity가 살아있어야 함</li>
 *   <li>TODO: [실서비스] 키 재발급 이력을 서버에 기록하는 로직 추가</li>
 * </ul>
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

    /**
     * 키 재발급 후 인증을 재시도합니다 (CASE 6 / CASE 10 공통 진입점).
     *
     * <p>호출 스레드: UI 스레드 (BiometricAuthManager 또는 BiometricAuthManager.startRenewal에서 호출)
     * <p>내부 처리: executor 백그라운드 스레드에서 키 삭제·생성·서버 갱신·챌린지 요청 수행
     *
     * @param activity         현재 활성 FragmentActivity (키 재발급 후 BiometricPrompt 재표시용)
     * @param deviceId         기기 ID
     * @param userId           사용자 ID
     * @param originalCallback 키 재발급 후 재인증 결과를 받을 원래 콜백 — UI 스레드에서 호출됨
     *
     * <p>예외 처리:
     * <ul>
     *   <li>DeviceNotFoundException → onError(DEVICE_NOT_FOUND)</li>
     *   <li>AccountLockedException → onAccountLocked()</li>
     *   <li>그 외 → onError(NETWORK_ERROR)</li>
     * </ul>
     */
    public void renewAndRetry(
            FragmentActivity activity,
            String deviceId,
            String userId,
            BiometricAuthManager.AuthCallback originalCallback) {

        Log.w("BIOMETRIC_LIB", "[KEY] KeyRenewalHandler > renewAndRetry : 키 재발급 시작");
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
                Log.w("BIOMETRIC_LIB", "[KEY] KeyRenewalHandler > deleteKeyPair : 기존 키 삭제 완료");

                // ② 새 EC 키쌍 생성
                ecKeyManager.generateKeyPair();
                Log.d(TAG, "새 EC 키쌍 생성 완료");

                // ③ 새 공개키 추출
                String newPublicKey = ecKeyManager.getPublicKeyBase64();

                // ④ 서버 공개키 갱신
                authApiClient.renewKey(deviceId, newPublicKey);
                Log.d(TAG, "서버 공개키 갱신 완료");
                Log.w("BIOMETRIC_LIB", "[KEY] KeyRenewalHandler > renewKey : 서버 공개키 갱신 완료");

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

    /**
     * 키 재발급 완료 후 BiometricPrompt를 다시 표시합니다.
     *
     * <p>호출 스레드: UI 스레드 (activity.runOnUiThread에서 호출됨)
     * <p>인증 성공 시 executor 백그라운드 스레드에서 서명·토큰 요청을 수행합니다.
     */
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

    /**
     * 16바이트 랜덤 nonce를 16진수 문자열(32자)로 생성합니다.
     * 재전송 공격 방지를 위해 챌린지 요청마다 새로 생성합니다.
     *
     * @return 32자 16진수 소문자 nonce 문자열
     */
    private String generateNonce() {
        byte[] bytes = new byte[BiometricLibConstants.NONCE_BYTE_SIZE];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * ECDSA 서명 대상 페이로드를 생성합니다.
     * 형식: "serverChallenge:clientNonce:deviceId:timestamp" (UTF-8 인코딩)
     *
     * @param serverChallenge 서버 챌린지 값
     * @param clientNonce     클라이언트 nonce
     * @param deviceId        기기 ID
     * @param timestamp       타임스탬프 (epoch ms)
     * @return UTF-8 인코딩된 서명 대상 바이트 배열
     */
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
