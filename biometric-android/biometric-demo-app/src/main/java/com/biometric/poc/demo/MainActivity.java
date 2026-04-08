package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.auth.UserChangeHandler;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.network.AuthApiClient.DeviceStatusResponse;
import com.biometric.poc.lib.network.DeviceNotFoundException;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_BUTTON_LABEL = "button_label";

    private TextView subtitleText;
    private ProgressBar progressBar;
    private TextView statusMessage;
    private EditText lockedUserIdEdit;
    private EditText passwordEdit;
    private MaterialButton loginButton;
    private MaterialButton btnUserChange;

    private TokenStorage tokenStorage;
    private AuthApiClient authApiClient;
    private EcKeyManager ecKeyManager;
    private UserChangeHandler userChangeHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** onDestroy 시 취소할 지연 화면 전환 Runnable. */
    @Nullable private Runnable pendingNavigation = null;

    private String pendingDeviceIdForLocked;
    private String pendingUserIdForLocked;

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

        setContentView(R.layout.activity_main);
        bindViews();
        authApiClient = BiometricApplication.getAuthApiClient();
        ecKeyManager = BiometricApplication.getEcKeyManager();
        userChangeHandler = new UserChangeHandler(
                this, ecKeyManager, tokenStorage, authApiClient,
                BiometricApplication.getExecutor());
        checkDeviceStatus();
    }

    private void bindViews() {
        subtitleText = findViewById(R.id.main_subtitle);
        progressBar = findViewById(R.id.main_progress);
        statusMessage = findViewById(R.id.main_status_message);
        lockedUserIdEdit = findViewById(R.id.main_locked_user_id);
        passwordEdit = findViewById(R.id.main_password);
        loginButton = findViewById(R.id.main_login_button);
        btnUserChange = findViewById(R.id.btnUserChange);
    }

    private void hideLockedUi() {
        lockedUserIdEdit.setVisibility(View.GONE);
        passwordEdit.setVisibility(View.GONE);
        loginButton.setVisibility(View.GONE);
    }

    private void checkDeviceStatus() {
        subtitleText.setVisibility(View.VISIBLE);
        subtitleText.setText(R.string.main_lookup_device_id);
        progressBar.setVisibility(View.VISIBLE);
        statusMessage.setVisibility(View.GONE);
        hideLockedUi();

        BiometricApplication.getExecutor().submit(
                () -> {
                    String deviceId =
                            Settings.Secure.getString(
                                    getContentResolver(), Settings.Secure.ANDROID_ID);
                    try {
                        DeviceStatusResponse response = authApiClient.getUserId(deviceId);
                        runOnUiThread(() -> handleDeviceStatus(response, deviceId));
                    } catch (DeviceNotFoundException e) {
                        runOnUiThread(() -> showNotRegisteredUi(deviceId));
                    } catch (Exception e) {
                        String msg =
                                e.getMessage() != null
                                        ? e.getMessage()
                                        : e.getClass().getSimpleName();
                        runOnUiThread(() -> showError(msg));
                    }
                });
    }

    private void handleDeviceStatus(DeviceStatusResponse response, String deviceId) {
        progressBar.setVisibility(View.GONE);
        subtitleText.setVisibility(View.GONE);
        String status = response.status != null ? response.status : "";
        switch (status) {
            case "ACTIVE":
                hideLockedUi();
                tokenStorage.saveRegistration(deviceId, response.userId);
                statusMessage.setVisibility(View.VISIBLE);
                statusMessage.setText(
                        getString(
                                R.string.main_greeting_format,
                                response.userId != null ? response.userId : ""));
                // 담당자 변경 버튼 표시
                btnUserChange.setVisibility(View.VISIBLE);
                btnUserChange.setOnClickListener(v -> showUserChangeDialog());
                pendingNavigation = () -> {
                    if (!isFinishing()) {
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                    }
                };
                mainHandler.postDelayed(pendingNavigation, BiometricLibConstants.UI_REDIRECT_DELAY_LONG_MS);
                break;
            case "LOCKED":
                showLockedUi(response.userId, deviceId);
                break;
            case "KEY_INVALIDATED":
                showKeyInvalidatedDialog(response.userId, deviceId);
                break;
            default:
                hideLockedUi();
                showError(getString(R.string.main_unknown_status, status));
                break;
        }
    }

    private void showNotRegisteredUi(String deviceId) {
        // TODO: [실서비스] USER_ID를 서버에서 검증 (MIS 사용자 DB 조회)
        // TODO: [실서비스] 1사용자 다기기 등록 정책 확인 후 기기 교체 시나리오(기존 기기 비활성화) 처리 필요
        Intent intent = new Intent(this, RegisterActivity.class);
        intent.putExtra("device_id", deviceId);
        intent.putExtra("button_label", "사용자ID 및 기기 등록");
        startActivity(intent);
        finish();
    }

    private void showLockedUi(String userId, String deviceId) {
        // TODO: [실서비스] 이 화면에서 직접 unlock을 호출하지 않음 — 실제 ID/PW는 MIS 인증 서버에서 검증 후
        //        MIS 서버 → biometric-auth-server PUT /api/device/unlock 호출, 앱은 unlock 완료 여부만 폴링 또는 콜백으로 확인
        pendingDeviceIdForLocked = deviceId;
        pendingUserIdForLocked = userId;
        subtitleText.setVisibility(View.VISIBLE);
        subtitleText.setText(R.string.main_locked_message);
        lockedUserIdEdit.setVisibility(View.VISIBLE);
        lockedUserIdEdit.setText(userId != null ? userId : "");
        lockedUserIdEdit.setEnabled(false);
        lockedUserIdEdit.setFocusable(false);
        passwordEdit.setVisibility(View.VISIBLE);
        passwordEdit.setText("");
        loginButton.setVisibility(View.VISIBLE);
        loginButton.setText(R.string.main_button_login);
        loginButton.setEnabled(true);
        loginButton.setOnClickListener(v -> onLockedLoginClick());
    }

    private void onLockedLoginClick() {
        String deviceId = pendingDeviceIdForLocked;
        String userId = pendingUserIdForLocked;
        if (deviceId == null || userId == null) {
            showError("내부 오류: 단말 정보가 없습니다.");
            return;
        }
        loginButton.setEnabled(false);
        BiometricApplication.getExecutor().submit(
                () -> {
                    try {
                        authApiClient.unlockDevice(deviceId);
                        tokenStorage.saveRegistration(deviceId, userId);
                        runOnUiThread(
                                () -> {
                                    Toast.makeText(
                                                    this,
                                                    R.string.main_unlock_success,
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                    pendingNavigation = () -> {
                                        if (!isFinishing()) {
                                            startActivity(new Intent(
                                                    MainActivity.this, LoginActivity.class));
                                            finish();
                                        }
                                    };
                                    mainHandler.postDelayed(
                                            pendingNavigation,
                                            BiometricLibConstants.UI_REDIRECT_DELAY_LONG_MS);
                                });
                    } catch (DeviceNotFoundException e) {
                        runOnUiThread(
                                () -> {
                                    loginButton.setEnabled(true);
                                    showError("기기를 찾을 수 없습니다.");
                                });
                    } catch (Exception e) {
                        String msg =
                                e.getMessage() != null
                                        ? e.getMessage()
                                        : e.getClass().getSimpleName();
                        runOnUiThread(
                                () -> {
                                    loginButton.setEnabled(true);
                                    showError(msg);
                                });
                    }
                });
    }

    private void showKeyInvalidatedDialog(String userId, String deviceId) {
        progressBar.setVisibility(View.GONE);
        subtitleText.setVisibility(View.GONE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.main_security_alert_title)
                .setMessage(R.string.main_key_invalidated_message)
                .setPositiveButton(
                        R.string.main_new_user_register,
                        (d, w) ->
                                BiometricApplication.getExecutor().submit(
                                        () -> {
                                            // TODO: [실서비스] 보안 이벤트 서버 로그 기록, 관리자 알림 연동 검토,
                                            //        기존 USER_ID 접근 권한 해제 처리 검토
                                            try {
                                                BiometricApplication.getEcKeyManager().deleteKeyPair();
                                                tokenStorage.clearRegistration();
                                                runOnUiThread(
                                                        () ->
                                                                showKeyInvalidatedRegisterUi(
                                                                        deviceId, userId));
                                            } catch (Exception e) {
                                                String msg =
                                                        e.getMessage() != null
                                                                ? e.getMessage()
                                                                : e.getClass()
                                                                        .getSimpleName();
                                                runOnUiThread(() -> showError(msg));
                                            }
                                        }))
                .setNegativeButton(
                        android.R.string.cancel,
                        (d, w) ->
                                new AlertDialog.Builder(this)
                                        .setMessage(R.string.main_must_reregister_exit)
                                        .setPositiveButton(
                                                android.R.string.ok,
                                                (d2, w2) -> finishAffinity())
                                        .show())
                .setCancelable(false)
                .show();
    }

    @SuppressWarnings("unused")
    private void showKeyInvalidatedRegisterUi(String deviceId, String userId) {
        Intent intent = new Intent(this, RegisterActivity.class);
        intent.putExtra("device_id", deviceId);
        intent.putExtra("button_label", "사용자 변경");
        startActivity(intent);
        finish();
    }

    // TODO: [리팩터링] showUserChangeDialog 로직이 LoginActivity와 유사함.
    //  참조하는 View 필드가 달라 즉시 추출은 어려움 — 실서비스 전환 시 공통 BaseActivity 또는
    //  UserChangeDialogHelper 클래스 추출 검토.
    private void showUserChangeDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.user_change_dialog_title))
                .setMessage(getString(R.string.user_change_dialog_message))
                .setPositiveButton("확인", (dialog, which) -> {
                    Log.d(TAG, "담당자 변경 확인 → 기기 자격증명 인증 시작");
                    userChangeHandler.verifyDeviceCredential(
                            this,
                            new UserChangeHandler.UserChangeCallback() {

                                @Override
                                public void onVerified() {
                                    progressBar.setVisibility(View.VISIBLE);
                                    btnUserChange.setEnabled(false);
                                    userChangeHandler.executeChange(MainActivity.this, this);
                                }

                                @Override
                                public void onChangeCompleted() {
                                    Log.d(TAG, "사용자 변경 완료 → 등록 화면 이동");
                                    progressBar.setVisibility(View.GONE);
                                    Toast.makeText(
                                            MainActivity.this,
                                            getString(R.string.user_change_completed),
                                            Toast.LENGTH_SHORT).show();
                                    pendingNavigation = () -> {
                                        if (!isFinishing()) {
                                            Intent intent = new Intent(
                                                    MainActivity.this, RegisterActivity.class);
                                            intent.putExtra("button_label", "신규 사용자 등록");
                                            startActivity(intent);
                                            finish();
                                        }
                                    };
                                    mainHandler.postDelayed(
                                            pendingNavigation,
                                            BiometricLibConstants.UI_REDIRECT_DELAY_MS);
                                }

                                @Override
                                public void onChangeFailed(ErrorCode errorCode) {
                                    progressBar.setVisibility(View.GONE);
                                    btnUserChange.setEnabled(true);
                                    Log.e(TAG, "사용자 변경 실패: " + errorCode);
                                    Toast.makeText(
                                            MainActivity.this,
                                            getString(R.string.contact_helpdesk),
                                            Toast.LENGTH_LONG).show();
                                }

                                @Override
                                public void onCanceled() {
                                    Log.d(TAG, "사용자 변경 취소");
                                    btnUserChange.setEnabled(true);
                                }
                            });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        subtitleText.setVisibility(View.GONE);
        statusMessage.setVisibility(View.VISIBLE);
        statusMessage.setText(message);
    }

    @Override
    protected void onDestroy() {
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
            pendingNavigation = null;
        }
        super.onDestroy();
    }
}
