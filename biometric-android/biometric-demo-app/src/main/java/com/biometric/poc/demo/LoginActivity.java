package com.biometric.poc.demo;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.provider.Settings;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;

import com.biometric.poc.demo.util.BiometricSettingsNavigator;
import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.auth.BiometricAuthManager;
import com.biometric.poc.lib.auth.UserChangeHandler;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.policy.FailurePolicyManager;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private TokenStorage tokenStorage;
    private AuthApiClient authApiClient;
    private BiometricAuthManager biometricAuthManager;
    private BiometricAuthManager.AuthCallback authCallback;
    private MaterialButton buttonBiometricLogin;
    private MaterialButton buttonIdPwLogin;
    private TextView textLockNotice;
    private TextView textFailureCount;
    private TextView tvStatus;
    private ProgressBar progressLogin;
    private View loginRoot;
    // CASE9: ACCOUNT_LOCKED 시 표시되는 ID/PW 입력 영역
    private LinearLayout layoutIdPw;
    private EditText etUserId;
    private EditText etPassword;
    private MaterialButton btnIdPwLogin;
    // CASE12: 담당자 변경 텍스트 버튼 (보조 진입점)
    private TextView tvUserChange;
    private UserChangeHandler userChangeHandler;

    /** Activity 전역 Handler — postDelayed 취소를 위해 멤버 변수로 관리. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** onDestroy 시 취소할 지연 화면 전환 Runnable. */
    @Nullable private Runnable pendingNavigation = null;

    @Nullable private CountDownTimer lockCountDownTimer;

    /** CountDownTimer 실행 중 */
    private boolean isCountingDown = false;

    /** onAccountLocked 수신 후 (로그아웃·재등록 등으로 해제 전까지) */
    private boolean isAccountLocked = false;

    private boolean isNotEnrolledDialogShowing = false;

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
        tvStatus = findViewById(R.id.tvStatus);
        progressLogin = findViewById(R.id.progress_login);
        layoutIdPw = findViewById(R.id.layoutIdPw);
        etUserId = findViewById(R.id.etUserId);
        etPassword = findViewById(R.id.etPassword);
        btnIdPwLogin = findViewById(R.id.btnIdPwLogin);

        EcKeyManager ecKeyManager = BiometricApplication.getEcKeyManager();
        authApiClient = BiometricApplication.getAuthApiClient();
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();
        biometricAuthManager =
                new BiometricAuthManager(
                        this,
                        authApiClient,
                        ecKeyManager,
                        tokenStorage,
                        failurePolicyManager,
                        BiometricApplication.getExecutor());

        userChangeHandler = new UserChangeHandler(
                this, ecKeyManager, tokenStorage, authApiClient,
                BiometricApplication.getExecutor());

        tvUserChange = findViewById(R.id.tvUserChange);
        if (tvUserChange != null) {
            tvUserChange.setOnClickListener(v -> showUserChangeDialog());
        }

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
    protected void onResume() {
        super.onResume();

        if (isCountingDown || isAccountLocked) {
            return;
        }

        int canAuth =
                BiometricManager.from(this)
                        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            buttonBiometricLogin.setEnabled(true);
            if (tvStatus != null) {
                tvStatus.setVisibility(View.GONE);
            }
            Log.d(TAG, "onResume: 안면인식 등록 확인됨");
        } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            buttonBiometricLogin.setEnabled(false);
            Log.d(TAG, "onResume: 안면인식 미등록 감지 → 안내 다이얼로그 표시");
            showNotEnrolledDialog();
        }
    }

    private void showNotEnrolledDialog() {
        if (isNotEnrolledDialogShowing) {
            return;
        }
        isNotEnrolledDialogShowing = true;

        new AlertDialog.Builder(this)
                .setTitle("안면인식 미등록")
                .setMessage(
                        "안면인식이 등록되지 않았습니다.\n"
                                + "설정에서 안면인식을 등록 후 이용해주세요.")
                .setPositiveButton(
                        "설정으로 이동",
                        (dialog, which) -> {
                            isNotEnrolledDialogShowing = false;
                            BiometricSettingsNavigator.navigate(LoginActivity.this);
                        })
                .setNegativeButton(
                        "나중에",
                        (dialog, which) -> {
                            isNotEnrolledDialogShowing = false;
                            if (tvStatus != null) {
                                tvStatus.setText("안면인식 등록 후 로그인이 가능합니다.");
                                tvStatus.setVisibility(View.VISIBLE);
                            }
                        })
                .setCancelable(false)
                .setOnDismissListener(dialog -> isNotEnrolledDialogShowing = false)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
            isCountingDown = false;
        }
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
            pendingNavigation = null;
        }
        if (biometricAuthManager != null) {
            biometricAuthManager.shutdown();
        }
        super.onDestroy();
    }

    private void startBiometricAuth() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
            isCountingDown = false;
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

        authCallback = new BiometricAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(
                            String userId, AuthApiClient.TokenResponse tokenResponse) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    if (tvStatus != null) {
                                        tvStatus.setVisibility(View.GONE);
                                    }
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
                                    isAccountLocked = false;
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    buttonIdPwLogin.setEnabled(true);
                                    Toast.makeText(
                                                    LoginActivity.this,
                                                    "서버에 등록 정보가 없습니다. 다시 등록해주세요.",
                                                    Toast.LENGTH_LONG)
                                            .show();
                                    pendingNavigation = () -> {
                                        if (!isFinishing()) {
                                            startActivity(new Intent(
                                                    LoginActivity.this, RegisterActivity.class));
                                            finish();
                                        }
                                    };
                                    mainHandler.postDelayed(
                                            pendingNavigation,
                                            BiometricLibConstants.UI_REDIRECT_DELAY_MS);
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
                                        isCountingDown = false;
                                    }
                                    isCountingDown = true;
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
                                                    isCountingDown = false;
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
                                    Log.w(TAG, "ACCOUNT_LOCKED 감지");
                                    isAccountLocked = true;
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(false);
                                    if (tvStatus != null) {
                                        tvStatus.setText(
                                                getString(R.string.error_account_locked));
                                        tvStatus.setTextColor(Color.RED);
                                        tvStatus.setVisibility(View.VISIBLE);
                                    }
                                    showIdPwLoginArea();
                                });
                    }

                    @Override
                    public void onSessionRetrying(int retryCount, int maxRetry) {
                        runOnUiThread(
                                () -> {
                                    if (tvStatus != null) {
                                        tvStatus.setText(
                                                getString(
                                                        R.string.error_session_retry,
                                                        retryCount, maxRetry));
                                        tvStatus.setTextColor(
                                                ContextCompat.getColor(
                                                        LoginActivity.this,
                                                        android.R.color.holo_orange_dark));
                                        tvStatus.setVisibility(View.VISIBLE);
                                    }
                                    progressLogin.setVisibility(View.VISIBLE);
                                    buttonBiometricLogin.setEnabled(false);
                                    Log.d(TAG, "onSessionRetrying: "
                                            + retryCount + "/" + maxRetry + " 표시");
                                });
                    }

                    @Override
                    public void onError(ErrorCode errorCode) {
                        runOnUiThread(
                                () -> {
                                    progressLogin.setVisibility(View.GONE);
                                    buttonBiometricLogin.setEnabled(true);
                                    // 재시도 중 표시된 주황색을 기본 빨간색으로 복원
                                    if (tvStatus != null) {
                                        tvStatus.setTextColor(Color.RED);
                                    }

                                    switch (errorCode) {
                                        case BIOMETRIC_NONE_ENROLLED:
                                            buttonBiometricLogin.setEnabled(false);
                                            showNotEnrolledDialog();
                                            break;

                                        case BIOMETRIC_HW_UNAVAILABLE:
                                            showErrorSnackbar(getString(R.string.error_biometric_hw_unavailable));
                                            break;

                                        case SESSION_EXPIRED:
                                            // CASE3 — 자동 재시도 소진 후 최종 안내
                                            if (tvStatus != null) {
                                                tvStatus.setVisibility(View.GONE);
                                            }
                                            showErrorSnackbar(getString(R.string.error_session_expired));
                                            break;

                                        case TIMESTAMP_OUT_OF_RANGE:
                                            // CASE4 — 시간 설정 안내 다이얼로그
                                            Log.w(TAG, "TIMESTAMP_OUT_OF_RANGE 감지 → 시간 설정 안내");
                                            showTimestampErrorDialog();
                                            break;

                                        case NONCE_REPLAY:
                                            // CASE5 — 안내 후 버튼 재활성화 (새로 누르면 새 nonce 생성)
                                            Log.w(TAG, "NONCE_REPLAY 감지");
                                            showErrorSnackbar(getString(R.string.error_nonce_replay));
                                            buttonBiometricLogin.setEnabled(true);
                                            break;

                                        case INVALID_SIGNATURE:
                                            // CASE6 — 3회 미만: 재시도 안내, 3회 이상은 BiometricAuthManager에서 자동 키 재발급
                                            showErrorSnackbar(getString(R.string.error_invalid_signature));
                                            break;

                                        case MISSING_SIGNATURE:
                                            // CASE7 — 앱 재설치 안내 다이얼로그
                                            Log.e(TAG, "MISSING_SIGNATURE 감지 → 앱 내부 오류");
                                            showMissingSignatureDialog();
                                            break;

                                        case DEVICE_NOT_FOUND:
                                            // CASE8 — 기기 미등록 안내 → RegisterActivity 유도
                                            Log.w(TAG, "DEVICE_NOT_FOUND 감지 → 기기 등록 프로세스 유도");
                                            showDeviceNotFoundDialog();
                                            break;

                                        case KEY_INVALIDATED:
                                            // CASE10 — 키 재발급 자동 처리 안내 다이얼로그
                                            showKeyInvalidatedDialog();
                                            break;

                                        case KEY_NOT_FOUND:
                                            showErrorSnackbar(getString(R.string.error_key_not_found));
                                            break;

                                        case NETWORK_ERROR:
                                            showErrorSnackbar(getString(R.string.error_network));
                                            break;

                                        default:
                                            showErrorSnackbar(getString(R.string.error_unknown));
                                            break;
                                    }
                                });
                    }
                };
        biometricAuthManager.authenticate(this, authCallback);
    }

    private void showKeyInvalidatedDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("보안키 재설정 필요")
                .setMessage(getString(R.string.error_key_invalidated))
                .setPositiveButton(
                        "확인",
                        (dialog, which) -> {
                            if (tvStatus != null) {
                                tvStatus.setText(getString(R.string.key_renewal_in_progress));
                                tvStatus.setVisibility(View.VISIBLE);
                            }
                            progressLogin.setVisibility(View.VISIBLE);
                            buttonBiometricLogin.setEnabled(false);
                            // startBiometricAuth() 대신 startRenewal() 직접 호출
                            // (challenge 재요청 없이 키 재발급 → BiometricPrompt 재실행)
                            biometricAuthManager.startRenewal(LoginActivity.this, authCallback);
                        })
                .setNegativeButton("취소", null)
                .setCancelable(false)
                .show();
    }

    private void showDeviceNotFoundDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("기기 미등록")
                .setMessage(getString(R.string.error_device_not_found))
                .setPositiveButton(
                        "확인",
                        (dialog, which) -> {
                            Log.d(TAG, "DEVICE_NOT_FOUND → 등록 화면 이동");
                            tokenStorage.clearRegistration();
                            // tokenStorage에 저장된 값 우선 사용, 없으면 ANDROID_ID 사용
                            String deviceId = tokenStorage.getDeviceId();
                            if (deviceId == null || deviceId.isEmpty()) {
                                deviceId = android.provider.Settings.Secure.getString(
                                        getContentResolver(),
                                        android.provider.Settings.Secure.ANDROID_ID);
                                Log.d(TAG, "device_id: tokenStorage 없음 → ANDROID_ID 사용");
                            } else {
                                // TODO: [실서비스] device_id 마스킹 처리 필요
                                Log.d(TAG, "device_id: tokenStorage 값 사용 = " + deviceId);
                            }
                            Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
                            intent.putExtra("device_id", deviceId);
                            intent.putExtra("button_label", "사용자ID 및 기기 등록");
                            startActivity(intent);
                            finish();
                        })
                .setNegativeButton(
                        "헬프데스크 문의",
                        (dialog, which) ->
                                // TODO: [실서비스] 실제 헬프데스크 연락처로 교체
                                Toast.makeText(
                                                this,
                                                getString(R.string.contact_helpdesk),
                                                Toast.LENGTH_LONG)
                                        .show())
                .setCancelable(false)
                .show();
    }

    private void showIdPwLoginArea() {
        if (layoutIdPw != null) {
            layoutIdPw.setVisibility(View.VISIBLE);
        }
        if (btnIdPwLogin != null) {
            btnIdPwLogin.setVisibility(View.VISIBLE);
            btnIdPwLogin.setOnClickListener(v -> {
                String inputId = etUserId != null
                        ? etUserId.getText().toString().trim() : "";
                String inputPw = etPassword != null
                        ? etPassword.getText().toString() : "";

                if (inputId.isEmpty() || inputPw.isEmpty()) {
                    Toast.makeText(this, "ID와 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }

                // PoC: 입력값 검증 없이 unlock 처리
                // TODO: [실서비스] MIS 인증 서버에서 ID/PW 검증 후 unlock 신호 전송
                Log.d(TAG, "ID/PW 입력 완료 → unlock 요청");
                progressLogin.setVisibility(View.VISIBLE);
                btnIdPwLogin.setEnabled(false);

                String deviceId = tokenStorage.getDeviceId();

                BiometricApplication.getExecutor().submit(() -> {
                    try {
                        authApiClient.unlockDevice(deviceId);
                        Log.d(TAG, "unlock 성공");

                        tokenStorage.saveRegistration(deviceId, tokenStorage.getUserId());

                        runOnUiThread(() -> {
                            if (layoutIdPw != null) {
                                layoutIdPw.setVisibility(View.GONE);
                            }
                            if (btnIdPwLogin != null) {
                                btnIdPwLogin.setVisibility(View.GONE);
                            }
                            progressLogin.setVisibility(View.GONE);
                            isAccountLocked = false;
                            if (tvStatus != null) {
                                tvStatus.setText(getString(R.string.unlock_success));
                                tvStatus.setTextColor(
                                        ContextCompat.getColor(
                                                LoginActivity.this,
                                                android.R.color.holo_green_dark));
                                tvStatus.setVisibility(View.VISIBLE);
                            }
                            buttonBiometricLogin.setEnabled(true);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "unlock 실패", e);
                        runOnUiThread(() -> {
                            progressLogin.setVisibility(View.GONE);
                            if (btnIdPwLogin != null) {
                                btnIdPwLogin.setEnabled(true);
                            }
                            Toast.makeText(
                                            LoginActivity.this,
                                            getString(R.string.contact_helpdesk),
                                            Toast.LENGTH_LONG)
                                    .show();
                        });
                    }
                });
            });
        }
    }

    private void showTimestampErrorDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("기기 시간 확인 필요")
                .setMessage(getString(R.string.error_timestamp_out_of_range))
                .setPositiveButton(
                        "시간 설정으로 이동",
                        (dialog, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
                                Log.d(TAG, "날짜 및 시간 설정 화면으로 이동");
                            } catch (ActivityNotFoundException e) {
                                Log.w(TAG, "DATE_SETTINGS 미지원 → 일반 설정으로 이동");
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        })
                .setNegativeButton("닫기", null)
                .setCancelable(false)
                .show();
    }

    private void showMissingSignatureDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("로그인 오류")
                .setMessage(getString(R.string.error_missing_signature))
                .setPositiveButton(
                        "앱 재시작",
                        (dialog, which) -> {
                            Log.d(TAG, "앱 재시작 실행");
                            Intent intent =
                                    getPackageManager().getLaunchIntentForPackage(getPackageName());
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(intent);
                            }
                            finishAffinity();
                        })
                .setNegativeButton("닫기", null)
                .setCancelable(false)
                .show();
    }

    // TODO: [리팩터링] showUserChangeDialog 로직이 MainActivity와 유사함.
    //  참조하는 View 필드가 달라 즉시 추출은 어려움 — 실서비스 전환 시 공통 BaseActivity 또는
    //  UserChangeDialogHelper 클래스 추출 검토.
    private void showUserChangeDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.user_change_dialog_title))
                .setMessage(getString(R.string.user_change_dialog_message))
                .setPositiveButton("확인", (dialog, which) ->
                        userChangeHandler.verifyDeviceCredential(
                                this,
                                new UserChangeHandler.UserChangeCallback() {

                                    @Override
                                    public void onVerified() {
                                        progressLogin.setVisibility(View.VISIBLE);
                                        if (tvUserChange != null) {
                                            tvUserChange.setEnabled(false);
                                        }
                                        userChangeHandler.executeChange(LoginActivity.this, this);
                                    }

                                    @Override
                                    public void onChangeCompleted() {
                                        progressLogin.setVisibility(View.GONE);
                                        Toast.makeText(
                                                LoginActivity.this,
                                                getString(R.string.user_change_completed),
                                                Toast.LENGTH_SHORT).show();
                                        pendingNavigation = () -> {
                                            if (!isFinishing()) {
                                                Intent intent = new Intent(
                                                        LoginActivity.this, RegisterActivity.class);
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
                                        progressLogin.setVisibility(View.GONE);
                                        if (tvUserChange != null) {
                                            tvUserChange.setEnabled(true);
                                        }
                                        Toast.makeText(
                                                LoginActivity.this,
                                                getString(R.string.contact_helpdesk),
                                                Toast.LENGTH_LONG).show();
                                    }

                                    @Override
                                    public void onCanceled() {
                                        if (tvUserChange != null) {
                                            tvUserChange.setEnabled(true);
                                        }
                                    }
                                }))
                .setNegativeButton("취소", null)
                .show();
    }

    private void showErrorSnackbar(String message) {
        Snackbar.make(loginRoot, message, Snackbar.LENGTH_LONG).show();
    }
}
