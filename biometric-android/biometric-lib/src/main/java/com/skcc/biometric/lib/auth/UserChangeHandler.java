package com.skcc.biometric.lib.auth;

import android.content.Context;
import android.util.Log;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;

import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.network.DeviceNotFoundException;
import com.skcc.biometric.lib.storage.TokenStorage;

import java.util.concurrent.ExecutorService;

/**
 * 사용자 변경 프로세스 담당.
 * CASE12: 패턴/PIN 인증 → 서버 삭제 → 로컬 삭제 → 등록 화면 유도.
 *
 * <p>TODO: [실서비스] 관리자 인증 토큰 검증 추가
 */
public class UserChangeHandler {

    private static final String TAG = "UserChangeHandler";

    // BiometricPrompt 표시 문자열 — biometric-lib은 R.string 접근 불가이므로 상수로 관리
    // TODO: [실서비스] 앱 레이어에서 PromptInfo를 주입하는 구조로 개선 검토
    private static final String PROMPT_TITLE_USER_CHANGE    = "사용자 변경 확인";
    private static final String PROMPT_SUBTITLE_USER_CHANGE = "기기 잠금을 해제하여 본인임을 확인합니다.";

    public interface UserChangeCallback {
        /** 기기 자격증명 인증 성공 */
        void onVerified();

        /** 서버 삭제 + 로컬 삭제 완료 → 등록 화면 이동 */
        void onChangeCompleted();

        /** 실패 */
        void onChangeFailed(ErrorCode errorCode);

        /** 사용자 취소 */
        void onCanceled();
    }

    private final Context context;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;
    private final AuthApiClient authApiClient;
    private final ExecutorService executor;

    public UserChangeHandler(
            Context context,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage,
            AuthApiClient authApiClient,
            ExecutorService executor) {
        this.context = context;
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
        this.authApiClient = authApiClient;
        this.executor = executor;
    }

    /** 1단계: BiometricPrompt로 기기 자격증명(PIN/패턴/비밀번호) 인증. */
    public void verifyDeviceCredential(
            FragmentActivity activity,
            UserChangeCallback callback) {

        BiometricPrompt.PromptInfo promptInfo =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(PROMPT_TITLE_USER_CHANGE)
                        .setSubtitle(PROMPT_SUBTITLE_USER_CHANGE)
                        // DEVICE_CREDENTIAL 단독: PIN/패턴/비밀번호로만 인증
                        // TODO: [실서비스] BIOMETRIC_STRONG 추가 검토
                        .setAllowedAuthenticators(
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();

        BiometricPrompt prompt = new BiometricPrompt(
                activity,
                executor,
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        Log.d(TAG, "기기 자격증명 인증 성공");
                        activity.runOnUiThread(callback::onVerified);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        Log.w(TAG, "기기 자격증명 인증 실패 (재시도 가능)");
                        // 실패해도 재시도 가능하므로 콜백 호출 안 함
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, CharSequence errString) {
                        if (errorCode == BiometricPrompt.ERROR_CANCELED
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            Log.d(TAG, "사용자 취소");
                            activity.runOnUiThread(callback::onCanceled);
                            return;
                        }
                        Log.e(TAG, "인증 오류: " + errString);
                        activity.runOnUiThread(
                                () -> callback.onChangeFailed(ErrorCode.UNKNOWN_ERROR));
                    }
                });

        prompt.authenticate(promptInfo);
    }

    /** 2단계: 서버 삭제 → 로컬 키 삭제 → 로컬 등록 정보 삭제. */
    public void executeChange(
            FragmentActivity activity,
            UserChangeCallback callback) {

        String deviceId = tokenStorage.getDeviceId();
        String userId   = tokenStorage.getUserId();

        // TODO: [실서비스] device_id 마스킹 처리 필요
        Log.w(TAG, "사용자 변경 실행 deviceId=" + deviceId + " userId=" + userId);

        executor.submit(() -> {
            try {
                // ① 서버 기기 등록 삭제 (먼저 — 순서 중요)
                authApiClient.unregisterDevice(deviceId, userId);
                Log.d(TAG, "서버 기기 등록 삭제 완료");

                // ② 로컬 키 삭제
                ecKeyManager.deleteKeyPair();
                Log.d(TAG, "로컬 키 삭제 완료");

                // ③ 로컬 등록 정보 삭제
                tokenStorage.clearAll();
                Log.d(TAG, "로컬 등록 정보 삭제 완료");

                activity.runOnUiThread(callback::onChangeCompleted);

            } catch (DeviceNotFoundException e) {
                Log.w(TAG, "서버 기기 정보 없음 → 로컬만 삭제 후 진행");
                // 서버에 없어도 로컬 삭제 후 정상 진행
                try {
                    ecKeyManager.deleteKeyPair();
                } catch (Exception ignored) {
                }
                tokenStorage.clearAll();
                activity.runOnUiThread(callback::onChangeCompleted);

            } catch (Exception e) {
                Log.e(TAG, "사용자 변경 실패", e);
                activity.runOnUiThread(
                        () -> callback.onChangeFailed(ErrorCode.NETWORK_ERROR));
            }
        });
    }
}
