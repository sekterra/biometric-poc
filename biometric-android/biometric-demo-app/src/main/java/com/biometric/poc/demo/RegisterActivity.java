package com.biometric.poc.demo;

import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.demo.util.BiometricSettingsNavigator;
import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.auth.BiometricRegistrar;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private TextView tvDeviceId;
    private TextView tvUserId;
    private TextView tvStatus;
    private EditText etUserId;
    private MaterialButton btnRegister;
    private ProgressBar progressBar;

    private String deviceId;
    private String userId;
    private String buttonLabel;

    private TokenStorage tokenStorage;
    private BiometricRegistrar registrar;

    /** Activity 전역 Handler — postDelayed 취소를 위해 멤버 변수로 관리. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** onDestroy 시 취소할 지연 화면 전환 Runnable. */
    @Nullable private Runnable pendingNavigation = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        if (tokenStorage.isRegistered()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_register);

        tvDeviceId = findViewById(R.id.tvDeviceId);
        tvUserId = findViewById(R.id.tvUserId);
        tvStatus = findViewById(R.id.tvStatus);
        etUserId = findViewById(R.id.etUserId);
        btnRegister = findViewById(R.id.btnRegister);
        progressBar = findViewById(R.id.progressBar);

        deviceId = getIntent().getStringExtra("device_id");
        userId = getIntent().getStringExtra("user_id");
        buttonLabel = getIntent().getStringExtra("button_label");

        if (deviceId == null || deviceId.isEmpty()) {
            deviceId =
                    Settings.Secure.getString(
                            getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        tvDeviceId.setText(deviceId != null ? deviceId : "");

        if (userId != null && !userId.isEmpty()) {
            tvUserId.setText(userId);
            tvUserId.setVisibility(View.VISIBLE);
            etUserId.setVisibility(View.GONE);
        } else {
            etUserId.setVisibility(View.VISIBLE);
            tvUserId.setVisibility(View.GONE);
        }

        btnRegister.setText(
                buttonLabel != null ? buttonLabel : "안면인식 등록 시작");

        EcKeyManager ecKeyManager = BiometricApplication.getEcKeyManager();
        registrar = new BiometricRegistrar(
                this,
                BiometricApplication.getAuthApiClient(),
                ecKeyManager,
                tokenStorage,
                BiometricApplication.getExecutor());

        btnRegister.setOnClickListener(v -> startRegister());
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
        super.onDestroy();
    }

    private void startRegister() {
        String effectiveUserId =
                userId != null && !userId.isEmpty() ? userId : etUserId.getText().toString().trim();

        if (effectiveUserId.isEmpty()) {
            Toast.makeText(this, "사용자 ID를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnRegister.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);

        registrar.register(
                this,
                deviceId,
                effectiveUserId,
                new BiometricRegistrar.RegisterCallback() {
                    @Override
                    public void onSuccess(String registeredUserId) {
                        runOnUiThread(
                                () -> {
                                    progressBar.setVisibility(View.GONE);
                                    tvStatus.setText("등록 완료! 사용자: " + registeredUserId);
                                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                                    tvStatus.setVisibility(View.VISIBLE);
                                    pendingNavigation = () -> {
                                        if (!isFinishing()) {
                                            startActivity(new Intent(
                                                    RegisterActivity.this, LoginActivity.class));
                                            finish();
                                        }
                                    };
                                    mainHandler.postDelayed(
                                            pendingNavigation,
                                            BiometricLibConstants.UI_REDIRECT_DELAY_MEDIUM_MS);
                                });
                    }

                    @Override
                    public void onError(ErrorCode errorCode) {
                        runOnUiThread(
                                () -> {
                                    progressBar.setVisibility(View.GONE);
                                    btnRegister.setEnabled(true);

                                    switch (errorCode) {
                                        case BIOMETRIC_NONE_ENROLLED:
                                            showNotEnrolledDialog();
                                            break;

                                        case DEVICE_NOT_FOUND:
                                            showErrorStatus(getString(R.string.error_device_not_found));
                                            break;

                                        case ALREADY_REGISTERED:
                                            // CASE11 — 이미 등록된 기기 → 로그인 화면 유도
                                            Log.w(TAG, "ALREADY_REGISTERED 감지 → 로그인 유도");
                                            showAlreadyRegisteredDialog();
                                            break;

                                        case NETWORK_ERROR:
                                            showErrorStatus(getString(R.string.error_network));
                                            break;

                                        default:
                                            showErrorStatus(getString(R.string.error_unknown));
                                            break;
                                    }
                                });
                    }
                });
    }

    private void showNotEnrolledDialog() {
        new AlertDialog.Builder(RegisterActivity.this)
                .setTitle("안면인식 미등록")
                .setMessage(
                        "안면인식이 등록되지 않았습니다.\n"
                                + "설정에서 안면인식을 등록 후 이용해주세요.")
                .setPositiveButton(
                        "설정으로 이동",
                        (d, w) -> BiometricSettingsNavigator.navigate(RegisterActivity.this))
                .setNegativeButton("나중에", null)
                .show();
    }

    private void showAlreadyRegisteredDialog() {
        // 우선순위: ① Intent extra "user_id" → ② EditText 입력값 → ③ tokenStorage 폴백
        String resolvedUserId = getIntent().getStringExtra("user_id");
        if (resolvedUserId == null || resolvedUserId.isEmpty()) {
            resolvedUserId = (etUserId != null)
                    ? etUserId.getText().toString().trim()
                    : null;
            Log.d(TAG, "userId: EditText 값 사용");
        } else {
            Log.d(TAG, "userId: Intent extra 값 사용 = " + resolvedUserId);
        }
        if (resolvedUserId == null || resolvedUserId.isEmpty()) {
            resolvedUserId = tokenStorage.getUserId();
            Log.d(TAG, "userId: tokenStorage 폴백 사용 = " + resolvedUserId);
        }
        if (resolvedUserId == null || resolvedUserId.isEmpty()) {
            Log.e(TAG, "userId 확인 불가 → 다이얼로그 취소");
            showErrorStatus("사용자 ID를 확인할 수 없습니다.");
            return;
        }

        String intentDeviceId = getIntent().getStringExtra("device_id");
        if (intentDeviceId == null || intentDeviceId.isEmpty()) {
            intentDeviceId = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
        }

        final String finalUserId = resolvedUserId;
        final String finalDeviceId = intentDeviceId;

        new AlertDialog.Builder(this)
                .setTitle("이미 등록된 기기")
                .setMessage(getString(R.string.error_already_registered))
                .setPositiveButton(
                        "확인",
                        (dialog, which) -> {
                            Log.d(TAG, "ALREADY_REGISTERED → 로그인 유도"
                                    + " deviceId=" + finalDeviceId
                                    + " userId=" + finalUserId);
                            // 서버에 ACTIVE 상태이므로 로컬 등록 플래그 복원
                            tokenStorage.saveRegistration(finalDeviceId, finalUserId);
                            Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                            intent.setFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            finish();
                        })
                .setNegativeButton("취소", null)
                .setCancelable(false)
                .show();
    }

    private void showErrorStatus(String message) {
        tvStatus.setText(message);
        tvStatus.setTextColor(Color.RED);
        tvStatus.setVisibility(View.VISIBLE);
    }
}
