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
 * UserChangeHandler
 * 사용자(담당자) 변경 프로세스를 담당하는 클래스 (CASE 12).
 *
 * <p>책임:
 * <ol>
 *   <li>1단계: BiometricPrompt(DEVICE_CREDENTIAL)로 기기 자격증명(PIN/패턴/비밀번호) 인증</li>
 *   <li>2단계: 서버 기기 등록 삭제 → 로컬 EC 키 삭제 → 로컬 등록 정보 삭제</li>
 *   <li>결과를 UserChangeCallback으로 전달 (등록 화면 이동은 호출자가 처리)</li>
 * </ol>
 *
 * <p>설계 의도:
 * <ul>
 *   <li>2단계를 분리한 이유: verifyDeviceCredential() 성공 콜백(onVerified)에서
 *       호출자가 확인 다이얼로그를 한 번 더 표시할 수 있도록 유연성 제공</li>
 *   <li>서버 삭제 실패(404)여도 로컬 삭제 후 onChangeCompleted()를 호출함 —
 *       이미 서버에 기기 정보가 없어도 사용자 변경 흐름은 정상 완료 처리</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>모든 UserChangeCallback 메서드는 UI 스레드에서 호출됨</li>
 *   <li>TODO: [실서비스] 관리자 인증 토큰 검증 추가</li>
 * </ul>
 */
public class UserChangeHandler {

    private static final String TAG = "UserChangeHandler";

    // BiometricPrompt 표시 문자열 — biometric-lib은 R.string 접근 불가이므로 상수로 관리
    // TODO: [실서비스] 앱 레이어에서 PromptInfo를 주입하는 구조로 개선 검토
    private static final String PROMPT_TITLE_USER_CHANGE    = "사용자 변경 확인";
    private static final String PROMPT_SUBTITLE_USER_CHANGE = "기기 잠금을 해제하여 본인임을 확인합니다.";

    /**
     * 사용자 변경 결과 콜백 인터페이스.
     *
     * <p>모든 메서드는 UI 스레드에서 호출됩니다.
     */
    public interface UserChangeCallback {
        /**
         * 1단계 완료: 기기 자격증명(PIN/패턴/비밀번호) 인증 성공.
         * 이 콜백 수신 후 executeChange()를 호출하여 2단계(서버+로컬 삭제)를 진행합니다.
         */
        void onVerified();

        /**
         * 2단계 완료: 서버 기기 삭제 + 로컬 키/등록 정보 삭제 완료.
         * 이 콜백 수신 후 등록 화면으로 이동합니다.
         */
        void onChangeCompleted();

        /**
         * 처리 실패.
         *
         * @param errorCode 실패 원인 코드 (NETWORK_ERROR, UNKNOWN_ERROR 등)
         */
        void onChangeFailed(ErrorCode errorCode);

        /** 사용자가 BiometricPrompt를 취소함 — 별도 처리 없이 이전 화면 유지. */
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

    /**
     * 1단계: BiometricPrompt(DEVICE_CREDENTIAL)로 기기 자격증명(PIN/패턴/비밀번호)을 인증합니다.
     *
     * <p>호출 스레드: UI 스레드 (BiometricPrompt는 UI 스레드에서 표시되어야 함)
     *
     * @param activity 현재 활성 FragmentActivity (BiometricPrompt 표시용)
     * @param callback 인증 결과 수신 콜백 — UI 스레드에서 호출됨
     *
     * <p>BiometricPrompt 콜백 처리:
     * <ul>
     *   <li>onAuthenticationSucceeded → onVerified()</li>
     *   <li>onAuthenticationFailed    → 재시도 가능, 콜백 호출 안 함</li>
     *   <li>onAuthenticationError (취소) → onCanceled()</li>
     *   <li>onAuthenticationError (그 외) → onChangeFailed(UNKNOWN_ERROR)</li>
     * </ul>
     */
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

    /**
     * 2단계: 서버 기기 삭제 → 로컬 EC 키 삭제 → 로컬 등록 정보 삭제.
     *
     * <p>호출 스레드: UI 스레드 (executor 백그라운드로 위임)
     * <p>결과 전달: activity.runOnUiThread()를 통해 UI 스레드에서 콜백 호출
     *
     * @param activity 현재 활성 FragmentActivity (콜백의 runOnUiThread에 사용)
     * @param callback 처리 결과 수신 콜백 — UI 스레드에서 호출됨
     *
     * <p>예외 처리:
     * <ul>
     *   <li>DeviceNotFoundException (서버 404) → 로컬만 삭제 후 onChangeCompleted() (서버 없어도 정상 완료)</li>
     *   <li>그 외 네트워크 오류 → onChangeFailed(NETWORK_ERROR)</li>
     * </ul>
     */
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
