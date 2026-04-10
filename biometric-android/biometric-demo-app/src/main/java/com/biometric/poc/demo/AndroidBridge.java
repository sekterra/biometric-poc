package com.biometric.poc.demo;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.demo.util.BiometricSettingsNavigator;
import com.skcc.biometric.lib.BiometricLibConstants;
import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.auth.BiometricAuthManager;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.storage.TokenStorage;

/**
 * WebView ↔ Native 통신 브릿지.
 *
 * <p>JS에서 {@code Android.xxx()} 형태로 호출. 콜백 결과는
 * {@link #callJs(String)} 를 통해 {@code webView.evaluateJavascript()} 로 전달.
 *
 * <p>주의: {@link JavascriptInterface} 메서드는 백그라운드 스레드에서 호출되므로
 * UI 작업은 반드시 {@code runOnUiThread()} 안에서 실행해야 한다.
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    /** WebView를 호스팅하는 Activity. BiometricPrompt는 FragmentActivity 필요. */
    private final AppCompatActivity activity;

    /** JS 함수 호출 대상 WebView. evaluateJavascript는 메인 스레드에서 실행. */
    private final WebView webView;

    private final BiometricAuthManager biometricAuthManager;
    private final UserChangeHandler userChangeHandler;
    private final TokenStorage tokenStorage;

    /** 메인 스레드 Handler — pendingNavigation 및 타이머 콜백용. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Activity 종료 후 화면 전환 방지용 — onDestroy 시 취소. */
    @Nullable
    Runnable pendingNavigation = null;

    /** 잠금 카운트다운 타이머 — onDestroy 시 cancel() 필수. */
    @Nullable
    CountDownTimer lockCountDownTimer = null;

    /** CountDownTimer 실행 중 여부 */
    boolean isCountingDown = false;

    /** ACCOUNT_LOCKED 수신 후 상태. onResume 시 버튼 재활성화 방지용. */
    boolean isAccountLocked = false;

    /** 안면인식 미등록 다이얼로그 중복 표시 방지 플래그. */
    private boolean isNotEnrolledDialogShowing = false;

    public AndroidBridge(
            AppCompatActivity activity,
            WebView webView,
            BiometricAuthManager biometricAuthManager,
            UserChangeHandler userChangeHandler,
            TokenStorage tokenStorage) {
        this.activity = activity;
        this.webView = webView;
        this.biometricAuthManager = biometricAuthManager;
        this.userChangeHandler = userChangeHandler;
        this.tokenStorage = tokenStorage;
    }

    /* =========================================================
       JS → Native : @JavascriptInterface 메서드
       (JS에서 Android.xxx() 로 호출)
       ========================================================= */

    /**
     * JS: {@code Android.startFaceLogin()}
     * 안면인식 로그인 버튼 클릭 시 호출. 백그라운드 스레드에서 실행되므로 UI 작업은 runOnUiThread 사용.
     */
    @JavascriptInterface
    public void startFaceLogin() {
        Log.d(TAG, "startFaceLogin() 호출");
        activity.runOnUiThread(() -> {
            // 진행 중인 카운트다운 취소 후 재시도 허용
            if (lockCountDownTimer != null) {
                lockCountDownTimer.cancel();
                lockCountDownTimer = null;
                isCountingDown = false;
            }
            biometricAuthManager.authenticate(activity, authCallback);
        });
    }

    /**
     * JS: {@code Android.startIdPwUnlock(userId, password)}
     * ACCOUNT_LOCKED 상태에서 ID/PW 입력 후 잠금 해제 요청.
     *
     * @param userId   입력된 사용자 ID
     * @param password 입력된 비밀번호 (PoC: 서버 검증 없이 unlock 호출)
     */
    @JavascriptInterface
    public void startIdPwUnlock(String userId, String password) {
        Log.d(TAG, "startIdPwUnlock() 호출");
        String deviceId = tokenStorage.getDeviceId();

        BiometricApplication.getExecutor().submit(() -> {
            try {
                // TODO: [실서비스] MIS 인증 서버에서 ID/PW 검증 후 unlock 신호 전송
                BiometricApplication.getAuthApiClient().unlockDevice(deviceId);
                Log.d(TAG, "unlock 성공");
                tokenStorage.saveRegistration(deviceId, tokenStorage.getUserId());
                isAccountLocked = false;
                callJs("onUnlockSuccess()");
            } catch (Exception e) {
                Log.e(TAG, "unlock 실패", e);
                callJs("onUnlockFailed()");
            }
        });
    }

    /**
     * JS: {@code Android.openUserChangeDialog()}
     * 담당자 변경 다이얼로그 표시 (복잡한 플로우 — Native AlertDialog 유지).
     */
    @JavascriptInterface
    public void openUserChangeDialog() {
        activity.runOnUiThread(() -> showUserChangeDialog());
    }

    /* =========================================================
       Native → JS : evaluateJavascript 콜백
       ========================================================= */

    /**
     * BiometricAuthManager 인증 콜백.
     * 모든 콜백은 biometricAuthManager 내부에서 runOnUiThread 처리 후 전달됨.
     */
    private final BiometricAuthManager.AuthCallback authCallback =
            new BiometricAuthManager.AuthCallback() {

        /**
         * 인증 + 토큰 발급 성공.
         *
         * @param userId        인증된 사용자 ID
         * @param tokenResponse B2 서버에서 발급된 토큰 (accessToken, refreshToken 등)
         */
        @Override
        public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
            Log.d(TAG, "onSuccess: userId=" + userId);
            callJs("onLoginSuccess()");

            // MainAfterLoginActivity로 이동 — 토큰은 Intent Extra로 전달
            pendingNavigation = () -> {
                if (!activity.isFinishing()) {
                    Intent intent = new Intent(activity, MainAfterLoginActivity.class);
                    intent.putExtra("user_id", userId);
                    intent.putExtra("access_token", tokenResponse.accessToken);
                    intent.putExtra("expires_in", tokenResponse.expiresIn);
                    activity.startActivity(intent);
                    activity.finish();
                }
            };
            mainHandler.postDelayed(pendingNavigation, BiometricLibConstants.UI_REDIRECT_DELAY_MS);
        }

        /**
         * 기기 미등록 상태 — RegisterActivity로 이동.
         * TokenStorage에 등록 정보가 없거나 서버에서 기기를 찾지 못한 경우.
         */
        @Override
        public void onNotRegistered() {
            Log.d(TAG, "onNotRegistered");
            isAccountLocked = false;
            callJs("onNotRegistered()");
            Toast.makeText(activity, "서버에 등록 정보가 없습니다. 다시 등록해주세요.",
                    Toast.LENGTH_LONG).show();
            pendingNavigation = () -> {
                if (!activity.isFinishing()) {
                    activity.startActivity(new Intent(activity, RegisterActivity.class));
                    activity.finish();
                }
            };
            mainHandler.postDelayed(pendingNavigation, BiometricLibConstants.UI_REDIRECT_DELAY_MS);
        }

        /**
         * 인증 실패 횟수 초과 → 잠금 상태.
         *
         * @param remainingSeconds 잠금 해제까지 남은 초
         */
        @Override
        public void onLockedOut(int remainingSeconds) {
            Log.d(TAG, "onLockedOut: " + remainingSeconds + "초");
            callJs("onLockedOut(" + remainingSeconds + ")");
            startCountdown(remainingSeconds);
        }

        /**
         * 안면인식 실패 — 아직 잠금 전.
         *
         * @param failureCount 누적 실패 횟수
         */
        @Override
        public void onRetry(int failureCount) {
            callJs("onRetry(" + failureCount + "," + BiometricLibConstants.MAX_FAILURE_COUNT_FOR_UI + ")");
        }

        /** 관리자에 의한 계정 잠금 — ID/PW 영역 표시. */
        @Override
        public void onAccountLocked() {
            Log.w(TAG, "onAccountLocked");
            isAccountLocked = true;
            callJs("onAccountLocked()");
        }

        /**
         * SESSION_EXPIRED 자동 재시도 중 상태 알림.
         *
         * @param retryCount 현재 재시도 횟수 (1부터)
         * @param maxRetry   최대 재시도 횟수
         */
        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
        }

        /**
         * 오류 발생 — ErrorCode를 한국어 메시지로 변환 후 JS로 전달.
         * 복잡한 인터랙션(다이얼로그)은 Native에서 처리.
         *
         * @param errorCode AAR이 전달하는 오류 코드
         */
        @Override
        public void onError(ErrorCode errorCode) {
            Log.w(TAG, "onError: " + errorCode);
            switch (errorCode) {

                case BIOMETRIC_NONE_ENROLLED:
                    // 기기에 얼굴인식 미등록 → 설정 유도 다이얼로그 (Native)
                    callJs("setFaceLoginEnabled(false)");
                    showNotEnrolledDialog();
                    break;

                case BIOMETRIC_HW_UNAVAILABLE:
                    callJs("onError('생체인식 기능을 사용할 수 없습니다. 잠시 후 다시 시도해주세요.')");
                    break;

                case KEY_INVALIDATED:
                    // 얼굴인식 재등록으로 Keystore 키 무효화 → 키 갱신 다이얼로그 (Native)
                    showKeyInvalidatedDialog();
                    break;

                case DEVICE_NOT_FOUND:
                    // 서버에 기기 등록 없음 → 기기 등록 안내 다이얼로그 (Native)
                    showDeviceNotFoundDialog();
                    break;

                case TIMESTAMP_OUT_OF_RANGE:
                    // 기기 시간 불일치 → 날짜 설정 안내 다이얼로그 (Native)
                    showTimestampErrorDialog();
                    break;

                case MISSING_SIGNATURE:
                    // 앱 내부 오류 → 재시작 안내 다이얼로그 (Native)
                    showMissingSignatureDialog();
                    break;

                case SESSION_EXPIRED:
                    callJs("hideStatusMsg()");
                    callJs("onError('네트워크 불안정 또는 인증 시간 초과입니다. 다시 시도해주세요.')");
                    break;

                case NONCE_REPLAY:
                    callJs("onError('보안 확인이 만료되었거나 중복 요청입니다. 다시 시도해주세요.')");
                    break;

                case INVALID_SIGNATURE:
                    callJs("onError('기기 인증 정보가 맞지 않습니다. 로그인을 다시 시도해주세요.')");
                    break;

                case KEY_NOT_FOUND:
                    callJs("onError('보안키를 찾을 수 없습니다. 잠시 후 다시 시도해주세요.')");
                    break;

                case NETWORK_ERROR:
                    callJs("onError('네트워크 연결을 확인 후 다시 시도해주세요.')");
                    break;

                default:
                    callJs("onError('알 수 없는 오류가 발생했습니다. 앱을 재시작하거나 헬프데스크로 문의해주세요.')");
                    break;
            }
        }
    };

    /* =========================================================
       Native AlertDialog — 복잡한 인터랙션은 Native에서 처리
       ========================================================= */

    /** KEY_INVALIDATED: 키 갱신 안내 → 확인 시 AAR의 startRenewal() 자동 실행. */
    private void showKeyInvalidatedDialog() {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("보안키 재설정 필요")
                        .setMessage("얼굴 등 생체 정보가 변경되어 보안키를 다시 설정해야 합니다.\n확인 버튼을 누르면 키를 다시 설정합니다.")
                        .setPositiveButton("확인", (d, w) -> {
                            callJs("showProgress(true)");
                            callJs("setFaceLoginEnabled(false)");
                            // challenge 재요청 없이 키 재발급 → BiometricPrompt 재실행
                            biometricAuthManager.startRenewal(activity, authCallback);
                        })
                        .setNegativeButton("취소", null)
                        .setCancelable(false)
                        .show());
    }

    /** DEVICE_NOT_FOUND: 기기 미등록 안내 → 등록 화면 이동. */
    private void showDeviceNotFoundDialog() {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("기기 미등록")
                        .setMessage("등록된 기기를 찾을 수 없습니다.\n확인 버튼을 클릭하면 기기 등록 과정이 진행됩니다.\n문제가 계속되면 헬프데스크로 문의하세요.")
                        .setPositiveButton("확인", (d, w) -> {
                            tokenStorage.clearRegistration();
                            String deviceId = tokenStorage.getDeviceId();
                            if (deviceId == null || deviceId.isEmpty()) {
                                deviceId = Settings.Secure.getString(
                                        activity.getContentResolver(),
                                        Settings.Secure.ANDROID_ID);
                            }
                            Intent intent = new Intent(activity, RegisterActivity.class);
                            intent.putExtra("device_id", deviceId);
                            intent.putExtra("button_label", "사용자ID 및 기기 등록");
                            activity.startActivity(intent);
                            activity.finish();
                        })
                        .setNegativeButton("헬프데스크 문의", (d, w) ->
                                Toast.makeText(activity,
                                        "문제가 지속되면 헬프데스크로 문의해주세요.",
                                        Toast.LENGTH_LONG).show())
                        .setCancelable(false)
                        .show());
    }

    /** TIMESTAMP_OUT_OF_RANGE: 기기 시간 설정 유도. */
    private void showTimestampErrorDialog() {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("기기 시간 확인 필요")
                        .setMessage("보안을 위해 기기 시간이 맞는지 확인합니다.\n설정 → 날짜 및 시간에서 자동 설정을 켜 주시고 잠시 후 다시 로그인해주세요.")
                        .setPositiveButton("시간 설정으로 이동", (d, w) -> {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
                            } catch (ActivityNotFoundException e) {
                                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        })
                        .setNegativeButton("닫기", null)
                        .setCancelable(false)
                        .show());
    }

    /** MISSING_SIGNATURE: 앱 재시작 유도. */
    private void showMissingSignatureDialog() {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("로그인 오류")
                        .setMessage("로그인 처리 중 문제가 발생했습니다.\n앱 종료 후 다시 로그인해 주시고, 반복되면 앱을 재설치하거나 고객센터로 문의해주세요.")
                        .setPositiveButton("앱 재시작", (d, w) -> {
                            Intent intent = activity.getPackageManager()
                                    .getLaunchIntentForPackage(activity.getPackageName());
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                activity.startActivity(intent);
                            }
                            activity.finishAffinity();
                        })
                        .setNegativeButton("닫기", null)
                        .setCancelable(false)
                        .show());
    }

    /** BIOMETRIC_NONE_ENROLLED: 안면인식 미등록 → 설정 화면 유도. */
    void showNotEnrolledDialog() {
        if (isNotEnrolledDialogShowing || activity.isFinishing()) return;
        isNotEnrolledDialogShowing = true;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("안면인식 미등록")
                        .setMessage("안면인식이 등록되지 않았습니다.\n설정에서 안면인식을 등록 후 이용해주세요.")
                        .setPositiveButton("설정으로 이동", (d, w) -> {
                            isNotEnrolledDialogShowing = false;
                            BiometricSettingsNavigator.navigate(activity);
                        })
                        .setNegativeButton("나중에", (d, w) -> {
                            isNotEnrolledDialogShowing = false;
                            callJs("showStatus('안면인식 등록 후 로그인이 가능합니다.', '#757575')");
                        })
                        .setCancelable(false)
                        .setOnDismissListener(d -> isNotEnrolledDialogShowing = false)
                        .show());
    }

    /** 담당자 변경 다이얼로그 — UserChangeHandler 플로우 실행. */
    private void showUserChangeDialog() {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("담당자 변경")
                .setMessage("이 기기에 저장된 로그인·인증 정보가 삭제됩니다.\n계속하시겠습니까?")
                .setPositiveButton("확인", (dialog, which) ->
                        userChangeHandler.verifyDeviceCredential(
                                activity,
                                new UserChangeHandler.UserChangeCallback() {

                                    @Override
                                    public void onVerified() {
                                        callJs("showProgress(true)");
                                        userChangeHandler.executeChange(activity, this);
                                    }

                                    @Override
                                    public void onChangeCompleted() {
                                        callJs("showProgress(false)");
                                        Toast.makeText(activity,
                                                "삭제가 완료되었습니다. 신규 등록 화면으로 이동합니다.",
                                                Toast.LENGTH_SHORT).show();
                                        pendingNavigation = () -> {
                                            if (!activity.isFinishing()) {
                                                Intent intent = new Intent(activity, RegisterActivity.class);
                                                intent.putExtra("button_label", "신규 사용자 등록");
                                                activity.startActivity(intent);
                                                activity.finish();
                                            }
                                        };
                                        mainHandler.postDelayed(pendingNavigation,
                                                BiometricLibConstants.UI_REDIRECT_DELAY_MS);
                                    }

                                    @Override
                                    public void onChangeFailed(ErrorCode errorCode) {
                                        callJs("showProgress(false)");
                                        Toast.makeText(activity,
                                                "문제가 지속되면 헬프데스크로 문의해주세요.",
                                                Toast.LENGTH_LONG).show();
                                    }

                                    @Override
                                    public void onCanceled() {
                                        // 취소 — 별도 처리 없음
                                    }
                                }))
                .setNegativeButton("취소", null)
                .show();
    }

    /* =========================================================
       카운트다운 타이머 (잠금 시간 표시)
       ========================================================= */

    /**
     * 잠금 카운트다운 시작 — 매초 JS {@code onCountdownTick()} 호출,
     * 완료 시 {@code onCountdownFinish()} 호출.
     *
     * @param seconds 잠금 남은 시간(초)
     */
    void startCountdown(int seconds) {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            isCountingDown = false;
        }
        isCountingDown = true;
        lockCountDownTimer = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) Math.ceil(millisUntilFinished / 1000.0);
                callJs("onCountdownTick(" + sec + ")");
            }

            @Override
            public void onFinish() {
                isCountingDown = false;
                lockCountDownTimer = null;
                callJs("onCountdownFinish()");
            }
        };
        lockCountDownTimer.start();
    }

    /** 카운트다운 타이머 해제 — Activity onDestroy 에서 호출. */
    void cancelCountdown() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
            isCountingDown = false;
        }
    }

    /** pendingNavigation 취소 — Activity onDestroy 에서 호출. */
    void cancelPendingNavigation() {
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
            pendingNavigation = null;
        }
    }

    /* =========================================================
       유틸리티
       ========================================================= */

    /**
     * JS 함수를 메인 스레드에서 실행.
     * evaluateJavascript는 반드시 메인 스레드에서 호출해야 한다.
     *
     * @param jsCall 예: "onLoginSuccess()" 또는 "onError('메시지')"
     */
    private void callJs(String jsCall) {
        activity.runOnUiThread(() ->
                webView.evaluateJavascript("javascript:" + jsCall, null));
    }
}
