package com.skens.nsms.biometric.bridge;

import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.auth.BiometricAuthManager;
import com.skcc.biometric.lib.auth.BiometricRegistrar;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.policy.FailurePolicyManager;
import com.skcc.biometric.lib.storage.TokenStorage;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BiometricBridge
 * biometric-lib(AAR)와 A2 앱(또는 AndroidBridge)을 연결하는 퍼사드(Facade) 클래스.
 *
 * <p>A2 앱은 이 클래스와 {@link BiometricBridgeCallback}만 사용합니다.
 * biometric-lib의 내부 클래스(BiometricAuthManager, ErrorCode 등)를
 * 직접 import하지 않아도 됩니다.
 *
 * <p>제공 기능:
 * <ul>
 *   <li>startLogin()      — 안면인식 로그인 (CASE 1~11)</li>
 *   <li>startUserChange() — 사용자 변경 (CASE 12)</li>
 *   <li>startRegister()   — 신규 기기 등록</li>
 *   <li>unlockWithIdPw()  — 계정 잠금 해제 (CASE 9 후속 처리)</li>
 * </ul>
 *
 * <p>내부 초기화 순서 (생성자):
 * <ol>
 *   <li>IO 스레드풀 생성 (4스레드 데몬)</li>
 *   <li>AuthApiClient 생성 (OkHttp 공유 클라이언트)</li>
 *   <li>TokenStorage 초기화 (EncryptedSharedPreferences)</li>
 *   <li>EcKeyManager 초기화 (키 별칭: packageName.biometric_ec_key)</li>
 *   <li>FailurePolicyManager, BiometricAuthManager, BiometricRegistrar, UserChangeHandler 생성</li>
 * </ol>
 *
 * <p>주의사항:
 * <ul>
 *   <li>Activity 생명주기에 맞게 인스턴스를 관리해야 함 (onCreate에서 생성 권장)</li>
 *   <li>모든 BiometricBridgeCallback 메서드는 UI 스레드에서 호출됨</li>
 *   <li>TokenStorage 초기화 실패 시 RuntimeException을 던짐 — 앱 시작 시 예외 처리 필요</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * BiometricBridge bridge = new BiometricBridge(activity, BuildConfig.SERVER_URL);
 * bridge.startLogin(new BiometricBridgeCallback() {
 *     public void onLoginSuccess(String userId, String token, int expiresIn) { ... }
 *     public void onError(String errorCode) { ... }
 *     // 나머지 콜백 구현
 * });
 * }</pre>
 */
public class BiometricBridge {

    private static final String TAG = "BiometricBridge";

    /** 인증 전체 플로우 오케스트레이터 */
    private final BiometricAuthManager biometricAuthManager;

    /** CASE 12 사용자 변경 플로우 */
    private final UserChangeHandler userChangeHandler;

    /** 기기 등록 플로우 */
    private final BiometricRegistrar biometricRegistrar;

    /** 토큰 및 등록 정보 저장소 */
    private final TokenStorage tokenStorage;

    /** B2 인증 서버 API 클라이언트 */
    private final AuthApiClient authApiClient;

    /** IO 작업용 스레드풀 — BiometricBridge 인스턴스가 소유 */
    private final ExecutorService executor;

    /** BiometricPrompt를 표시할 Activity — 생명주기 주의 */
    private final FragmentActivity activity;

    /**
     * BiometricBridge를 초기화합니다.
     *
     * <p>내부적으로 AuthApiClient, TokenStorage, EcKeyManager, BiometricAuthManager,
     * UserChangeHandler, BiometricRegistrar를 생성하고 의존성을 주입합니다.
     *
     * @param activity  BiometricPrompt를 표시할 FragmentActivity (AppCompatActivity도 가능)
     * @param serverUrl B2 인증 서버 주소 (예: "https://auth.example.com")
     * @throws RuntimeException TokenStorage 초기화 실패 시 (GeneralSecurityException/IOException 래핑)
     */
    public BiometricBridge(FragmentActivity activity, String serverUrl) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > init : 브릿지 초기화 시작 serverUrl=" + serverUrl);
        this.activity = activity;

        // IO 스레드풀 — 네트워크/암호화 작업 전용
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("biometric-bridge-io");
            return t;
        });

        // B2 인증 서버 클라이언트
        this.authApiClient = new AuthApiClient(serverUrl);

        // 암호화 저장소 — 앱 패키지명 기반 키로 EncryptedSharedPreferences 초기화
        try {
            this.tokenStorage = new TokenStorage(activity);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // EC 키 관리자 — 키 별칭은 호스트 앱 패키지명 기반 (A2 앱과 키 공유 보장)
        // 형식: "<applicationId>.biometric_ec_key"
        String keyAlias = activity.getApplicationContext().getPackageName() + ".biometric_ec_key";
        EcKeyManager ecKeyManager = new EcKeyManager(keyAlias);
        Log.d(TAG, "EcKeyManager 초기화: keyAlias=" + keyAlias);

        // 로컬 실패 정책 관리자 (잠금 시간 등)
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();

        // 인증 오케스트레이터
        this.biometricAuthManager = new BiometricAuthManager(
                activity,
                authApiClient,
                ecKeyManager,
                tokenStorage,
                failurePolicyManager,
                executor);

        // 기기 등록 관리자
        this.biometricRegistrar = new BiometricRegistrar(
                activity,
                authApiClient,
                ecKeyManager,
                tokenStorage,
                executor);

        // 사용자 변경 관리자
        this.userChangeHandler = new UserChangeHandler(
                activity,
                ecKeyManager,
                tokenStorage,
                authApiClient,
                executor);

        Log.d(TAG, "BiometricBridge 초기화 완료 (serverUrl=" + serverUrl + ")");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > init : 브릿지 초기화 완료");
    }

    /**
     * 안면인식 로그인을 시작합니다.
     *
     * <p>BiometricAuthManager를 통해 챌린지 요청 → 서명 → 토큰 발급 플로우를 실행하고,
     * 결과를 BiometricBridgeCallback으로 전달합니다.
     *
     * <p>모든 콜백은 UI 스레드에서 호출됩니다.
     *
     * @param callback 인증 결과 수신 콜백 (원시 타입만 사용 — AAR 타입 미노출)
     *
     * <p>내부 콜백 매핑:
     * <ul>
     *   <li>onSuccess      → onLoginSuccess(userId, accessToken, expiresIn)</li>
     *   <li>onNotRegistered → onNotRegistered()</li>
     *   <li>onLockedOut    → onLockedOut(remainingSeconds)</li>
     *   <li>onRetry        → onRetry(failureCount)</li>
     *   <li>onAccountLocked → onAccountLocked()</li>
     *   <li>onSessionRetrying → onSessionRetrying(retryCount, maxRetry)</li>
     *   <li>onError        → onError(errorCode.name())</li>
     * </ul>
     */
    public void startLogin(BiometricBridgeCallback callback) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > startLogin : 로그인 요청 수신");
        biometricAuthManager.authenticate(activity, new BiometricAuthManager.AuthCallback() {

            @Override
            public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
                Log.d(TAG, "startLogin onSuccess: userId=" + userId);
                Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
                callback.onLoginSuccess(userId, tokenResponse.accessToken, tokenResponse.expiresIn);
            }

            @Override
            public void onNotRegistered() {
                Log.d(TAG, "startLogin onNotRegistered");
                Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onNotRegistered : 미등록 기기");
                callback.onNotRegistered();
            }

            @Override
            public void onLockedOut(int remainingSeconds) {
                Log.d(TAG, "startLogin onLockedOut: " + remainingSeconds + "초");
                Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLockedOut : 일시잠금 remainingSeconds=" + remainingSeconds);
                callback.onLockedOut(remainingSeconds);
            }

            @Override
            public void onRetry(int failureCount) {
                Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onRetry : 재시도 failureCount=" + failureCount);
                callback.onRetry(failureCount);
            }

            @Override
            public void onAccountLocked() {
                Log.w(TAG, "startLogin onAccountLocked");
                Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onAccountLocked : 계정 잠금");
                callback.onAccountLocked();
            }

            @Override
            public void onSessionRetrying(int retryCount, int maxRetry) {
                Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onSessionRetrying : 세션 재시도 " + retryCount + "/" + maxRetry);
                callback.onSessionRetrying(retryCount, maxRetry);
            }

            @Override
            public void onError(ErrorCode errorCode) {
                Log.w(TAG, "startLogin onError: " + errorCode);
                Log.e("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onError : 오류 errorCode=" + errorCode.name());
                // ErrorCode enum 이름을 문자열로 변환 — A2 앱에서 AAR enum 없이 처리 가능
                callback.onError(errorCode.name());
            }
        });
    }

    /**
     * 사용자(담당자) 변경 플로우를 시작합니다 (CASE 12).
     *
     * <p>기기 자격증명(PIN/패턴/비밀번호) 인증 후 서버·로컬 등록 정보를 삭제합니다.
     * 완료 시 {@code onLoginSuccess(userId="", accessToken="", expiresIn=0)}으로 알립니다.
     * 사용자가 취소하면 콜백 없이 이전 UI를 유지합니다.
     *
     * @param callback 변경 결과 수신 콜백 — UI 스레드에서 호출됨
     */
    public void startUserChange(BiometricBridgeCallback callback) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > startUserChange : 사용자 변경 요청 수신");
        Log.d("BIOMETRIC_LIB", "[AUTH] CASE12 사용자 변경 시작");
        userChangeHandler.verifyDeviceCredential(activity,
                new UserChangeHandler.UserChangeCallback() {

                    @Override
                    public void onVerified() {
                        // 기기 자격증명 인증 성공 → 서버+로컬 등록 정보 삭제 실행
                        userChangeHandler.executeChange(activity, this);
                    }

                    @Override
                    public void onChangeCompleted() {
                        Log.d(TAG, "startUserChange onChangeCompleted");
                        // onLoginSuccess 재사용 — userId/accessToken은 빈값 (변경 완료 신호)
                        callback.onLoginSuccess("", "", 0);
                    }

                    @Override
                    public void onChangeFailed(ErrorCode errorCode) {
                        Log.w(TAG, "startUserChange onChangeFailed: " + errorCode);
                        callback.onError(errorCode.name());
                    }

                    @Override
                    public void onCanceled() {
                        // 사용자 취소 — 별도 콜백 없이 무시 (UI는 이전 상태 유지)
                        Log.d(TAG, "startUserChange onCanceled");
                    }
                });
    }

    /**
     * 신규 기기 등록 플로우를 시작합니다.
     *
     * <p>EC 키쌍을 생성하고 B2 서버에 기기를 등록합니다.
     * 완료 시 {@code onLoginSuccess(userId, accessToken="", expiresIn=0)}으로 알립니다.
     *
     * @param deviceId 기기 고유 ID (Settings.Secure.ANDROID_ID 권장)
     * @param userId   등록할 사용자 ID
     * @param callback 등록 결과 수신 콜백 — UI 스레드에서 호출됨
     */
    public void startRegister(String deviceId, String userId, BiometricBridgeCallback callback) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > startRegister : 등록 요청 수신");
        biometricRegistrar.register(activity, deviceId, userId,
                new BiometricRegistrar.RegisterCallback() {

                    @Override
                    public void onSuccess(String registeredUserId) {
                        Log.d(TAG, "startRegister onSuccess: userId=" + registeredUserId);
                        callback.onLoginSuccess(registeredUserId, "", 0);
                    }

                    @Override
                    public void onError(ErrorCode errorCode) {
                        Log.w(TAG, "startRegister onError: " + errorCode);
                        callback.onError(errorCode.name());
                    }
                });
    }

    /**
     * ID/PW로 계정 잠금을 해제합니다 (CASE 9 후속 처리).
     *
     * <p>서버 잠금 해제 API(PUT /api/device/unlock)를 호출하고 결과를 콜백으로 전달합니다.
     * 완료 시 {@code onLoginSuccess(userId, accessToken="", expiresIn=0)}으로 알립니다.
     *
     * <p>TODO: [실서비스] password를 서버로 전달하여 검증 후 잠금 해제 (현재는 MIS 우회 구조)
     *
     * @param userId   사용자 ID
     * @param password 사용자 비밀번호 (민감 정보 — 로그 출력 금지)
     * @param callback 잠금 해제 결과 수신 콜백 — UI 스레드에서 호출됨
     */
    public void unlockWithIdPw(String userId, String password, BiometricBridgeCallback callback) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] BiometricBridge > unlockWithIdPw : 잠금 해제 요청 수신");
        String deviceId = tokenStorage.getDeviceId();
        executor.submit(() -> {
            try {
                // TODO: [실서비스] password를 서버로 전달하여 검증 후 잠금 해제
                authApiClient.unlockDevice(deviceId);
                tokenStorage.saveRegistration(deviceId, userId);
                Log.d(TAG, "unlockWithIdPw 성공: userId=" + userId);
                activity.runOnUiThread(() -> callback.onLoginSuccess(userId, "", 0));
            } catch (Exception e) {
                Log.e(TAG, "unlockWithIdPw 실패", e);
                activity.runOnUiThread(() -> callback.onError(ErrorCode.NETWORK_ERROR.name()));
            }
        });
    }

    /**
     * 버전 정보 반환.
     *
     * @return 브릿지 버전 문자열
     */
    public static String getVersion() {
        return "1.0.0";
    }
}
