package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.storage.TokenStorage;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 로그인 성공 결과 화면 — WebView 기반 HTML/JS UI.
 *
 * <p>레이아웃: {@code assets/main_after_login.html}
 * <p>데이터 전달: {@code onPageFinished} 에서 {@code initPage(userId, tokenPreview, expiresMin)} 호출.
 * <p>브릿지: {@link AfterLoginBridge#logout()} → 토큰 삭제 후 LoginActivity 이동.
 */
public class MainAfterLoginActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Intent Extra 수신 ────────────────────────────────────
        Intent intent = getIntent();
        String userId    = intent.getStringExtra("user_id");
        String accessToken = intent.getStringExtra("access_token");
        int expiresIn    = intent.getIntExtra("expires_in",
                BiometricLibConstants.TOKEN_EXPIRES_IN_DEFAULT_SEC);

        // Intent Extra 없으면 TokenStorage에서 폴백
        if (userId == null || accessToken == null) {
            try {
                TokenStorage ts = new TokenStorage(this);
                if (userId == null)      userId      = ts.getUserId();
                if (accessToken == null) accessToken = ts.getAccessToken();
            } catch (GeneralSecurityException | IOException ignored) {
            }
        }

        // ── 표시용 값 준비 ───────────────────────────────────────
        final String displayUserId = userId != null ? userId : "-";
        final String token = accessToken != null ? accessToken : "";
        final String tokenPreview = token.isEmpty() ? "-" :
                token.substring(0, Math.min(BiometricLibConstants.TOKEN_PREVIEW_MAX_CHARS, token.length())) + "...";
        final int expiresMinutes = expiresIn / 60;

        // ── WebView 설정 ─────────────────────────────────────────
        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        // 브릿지 등록 — JS에서 Android.logout() 호출
        webView.addJavascriptInterface(new AfterLoginBridge(), "Android");

        // 페이지 로드 완료 후 initPage() 호출 — evaluateJavascript는 DOM 준비 후 실행
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 문자열 내 작은따옴표 이스케이프 (JS 구문 오류 방지)
                String safeUserId = displayUserId.replace("'", "\\'");
                String safeToken  = tokenPreview.replace("'", "\\'");
                String js = "initPage('" + safeUserId + "','" + safeToken + "'," + expiresMinutes + ")";

                // onPageFinished는 메인 스레드에서 호출되므로 runOnUiThread 래핑은 선택적이지만 명시적으로 보장
                runOnUiThread(() -> webView.evaluateJavascript("javascript:" + js, null));
            }
        });

        webView.loadUrl("file:///android_asset/main_after_login.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.stopLoading();
        webView.destroy();
        super.onDestroy();
    }

    /* =========================================================
       JavascriptInterface 브릿지
       ========================================================= */

    /**
     * JS에서 {@code Android.logout()} 호출 시 실행.
     * @JavascriptInterface 메서드는 백그라운드 스레드에서 호출되므로 UI 작업은 runOnUiThread 사용.
     */
    private class AfterLoginBridge {

        /** JS: {@code Android.logout()} — 토큰 삭제 후 LoginActivity로 이동 */
        @JavascriptInterface
        public void logout() {
            runOnUiThread(() -> {
                try {
                    new TokenStorage(MainAfterLoginActivity.this).clearAll();
                } catch (GeneralSecurityException | IOException ignored) {
                }
                Intent login = new Intent(MainAfterLoginActivity.this, LoginActivity.class);
                startActivity(login);
                finishAffinity();
            });
        }
    }
}
