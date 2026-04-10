package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.skcc.biometric.lib.BiometricLibConstants;
import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.network.AuthApiClient.DeviceStatusResponse;
import com.skcc.biometric.lib.network.DeviceNotFoundException;
import com.skcc.biometric.lib.storage.TokenStorage;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 앱 진입점 Activity — WebView 기반 HTML/JS UI.
 *
 * <p>레이아웃: {@code assets/main.html}
 * <p>흐름: 기기 상태 서버 조회 → 결과에 따라 JS 콜백 호출
 * <ul>
 *   <li>ACTIVE → {@code onStatusActive(userId)} → 2초 후 LoginActivity 이동</li>
 *   <li>LOCKED → {@code onStatusLocked(userId)} → ID/PW 입력 후 잠금 해제</li>
 *   <li>KEY_INVALIDATED → Native AlertDialog → 키 삭제 후 RegisterActivity 이동</li>
 * </ul>
 * <p>UserChangeHandler 콜백·AlertDialog는 Native 유지.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private WebView webView;
    private TokenStorage tokenStorage;
    private UserChangeHandler userChangeHandler;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** onDestroy 시 취소할 지연 화면 전환 Runnable */
    @Nullable
    private Runnable pendingNavigation = null;

    /** LOCKED 상태에서 unlock 요청에 필요한 기기·사용자 ID */
    private String pendingDeviceId;
    private String pendingUserId;

    /** WebView 로드 완료 여부 — onPageFinished 이전 evaluateJavascript 방지 */
    private boolean pageLoaded = false;

    /** pageLoaded 전 수신된 상태 콜백 저장 (페이지 로드 전 API 응답 도착 대비) */
    @Nullable
    private Runnable deferredStatusCall = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── TokenStorage 초기화 ──────────────────────────────────
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // 이미 등록된 경우 로그인 화면으로 바로 이동
        if (tokenStorage.isRegistered()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // ── UserChangeHandler 초기화 ─────────────────────────────
        userChangeHandler = new UserChangeHandler(
                this,
                BiometricApplication.getEcKeyManager(),
                tokenStorage,
                BiometricApplication.getAuthApiClient(),
                BiometricApplication.getExecutor());

        // ── WebView 설정 ─────────────────────────────────────────
        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new MainBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // 페이지 로드 전 도착한 상태 콜백이 있으면 지금 실행
                if (deferredStatusCall != null) {
                    runOnUiThread(deferredStatusCall);
                    deferredStatusCall = null;
                }
            }
        });

        webView.loadUrl("file:///android_asset/main.html");

        // ── 기기 상태 서버 조회 시작 ─────────────────────────────
        checkDeviceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // isRegistered()가 true인 경우 onCreate에서 finish()를 호출하고 webView를 초기화하지 않으므로
        // onResume()이 호출되면 NPE 발생 — null 가드 추가
        if (webView == null) return;
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView == null) return;
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
            pendingNavigation = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    /* =========================================================
       기기 상태 조회 — 서버 API 호출
       ========================================================= */

    private void checkDeviceStatus() {
        callJs("onStatusLoading()");

        BiometricApplication.getExecutor().submit(() -> {
            String deviceId = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID);
            try {
                DeviceStatusResponse response =
                        BiometricApplication.getAuthApiClient().getUserId(deviceId);
                runOnUiThread(() -> handleDeviceStatus(response, deviceId));
            } catch (DeviceNotFoundException e) {
                // 미등록 기기 → RegisterActivity로 이동
                runOnUiThread(() -> navigateToRegister(deviceId));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> callJs("onStatusError('" + escapeJs(msg) + "')"));
            }
        });
    }

    private void handleDeviceStatus(DeviceStatusResponse response, String deviceId) {
        String status = response.status != null ? response.status : "";
        switch (status) {
            case "ACTIVE":
                tokenStorage.saveRegistration(deviceId, response.userId);
                String safeUserId = escapeJs(response.userId != null ? response.userId : "");
                callJs("onStatusActive('" + safeUserId + "')");

                // 2초 후 LoginActivity 자동 이동
                pendingNavigation = () -> {
                    if (!isFinishing()) {
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                    }
                };
                mainHandler.postDelayed(pendingNavigation, BiometricLibConstants.UI_REDIRECT_DELAY_LONG_MS);
                break;

            case "LOCKED":
                pendingDeviceId = deviceId;
                pendingUserId   = response.userId;
                String safeLockedUserId = escapeJs(response.userId != null ? response.userId : "");
                callJs("onStatusLocked('" + safeLockedUserId + "')");
                break;

            case "KEY_INVALIDATED":
                // 완전히 Native에서 처리 — AlertDialog 표시
                callJs("onStatusKeyInvalidated()");
                showKeyInvalidatedDialog(response.userId, deviceId);
                break;

            default:
                callJs("onStatusError('" + escapeJs("알 수 없는 상태: " + status) + "')");
                break;
        }
    }

    private void navigateToRegister(String deviceId) {
        Intent intent = new Intent(this, RegisterActivity.class);
        intent.putExtra("device_id", deviceId);
        intent.putExtra("button_label", "사용자ID 및 기기 등록");
        startActivity(intent);
        finish();
    }

    /* =========================================================
       JavascriptInterface 브릿지
       ========================================================= */

    private class MainBridge {

        /**
         * JS: {@code Android.submitLockedLogin(password)}
         * LOCKED 상태에서 비밀번호 입력 후 로그인 버튼 클릭 시 호출.
         *
         * @param password JS에서 전달받은 비밀번호 (PoC: 실제 검증 없이 unlock 호출)
         */
        @JavascriptInterface
        public void submitLockedLogin(String password) {
            String deviceId = pendingDeviceId;
            String userId   = pendingUserId;

            if (deviceId == null || userId == null) {
                runOnUiThread(() -> callJs("onUnlockFailed('내부 오류: 단말 정보가 없습니다.')"));
                return;
            }

            BiometricApplication.getExecutor().submit(() -> {
                try {
                    // TODO: [실서비스] MIS 인증 서버에서 ID/PW 검증 후 unlock 신호 전송
                    BiometricApplication.getAuthApiClient().unlockDevice(deviceId);
                    tokenStorage.saveRegistration(deviceId, userId);

                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "잠금이 해제되었습니다.", Toast.LENGTH_SHORT).show();
                        callJs("onUnlockSuccess()");

                        // 2초 후 LoginActivity 이동
                        pendingNavigation = () -> {
                            if (!isFinishing()) {
                                startActivity(new Intent(MainActivity.this, LoginActivity.class));
                                finish();
                            }
                        };
                        mainHandler.postDelayed(pendingNavigation, BiometricLibConstants.UI_REDIRECT_DELAY_LONG_MS);
                    });
                } catch (DeviceNotFoundException e) {
                    runOnUiThread(() -> callJs("onUnlockFailed('기기를 찾을 수 없습니다.')"));
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    runOnUiThread(() -> callJs("onUnlockFailed('" + escapeJs(msg) + "')"));
                }
            });
        }

        /**
         * JS: {@code Android.openUserChangeDialog()}
         * ACTIVE 상태의 담당자 변경 버튼 클릭 시 호출.
         */
        @JavascriptInterface
        public void openUserChangeDialog() {
            runOnUiThread(() -> showUserChangeDialog());
        }
    }

    /* =========================================================
       Native AlertDialog — 복잡한 인터랙션은 Native에서 처리
       ========================================================= */

    /** KEY_INVALIDATED: 새 얼굴 등록 감지 → 키 삭제 후 RegisterActivity 이동 */
    private void showKeyInvalidatedDialog(String userId, String deviceId) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("보안 알림")
                .setMessage("새로운 얼굴 등록이 감지되었습니다.\n기존 사용자 정보를 제거하고 재등록을 진행하시겠습니까?")
                .setPositiveButton("신규 사용자 등록", (d, w) ->
                        BiometricApplication.getExecutor().submit(() -> {
                            try {
                                // TODO: [실서비스] 보안 이벤트 서버 로그 기록, 관리자 알림 연동 검토
                                BiometricApplication.getEcKeyManager().deleteKeyPair();
                                tokenStorage.clearRegistration();
                                runOnUiThread(() -> {
                                    Intent intent = new Intent(MainActivity.this, RegisterActivity.class);
                                    intent.putExtra("device_id", deviceId);
                                    intent.putExtra("button_label", "사용자 변경");
                                    startActivity(intent);
                                    finish();
                                });
                            } catch (Exception e) {
                                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                                runOnUiThread(() -> callJs("onStatusError('" + escapeJs(msg) + "')"));
                            }
                        }))
                .setNegativeButton("취소", (d, w) ->
                        new AlertDialog.Builder(this)
                                .setMessage("재등록을 완료해야 안면인식 로그인을 사용할 수 있습니다.\n앱을 종료합니다.")
                                .setPositiveButton("확인", (d2, w2) -> finishAffinity())
                                .show())
                .setCancelable(false)
                .show();
    }

    /** 담당자 변경 다이얼로그 — UserChangeHandler 플로우 실행 */
    private void showUserChangeDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("담당자 변경")
                .setMessage("이 기기에 저장된 로그인·인증 정보가 삭제됩니다.\n계속하시겠습니까?")
                .setPositiveButton("확인", (dialog, which) -> {
                    Log.d(TAG, "담당자 변경 확인 → 기기 자격증명 인증 시작");
                    userChangeHandler.verifyDeviceCredential(
                            this,
                            new UserChangeHandler.UserChangeCallback() {

                                @Override
                                public void onVerified() {
                                    callJs("onStatusLoading()");
                                    userChangeHandler.executeChange(MainActivity.this, this);
                                }

                                @Override
                                public void onChangeCompleted() {
                                    Log.d(TAG, "사용자 변경 완료 → 등록 화면 이동");
                                    Toast.makeText(MainActivity.this,
                                            "삭제가 완료되었습니다. 신규 등록 화면으로 이동합니다.",
                                            Toast.LENGTH_SHORT).show();
                                    pendingNavigation = () -> {
                                        if (!isFinishing()) {
                                            Intent intent = new Intent(MainActivity.this, RegisterActivity.class);
                                            intent.putExtra("button_label", "신규 사용자 등록");
                                            startActivity(intent);
                                            finish();
                                        }
                                    };
                                    mainHandler.postDelayed(pendingNavigation,
                                            BiometricLibConstants.UI_REDIRECT_DELAY_MS);
                                }

                                @Override
                                public void onChangeFailed(ErrorCode errorCode) {
                                    Log.e(TAG, "사용자 변경 실패: " + errorCode);
                                    Toast.makeText(MainActivity.this,
                                            "문제가 지속되면 헬프데스크로 문의해주세요.",
                                            Toast.LENGTH_LONG).show();
                                    callJs("onStatusActive('" + escapeJs(
                                            pendingUserId != null ? pendingUserId : "") + "')");
                                }

                                @Override
                                public void onCanceled() {
                                    Log.d(TAG, "사용자 변경 취소");
                                }
                            });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /* =========================================================
       유틸리티
       ========================================================= */

    /**
     * JS 함수를 메인 스레드에서 실행.
     * 페이지 로드 전 호출된 경우 deferredStatusCall에 저장했다가 onPageFinished 후 실행.
     *
     * @param jsCall 예: "onStatusActive('user01')"
     */
    private void callJs(String jsCall) {
        Runnable r = () -> webView.evaluateJavascript("javascript:" + jsCall, null);
        if (pageLoaded) {
            runOnUiThread(r);
        } else {
            // 페이지 로드 완료 전이면 deferred 저장 (마지막 상태만 유지)
            deferredStatusCall = r;
        }
    }

    /** JS 문자열 내 작은따옴표 이스케이프 */
    private static String escapeJs(String s) {
        return s != null ? s.replace("'", "\\'") : "";
    }
}
