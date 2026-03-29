package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.auth.BiometricAuthManager;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.crypto.KeyInvalidationHandler;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.policy.FailurePolicyManager;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class LoginActivity extends AppCompatActivity {

    private TokenStorage tokenStorage;
    private BiometricAuthManager biometricAuthManager;
    private MaterialButton buttonBiometricLogin;
    private MaterialButton buttonIdPwLogin;
    private TextView textLockNotice;
    private TextView textFailureCount;
    private ProgressBar progressLogin;
    private View loginRoot;

    @Nullable private CountDownTimer lockCountDownTimer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        if (tokenStorage.getAccessToken() != null) {
            startActivity(new Intent(this, MainAfterLoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        loginRoot = findViewById(R.id.login_root);
        buttonBiometricLogin = findViewById(R.id.button_biometric_login);
        buttonIdPwLogin = findViewById(R.id.button_idpw_login);
        textLockNotice = findViewById(R.id.text_lock_notice);
        textFailureCount = findViewById(R.id.text_failure_count);
        progressLogin = findViewById(R.id.progress_login);

        EcKeyManager ecKeyManager = new EcKeyManager();
        AuthApiClient authApiClient = new AuthApiClient(BuildConfig.SERVER_URL);
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();
        KeyInvalidationHandler keyInvalidationHandler =
                new KeyInvalidationHandler(this, ecKeyManager, tokenStorage, authApiClient);
        biometricAuthManager =
                new BiometricAuthManager(
                        this,
                        authApiClient,
                        ecKeyManager,
                        tokenStorage,
                        failurePolicyManager,
                        keyInvalidationHandler);

        buttonBiometricLogin.setOnClickListener(v -> startBiometricAuth());
        buttonIdPwLogin.setOnClickListener(
                v ->
                        Toast.makeText(
                                        this,
                                        "ID/PW 로그인은 기존 MIS 앱에서 진행해주세요. (PoC 시연 안내)",
                                        Toast.LENGTH_LONG)
                                .show());
    }

    @Override
    protected void onDestroy() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
        }
        super.onDestroy();
    }

    private void startBiometricAuth() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
        }
        runOnUiThread(
                () -> {
                    textLockNotice.setVisibility(View.GONE);
                    textFailureCount.setVisibility(View.GONE);
                    buttonIdPwLogin.setVisibility(View.GONE);
                    progressLogin.setVisibility(View.VISIBLE);
                    buttonBiometricLogin.setEnabled(false);
                    buttonIdPwLogin.setEnabled(false);
                });

        biometricAuthManager.authenticate(
                this,
                new BiometricAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(
                            String userId, AuthApiClient.TokenResponse tokenResponse) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    Intent intent =
                                            new Intent(
                                                    LoginActivity.this,
                                                    MainAfterLoginActivity.class);
                                    intent.putExtra("user_id", userId);
                                    intent.putExtra(
                                            "access_token", tokenResponse.accessToken);
                                    intent.putExtra("expires_in", tokenResponse.expiresIn);
                                    startActivity(intent);
                                    finish();
                                });
                    }

                    @Override
                    public void onNotRegistered() {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    startActivity(
                                            new Intent(
                                                    LoginActivity.this,
                                                    RegisterActivity.class));
                                });
                    }

                    @Override
                    public void onLockedOut(int remainingSeconds) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(false);
                                    textLockNotice.setVisibility(View.VISIBLE);
                                    textLockNotice.setText(
                                            "인증 " + remainingSeconds + "초 잠금 중...");
                                    if (lockCountDownTimer != null) {
                                        lockCountDownTimer.cancel();
                                    }
                                    lockCountDownTimer =
                                            new CountDownTimer(
                                                    remainingSeconds * 1000L, 1000L) {
                                                @Override
                                                public void onTick(long millisUntilFinished) {
                                                    int sec =
                                                            (int)
                                                                    Math.ceil(
                                                                            millisUntilFinished
                                                                                    / 1000.0);
                                                    textLockNotice.setText(
                                                            "인증 " + sec + "초 잠금 중...");
                                                }

                                                @Override
                                                public void onFinish() {
                                                    textLockNotice.setVisibility(View.GONE);
                                                    buttonBiometricLogin.setEnabled(true);
                                                    lockCountDownTimer = null;
                                                }
                                            };
                                    lockCountDownTimer.start();
                                });
                    }

                    @Override
                    public void onRetry(int failureCount) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    textFailureCount.setVisibility(View.VISIBLE);
                                    textFailureCount.setText(
                                            "인증 실패 "
                                                    + failureCount
                                                    + "회 / "
                                                    + BiometricLibConstants.MAX_FAILURE_COUNT_FOR_UI
                                                    + "회 초과 시 계정 잠금");
                                });
                    }

                    @Override
                    public void onAccountLocked() {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    textLockNotice.setVisibility(View.VISIBLE);
                                    textLockNotice.setText(
                                            "인증 "
                                                    + BiometricLibConstants.MAX_FAILURE_COUNT_FOR_UI
                                                    + "회 실패로 계정이 잠겼습니다. ID/PW로 로그인해주세요.");
                                    buttonIdPwLogin.setVisibility(View.VISIBLE);
                                    buttonIdPwLogin.setEnabled(true);
                                    buttonBiometricLogin.setEnabled(false);
                                });
                    }

                    @Override
                    public void onKeyInvalidated() {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    new AlertDialog.Builder(LoginActivity.this)
                                            .setMessage(
                                                    "보안을 위해 안면인식을 다시 등록해야 합니다.")
                                            .setPositiveButton(
                                                    android.R.string.ok,
                                                    (d, w) ->
                                                            startActivity(
                                                                    new Intent(
                                                                            LoginActivity.this,
                                                                            RegisterActivity
                                                                                    .class)))
                                            .show();
                                });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    Snackbar.make(loginRoot, message, Snackbar.LENGTH_LONG)
                                            .show();
                                });
                    }
                });
    }
}
