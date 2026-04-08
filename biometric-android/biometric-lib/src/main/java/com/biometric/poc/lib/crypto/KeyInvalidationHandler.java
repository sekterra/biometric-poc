package com.biometric.poc.lib.crypto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.storage.TokenStorage;

import java.util.concurrent.ExecutorService;

public class KeyInvalidationHandler {

    @SuppressWarnings("unused")
    private final Context context;
    private final EcKeyManager ecKeyManager;
    private final TokenStorage tokenStorage;
    private final AuthApiClient authApiClient;
    private final ExecutorService ioExecutor;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public KeyInvalidationHandler(
            Context context,
            EcKeyManager ecKeyManager,
            TokenStorage tokenStorage,
            AuthApiClient authApiClient,
            ExecutorService ioExecutor) {
        this.context = context.getApplicationContext();
        this.ecKeyManager = ecKeyManager;
        this.tokenStorage = tokenStorage;
        this.authApiClient = authApiClient;
        this.ioExecutor = ioExecutor;
    }

    public void handleInvalidation(String deviceId, KeyInvalidationCallback callback) {
        try {
            ecKeyManager.deleteKeyPair();
        } catch (Exception e) {
            runOnUiThread(() -> callback.onError(errorMessage(e)));
            return;
        }

        try {
            tokenStorage.clearRegistration();
        } catch (Exception e) {
            runOnUiThread(() -> callback.onError(errorMessage(e)));
            return;
        }

        ioExecutor.submit(() -> {
            try {
                if (!authApiClient.updateKeyStatus(deviceId)) {
                    runOnUiThread(
                            () -> callback.onError("updateKeyStatus failed (device not found)"));
                    return;
                }
                runOnUiThread(callback::onInvalidated);
            } catch (Exception e) {
                String msg = errorMessage(e);
                runOnUiThread(() -> callback.onError(msg));
            }
        });
    }

    private void runOnUiThread(Runnable action) {
        mainHandler.post(action);
    }

    private static String errorMessage(Throwable e) {
        String m = e.getMessage();
        if (m != null && !m.isEmpty()) {
            return m;
        }
        return e.getClass().getSimpleName();
    }
}
