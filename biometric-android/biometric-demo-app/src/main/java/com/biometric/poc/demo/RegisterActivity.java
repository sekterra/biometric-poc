package com.biometric.poc.demo;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.lib.auth.BiometricRegistrar;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class RegisterActivity extends AppCompatActivity {

    private MaterialButton buttonRegister;
    private ProgressBar progressRegister;
    private TextView statusMessage;
    private BiometricRegistrar registrar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        buttonRegister = findViewById(R.id.button_register);
        progressRegister = findViewById(R.id.progress_register);
        statusMessage = findViewById(R.id.status_message);

        TokenStorage tokenStorage;
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        EcKeyManager ecKeyManager = new EcKeyManager();
        AuthApiClient authApiClient = new AuthApiClient(BuildConfig.SERVER_URL);
        registrar = new BiometricRegistrar(this, authApiClient, ecKeyManager, tokenStorage);

        buttonRegister.setOnClickListener(v -> startRegister());
    }

    private void startRegister() {
        runOnUiThread(
                () -> {
                    buttonRegister.setEnabled(false);
                    progressRegister.setVisibility(View.VISIBLE);
                    statusMessage.setVisibility(View.GONE);
                });

        registrar.register(
                this,
                new BiometricRegistrar.RegisterCallback() {
                    @Override
                    public void onSuccess(String userId) {
                        runOnUiThread(
                                () -> {
                                    statusMessage.setVisibility(View.VISIBLE);
                                    statusMessage.setTextColor(
                                            getResources()
                                                    .getColor(
                                                            android.R.color.holo_green_dark,
                                                            getTheme()));
                                    statusMessage.setText("등록 완료! 사용자: " + userId);
                                    progressRegister.setVisibility(View.GONE);
                                    new Handler(Looper.getMainLooper())
                                            .postDelayed(
                                                    () -> {
                                                        startActivity(
                                                                new Intent(
                                                                        RegisterActivity.this,
                                                                        LoginActivity.class));
                                                        finish();
                                                    },
                                                    1500);
                                });
                    }

                    @Override
                    public void onNotEnrolled() {
                        runOnUiThread(
                                () -> {
                                    progressRegister.setVisibility(View.GONE);
                                    buttonRegister.setEnabled(true);
                                    new AlertDialog.Builder(RegisterActivity.this)
                                            .setMessage(
                                                    "기기 설정 > 생체 인식에서 안면인식을 먼저 등록해주세요.")
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show();
                                });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(
                                () -> {
                                    statusMessage.setVisibility(View.VISIBLE);
                                    statusMessage.setTextColor(Color.parseColor("#D32F2F"));
                                    statusMessage.setText("등록 실패: " + message);
                                    progressRegister.setVisibility(View.GONE);
                                    buttonRegister.setEnabled(true);
                                });
                    }
                });
    }
}
