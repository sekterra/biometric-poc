package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.demo.util.BiometricSettingsNavigator;
import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.auth.BiometricRegistrar;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.storage.TokenStorage;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 안면인식 등록 화면 — WebView 기반 HTML/JS UI.
 *
 * <p>레이아웃: {@code assets/register.html}
 * <p>데이터 전달: {@code onPageFinished} 에서 {@code initPage(deviceId, userId, buttonLabel)} 호출.
 * <p>브릿지: {@link RegisterBridge#startRegister(String)}
 * <p>AlertDialog(안면인식 미등록, 이미등록)는 Native 유지.
 */
public class RegisterActivity extends AppCompatActivity {

    private WebView webView;
    private BiometricRegistrar registrar;
    private TokenStorage tokenStorage;
    private String deviceId;
    private String userId;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** onDestroy 시 취소할 지연 화면 전환 Runnable */
    @Nullable
    private Runnable pendingNavigation = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── TokenStorage 초기화 ──────────────────────────────────
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // 이미 등록된 경우 로그인 화면으로 이동
        if (tokenStorage.isRegistered()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // ── Intent Extra 수신 ────────────────────────────────────
        deviceId = getIntent().getStringExtra("device_id");
        userId   = getIntent().getStringExtra("user_id");
        String buttonLabel = getIntent().getStringExtra("button_label");

        // deviceId 없으면 ANDROID_ID로 폴백
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        final String finalDeviceId  = deviceId;
        final String finalUserId    = userId   != null ? userId   : "";
        final String finalBtnLabel  = buttonLabel != null ? buttonLabel : "안면인식 등록 시작";

        // ── AAR 컴포넌트 초기화 ──────────────────────────────────
        EcKeyManager ecKeyManager = BiometricApplication.getEcKeyManager();
        registrar = new BiometricRegistrar(
                this,
                BiometricApplication.getAuthApiClient(),
                ecKeyManager,
                tokenStorage,
                BiometricApplication.getExecutor());

        // ── WebView 설정 ─────────────────────────────────────────
        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new RegisterBridge(), "Android");

        // 페이지 로드 완료 후 initPage() 호출
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String safeDeviceId = finalDeviceId.replace("'", "\\'");
                String safeUserId   = finalUserId.replace("'", "\\'");
                String safeBtnLabel = finalBtnLabel.replace("'", "\\'");
                String js = "initPage('" + safeDeviceId + "','" + safeUserId + "','" + safeBtnLabel + "')";
                runOnUiThread(() -> webView.evaluateJavascript("javascript:" + js, null));
            }
        });

        webView.loadUrl("file:///android_asset/register.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // isRegistered()가 true인 경우 onCreate에서 finish()를 호출하고 webView를 초기화하지 않으므로
        // onResume() 호출 시 NPE 발생 — null 가드 추가
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
        if (registrar != null) {
            registrar.shutdown();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    /* =========================================================
       JavascriptInterface 브릿지
       ========================================================= */

    private class RegisterBridge {

        /**
         * JS: {@code Android.startRegister(userId)}
         * HTML에서 사용자 ID를 수집한 후 등록 버튼 클릭 시 호출.
         *
         * @param inputUserId JS에서 전달받은 사용자 ID
         */
        @JavascriptInterface
        public void startRegister(String inputUserId) {
            // userId는 Intent Extra 우선, 없으면 JS 입력값 사용
            String effectiveUserId = (userId != null && !userId.isEmpty()) ? userId : inputUserId;

            runOnUiThread(() ->
                    registrar.register(
                            RegisterActivity.this,
                            deviceId,
                            effectiveUserId,
                            new BiometricRegistrar.RegisterCallback() {

                                @Override
                                public void onSuccess(String registeredUserId) {
                                    // 성공 메시지 표시 → 1.5초 후 LoginActivity 이동
                                    runOnUiThread(() -> {
                                        webView.evaluateJavascript(
                                                "javascript:onRegisterSuccess('" + registeredUserId.replace("'", "\\'") + "')",
                                                null);
                                        pendingNavigation = () -> {
                                            if (!isFinishing()) {
                                                startActivity(new Intent(RegisterActivity.this, LoginActivity.class));
                                                finish();
                                            }
                                        };
                                        mainHandler.postDelayed(pendingNavigation,
                                                BiometricLibConstants.UI_REDIRECT_DELAY_MEDIUM_MS);
                                    });
                                }

                                @Override
                                public void onError(ErrorCode errorCode) {
                                    runOnUiThread(() -> handleRegisterError(errorCode));
                                }
                            }));
        }
    }

    /* =========================================================
       오류 처리 — ErrorCode에 따라 JS 콜백 또는 Native AlertDialog 분기
       ========================================================= */

    private void handleRegisterError(ErrorCode errorCode) {
        switch (errorCode) {
            case BIOMETRIC_NONE_ENROLLED:
                // 안면인식 미등록 → 설정 유도 다이얼로그 (Native 유지)
                webView.evaluateJavascript("javascript:onRegisterError('BIOMETRIC_NONE_ENROLLED','')", null);
                showNotEnrolledDialog();
                break;

            case ALREADY_REGISTERED:
                // 이미 등록된 기기 → 로그인 유도 다이얼로그 (Native 유지)
                webView.evaluateJavascript("javascript:onRegisterError('ALREADY_REGISTERED','')", null);
                showAlreadyRegisteredDialog();
                break;

            case NETWORK_ERROR:
                webView.evaluateJavascript(
                        "javascript:onRegisterError('NETWORK_ERROR','네트워크 연결을 확인 후 다시 시도해주세요.')", null);
                break;

            default:
                webView.evaluateJavascript(
                        "javascript:onRegisterError('UNKNOWN','알 수 없는 오류가 발생했습니다. 다시 시도해주세요.')", null);
                break;
        }
    }

    /** BIOMETRIC_NONE_ENROLLED: 안면인식 미등록 → 설정 화면 유도 */
    private void showNotEnrolledDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("안면인식 미등록")
                .setMessage("안면인식이 등록되지 않았습니다.\n설정에서 안면인식을 등록 후 이용해주세요.")
                .setPositiveButton("설정으로 이동",
                        (d, w) -> BiometricSettingsNavigator.navigate(RegisterActivity.this))
                .setNegativeButton("나중에", null)
                .show();
    }

    /** ALREADY_REGISTERED: 이미 등록된 기기 → 로컬 등록 복원 후 LoginActivity 이동 */
    private void showAlreadyRegisteredDialog() {
        if (isFinishing()) return;

        // userId 우선순위: Intent Extra → tokenStorage 폴백
        String resolvedUserId = getIntent().getStringExtra("user_id");
        if (resolvedUserId == null || resolvedUserId.isEmpty()) {
            resolvedUserId = tokenStorage.getUserId();
        }
        final String finalUserId   = resolvedUserId != null ? resolvedUserId : "";

        String intentDeviceId = getIntent().getStringExtra("device_id");
        if (intentDeviceId == null || intentDeviceId.isEmpty()) {
            intentDeviceId = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        final String finalDeviceId = intentDeviceId;

        new AlertDialog.Builder(this)
                .setTitle("이미 등록된 기기")
                .setMessage("이 기기는 이미 인증서버에 등록되어 있습니다.\n확인 버튼 클릭시 자동으로 안면인식 절차가 진행됩니다.")
                .setPositiveButton("확인", (d, w) -> {
                    // 서버에 ACTIVE 상태이므로 로컬 등록 플래그 복원
                    tokenStorage.saveRegistration(finalDeviceId, finalUserId);
                    Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("취소", null)
                .setCancelable(false)
                .show();
    }
}
