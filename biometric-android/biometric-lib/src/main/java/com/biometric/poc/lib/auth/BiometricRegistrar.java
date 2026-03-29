package com.biometric.poc.lib.auth;

import android.content.Context;
import android.provider.Settings;

import androidx.biometric.BiometricManager;
import androidx.fragment.app.FragmentActivity;

import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.storage.TokenStorage;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BiometricRegistrar {

    private final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("biometric-poc-register");
                        return t;
                    });

    private final Context context;
    private final AuthApiClient authApiClient;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;

    public BiometricRegistrar(
            Context context,
            AuthApiClient authApiClient,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage) {
        this.context = context.getApplicationContext();
        this.authApiClient = authApiClient;
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
    }

    public void register(FragmentActivity activity, RegisterCallback callback) {
        BiometricManager biometricManager = BiometricManager.from(context);
        int canAuth =
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            activity.runOnUiThread(callback::onNotEnrolled);
            return;
        }

        try {
            if (!ecKeyManager.isKeyGenerated()) {
                ecKeyManager.generateKeyPair();
            }
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            activity.runOnUiThread(() -> callback.onError("키 생성 실패: " + detail));
            return;
        }

        ioExecutor.submit(() -> runRegisterNetwork(activity, callback));
    }

    private void runRegisterNetwork(FragmentActivity activity, RegisterCallback callback) {
        try {
            String deviceId =
                    Settings.Secure.getString(
                            context.getContentResolver(), Settings.Secure.ANDROID_ID);
            String userId = authApiClient.getUserId(deviceId);
            String publicKeyBase64 = ecKeyManager.getPublicKeyBase64();
            String enrolledAt = Instant.now().toString();
            boolean registered =
                    authApiClient.registerDevice(
                            deviceId, userId, publicKeyBase64, enrolledAt);
            if (!registered) {
                activity.runOnUiThread(
                        () -> callback.onError("기기 등록에 실패했습니다 (이미 등록됨)"));
                return;
            }
            tokenStorage.saveRegistration(deviceId, userId);
            activity.runOnUiThread(() -> callback.onSuccess(userId));
        } catch (Exception e) {
            String msg = e.getMessage();
            activity.runOnUiThread(
                    () ->
                            callback.onError(
                                    msg != null && !msg.isEmpty()
                                            ? msg
                                            : e.getClass().getSimpleName()));
        }
    }

    public interface RegisterCallback {

        void onSuccess(String userId);

        void onNotEnrolled();

        void onError(String message);
    }
}
