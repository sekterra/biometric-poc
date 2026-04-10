package com.skcc.biometric.lib.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.skcc.biometric.lib.BiometricLibConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 토큰·등록 정보 저장. EncryptedSharedPreferences 사용.
 *
 * <p>TODO: [실서비스] 키 회전(MasterKey 재생성)·백업 제외 옵션·앱 잠금 연동 검토.
 */
public class TokenStorage {

    private static final String KEY_ACCESS_TOKEN = "biometric.access_token";
    private static final String KEY_REFRESH_TOKEN = "biometric.refresh_token";
    private static final String KEY_REGISTERED = "biometric.registered";
    private static final String KEY_DEVICE_ID = "biometric.device_id";
    private static final String KEY_USER_ID = "biometric.user_id";

    private final SharedPreferences prefs;

    public TokenStorage(Context context) throws GeneralSecurityException, IOException {
        Context appContext = context.getApplicationContext();
        // Android 12+ (Samsung Knox) 호환: MasterKey.Builder API 사용
        // security-crypto:1.0.0의 MasterKeys.getOrCreate()는 Android 12+ Keystore 변경과 충돌
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
