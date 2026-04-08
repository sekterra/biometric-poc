A1 → A2 AAR 이식 가이드
STEP 1. AAR 파일 빌드 (A1에서)
A1 프로젝트 루트(biometric-android/)에서 아래 명령 실행:

./gradlew :biometric-lib:assembleRelease
빌드 결과 경로:

biometric-android/biometric-lib/build/outputs/aar/biometric-lib-release.aar
이 파일을 A2 프로젝트로 복사합니다.

STEP 2. A2 프로젝트에 AAR 배치
A2 프로젝트 내 libs/ 폴더를 생성하고 복사:

A2-Project/
  app/
    libs/
      biometric-lib-release.aar   ← 여기
    build.gradle
STEP 3. A2의 build.gradle 수정
app/build.gradle에 아래 내용을 추가/수정합니다.

① compileOptions — Java 11 필수

android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig true   // BuildConfig.SERVER_URL 사용 시 필요
    }
}
② AAR + 의존성 추가

dependencies {
    // AAR 파일 직접 참조
    implementation files('libs/biometric-lib-release.aar')
    // AAR 내부에서 사용하는 라이브러리 (A2에도 반드시 선언 필요)
    implementation 'androidx.fragment:fragment:1.6.2'
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}
AAR은 자신의 의존성을 포함하지 않기 때문에 A2에서 직접 선언해야 합니다.

③ SERVER_URL 환경별 설정

buildTypes {
    debug {
        buildConfigField "String", "SERVER_URL", "\"http://172.20.10.4:8080\""
        // 에뮬레이터 사용 시: "http://10.0.2.2:8080"
    }
    release {
        buildConfigField "String", "SERVER_URL", "\"https://B1-or-B2-domain.com\""
        minifyEnabled true
        shrinkResources true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
STEP 4. AndroidManifest.xml 권한 추가
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- 안면인식 기능 선언 (required=false: 없어도 설치 가능) -->
<uses-feature
    android:name="android.hardware.biometrics.face"
    android:required="false" />
<application
    android:name=".YourApplication"   <!-- STEP 5에서 생성 -->
    ...
    android:networkSecurityConfig="@xml/network_security_config">   <!-- 개발 서버 HTTP 사용 시 -->
STEP 5. 네트워크 보안 설정 (개발 환경 HTTP 허용)
res/xml/network_security_config.xml 파일 생성:

<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- PoC/개발 전용: 개발 서버 IP만 HTTP 허용 -->
    <!-- TODO: [실서비스] 이 파일 전체 삭제, HTTPS 전용 운영 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">172.20.10.4</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false"/>
</network-security-config>
STEP 6. Application 클래스 — 싱글턴 초기화
import android.app.Application;
import com.biometric.poc.lib.crypto.EcKeyManager;
import com.biometric.poc.lib.network.AuthApiClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class YourApplication extends Application {
    private static AuthApiClient authApiClient;
    private static ExecutorService executor;
    private static EcKeyManager ecKeyManager;
    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        authApiClient = new AuthApiClient(BuildConfig.SERVER_URL);
        // 앱 패키지명 기반 키 별칭 — 다른 앱 Keystore 항목과 충돌 방지
        ecKeyManager = new EcKeyManager(BuildConfig.APPLICATION_ID + ".biometric_ec_key");
    }
    public static AuthApiClient getAuthApiClient() { return authApiClient; }
    public static ExecutorService getExecutor()    { return executor; }
    public static EcKeyManager getEcKeyManager()   { return ecKeyManager; }
}
STEP 7. TokenStorage 초기화 패턴
TokenStorage 생성자는 checked exception을 던지므로, 각 화면에서 아래처럼 초기화합니다.

private TokenStorage tokenStorage;
@Override
protected void onCreate(Bundle savedInstanceState) {
    try {
        tokenStorage = new TokenStorage(this);
    } catch (GeneralSecurityException | IOException e) {
        // 암호화 초기화 실패 — 앱 종료 또는 오류 화면 표시
        finish();
        return;
    }
    // 이후 로직
}
STEP 8. 생체인식 등록 화면 구현
import com.biometric.poc.lib.auth.BiometricRegistrar;
import com.biometric.poc.lib.auth.BiometricRegistrar.RegisterCallback;
import com.biometric.poc.lib.ErrorCode;
import com.biometric.poc.lib.policy.FailurePolicyManager;
public class RegisterActivity extends AppCompatActivity {
    private BiometricRegistrar registrar;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ... tokenStorage 초기화 (STEP 7)
        registrar = new BiometricRegistrar(
            this,
            YourApplication.getAuthApiClient(),
            YourApplication.getEcKeyManager(),
            tokenStorage,
            YourApplication.getExecutor()
        );
    }
    private void startRegister(String deviceId, String userId) {
        registrar.register(this, deviceId, userId, new RegisterCallback() {
            @Override
            public void onSuccess(String userId) {
                // 등록 완료 → 로그인 화면 이동
            }
            @Override
            public void onError(ErrorCode errorCode) {
                switch (errorCode) {
                    case BIOMETRIC_NONE_ENROLLED:
                        // 기기에 안면인식 미등록 → 설정 화면 안내
                        break;
                    case BIOMETRIC_HW_UNAVAILABLE:
                        // 생체인식 하드웨어 불가
                        break;
                    case ALREADY_REGISTERED:
                        // 이미 등록된 기기
                        break;
                    case NETWORK_ERROR:
                        // 네트워크 오류
                        break;
                    default:
                        break;
                }
            }
        });
    }
}
STEP 9. 생체인식 로그인 화면 구현
import com.biometric.poc.lib.auth.BiometricAuthManager;
import com.biometric.poc.lib.auth.BiometricAuthManager.AuthCallback;
import com.biometric.poc.lib.network.AuthApiClient.TokenResponse;
import com.biometric.poc.lib.policy.FailurePolicyManager;
public class LoginActivity extends AppCompatActivity {
    private BiometricAuthManager biometricAuthManager;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ... tokenStorage 초기화 (STEP 7)
        biometricAuthManager = new BiometricAuthManager(
            this,
            YourApplication.getAuthApiClient(),
            YourApplication.getEcKeyManager(),
            tokenStorage,
            new FailurePolicyManager(),
            YourApplication.getExecutor()
        );
    }
    private void startBiometricLogin() {
        biometricAuthManager.authenticate(this, new AuthCallback() {
            @Override
            public void onSuccess(String userId, TokenResponse tokenResponse) {
                // 로그인 성공
                // tokenResponse.accessToken  → A2 자체 세션에 저장 후 B1/B2에 전달
                // tokenResponse.refreshToken → A2에서 토큰 갱신에 사용
            }
            @Override
            public void onNotRegistered() {
                // 미등록 기기 → 등록 화면으로 이동
            }
            @Override
            public void onRetry(int failureCount) {
                // 인증 실패 (얼굴 불일치) — 남은 횟수 표시
            }
            @Override
            public void onLockedOut(int remainingSeconds) {
                // 로컬 잠금 상태 — remainingSeconds 후 재시도 가능
            }
            @Override
            public void onAccountLocked() {
                // 계정 잠금 — 관리자 해제 필요
            }
            @Override
            public void onSessionRetrying(int retryCount, int maxRetry) {
                // SESSION_EXPIRED 자동 재시도 중 (라이브러리 내부 처리)
            }
            @Override
            public void onError(ErrorCode errorCode) {
                switch (errorCode) {
                    case KEY_INVALIDATED:
                        // 안면인식 변경으로 키 무효화 → 재등록 유도
                        // biometricAuthManager.startRenewal(activity, callback) 호출
                        break;
                    case BIOMETRIC_NONE_ENROLLED:
                        // 생체인식 미등록 → 설정 화면 안내
                        break;
                    case NETWORK_ERROR:
                        break;
                    default:
                        break;
                }
            }
        });
    }
}
STEP 10. ProGuard 규칙 추가 (release 빌드 시)
app/proguard-rules.pro에 추가:

# biometric-lib 네트워크 모델 보존 (Gson 직렬화)
-keep class com.biometric.poc.lib.network.** { *; }
# ErrorCode enum 보존
-keep enum com.biometric.poc.lib.ErrorCode { *; }
# Keystore 관련 클래스 보존
-keep class com.biometric.poc.lib.crypto.** { *; }
# 콜백 인터페이스 보존
-keep interface com.biometric.poc.lib.auth.** { *; }
체크리스트 요약
[ ] STEP 1  biometric-lib-release.aar 빌드
[ ] STEP 2  A2의 app/libs/ 폴더에 AAR 복사
[ ] STEP 3  build.gradle — compileOptions, dependencies, SERVER_URL 추가
[ ] STEP 4  AndroidManifest — USE_BIOMETRIC, INTERNET 권한, Application 등록
[ ] STEP 5  network_security_config.xml 생성 (개발 서버 HTTP 허용)
[ ] STEP 6  Application 클래스 — AuthApiClient, EcKeyManager, Executor 싱글턴
[ ] STEP 7  각 화면에서 TokenStorage 초기화 (try-catch 필수)
[ ] STEP 8  등록 화면 — BiometricRegistrar 연결
[ ] STEP 9  로그인 화면 — BiometricAuthManager 연결
[ ] STEP 10 proguard-rules.pro 추가 (release 빌드 시)
주요 주의사항
항목	내용
A2 minSdk	28 이상 권장. 28 미만이면 biometric 기능 호출 전 Build.VERSION.SDK_INT >= 28 분기 필요
deviceId	Settings.Secure.ANDROID_ID 사용 (A1 데모와 동일 방식)
B1 연동 포인트	onSuccess()의 tokenResponse.accessToken을 B1 API 호출 시 Authorization 헤더로 전달
TokenStorage	AAR 내부에서 토큰 저장까지 처리. A2에서 별도 저장 불필요 (단, getAccessToken()으로 읽기 가능)
질문이
