package com.biometric.poc.lib.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.biometric.poc.lib.BiometricLibConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 토큰·등록 정보 저장. EncryptedSharedPreferences 사용.
 *
 * <p>TODO: [실서비스] 키 회전(MasterKey 재생성)·백업 제외 옵션·앱 잠금 연동 검토.
 */
public class TokenStorage {

    public static final String KEY_ACCESS_TOKEN = "access_token";
    public static final String KEY_REFRESH_TOKEN = "refresh_token";
    public static final String KEY_REGISTERED = "registered";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_USER_ID = "user_id";

    private final SharedPreferences prefs;

    public TokenStorage(Context context) throws GeneralSecurityException, IOException {
        Context appContext = context.getApplicationContext();
        MasterKey masterKey =
                new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
        prefs =
                EncryptedSharedPreferences.create(
                        appContext,
                        BiometricLibConstants.PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public void saveTokens(String accessToken, String refreshToken) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public void saveRegistration(String deviceId, String userId) {
        prefs.edit()
                .putBoolean(KEY_REGISTERED, true)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_USER_ID, userId)
                .apply();
    }

    public boolean isRegistered() {
        return prefs.getBoolean(KEY_REGISTERED, false);
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, null);
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }

    public void clearTokens() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply();
    }

    public void clearRegistration() {
        prefs.edit()
                .remove(KEY_REGISTERED)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_USER_ID)
                .apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
