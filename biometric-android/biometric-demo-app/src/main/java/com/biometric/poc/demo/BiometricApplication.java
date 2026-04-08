package com.biometric.poc.demo;

import android.app.Application;
import android.util.Log;

import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 앱 전역 싱글턴 리소스 관리.
 *
 * <ul>
 *   <li>{@link AuthApiClient} — OkHttpClient 포함, Activity마다 재생성하면 불필요한 리소스 낭비.
 *   <li>{@link ExecutorService} — 스레드풀을 공유해 전체 스레드 수 상한 제어.
 * </ul>
 *
 * <p>사용: {@code BiometricApplication.getAuthApiClient()}, {@code BiometricApplication.getExecutor()}
 *
 * <p>TODO: [실서비스] Hilt/Dagger 등 DI 프레임워크로 교체 검토.
 */
public class BiometricApplication extends Application {

    private static final String TAG = "BiometricApplication";

    private static AuthApiClient authApiClient;
    private static ExecutorService executor;
    private static EcKeyManager ecKeyManager;

    @Override
    public void onCreate() {
        super.onCreate();
        // TODO: [실서비스] 앱 부하에 맞게 스레드 수 조정
        executor = Executors.newFixedThreadPool(
                4,
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("biometric-poc-io");
                    return t;
                });
        authApiClient = new AuthApiClient(BuildConfig.SERVER_URL);
        // 앱 패키지명 기반 키 별칭 — 다른 앱의 Keystore 항목과 충돌 방지
        ecKeyManager = new EcKeyManager(BuildConfig.APPLICATION_ID + ".biometric_ec_key");
        Log.d(TAG, "BiometricApplication 초기화 완료 (SERVER_URL=" + BuildConfig.SERVER_URL + ")");
    }

    /** 앱 전역 공유 {@link AuthApiClient} 인스턴스. */
    public static AuthApiClient getAuthApiClient() {
        return authApiClient;
    }

    /**
     * 앱 전역 공유 {@link EcKeyManager} 인스턴스.
     * 키 별칭은 {@code BuildConfig.APPLICATION_ID + ".biometric_ec_key"} 로 고정.
     */
    public static EcKeyManager getEcKeyManager() {
        return ecKeyManager;
    }

    /**
     * 앱 전역 공유 {@link ExecutorService}.
     * Activity.onDestroy()에서 shutdown() 호출 금지 — Application 생명주기와 함께 종료됨.
     */
    public static ExecutorService getExecutor() {
        return executor;
    }
}
