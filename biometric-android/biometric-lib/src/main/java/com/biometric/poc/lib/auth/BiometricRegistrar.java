package com.biometric.poc.lib.auth;

import android.content.Context;
import android.util.Log;

import androidx.biometric.BiometricManager;
import androidx.fragment.app.FragmentActivity;

import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.storage.TokenStorage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

public class BiometricRegistrar {

    private static final String TAG = "BiometricRegistrar";

    /** BiometricManager 인스턴스 재사용 — canAuthenticate() 호출마다 생성 방지. */
    private final BiometricManager biometricManager;

    /** 외부(BiometricApplication)에서 주입받은 공유 executor — 직접 소유하지 않음. */
    private final ExecutorService ioExecutor;

    private final Context context;
    private final AuthApiClient authApiClient;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;

    public BiometricRegistrar(
            Context context,
            AuthApiClient authApiClient,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage,
            ExecutorService ioExecutor) {
        this.context = context.getApplicationContext();
        this.authApiClient = authApiClient;
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
        this.ioExecutor = ioExecutor;
        this.biometricManager = BiometricManager.from(this.context);
    }

    public void register(
            FragmentActivity activity,
            String deviceId,
            String userId,
            RegisterCallback callback) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            activity.runOnUiThread(() -> callback.onError(ErrorCode.UNKNOWN_ERROR));
            return;
        }
        if (userId == null || userId.trim().isEmpty()) {
            activity.runOnUiThread(() -> callback.onError(ErrorCode.UNKNOWN_ERROR));
            return;
        }
        final String uid = userId.trim();
        final String did = deviceId.trim();

        int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            activity.runOnUiThread(() -> callback.onError(ErrorCode.BIOMETRIC_NONE_ENROLLED));
            return;
        }
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            activity.runOnUiThread(() -> callback.onError(ErrorCode.BIOMETRIC_HW_UNAVAILABLE));
            return;
        }

        try {
            if (!ecKeyManager.isKeyGenerated()) {
                ecKeyManager.generateKeyPair();
            }
        } catch (Exception e) {
            Log.e(TAG, "키 생성 실패", e);
            activity.runOnUiThread(() -> callback.onError(ErrorCode.UNKNOWN_ERROR));
            return;
        }

        ioExecutor.submit(() -> runRegisterNetwork(activity, did, uid, callback));
    }

    private void runRegisterNetwork(
            FragmentActivity activity, String deviceId, String userId, RegisterCallback callback) {
        try {
            String publicKeyBase64 = ecKeyManager.getPublicKeyBase64();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String enrolledAt = sdf.format(new Date());
            boolean registered =
                    authApiClient.registerDevice(
                            deviceId, userId, publicKeyBase64, enrolledAt);
            if (!registered) {
                activity.runOnUiThread(() -> callback.onError(ErrorCode.ALREADY_REGISTERED));
                return;
            }
            tokenStorage.saveRegistration(deviceId, userId);
            activity.runOnUiThread(() -> callback.onSuccess(userId));
        } catch (Exception e) {
            Log.e(TAG, "기기 등록 실패", e);
            activity.runOnUiThread(() -> callback.onError(ErrorCode.NETWORK_ERROR));
        }
    }

    /**
     * executor는 BiometricApplication이 관리하므로 Activity에서 직접 종료하지 않음.
     * 앱 프로세스 종료 시 OS가 정리.
     */
    public void shutdown() {
        Log.d(TAG, "shutdown() 호출 — executor는 BiometricApplication이 관리");
    }

    public interface RegisterCallback {

        void onSuccess(String userId);

        /** onNotEnrolled() 제거 — onError(ErrorCode.BIOMETRIC_NONE_ENROLLED)로 통합 */
        void onError(ErrorCode errorCode);
    }
}
