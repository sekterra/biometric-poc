package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.skcc.biometric.lib.auth.BiometricAuthManager;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.policy.FailurePolicyManager;
import com.skcc.biometric.lib.storage.TokenStorage;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 안면인식 로그인 화면 — WebView 기반 HTML/JS UI.
 *
 * <p>레이아웃: {@code assets/login.html} (로컬 파일 로드)
 * <p>Native ↔ JS 통신: {@link AndroidBridge} (@JavascriptInterface)
 * <p>BiometricPrompt는 OS 오버레이로 WebView 위에 표시되므로 수정 불필요.
 *
 * <p>※ AppCompatActivity는 FragmentActivity를 상속하므로 BiometricPrompt 요건 충족.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private WebView webView;
    private AndroidBridge bridge;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── TokenStorage 초기화 ──────────────────────────────────
        TokenStorage tokenStorage;
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // 이미 로그인된 경우 메인 화면으로 바로 이동
        if (tokenStorage.getAccessToken() != null) {
            startActivity(new Intent(this, MainAfterLoginActivity.class));
            finish();
            return;
        }

        // ── WebView 설정 ─────────────────────────────────────────
        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);  // JS 활성화 (Bridge 통신 필수)
        webView.getSettings().setDomStorageEnabled(true);   // sessionStorage / localStorage 허용

        // 외부 브라우저로 열리지 않도록 모든 URL을 WebView 내에서 처리
        webView.setWebViewClient(new WebViewClient());

        // ── 생체인증 컴포넌트 초기화 ─────────────────────────────
        EcKeyManager ecKeyManager = BiometricApplication.getEcKeyManager();
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();

        BiometricAuthManager biometricAuthManager = new BiometricAuthManager(
                this,
                BiometricApplication.getAuthApiClient(),
                ecKeyManager,
                tokenStorage,
                failurePolicyManager,
                BiometricApplication.getExecutor());

        UserChangeHandler userChangeHandler = new UserChangeHandler(
                this, ecKeyManager, tokenStorage,
                BiometricApplication.getAuthApiClient(),
                BiometricApplication.getExecutor());

        // ── JavascriptInterface 브릿지 등록 ──────────────────────
        // JS에서 Android.xxx() 형태로 호출
        bridge = new AndroidBridge(
                this, webView, biometricAuthManager, userChangeHandler, tokenStorage);
        webView.addJavascriptInterface(bridge, "Android");

        // 로컬 HTML 파일 로드
        webView.loadUrl("file:///android_asset/login.html");

        Log.d(TAG, "LoginActivity(WebView) 초기화 완료");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // getAccessToken()이 non-null인 경우 onCreate에서 finish()를 호출하고
        // webView/bridge를 초기화하지 않으므로 onResume() 호출 시 NPE 발생 — null 가드 추가
        if (webView == null || bridge == null) return;
        webView.onResume();

        // 잠금 / 계정 잠금 상태 중이면 버튼 상태 재확인 불필요
        if (bridge.isCountingDown || bridge.isAccountLocked) return;

        // 안면인식 등록 여부 확인 — 미등록 시 버튼 비활성화 + 안내 다이얼로그
        int canAuth = BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            // JS 로드 완료 여부와 무관하게 호출해도 무방 (evaluateJavascript는 DOM 준비 후 실행)
            webView.evaluateJavascript("javascript:setFaceLoginEnabled(true)", null);
            Log.d(TAG, "onResume: 안면인식 등록 확인됨");
        } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            webView.evaluateJavascript("javascript:setFaceLoginEnabled(false)", null);
            Log.d(TAG, "onResume: 안면인식 미등록 감지 → 안내 다이얼로그 표시");
            bridge.showNotEnrolledDialog();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView == null) return;
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            // 예약된 화면 전환 취소 (메모리 누수 방지)
            bridge.cancelPendingNavigation();
            // 잠금 카운트다운 타이머 해제
            bridge.cancelCountdown();
        }

        // BiometricAuthManager executor는 BiometricApplication이 관리하므로 shutdown() 불필요

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
}
