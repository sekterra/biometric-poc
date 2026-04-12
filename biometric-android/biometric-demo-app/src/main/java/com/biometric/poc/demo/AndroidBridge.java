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
// [보존] biometric-bridge 경유 시 사용 — demo-app은 lib 직접 사용으로 미참조
// import com.skens.nsms.biometric.bridge.BiometricBridge;
// import com.skens.nsms.biometric.bridge.BiometricBridgeCallback;

/**
 * AndroidBridge
 * WebView ↔ Native 통신 브릿지 클래스 (WebApp 기반 A1 데모 앱 전용).
 *
 * <p>역할:
 * <ul>
 *   <li>JS → Native: {@code @JavascriptInterface} 메서드로 JS 호출 수신</li>
 *   <li>Native → JS: {@link #callJs(String)}를 통해 {@code evaluateJavascript()}로 결과 전달</li>
 *   <li>{@link BiometricAuthManager}·{@link UserChangeHandler}·{@link AuthApiClient}로 플로우 실행 (bridge 미경유)</li>
 *   <li>Native AlertDialog로 복잡한 인터랙션(키 무효화 안내, 기기 미등록 안내 등) 처리</li>
 * </ul>
 *
 * <p>JS 호출 가능 메서드:
 * <ul>
 *   <li>{@code Android.startFaceLogin()}     — 안면인식 로그인 시작</li>
 *   <li>{@code Android.startIdPwUnlock(userId, password)} — ID/PW로 계정 잠금 해제</li>
 *   <li>{@code Android.openUserChangeDialog()} / {@code Android.startUserChange()} — 담당자 변경</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>{@code @JavascriptInterface} 메서드는 백그라운드 스레드(JavaBridge 스레드)에서 호출됨</li>
 *   <li>UI 작업은 반드시 {@code runOnUiThread()} 또는 {@code mainHandler.post()} 안에서 실행해야 함</li>
 *   <li>pendingNavigation, lockCountDownTimer는 onDestroy()에서 반드시 정리(cancel) 필요</li>
 *   <li>BiometricAuthManager는 Activity 생명주기와 함께 관리됨</li>
 * </ul>
 *
 * <p>변경 이력:
 * demo-app은 biometric-lib 직접 사용. BiometricBridge 경유 코드는 주석 처리로 보존 (모듈은 유지).
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    /** WebView를 호스팅하는 Activity. BiometricPrompt는 FragmentActivity 필요. */
    private final AppCompatActivity activity;

    /** JS 함수 호출 대상 WebView. evaluateJavascript는 메인 스레드에서 실행. */
    private final WebView webView;

    // ── [기존 필드 보존] ─────────────────────────────────────────────────────
    // biometricAuthManager: startRenewal(KEY_INVALIDATED 다이얼로그에서 재사용)
    private final BiometricAuthManager biometricAuthManager;
    private final UserChangeHandler userChangeHandler;
    private final TokenStorage tokenStorage;
    // ────────────────────────────────────────────────────────────────────────

    // [보존] BiometricBridge 경유 진입점 — lib 직접 사용으로 필드 미사용
    // private final BiometricBridge biometricBridge;

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

    /**
     * AndroidBridge를 초기화합니다.
     *
     * <p>KEY_INVALIDATED 다이얼로그에서 startRenewal()을 위해 biometricAuthManager를 유지합니다.
     *
     * @param activity             호스팅 Activity (BiometricPrompt 및 AlertDialog 표시용)
     * @param webView              JS 통신 대상 WebView (evaluateJavascript 호출 대상)
     * @param biometricAuthManager AuthManager — authenticate / startRenewal
     * @param userChangeHandler    담당자 변경 — verifyDeviceCredential / executeChange
     * @param tokenStorage         토큰/등록 저장소 — showDeviceNotFoundDialog 등에서 참조
     */
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
        // [보존] BiometricBridge 초기화 — serverUrl 은 bridge 전용
        // this.biometricBridge = new BiometricBridge(activity, serverUrl);
    }

    /* =========================================================
       JS → Native : @JavascriptInterface 메서드
       (JS에서 Android.xxx() 로 호출)
       ========================================================= */

    /**
     * JS: {@code Android.startFaceLogin()}
     * 안면인식 로그인 버튼 클릭 시 JS에서 호출됩니다.
     *
     * <p>호출 스레드: JavaBridge 백그라운드 스레드 → runOnUiThread()로 UI 작업 위임
     * <p>{@link BiometricAuthManager#authenticate(androidx.fragment.app.FragmentActivity, BiometricAuthManager.AuthCallback)} 로 인증을 시작합니다.
     * <p>진행 중인 카운트다운 타이머가 있으면 취소 후 재시도를 허용합니다.
     */
    @JavascriptInterface
    public void startFaceLogin() {
        Log.d(TAG, "startFaceLogin() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신");
        activity.runOnUiThread(() -> {
            // 진행 중인 카운트다운 취소 후 재시도 허용
            if (lockCountDownTimer != null) {
                lockCountDownTimer.cancel();
                lockCountDownTimer = null;
                isCountingDown = false;
            }

            // ── [보존] BiometricBridge 경유 인증 ───────────────────────────
            // biometricBridge.startLogin(bridgeLoginCallback);
            // ─────────────────────────────────────────────────────────────

            // ── biometric-lib 직접 사용 ────────────────────────────────────
            biometricAuthManager.authenticate(activity, authCallback);
            // ─────────────────────────────────────────────────────────────
        });
    }

    /**
     * JS: {@code Android.startIdPwUnlock(userId, password)}
     * ACCOUNT_LOCKED 상태(CASE 9)에서 ID/PW 입력 후 잠금 해제 요청.
     *
     * <p>호출 스레드: JavaBridge 백그라운드 스레드
     * <p>{@link AuthApiClient#unlockDevice(String)} 및 {@link TokenStorage}로 동일 동작 수행 (bridge 미경유).
     *
     * @param userId   입력된 사용자 ID
     * @param password 입력된 비밀번호 (민감 정보 — 로그 출력 금지)
     */
    @JavascriptInterface
    public void startIdPwUnlock(String userId, String password) {
        Log.d(TAG, "startIdPwUnlock() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > unlockWithIdPw : 잠금 해제 요청 수신");
        // [보존] BiometricBridge.unlockWithIdPw(...)
        // biometricBridge.unlockWithIdPw(userId, password, new BiometricBridgeCallback() { ... });

        String deviceId = tokenStorage.getDeviceId();
        BiometricApplication.getExecutor().submit(() -> {
            try {
                BiometricApplication.getAuthApiClient().unlockDevice(deviceId);
                tokenStorage.saveRegistration(deviceId, userId);
                Log.d(TAG, "unlockWithIdPw 성공: userId=" + userId);
                activity.runOnUiThread(() -> {
                    isAccountLocked = false;
                    callJs("onUnlockSuccess()");
                });
            } catch (Exception e) {
                Log.e(TAG, "unlockWithIdPw 실패", e);
                activity.runOnUiThread(() -> callJs("onUnlockFailed()"));
            }
        });
    }

    /**
     * JS: {@code Android.openUserChangeDialog()}
     * 담당자 변경 다이얼로그를 표시합니다 (CASE 12).
     *
     * <p>호출 스레드: JavaBridge 백그라운드 스레드 → runOnUiThread()로 UI 작업 위임
     * <p>{@link UserChangeHandler} 직접 사용 — {@link #showUserChangeDialog()}.
     */
    @JavascriptInterface
    public void openUserChangeDialog() {
        activity.runOnUiThread(() -> {
            // [보존] BiometricBridge 경유
            // showUserChangeDialogViaBridge();
            showUserChangeDialog();
        });
    }

    /**
     * JS: {@code Android.startUserChange()}
     * 담당자 변경 — {@link UserChangeHandler} 경로와 동일.
     */
    @JavascriptInterface
    public void startUserChange() {
        openUserChangeDialog();
    }

    /* =========================================================
       [보존] BiometricBridge 콜백(bridgeLoginCallback) — biometric-bridge 미의존으로 소스 제외.
       이전 private final BiometricBridgeCallback bridgeLoginCallback = ... 전문은 Git 이력 또는
       동일 시점의 biometric-bridge 연동 커밋에서 확인.
       startFaceLogin()은 아래 authCallback + authenticate() 사용.
       ========================================================= */

    /* =========================================================
       Native → JS : evaluateJavascript 콜백
       ========================================================= */

    /**
     * BiometricAuthManager 인증 콜백 — startFaceLogin()에서 사용.
     * showKeyInvalidatedDialog()의 startRenewal() 재시도에도 동일 인스턴스 사용.
     */
    private final BiometricAuthManager.AuthCallback authCallback =
            new BiometricAuthManager.AuthCallback() {

        @Override
        public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
            Log.d(TAG, "[authCallback] onSuccess: userId=" + userId);
            Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
            callJs("onLoginSuccess()");
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

        @Override
        public void onNotRegistered() {
            Log.d(TAG, "[authCallback] onNotRegistered");
            Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onNotRegistered");
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

        @Override
        public void onLockedOut(int remainingSeconds) {
            Log.d(TAG, "[authCallback] onLockedOut: " + remainingSeconds + "초");
            Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLockedOut: " + remainingSeconds + "초");
            callJs("onLockedOut(" + remainingSeconds + ")");
            startCountdown(remainingSeconds);
        }

        @Override
        public void onRetry(int failureCount) {
            Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onRetry: failureCount=" + failureCount);
            callJs("onRetry(" + failureCount + "," + BiometricLibConstants.MAX_FAILURE_COUNT_FOR_UI + ")");
        }

        @Override
        public void onAccountLocked() {
            Log.w(TAG, "[authCallback] onAccountLocked");
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onAccountLocked");
            isAccountLocked = true;
            callJs("onAccountLocked()");
        }

        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            Log.d("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onSessionRetrying: " + retryCount + "/" + maxRetry);
            callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
        }

        @Override
        public void onError(ErrorCode errorCode) {
            Log.w(TAG, "[authCallback] onError: " + errorCode);
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onError: " + errorCode.name());
            switch (errorCode) {
                case BIOMETRIC_NONE_ENROLLED:
                    callJs("setFaceLoginEnabled(false)");
                    showNotEnrolledDialog();
                    break;
                case BIOMETRIC_HW_UNAVAILABLE:
                    callJs("onError('생체인식 기능을 사용할 수 없습니다. 잠시 후 다시 시도해주세요.')");
                    break;
                case KEY_INVALIDATED:
                    showKeyInvalidatedDialog();
                    break;
                case DEVICE_NOT_FOUND:
                    showDeviceNotFoundDialog();
                    break;
                case TIMESTAMP_OUT_OF_RANGE:
                    showTimestampErrorDialog();
                    break;
                case MISSING_SIGNATURE:
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

    /**
     * KEY_INVALIDATED: 키 갱신 안내 → 확인 시 AAR의 startRenewal() 자동 실행.
     * [주의] startRenewal은 기존 biometricAuthManager 필드를 재사용.
     */
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

    /**
     * [보존] BiometricBridge.startUserChange() 기반 담당자 변경 — bridge 미의존 시 미호출.
     * 동작은 {@link #showUserChangeDialog()} 와 동일 목적(UserChangeHandler).
     */
    @SuppressWarnings("unused")
    private void showUserChangeDialogViaBridge() {
        // [보존] biometricBridge.startUserChange(BiometricBridgeCallback) 전문은 Git 이력 참고
    }

    /**
     * 담당자 변경 다이얼로그 — {@link UserChangeHandler} 직접 사용.
     */
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
     * 잠금 카운트다운 타이머를 시작합니다.
     * 기존 타이머가 있으면 먼저 취소 후 새로 시작합니다.
     * 매초 {@code onCountdownTick(sec)}을 JS로 전달하고,
     * 완료 시 {@code onCountdownFinish()}를 전달합니다.
     *
     * @param seconds 카운트다운 시간(초)
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

    /** 진행 중인 카운트다운 타이머를 취소합니다. Activity onDestroy에서 호출 필요. */
    void cancelCountdown() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
            isCountingDown = false;
        }
    }

    /** 예약된 화면 전환(pendingNavigation)을 취소합니다. Activity onDestroy에서 호출 필요. */
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
     * Native에서 JS 함수를 호출합니다.
     *
     * <p>호출 스레드: 어느 스레드에서도 호출 가능 (내부적으로 runOnUiThread()로 위임)
     * <p>evaluateJavascript()는 메인 스레드에서만 실행 가능하므로 반드시 runOnUiThread 사용.
     *
     * @param jsCall 실행할 JS 표현식 (예: "onLoginSuccess()", "onRetry(2, 5)")
     */
    private void callJs(String jsCall) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function=" + jsCall);
        activity.runOnUiThread(() ->
                webView.evaluateJavascript("javascript:" + jsCall, null));
    }
}
