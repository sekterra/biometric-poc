package com.skcc.biometric.lib.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.skcc.biometric.lib.BiometricLibConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * TokenStorage
 * 액세스 토큰, 리프레시 토큰, 기기 등록 정보를 암호화 저장하는 클래스.
 *
 * <p>저장 방식: {@link EncryptedSharedPreferences} (AES256-GCM 암호화)
 *
 * <p>저장 항목:
 * <ul>
 *   <li>biometric.access_token   — 서버 발급 액세스 토큰 (민감 정보)</li>
 *   <li>biometric.refresh_token  — 서버 발급 리프레시 토큰 (민감 정보)</li>
 *   <li>biometric.registered     — 기기 등록 여부 (boolean)</li>
 *   <li>biometric.device_id      — 기기 고유 ID (ANDROID_ID 등)</li>
 *   <li>biometric.user_id        — 등록된 사용자 ID</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>생성자가 {@link GeneralSecurityException} / {@link IOException}을 던지므로
 *       반드시 try-catch 또는 상위 전파 처리 필요</li>
 *   <li>앱 패키지 단위로 격리되며, 다른 앱에서 접근 불가</li>
 *   <li>TODO: [실서비스] MasterKey 키 회전, 백업 제외(android:allowBackup=false), 앱 잠금 연동 검토</li>
 * </ul>
 */
public class TokenStorage {

    private static final String KEY_ACCESS_TOKEN = "biometric.access_token";
    private static final String KEY_REFRESH_TOKEN = "biometric.refresh_token";
    private static final String KEY_REGISTERED = "biometric.registered";
    private static final String KEY_DEVICE_ID = "biometric.device_id";
    private static final String KEY_USER_ID = "biometric.user_id";

    private final SharedPreferences prefs;

    /**
     * EncryptedSharedPreferences를 초기화합니다.
     *
     * <p>MasterKey.Builder API를 사용하는 이유:
     * 구버전 security-crypto의 MasterKeys.getOrCreate()는 Android 12+ Keystore 변경과 충돌.
     * MasterKey.Builder(AES256_GCM)가 호환성이 더 좋습니다.
     *
     * @param context 애플리케이션 Context (getApplicationContext()로 변환됨)
     * @throws GeneralSecurityException 키 생성 또는 암호화 초기화 실패 시
     * @throws IOException              SharedPreferences 파일 접근 실패 시
     */
    public TokenStorage(Context context) throws GeneralSecurityException, IOException {
        Context appContext = context.getApplicationContext();
        // Android 12+ (Samsung Knox) 호환: MasterKey.Builder API 사용
        // security-crypto:1.0.0의 MasterKeys.getOrCreate()는 Android 12+ Keystore 변경과 충돌
        MasterKey masterKey =
                new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
        try {
            prefs =
                    EncryptedSharedPreferences.create(
                            appContext,
                            BiometricLibConstants.PREFS_NAME,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            Log.d("BIOMETRIC_LIB", "[STORAGE] TokenStorage > init : 초기화 성공");
        } catch (GeneralSecurityException | IOException e) {
            Log.e("BIOMETRIC_LIB", "[STORAGE] TokenStorage > init : 초기화 실패 " + e.getMessage());
            throw e;
        }
    }

    /**
     * 액세스 토큰과 리프레시 토큰을 암호화 저장합니다.
     * 인증 성공(CASE 1) 또는 키 재발급 후 로그인 성공 시 호출됩니다.
     *
     * @param accessToken  서버 발급 액세스 토큰 (민감 정보 — 절대 로그 출력 금지)
     * @param refreshToken 서버 발급 리프레시 토큰 (민감 정보 — 절대 로그 출력 금지)
     */
    public void saveTokens(String accessToken, String refreshToken) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .apply();
        Log.d("BIOMETRIC_LIB", "[STORAGE] TokenStorage > saveTokens : 토큰 저장 완료");
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    /**
     * 기기 등록 정보(기기 ID, 사용자 ID, 등록 완료 플래그)를 암호화 저장합니다.
     * 등록 성공 또는 잠금 해제 완료 시 호출됩니다.
     *
     * @param deviceId 기기 고유 ID (Settings.Secure.ANDROID_ID 등)
     * @param userId   등록된 사용자 ID
     */
    public void saveRegistration(String deviceId, String userId) {
        prefs.edit()
                .putBoolean(KEY_REGISTERED, true)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_USER_ID, userId)
                .apply();
        Log.d("BIOMETRIC_LIB", "[STORAGE] TokenStorage > saveRegistration : 등록 정보 저장 완료");
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

    /** 액세스 토큰·리프레시 토큰만 삭제합니다 (기기 등록 정보는 유지). */
    public void clearTokens() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply();
    }

    /**
     * 기기 등록 정보(기기 ID, 사용자 ID, 등록 플래그)를 삭제합니다.
     * 서버에서 기기 미등록(404) 응답 시 로컬을 동기화하기 위해 호출됩니다.
     * 토큰은 유지됩니다.
     */
    public void clearRegistration() {
        prefs.edit()
                .remove(KEY_REGISTERED)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_USER_ID)
                .apply();
    }

    /**
     * 토큰·등록 정보를 포함한 모든 저장 데이터를 삭제합니다.
     * 사용자 변경(CASE 12) 완료 시 호출됩니다.
     */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
