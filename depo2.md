A2 안면인식 로그인 통합 가이드
전체 흐름
[A2 로그인화면]
     │
     ▼
[최초 1회] BiometricRegistrar.register()
     │  → EC 키쌍 생성 (Android Keystore)
     │  → 공개키를 B2 서버에 등록
     │
     ▼
[매 로그인] BiometricAuthManager.authenticate()
     │  → B2에서 Challenge 발급
     │  → 안면인식 BiometricPrompt 표시
     │  → 인증 성공 시 EC 서명 생성
     │  → B2에 서명 전송 → AccessToken 수령
     │
     ▼
AuthCallback.onSuccess() → accessToken 저장 후 메인화면 이동
STEP 1. AAR 및 의존성 추가
A2의 app/libs/ 폴더에 biometric-lib-release.aar 복사 후 app/build.gradle 수정:

android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}
dependencies {
    // ── biometric AAR ──────────────────────────────────────────
    implementation files('libs/biometric-lib-release.aar')
    // ── AAR 이 참조하는 의존성 (A2에서 직접 선언 필요) ──────────
    implementation 'androidx.fragment:fragment:1.6.2'
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.8.9'  // security-crypto 전이 의존성 충돌 해소
}
STEP 2. Application 클래스 생성
AAR 내부 컴포넌트를 앱 전역 싱글턴으로 관리합니다.

// A2Application.java
public class A2Application extends Application {
    private static AuthApiClient authApiClient;   // B2 서버 통신 클라이언트
    private static EcKeyManager ecKeyManager;     // Android Keystore EC 키 관리
    private static ExecutorService executor;      // 공유 백그라운드 스레드풀
    @Override
    public void onCreate() {
        super.onCreate();
        // 백그라운드 스레드풀 — 최대 4개 스레드 (네트워크 + 서명 작업용)
        executor = Executors.newFixedThreadPool(4);
        // B2 서버 주소 — BuildConfig로 dev/prod 분리 권장
        authApiClient = new AuthApiClient("https://your-b2-server.com");
        // EC 키 별칭 — 앱 패키지명 기반으로 타 앱과 충돌 방지
        ecKeyManager = new EcKeyManager(getPackageName() + ".biometric_ec_key");
    }
    public static AuthApiClient getAuthApiClient() { return authApiClient; }
    public static EcKeyManager getEcKeyManager()   { return ecKeyManager; }
    public static ExecutorService getExecutor()    { return executor; }
}
AndroidManifest.xml에 등록:

<application
    android:name=".A2Application"
    ... >
STEP 3. 로그인 화면에 안면인식 버튼 추가
activity_login.xml에 버튼 추가:

<Button
    android:id="@+id/btnFaceLogin"
    android:text="안면인식 로그인" />
STEP 4. LoginActivity — 핵심 코드
public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    // 안면인식 로그인 매니저 — authenticate() 호출의 진입점
    private BiometricAuthManager biometricAuthManager;
    // 안면인식 등록 — 최초 1회만 필요
    private BiometricRegistrar biometricRegistrar;
    // 토큰 저장소 — 로그인 성공 후 accessToken 읽기에 사용
    private TokenStorage tokenStorage;
    // Handler postDelayed 취소용 (메모리 누수 방지)
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNavigation = null;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        initBiometric();
        findViewById(R.id.btnFaceLogin).setOnClickListener(v -> startFaceLogin());
    }
    /** AAR 컴포넌트 초기화 — Application 싱글턴 재사용 */
    private void initBiometric() {
        try {
            // TokenStorage: EncryptedSharedPreferences 기반 토큰·기기ID 저장소
            tokenStorage = new TokenStorage(this);
            // FailurePolicyManager: 인증 실패 횟수/잠금 정책 관리
            FailurePolicyManager failurePolicyManager = new FailurePolicyManager();
            // BiometricAuthManager: 안면인식 인증 + Challenge/Token 발급 오케스트레이션
            biometricAuthManager = new BiometricAuthManager(
                    this,
                    A2Application.getAuthApiClient(),  // B2 통신 클라이언트
                    A2Application.getEcKeyManager(),   // Keystore EC 키
                    tokenStorage,
                    failurePolicyManager,
                    A2Application.getExecutor()        // 공유 스레드풀
            );
            // BiometricRegistrar: 최초 1회 기기 등록 (EC 키 생성 + B2 공개키 등록)
            biometricRegistrar = new BiometricRegistrar(
                    this,
                    A2Application.getAuthApiClient(),
                    A2Application.getEcKeyManager(),
                    tokenStorage,
                    A2Application.getExecutor()
            );
        } catch (Exception e) {
            Log.e(TAG, "biometric 초기화 실패", e);
            showError("생체인식 초기화에 실패했습니다.");
        }
    }
    /** 안면인식 로그인 버튼 클릭 시 호출 */
    private void startFaceLogin() {
        if (!tokenStorage.isRegistered()) {
            // 미등록 기기 → 먼저 등록 진행
            startFaceRegister();
            return;
        }
        // 등록된 기기 → 바로 인증
        biometricAuthManager.authenticate(this, authCallback);
    }
    /** 최초 1회 기기 등록 — deviceId, userId는 A2의 기존 로그인 세션에서 가져옴 */
    private void startFaceRegister() {
        String deviceId = getDeviceId();  // A2에서 관리하는 기기 고유 ID
        String userId   = getLoggedInUserId();  // A2의 현재 로그인 사용자 ID
        biometricRegistrar.register(this, deviceId, userId, new BiometricRegistrar.RegisterCallback() {
            /** 등록 성공 → 이어서 인증 진행 */
            @Override
            public void onSuccess(String userId) {
                biometricAuthManager.authenticate(LoginActivity.this, authCallback);
            }
            /** 등록 실패 → ErrorCode로 원인 파악 */
            @Override
            public void onError(ErrorCode errorCode) {
                handleError(errorCode);
            }
        });
    }
    /**
     * 인증 콜백 — BiometricAuthManager.authenticate() 결과를 수신.
     * 모든 콜백은 메인 스레드에서 호출됨.
     */
    private final BiometricAuthManager.AuthCallback authCallback =
            new BiometricAuthManager.AuthCallback() {
        /**
         * 인증 + 토큰 발급 성공.
         *
         * @param userId        인증된 사용자 ID
         * @param tokenResponse B2가 발급한 토큰 (accessToken, refreshToken 포함)
         */
        @Override
        public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
            Log.d(TAG, "로그인 성공: userId=" + userId);
            // A2의 세션에 토큰 저장 (AAR의 TokenStorage와 별도로 A2 자체 세션 관리)
            saveSessionToken(tokenResponse.accessToken);
            // 메인화면으로 이동 (약간의 딜레이로 UX 개선)
            pendingNavigation = () -> {
                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                finish();
            };
            mainHandler.postDelayed(pendingNavigation, 500);
        }
        /**
         * 기기 미등록 상태 — 서버에 등록 정보가 없거나 로컬 등록 정보가 삭제된 경우.
         * 재등록 화면으로 유도.
         */
        @Override
        public void onNotRegistered() {
            showError("기기가 등록되지 않았습니다. 다시 등록해주세요.");
            startFaceRegister();
        }
        /**
         * 계정 잠금 상태.
         *
         * @param remainingSeconds 잠금 해제까지 남은 시간(초)
         */
        @Override
        public void onLockedOut(int remainingSeconds) {
            showError("인증 실패 횟수 초과. " + remainingSeconds + "초 후 다시 시도하세요.");
        }
        /**
         * 인증 실패 (얼굴 불일치) — 아직 잠금 전.
         *
         * @param failureCount 누적 실패 횟수
         */
        @Override
        public void onRetry(int failureCount) {
            showError("인증에 실패했습니다. (" + failureCount + "회)");
        }
        /** 관리자에 의한 계정 잠금 — 관리자에게 문의 안내 */
        @Override
        public void onAccountLocked() {
            showError("계정이 잠겼습니다. 관리자에게 문의하세요.");
        }
        /**
         * SESSION_EXPIRED 자동 재시도 중 상태 알림.
         * UI에 "재시도 중..." 표시 용도.
         *
         * @param retryCount 현재 재시도 횟수 (1부터)
         * @param maxRetry   최대 재시도 횟수 (기본 2회)
         */
        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            Log.d(TAG, "세션 재시도 중 " + retryCount + "/" + maxRetry);
            // 필요 시 UI에 로딩 표시
        }
        /**
         * 오류 발생 — ErrorCode로 원인 파악 후 사용자 안내.
         *
         * @param errorCode 오류 코드 (ErrorCode enum)
         */
        @Override
        public void onError(ErrorCode errorCode) {
            handleError(errorCode);
        }
    };
    /** ErrorCode → 사용자 메시지 변환 */
    private void handleError(ErrorCode errorCode) {
        String message;
        switch (errorCode) {
            case BIOMETRIC_NONE_ENROLLED:
                // 기기에 얼굴인식이 미등록 → 설정 화면으로 유도
                message = "얼굴인식이 등록되지 않았습니다. 기기 설정에서 등록해주세요.";
                break;
            case BIOMETRIC_HW_UNAVAILABLE:
                // API 28 미만 기기 또는 하드웨어 없음
                message = "이 기기는 안면인식을 지원하지 않습니다.";
                break;
            case KEY_INVALIDATED:
                // 얼굴인식 재등록으로 Keystore 키 무효화 → 키 갱신 필요
                showKeyInvalidatedDialog();
                return;
            case ACCOUNT_LOCKED:
                message = "계정이 잠겼습니다. 관리자에게 문의하세요.";
                break;
            case NETWORK_ERROR:
                message = "네트워크 오류가 발생했습니다. 연결 상태를 확인해주세요.";
                break;
            case INVALID_SIGNATURE:
                message = "서명 검증에 실패했습니다.";
                break;
            case SESSION_EXPIRED:
                message = "세션이 만료되었습니다. 다시 시도해주세요.";
                break;
            default:
                message = "오류가 발생했습니다. 다시 시도해주세요.";
                break;
        }
        showError(message);
    }
    /**
     * KEY_INVALIDATED 처리 — 얼굴 재등록 시 Keystore 키가 무효화됨.
     * 사용자 확인 후 AAR의 키 갱신 흐름 자동 실행.
     */
    private void showKeyInvalidatedDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("얼굴인식 정보 변경 감지")
                .setMessage("기기의 얼굴인식 정보가 변경되었습니다. 보안 키를 갱신합니다.")
                .setPositiveButton("확인", (d, w) ->
                        // startRenewal: 새 키쌍 생성 → B2에 공개키 업데이트 → 재인증
                        biometricAuthManager.startRenewal(this, authCallback))
                .setCancelable(false)
                .show();
    }
    private void showError(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // postDelayed 예약 취소 — Activity 종료 후 화면 전환 방지 (메모리 누수 방지)
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
        }
    }
    // A2에서 구현해야 할 메서드 ↓
    private String getDeviceId() { /* A2의 기기 고유 ID 반환 (예: UUID 저장값) */ return ""; }
    private String getLoggedInUserId() { /* A2의 현재 로그인 사용자 ID 반환 */ return ""; }
    private void saveSessionToken(String token) { /* A2 세션에 accessToken 저장 */ }
}
STEP 5. TokenResponse 주요 필드
// AuthApiClient.TokenResponse — onSuccess()에서 수신
tokenResponse.accessToken    // B2 발급 액세스 토큰 → A2 API 호출 시 Authorization 헤더에 사용
tokenResponse.refreshToken   // 리프레시 토큰 → 토큰 갱신 시 사용 (갱신 로직은 A2에서 처리)
tokenResponse.tokenType      // "Bearer"
tokenResponse.expiresIn      // 유효 시간(초)
STEP 6. 네트워크 보안 설정
res/xml/network_security_config.xml (HTTP 허용이 필요한 개발환경에서만):

<network-security-config>
    <!-- 개발 서버 IP만 HTTP 허용 — 운영 시 삭제 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>      <!-- 에뮬레이터 -->
        <domain includeSubdomains="false">192.168.x.x</domain>   <!-- B2 개발 서버 IP -->
    </domain-config>
    <base-config cleartextTrafficPermitted="false"/>
</network-security-config>
정리 — A2에서 직접 구현해야 할 항목
항목	설명
getDeviceId()	기기 고유 ID (UUID 등, A2에서 관리)
getLoggedInUserId()	현재 로그인 사용자 ID
saveSessionToken()	accessToken을 A2 세션에 저장
등록 UI 진입 조건	tokenStorage.isRegistered() 로 분기
토큰 갱신	refreshToken 활용한 갱신 로직 (A2 자체 구현)
