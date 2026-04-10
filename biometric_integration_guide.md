# 안면인식 생체인증 라이브러리 A2 이식 가이드

> **대상 독자:** 운영 MIS 앱(A2)에 `biometric-lib` AAR을 탑재하는 Android 개발자  
> **기준 소스:** `biometric-poc` PoC 프로젝트 (2026-04-10 기준)  
> **언어:** Java (라이브러리 및 데모 앱 모두 Java로 구현됨)

---

## 목차

1. [전체 CASE 목록](#1-전체-case-목록)
2. [STEP 1. 사전 준비](#step-1-사전-준비)
3. [STEP 2. 초기화 코드 이식](#step-2-초기화-코드-이식)
4. [STEP 3. 로그인 화면 버튼 추가](#step-3-로그인-화면-버튼-추가)
5. [STEP 4. CASE별 분기 처리 이식](#step-4-case별-분기-처리-이식)
6. [STEP 5. 등록 프로세스 이식](#step-5-등록-프로세스-이식-사용자-변경-포함)
7. [STEP 6. 토큰 생성 및 B2 서버 연동](#step-6-토큰-생성-및-b2-서버-연동)
8. [STEP 7. 예외 처리 체크리스트](#step-7-예외-처리-체크리스트)

---

## 1. 전체 CASE 목록

아래 12개 케이스는 PoC 소스 코드(`BiometricAuthManager`, `KeyRenewalHandler`, `UserChangeHandler`, `AndroidBridge` 등)에서 도출한 전체 상태 분류입니다.

---

### CASE 1 — 정상 로그인 성공

| 항목 | 내용 |
|------|------|
| **상태명** | 로그인 성공 (ACTIVE + 인증 완료) |
| **발생 조건** | 안면인식 성공 → ECDSA 서명 → 서버 토큰 발급 HTTP 200 |
| **처리 흐름** | `BiometricPrompt.onAuthenticationSucceeded()` → `ecKeyManager.signPayload()` → `authApiClient.requestToken()` → `tokenStorage.saveTokens()` → `AuthCallback.onSuccess()` |
| **A2 대응 동작** | 로그인 성공 UI 표시 후 메인 화면으로 전환. `tokenResponse.accessToken`을 A2 세션에 저장. |

---

### CASE 2 — 안면인식 실패 (재시도 가능)

| 항목 | 내용 |
|------|------|
| **상태명** | 안면 불일치 재시도 |
| **발생 조건** | `BiometricPrompt.onAuthenticationFailed()` 호출 + 누적 실패 횟수가 `maxRetryBeforeLockout` 미달 + `accountLockThreshold` 미달 |
| **처리 흐름** | `failurePolicyManager.recordFailure()` → `failurePolicyManager.isLocallyLocked()` = false → `AuthCallback.onRetry(failureCount)` |
| **A2 대응 동작** | 남은 시도 횟수 안내 메시지 표시 (예: "인증 실패 N회 / 최대 M회"). 버튼은 활성 상태 유지. |

---

### CASE 3 — SESSION_EXPIRED 자동 재시도

| 항목 | 내용 |
|------|------|
| **상태명** | 챌린지 세션 만료 → 자동 재시도 |
| **발생 조건** | `requestToken()` 응답 HTTP 401, `error="SESSION_EXPIRED"` + `sessionRetryCount < MAX_SESSION_RETRY(2)` |
| **처리 흐름** | `TokenVerificationException("SESSION_EXPIRED")` catch → `sessionRetryCount++` → `AuthCallback.onSessionRetrying(retryCount, maxRetry)` → 새 Challenge 요청 → BiometricPrompt 재실행 |
| **A2 대응 동작** | "인증을 다시 시도합니다 (N/M)" 형태의 진행 메시지 표시. 사용자 별도 액션 불필요. |

---

### CASE 4 — 로컬 일시 잠금

| 항목 | 내용 |
|------|------|
| **상태명** | 안면인식 연속 실패로 인한 일시 잠금 |
| **발생 조건** | `failureCount >= maxRetryBeforeLockout` AND 잠금 시간(`lockoutSeconds`) 미경과 |
| **처리 흐름** | `failurePolicyManager.isLocallyLocked()` = true → `getLockRemainingSeconds()` → `AuthCallback.onLockedOut(remainingSeconds)` |
| **A2 대응 동작** | 안면인식 버튼 비활성화 + 카운트다운 타이머 표시. `onCountdownFinish()` 시 버튼 재활성화. |

---

### CASE 5 — 기기 안면인식 미등록

| 항목 | 내용 |
|------|------|
| **상태명** | 기기에 얼굴 데이터 미등록 |
| **발생 조건** | `biometricManager.canAuthenticate(BIOMETRIC_WEAK)` = `BIOMETRIC_ERROR_NONE_ENROLLED` |
| **처리 흐름** | `authenticate()` 또는 `register()` 내 사전 체크 → `AuthCallback.onError(ErrorCode.BIOMETRIC_NONE_ENROLLED)` |
| **A2 대응 동작** | 안면인식 버튼 비활성화 + "설정 → 얼굴 등록" 유도 다이얼로그 (Native AlertDialog). |

---

### CASE 6 — INVALID_SIGNATURE 누적 → 키 자동 재발급

| 항목 | 내용 |
|------|------|
| **상태명** | 서명 불일치 연속 발생으로 인한 자동 키 재발급 |
| **발생 조건** | `requestToken()` HTTP 401 + `error="INVALID_SIGNATURE"` 연속 `INVALID_SIGNATURE_RENEWAL_THRESHOLD(3)`회 이상 |
| **처리 흐름** | `invalidSignatureCount >= 3` → `invalidSignatureCount = 0` → `keyRenewalHandler.renewAndRetry()` → 기존 키 삭제 → 새 EC 키쌍 생성 → 서버 공개키 갱신(`renewKey`) → 새 Challenge → BiometricPrompt 재실행 |
| **A2 대응 동작** | 진행 스피너 표시. 완료 후 `onSuccess` 또는 `onError` 수신. |

---

### CASE 7 — 기기 서버 미등록

| 항목 | 내용 |
|------|------|
| **상태명** | 서버에 기기 등록 정보 없음 |
| **발생 조건** | `getChallenge()` 또는 `runPrepareAndShowPrompt()` 에서 `DeviceNotFoundException` (HTTP 404) |
| **처리 흐름** | `DeviceNotFoundException` catch → `tokenStorage.clearRegistration()` → `failurePolicyManager.invalidatePolicy()` → `AuthCallback.onNotRegistered()` |
| **A2 대응 동작** | 등록 화면(RegisterActivity 상당)으로 이동 유도. `TokenStorage.clearRegistration()` 호출 선행 필요. |

---

### CASE 8 — 네트워크 오류

| 항목 | 내용 |
|------|------|
| **상태명** | 서버 통신 실패 |
| **발생 조건** | `IOException` 발생 (챌린지 요청, 토큰 요청, 정책 조회 등 모든 네트워크 단계) |
| **처리 흐름** | `Exception` catch (fallback) → `AuthCallback.onError(ErrorCode.NETWORK_ERROR)` |
| **A2 대응 동작** | "네트워크 연결을 확인해주세요" 메시지 + 재시도 버튼 표시. |

---

### CASE 9 — 계정 잠금 (ACCOUNT_LOCKED)

| 항목 | 내용 |
|------|------|
| **상태명** | 관리자 계정 잠금 |
| **발생 조건** | `failureCount >= accountLockThreshold` → `authApiClient.lockAccount()` 서버 잠금 완료, 또는 서버 기기 상태가 `LOCKED` |
| **처리 흐름** | `runAccountLock()` → `authApiClient.lockAccount()` → `failurePolicyManager.invalidatePolicy()` → `AuthCallback.onAccountLocked()` |
| **A2 대응 동작** | 안면인식 버튼 숨김 + ID/PW 입력 영역 표시. `startIdPwUnlock(userId, password)` 브릿지 메서드로 잠금 해제. |

---

### CASE 10 — KEY_INVALIDATED (서버 감지)

| 항목 | 내용 |
|------|------|
| **상태명** | 서버가 키 무효화 감지 (챌린지 단계 409) |
| **발생 조건** | `getChallenge()` 응답 HTTP 409 → `KeyInvalidatedException` |
| **처리 흐름** | `KeyInvalidatedException` catch → `AuthCallback.onError(ErrorCode.KEY_INVALIDATED)` → `showKeyInvalidatedDialog()` → 사용자 확인 시 `biometricAuthManager.startRenewal()` → `KeyRenewalHandler.renewAndRetry()` |
| **A2 대응 동작** | Native AlertDialog("보안키 재설정 필요") 표시. 확인 시 키 재발급 자동 진행. 취소 시 로그인 화면 유지. |

---

### CASE 11 — SESSION_EXPIRED 재시도 횟수 초과

| 항목 | 내용 |
|------|------|
| **상태명** | 세션 만료 재시도 한계 도달 |
| **발생 조건** | SESSION_EXPIRED가 `MAX_SESSION_RETRY(2)`회 자동 재시도 후에도 반복 발생 |
| **처리 흐름** | `sessionRetryCount >= MAX_SESSION_RETRY` → `sessionRetryCount = 0` → `AuthCallback.onError(ErrorCode.SESSION_EXPIRED)` |
| **A2 대응 동작** | "네트워크 불안정 또는 인증 시간 초과" 메시지 표시 + 수동 재시도 버튼. |

---

### CASE 12 — 사용자 변경 (담당자 변경)

| 항목 | 내용 |
|------|------|
| **상태명** | 기존 사용자 정보 삭제 후 신규 사용자 등록 |
| **발생 조건** | 사용자가 "담당자 변경" 버튼 클릭 → 다이얼로그 확인 → PIN/패턴 인증 성공 |
| **처리 흐름** | `openUserChangeDialog()` → `UserChangeHandler.verifyDeviceCredential()` (PIN/패턴 BiometricPrompt) → `onVerified()` → `UserChangeHandler.executeChange()` → `authApiClient.unregisterDevice()` → `ecKeyManager.deleteKeyPair()` → `tokenStorage.clearAll()` → `onChangeCompleted()` → 등록 화면 이동 |
| **A2 대응 동작** | 완료 후 신규 등록 화면으로 전환. 서버 삭제 실패(404)여도 로컬 삭제 후 정상 진행. |

---

## STEP 1. 사전 준비

### 1-1. AAR 파일 준비 및 복사 위치

`biometric-lib` 모듈을 AAR로 빌드한 후 A2 프로젝트에 복사합니다.

**빌드 방법 (PoC 프로젝트에서):**

```bash
# biometric-android 폴더에서 실행
./gradlew :biometric-lib:assembleRelease
# 출력 경로: biometric-lib/build/outputs/aar/biometric-lib-release.aar
```

**A2 프로젝트 복사 위치:**

```
[A2_프로젝트_루트]/
└── app/
    └── libs/
        └── biometric-lib-release.aar   ← 여기에 복사
```

> **주의:** `libs/` 폴더가 없으면 생성합니다.

---

### 1-2. `build.gradle` (app 모듈) 추가 항목

```groovy
android {
    // A2 기존 설정 유지
    // biometric-lib minSdk는 23이나, 생체인증 실제 동작은 API 28 이상에서만 가능
    // (런타임에서 Build.VERSION.SDK_INT < 28 체크로 처리됨)

    compileOptions {
        // biometric-lib은 Java 11 기준으로 컴파일됨
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }

    buildFeatures {
        // BuildConfig.SERVER_URL 등 빌드 필드 사용 시 필요
        buildConfig true
    }

    buildTypes {
        release {
            // 실서비스: B2 인증 서버 HTTPS 주소로 교체 필수
            buildConfigField "String", "BIOMETRIC_SERVER_URL", "\"https://api.your-domain.com\""
        }
        debug {
            // 개발 테스트: 실기기 핫스팟 연결 시 서버 IP
            buildConfigField "String", "BIOMETRIC_SERVER_URL", "\"http://192.168.x.x:8080\""
        }
    }
}

dependencies {
    // ─── AAR 참조 ──────────────────────────────────────────────
    // libs 폴더 내 모든 AAR/JAR 자동 포함
    implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])

    // ─── biometric-lib 의존 라이브러리 (호스트 앱에서 선언 필수) ──
    implementation 'androidx.fragment:fragment:1.6.2'
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // gson: biometric-lib이 compileOnly로 선언하므로 호스트 앱에서 반드시 제공해야 함
    // A2에 이미 gson 2.2.4 이상이 있으면 해당 버전 사용 가능
    implementation 'com.google.code.gson:gson:2.8.9'

    // ─── A2 기존 의존성 유지 ────────────────────────────────────
    implementation 'androidx.appcompat:appcompat:1.7.0'
}
```

> **중요 — gson 버전:** `biometric-lib`은 gson을 `compileOnly`로 선언합니다.  
> A2에 이미 gson이 있다면 그 버전이 사용됩니다. gson 2.2.4 이상이면 정상 동작합니다.

---

### 1-3. `AndroidManifest.xml` 추가 항목

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 생체인증 권한 (필수) -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <!-- 서버 통신 권한 (A2에 이미 있으면 중복 선언 불필요) -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 안면인식 하드웨어 선언 (required=false: 없어도 설치 가능) -->
    <uses-feature
        android:name="android.hardware.biometrics.face"
        android:required="false" />

    <application
        android:name=".A2Application"  <!-- A2 기존 Application 클래스 유지 -->
        android:networkSecurityConfig="@xml/network_security_config"  <!-- HTTP 허용 시 필요 -->
        ...>

        <!-- A2 기존 Activity 목록 유지 -->

        <!-- 안면인식 등록 화면 (신규 추가) -->
        <activity
            android:name=".biometric.BiometricRegisterActivity"
            android:exported="false" />

    </application>
</manifest>
```

**HTTP 통신이 필요한 경우 `res/xml/network_security_config.xml` 추가:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <!-- 개발 서버 IP만 HTTP 허용, 운영은 HTTPS 전용 -->
        <domain includeSubdomains="false">192.168.x.x</domain>
    </domain-config>
</network-security-config>
```

---

### ✅ STEP 1 완료 확인 체크리스트

- [ ] `app/libs/biometric-lib-release.aar` 파일이 존재함
- [ ] `build.gradle`에 `fileTree(dir: 'libs')` 의존성 추가됨
- [ ] `androidx.biometric:biometric:1.1.0` 의존성 추가됨
- [ ] `androidx.security:security-crypto:1.1.0-alpha06` 의존성 추가됨
- [ ] `com.squareup.okhttp3:okhttp:4.12.0` 의존성 추가됨
- [ ] `gson` 의존성이 A2에 선언되어 있음 (2.2.4 이상)
- [ ] `AndroidManifest.xml`에 `USE_BIOMETRIC` 권한 추가됨
- [ ] 개발/운영 환경별 서버 URL `buildConfigField` 설정됨
- [ ] Gradle Sync 성공 확인

---

## STEP 2. 초기화 코드 이식

### 2-1. Application 클래스에서 전역 초기화

A2의 `Application` 클래스(또는 신규 생성)에 생체인증 컴포넌트를 초기화합니다.  
`AuthApiClient`와 `ExecutorService`는 Activity마다 생성하면 리소스 낭비가 발생하므로 **전역 싱글턴**으로 관리합니다.

```java
// A2Application.java (A2의 기존 Application 클래스에 추가)
public class A2Application extends Application {

    // ── 생체인증 전역 컴포넌트 ────────────────────────────────
    // AuthApiClient: B2 인증 서버와 통신하는 HTTP 클라이언트 (OkHttp 기반)
    private static AuthApiClient biometricApiClient;

    // ExecutorService: 네트워크·암호화 작업용 스레드풀 (최대 4개 스레드)
    private static ExecutorService biometricExecutor;

    // EcKeyManager: Android Keystore 기반 ECDSA 키 생성·서명 관리자
    // 키 별칭은 앱 패키지명 기반으로 설정하여 다른 앱과 충돌 방지
    private static EcKeyManager ecKeyManager;

    @Override
    public void onCreate() {
        super.onCreate();

        // ── 기존 A2 초기화 코드 유지 ────────────────────────

        // ── 생체인증 컴포넌트 초기화 ─────────────────────────
        initBiometricComponents();
    }

    private void initBiometricComponents() {
        // 스레드풀 생성: daemon 스레드로 설정하여 앱 종료 시 자동 정리
        biometricExecutor = Executors.newFixedThreadPool(
                4,  // TODO: [실서비스] A2 부하에 맞게 스레드 수 조정
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("a2-biometric-io");
                    return t;
                });

        // B2 인증 서버 클라이언트 생성
        // BuildConfig.BIOMETRIC_SERVER_URL: build.gradle에서 환경별로 설정
        biometricApiClient = new AuthApiClient(BuildConfig.BIOMETRIC_SERVER_URL);

        // EC 키 관리자 생성: 키 별칭은 패키지명 기반으로 고정
        // 주의: 키 별칭 변경 시 기존 등록 정보와 불일치 → 재등록 필요
        ecKeyManager = new EcKeyManager(getPackageName() + ".biometric_ec_key");

        Log.d("A2Application", "생체인증 컴포넌트 초기화 완료");
    }

    /** B2 인증 서버 통신 클라이언트 반환 */
    public static AuthApiClient getBiometricApiClient() { return biometricApiClient; }

    /** ECDSA 키 생성·서명 관리자 반환 */
    public static EcKeyManager getEcKeyManager() { return ecKeyManager; }

    /** 생체인증 백그라운드 스레드풀 반환 */
    public static ExecutorService getBiometricExecutor() { return biometricExecutor; }
}
```

---

### 2-2. 로그인 Activity에서 인증 컴포넌트 초기화

```java
// BiometricLoginActivity.java (A2 로그인 Activity에 통합 또는 신규 생성)
public class BiometricLoginActivity extends AppCompatActivity {
    // AppCompatActivity는 FragmentActivity를 상속하므로 BiometricPrompt 요건 충족
    // 주의: FragmentActivity가 아닌 Activity를 사용하면 BiometricPrompt 동작 불가

    private BiometricAuthManager biometricAuthManager;
    // BiometricAuthManager: 정책 로드 → 챌린지 요청 → BiometricPrompt → 서명 → 토큰 발급
    // 전체 안면인식 인증 플로우를 오케스트레이션하는 핵심 클래스

    private TokenStorage tokenStorage;
    // TokenStorage: EncryptedSharedPreferences 기반 안전한 토큰·등록정보 저장소

    private UserChangeHandler userChangeHandler;
    // UserChangeHandler: 사용자 변경 프로세스 (PIN 인증 → 서버 삭제 → 로컬 삭제)

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── TokenStorage 초기화 ──────────────────────────────
        // GeneralSecurityException/IOException: EncryptedSharedPreferences 초기화 실패
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            // 실서비스: 사용자에게 앱 재설치 안내
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // ── FailurePolicyManager 초기화 ─────────────────────
        // FailurePolicyManager: 로컬 실패 횟수·잠금 시간 관리 + 서버 정책 캐시
        // 주의: LoginActivity 생명주기와 함께 생성 (전역 싱글턴 불필요)
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();

        // ── BiometricAuthManager 초기화 ─────────────────────
        biometricAuthManager = new BiometricAuthManager(
                this,                                       // Context (ApplicationContext로 내부 변환됨)
                A2Application.getBiometricApiClient(),      // AuthApiClient: B2 서버 통신
                A2Application.getEcKeyManager(),            // EcKeyManager: 키 생성·서명
                tokenStorage,                               // TokenStorage: 토큰 저장
                failurePolicyManager,                       // FailurePolicyManager: 실패 정책
                A2Application.getBiometricExecutor());      // ExecutorService: 백그라운드 스레드풀

        // ── UserChangeHandler 초기화 ────────────────────────
        userChangeHandler = new UserChangeHandler(
                this,
                A2Application.getEcKeyManager(),
                tokenStorage,
                A2Application.getBiometricApiClient(),
                A2Application.getBiometricExecutor());

        // A2 로그인 화면 레이아웃 설정
        setContentView(R.layout.activity_login);

        setupButtons(); // STEP 3 참조
    }

    @Override
    protected void onDestroy() {
        // biometricAuthManager.shutdown()은 내부적으로 no-op
        // executor는 Application이 관리하므로 여기서 종료 금지
        super.onDestroy();
    }
}
```

---

### ✅ STEP 2 완료 확인 체크리스트

- [ ] `Application` 클래스에 `AuthApiClient`, `EcKeyManager`, `ExecutorService` 전역 초기화 추가됨
- [ ] 로그인 Activity가 `AppCompatActivity`(또는 `FragmentActivity`) 상속 확인
- [ ] `TokenStorage` 초기화 예외(`GeneralSecurityException`, `IOException`) 처리됨
- [ ] `BiometricAuthManager` 생성자 6개 파라미터 모두 전달됨
- [ ] `UserChangeHandler` 생성자 5개 파라미터 모두 전달됨
- [ ] `onDestroy()`에서 executor를 `shutdown()` 호출하지 않음 (Application이 관리)

---

## STEP 3. 로그인 화면 버튼 추가

### 3-1. 레이아웃에 버튼 2개 추가

```xml
<!-- res/layout/activity_login.xml에 추가 -->

<!-- (1) 안면인식 로그인 버튼 -->
<Button
    android:id="@+id/btn_face_login"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="안면인식 로그인"
    android:textSize="16sp" />

<!-- (2) 사용자 변경 버튼 -->
<Button
    android:id="@+id/btn_user_change"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="담당자 변경"
    android:textSize="14sp" />
```

---

### 3-2. 버튼 클릭 이벤트 처리

```java
// BiometricLoginActivity.java — setupButtons() 메서드
private void setupButtons() {

    // ─── (1) 안면인식 로그인 버튼 ────────────────────────────
    Button btnFaceLogin = findViewById(R.id.btn_face_login);

    btnFaceLogin.setOnClickListener(v -> {
        // biometricAuthManager.authenticate()
        //   파라미터:
        //     activity (FragmentActivity): BiometricPrompt를 표시할 Activity (this)
        //     callback (AuthCallback): 인증 결과를 수신할 콜백 인터페이스
        //   반환값: void (결과는 AuthCallback으로 비동기 수신)
        //   주의: API 28 미만 기기는 내부에서 자동으로 onError(BIOMETRIC_HW_UNAVAILABLE) 호출됨
        //   주의: 미등록 상태라면 즉시 onNotRegistered() 콜백 호출됨
        biometricAuthManager.authenticate(this, authCallback);
    });

    // ─── (2) 사용자 변경 버튼 ───────────────────────────────
    Button btnUserChange = findViewById(R.id.btn_user_change);

    btnUserChange.setOnClickListener(v -> {
        // showUserChangeDialog()
        //   역할: AlertDialog 표시 → 확인 시 UserChangeHandler 플로우 시작
        //   주의: AlertDialog는 반드시 UI 스레드에서 표시해야 함 (이미 onClick은 UI 스레드)
        showUserChangeDialog();
    });
}

// ─── AuthCallback 구현 ────────────────────────────────────────
// 모든 콜백 메서드는 biometricAuthManager 내부에서 runOnUiThread() 처리 후 전달됨
// 별도로 runOnUiThread() 감쌀 필요 없음
private final BiometricAuthManager.AuthCallback authCallback =
        new BiometricAuthManager.AuthCallback() {

    @Override
    public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
        // CASE 1: 로그인 성공
        // userId: 인증된 사용자 ID
        // tokenResponse.accessToken: B2 서버 발급 액세스 토큰
        // tokenResponse.refreshToken: 리프레시 토큰
        // tokenResponse.expiresIn: 만료 시간(초), 없으면 BiometricLibConstants.TOKEN_EXPIRES_IN_DEFAULT_SEC(1800)
        // → STEP 6 참조
    }

    @Override
    public void onNotRegistered() {
        // CASE 7: 기기 미등록 → 등록 화면 이동
    }

    @Override
    public void onLockedOut(int remainingSeconds) {
        // CASE 4: 일시 잠금
        // remainingSeconds: 잠금 해제까지 남은 초
    }

    @Override
    public void onRetry(int failureCount) {
        // CASE 2: 재시도
        // failureCount: 현재까지 누적 실패 횟수
    }

    @Override
    public void onAccountLocked() {
        // CASE 9: 계정 잠금
    }

    @Override
    public void onSessionRetrying(int retryCount, int maxRetry) {
        // CASE 3: SESSION_EXPIRED 자동 재시도 중
        // retryCount: 현재 재시도 횟수 (1부터 시작)
        // maxRetry: 최대 재시도 횟수 (BiometricLibConstants.MAX_SESSION_RETRY = 2)
    }

    @Override
    public void onError(ErrorCode errorCode) {
        // CASE 5, 6, 8, 10, 11 등 각종 오류
        // → STEP 4에서 상세 분기 처리
    }
};
```

---

### 3-3. `onResume()`에서 안면인식 등록 여부 확인

로그인 화면이 재개될 때 기기에 안면인식이 등록되어 있는지 확인하여 버튼 활성화 여부를 결정합니다.

```java
@Override
protected void onResume() {
    super.onResume();

    // 잠금 상태 중이면 버튼 상태 재확인 불필요
    if (isLockedOut || isAccountLocked) return;

    // canAuthenticate(): 기기의 생체인증 가용 여부 확인
    //   BIOMETRIC_SUCCESS: 안면인식(또는 지문) 등록됨 → 버튼 활성화
    //   BIOMETRIC_ERROR_NONE_ENROLLED: 미등록 → 버튼 비활성화 + 안내
    int canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);

    Button btnFaceLogin = findViewById(R.id.btn_face_login);
    if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
        btnFaceLogin.setEnabled(true);
    } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
        btnFaceLogin.setEnabled(false);
        showNotEnrolledDialog(); // 설정 화면 유도
    }
}
```

---

### ✅ STEP 3 완료 확인 체크리스트

- [ ] 안면인식 로그인 버튼 (`btn_face_login`) 레이아웃에 추가됨
- [ ] 담당자 변경 버튼 (`btn_user_change`) 레이아웃에 추가됨
- [ ] `authenticate(this, authCallback)` 호출 시 `this`가 `FragmentActivity`임 확인
- [ ] `AuthCallback` 인터페이스 7개 메서드 모두 구현됨
- [ ] `onResume()`에서 `canAuthenticate()` 체크 후 버튼 활성화 처리됨
- [ ] 콜백 메서드에서 별도 `runOnUiThread()` 감싸지 않음 (이미 UI 스레드에서 전달됨)

---

## STEP 4. CASE별 분기 처리 이식

### 4-1. 서버 상태 조회 (MainActivity 상당 — 앱 최초 진입점)

PoC의 `MainActivity`는 앱 최초 진입 시 서버에서 기기 상태를 조회합니다.  
A2에서는 로그인 화면 진입 전(스플래시 또는 별도 초기화 Activity)에 동일하게 구현합니다.

```java
// A2SplashActivity.java 또는 A2LoginCheckActivity.java
private void checkDeviceStatusAndRoute() {
    // 이미 등록된 경우 → 바로 로그인 화면으로 이동
    if (tokenStorage.isRegistered()) {
        navigateToLogin();
        return;
    }

    // 서버에서 기기 상태 조회 (백그라운드 스레드에서 실행)
    A2Application.getBiometricExecutor().submit(() -> {
        // ANDROID_ID: 기기 고유 식별자 (Settings.Secure.ANDROID_ID)
        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        try {
            // authApiClient.getUserId(): GET /api/device/user-id?device_id={deviceId}
            // 반환: DeviceStatusResponse { userId, status }
            // 예외: DeviceNotFoundException (HTTP 404) — 미등록 기기
            AuthApiClient.DeviceStatusResponse response =
                    A2Application.getBiometricApiClient().getUserId(deviceId);

            runOnUiThread(() -> handleDeviceStatus(response, deviceId));

        } catch (DeviceNotFoundException e) {
            // 서버에 기기 미등록 → 등록 화면으로 이동
            runOnUiThread(() -> navigateToRegister(deviceId));

        } catch (Exception e) {
            // 네트워크 오류 등
            runOnUiThread(() -> showNetworkError(e.getMessage()));
        }
    });
}

// 서버 상태값에 따라 화면 분기
private void handleDeviceStatus(AuthApiClient.DeviceStatusResponse response, String deviceId) {
    String status = response.status != null ? response.status : "";

    switch (status) {

        case "ACTIVE":
            // 정상 등록 상태 → 등록 정보 로컬 저장 후 로그인 화면 이동
            // tokenStorage.saveRegistration(): deviceId와 userId를 EncryptedSharedPreferences에 저장
            tokenStorage.saveRegistration(deviceId, response.userId);
            navigateToLogin(); // 2초 후 자동 이동 (UX 개선 시 딜레이 추가)
            break;

        case "LOCKED":
            // 계정 잠금 상태 → ID/PW 잠금 해제 화면 표시
            // pendingDeviceId, pendingUserId 보관 후 unlock API 호출에 사용
            showLockedStateUI(deviceId, response.userId);
            break;

        case "KEY_INVALIDATED":
            // 얼굴 재등록으로 키 무효화 → Native AlertDialog 표시
            showKeyInvalidatedDialogForRegistration(response.userId, deviceId);
            break;

        default:
            // 알 수 없는 상태
            showStatusError("알 수 없는 기기 상태: " + status);
            break;
    }
}
```

---

### 4-2. AuthCallback 내 CASE별 분기 처리

```java
private final BiometricAuthManager.AuthCallback authCallback =
        new BiometricAuthManager.AuthCallback() {

    // ── CASE 1: 로그인 성공 ──────────────────────────────────
    @Override
    public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
        // A2 메인 화면으로 전환
        // tokenResponse.accessToken을 A2 세션 관리에 전달
        navigateToMain(userId, tokenResponse);
    }

    // ── CASE 7: 기기 미등록 ──────────────────────────────────
    @Override
    public void onNotRegistered() {
        // 토스트 메시지 + 등록 화면으로 이동
        Toast.makeText(BiometricLoginActivity.this,
                "기기 등록 정보가 없습니다. 다시 등록해주세요.", Toast.LENGTH_LONG).show();
        navigateToRegister();
    }

    // ── CASE 4: 일시 잠금 ───────────────────────────────────
    @Override
    public void onLockedOut(int remainingSeconds) {
        isLockedOut = true;
        // 안면인식 버튼 비활성화
        btnFaceLogin.setEnabled(false);
        // 카운트다운 타이머 시작
        startLockCountdown(remainingSeconds); // onCountdownFinish 시 버튼 재활성화
    }

    // ── CASE 2: 재시도 ──────────────────────────────────────
    @Override
    public void onRetry(int failureCount) {
        // 남은 시도 횟수 안내 (MAX_FAILURE_COUNT_FOR_UI = 5)
        int maxCount = BiometricLibConstants.MAX_FAILURE_COUNT_FOR_UI;
        showRetryMessage(failureCount, maxCount);
        // 예: tvStatus.setText("인증 실패 " + failureCount + "회 / 최대 " + maxCount + "회");
    }

    // ── CASE 9: 계정 잠금 ────────────────────────────────────
    @Override
    public void onAccountLocked() {
        isAccountLocked = true;
        // 안면인식 버튼 숨기고 ID/PW 입력 영역 표시
        btnFaceLogin.setVisibility(View.GONE);
        layoutIdPwInput.setVisibility(View.VISIBLE);
    }

    // ── CASE 3: SESSION_EXPIRED 자동 재시도 중 ───────────────
    @Override
    public void onSessionRetrying(int retryCount, int maxRetry) {
        // 진행 상태 안내 메시지
        tvStatus.setText("인증을 다시 시도합니다... (" + retryCount + "/" + maxRetry + ")");
        // 버튼 비활성화 (재시도 완료 후 onSuccess/onError에서 재활성화)
        btnFaceLogin.setEnabled(false);
    }

    // ── CASE 5, 6, 8, 10, 11 등 오류 분기 ─────────────────────
    @Override
    public void onError(ErrorCode errorCode) {
        // 버튼 재활성화 (일부 케이스 제외)
        btnFaceLogin.setEnabled(true);

        switch (errorCode) {

            case BIOMETRIC_NONE_ENROLLED:
                // CASE 5: 기기에 얼굴 미등록
                btnFaceLogin.setEnabled(false);
                showNotEnrolledDialog(); // "설정으로 이동" 다이얼로그
                break;

            case BIOMETRIC_HW_UNAVAILABLE:
                // 하드웨어 미지원 또는 일시 불가
                showMessage("생체인식 기능을 사용할 수 없습니다. 잠시 후 다시 시도해주세요.");
                break;

            case KEY_INVALIDATED:
                // CASE 10: 서버 감지 키 무효화 → 키 갱신 다이얼로그 (Native)
                showKeyInvalidatedDialog();
                break;

            case DEVICE_NOT_FOUND:
                // CASE 7 변형: 로그인 시도 중 기기 미등록 감지
                showDeviceNotFoundDialog();
                break;

            case TIMESTAMP_OUT_OF_RANGE:
                // 기기 시간 불일치
                showTimestampErrorDialog(); // "날짜 및 시간 설정" 이동 유도
                break;

            case MISSING_SIGNATURE:
                // 서명 누락 (앱 내부 오류)
                showMissingSignatureDialog(); // 앱 재시작 유도
                break;

            case SESSION_EXPIRED:
                // CASE 11: 재시도 횟수 초과 후 최종 실패
                btnFaceLogin.setEnabled(true);
                showMessage("네트워크 불안정 또는 인증 시간 초과입니다. 다시 시도해주세요.");
                break;

            case NONCE_REPLAY:
                showMessage("보안 확인이 만료되었습니다. 다시 시도해주세요.");
                break;

            case INVALID_SIGNATURE:
                // CASE 6 임계 미달 시 (임계 도달 시에는 자동 키 재발급으로 처리됨)
                showMessage("기기 인증 정보가 맞지 않습니다. 로그인을 다시 시도해주세요.");
                break;

            case NETWORK_ERROR:
                // CASE 8: 네트워크 오류
                showMessage("네트워크 연결을 확인 후 다시 시도해주세요.");
                break;

            default:
                showMessage("알 수 없는 오류가 발생했습니다. 앱을 재시작하거나 헬프데스크로 문의해주세요.");
                break;
        }
    }
};
```

---

### 4-3. KEY_INVALIDATED 다이얼로그 (CASE 10)

```java
// CASE 10: 서버가 키 무효화 감지 → 사용자에게 키 재발급 안내
private void showKeyInvalidatedDialog() {
    if (isFinishing()) return;
    new AlertDialog.Builder(this)
            .setTitle("보안키 재설정 필요")
            .setMessage("얼굴 등 생체 정보가 변경되어 보안키를 다시 설정해야 합니다.\n확인 버튼을 누르면 키를 다시 설정합니다.")
            .setPositiveButton("확인", (d, w) -> {
                btnFaceLogin.setEnabled(false);
                // biometricAuthManager.startRenewal()
                //   역할: 새 Challenge 요청 없이 바로 키 재발급 → BiometricPrompt 재실행
                //   파라미터: activity (FragmentActivity), callback (AuthCallback)
                //   주의: 확인 버튼 클릭은 UI 스레드이므로 runOnUiThread 불필요
                biometricAuthManager.startRenewal(this, authCallback);
            })
            .setNegativeButton("취소", null)
            .setCancelable(false)
            .show();
}
```

---

### 4-4. ACCOUNT_LOCKED 상태에서 ID/PW 잠금 해제

```java
// CASE 9: 계정 잠금 상태에서 ID/PW로 잠금 해제
private void attemptIdPwUnlock(String userId, String password) {
    String deviceId = tokenStorage.getDeviceId();

    // 네트워크 작업이므로 백그라운드 스레드에서 실행
    A2Application.getBiometricExecutor().submit(() -> {
        try {
            // authApiClient.unlockDevice(): PUT /api/device/unlock
            //   파라미터: deviceId (String) — ANDROID_ID 기반 기기 식별자
            //   반환: boolean true (성공)
            //   예외: DeviceNotFoundException (HTTP 404), RuntimeException("NOT_LOCKED" — 400)
            // TODO: [실서비스] MIS 인증 서버에서 userId/password 검증 후 unlock 호출
            A2Application.getBiometricApiClient().unlockDevice(deviceId);
            tokenStorage.saveRegistration(deviceId, userId);
            isAccountLocked = false;

            runOnUiThread(() -> {
                // ID/PW 영역 숨기고 안면인식 버튼 다시 표시
                layoutIdPwInput.setVisibility(View.GONE);
                btnFaceLogin.setVisibility(View.VISIBLE);
                btnFaceLogin.setEnabled(true);
                showMessage("잠금이 해제되었습니다.");
            });

        } catch (Exception e) {
            runOnUiThread(() ->
                showMessage("잠금 해제에 실패했습니다. 관리자에게 문의하세요."));
        }
    });
}
```

---

### ✅ STEP 4 완료 확인 체크리스트

- [ ] 앱 진입점에서 `authApiClient.getUserId(deviceId)` 호출하여 서버 상태 조회됨
- [ ] `handleDeviceStatus()`에서 `ACTIVE` / `LOCKED` / `KEY_INVALIDATED` 분기 처리됨
- [ ] `AuthCallback.onError()`에서 `ErrorCode` 열거값별 분기 처리됨
- [ ] `KEY_INVALIDATED` 시 `biometricAuthManager.startRenewal()` 호출됨
- [ ] `onAccountLocked()` 시 ID/PW 입력 영역 표시됨
- [ ] `onLockedOut()` 시 카운트다운 타이머 시작됨
- [ ] `onSessionRetrying()` 시 버튼 비활성화 + 진행 메시지 표시됨
- [ ] 네트워크 호출은 반드시 백그라운드 스레드(`biometricExecutor`)에서 실행됨
- [ ] UI 업데이트는 반드시 `runOnUiThread()` 안에서 실행됨

---

## STEP 5. 등록 프로세스 이식 (사용자 변경 포함)

### 5-1. BiometricRegistrar.register() 호출

신규 사용자 등록 화면에서 `BiometricRegistrar.register()`를 호출합니다.

```java
// BiometricRegisterActivity.java
public class BiometricRegisterActivity extends AppCompatActivity {

    private BiometricRegistrar biometricRegistrar;
    // BiometricRegistrar: EC 키 생성 → 서버 등록 → 로컬 저장까지 등록 전체 흐름 관리

    private TokenStorage tokenStorage;
    private String deviceId; // ANDROID_ID 기반 기기 식별자

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_biometric_register);

        // deviceId: 서버가 기기를 식별하는 키 (ANDROID_ID 권장)
        deviceId = getIntent().getStringExtra("device_id");
        if (deviceId == null) {
            deviceId = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("TokenStorage 초기화 실패", e);
        }

        // BiometricRegistrar 생성자 파라미터:
        //   context: 앱 컨텍스트 (내부에서 applicationContext로 변환)
        //   authApiClient: B2 서버 통신
        //   ecKeyManager: 키 생성·공개키 추출
        //   tokenStorage: 등록 완료 후 deviceId/userId 저장
        //   ioExecutor: 네트워크 작업용 백그라운드 스레드풀
        biometricRegistrar = new BiometricRegistrar(
                this,
                A2Application.getBiometricApiClient(),
                A2Application.getEcKeyManager(),
                tokenStorage,
                A2Application.getBiometricExecutor());

        Button btnRegister = findViewById(R.id.btn_register);
        btnRegister.setOnClickListener(v -> startRegistration());
    }

    private void startRegistration() {
        // 사용자 ID 입력값 가져오기 (A2 사용자 ID 체계에 맞게 조정)
        String userId = etUserId.getText().toString().trim();

        // biometricRegistrar.register()
        //   파라미터:
        //     activity (FragmentActivity): BiometricPrompt 표시용 — AppCompatActivity가 상속하므로 OK
        //     deviceId (String): 기기 식별자 (ANDROID_ID) — null/빈값 불가
        //     userId (String): 사용자 ID — null/빈값 불가
        //     callback (RegisterCallback): 등록 결과 수신 콜백
        //   반환값: void (결과는 RegisterCallback으로 비동기 수신)
        //   주의: API 28 미만 → 즉시 onError(BIOMETRIC_HW_UNAVAILABLE) 호출
        //   주의: 기기에 안면인식 미등록 → 즉시 onError(BIOMETRIC_NONE_ENROLLED) 호출
        //   주의: 이미 등록된 기기 → onError(ALREADY_REGISTERED) 호출
        biometricRegistrar.register(this, deviceId, userId, registerCallback);
    }

    // ─── RegisterCallback 구현 ────────────────────────────────
    private final BiometricRegistrar.RegisterCallback registerCallback =
            new BiometricRegistrar.RegisterCallback() {

        @Override
        public void onSuccess(String userId) {
            // 등록 성공: tokenStorage에 deviceId/userId 자동 저장됨
            // userId: 등록된 사용자 ID
            runOnUiThread(() -> {
                Toast.makeText(BiometricRegisterActivity.this,
                        userId + " 님 등록 완료!", Toast.LENGTH_SHORT).show();
                // 로그인 화면으로 이동 (1초 딜레이 UX 개선)
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> navigateToLogin(),
                        BiometricLibConstants.UI_REDIRECT_DELAY_MS);
            });
        }

        //@Override
        //public void onError(ErrorCode errorCode) {
        //    // 등록 실패 처리
        //    runOnUiThread(() -> {
        //        switch (errorCode) {
        //            case BIOMETRIC_NONE_ENROLLED:
        //                // 기기에 얼굴 미등록 → 설정 화면 유도
        //                showNotEnrolledDialog();
        //                break;
        //            case BIOMETRIC_HW_UNAVAILABLE:
        //                showMessage("생체인식 하드웨어를 사용할 수 없습니다.");
        //                break;
        //            case ALREADY_REGISTERED:
        //                // 이미 등록된 기기 재등록 시도
        //                showMessage("이미 등록된 기기입니다. 담당자 변경을 이용해주세요.");
        //                break;
        //            case NETWORK_ERROR:
        //                showMessage("네트워크 오류. 연결 확인 후 다시 시도해주세요.");
        //                break;
        //            default:
        //                showMessage("등록 실패: " + errorCode.name());
        //                break;
        //        }
        //    });
        //}
    };
}
```

---

### 5-2. 사용자 변경 (CASE 12) — 별도 흐름

사용자 변경은 2단계로 진행됩니다.

**1단계:** PIN/패턴 인증 (`verifyDeviceCredential`)  
**2단계:** 서버 삭제 + 로컬 삭제 (`executeChange`)

```java
// AndroidBridge.showUserChangeDialog() 또는 로그인 Activity에서 직접 호출
private void showUserChangeDialog() {
    if (isFinishing()) return;
    new AlertDialog.Builder(this)
            .setTitle("담당자 변경")
            .setMessage("이 기기에 저장된 로그인·인증 정보가 삭제됩니다.\n계속하시겠습니까?")
            .setPositiveButton("확인", (dialog, which) -> {

                // 1단계: userChangeHandler.verifyDeviceCredential()
                //   역할: BiometricPrompt를 DEVICE_CREDENTIAL(PIN/패턴/비밀번호) 모드로 실행
                //   파라미터:
                //     activity (FragmentActivity): 현재 Activity
                //     callback (UserChangeCallback): 4개 콜백 메서드 구현 필요
                //   주의: 취소 시 onCanceled() 호출 — 별도 처리 없어도 됨
                userChangeHandler.verifyDeviceCredential(this, userChangeCallback);
            })
            .setNegativeButton("취소", null)
            .show();
}

private final UserChangeHandler.UserChangeCallback userChangeCallback =
        new UserChangeHandler.UserChangeCallback() {

    @Override
    public void onVerified() {
        // PIN 인증 성공 → 진행 스피너 표시 후 2단계 실행
        showProgress(true);

        // 2단계: userChangeHandler.executeChange()
        //   역할: ① 서버 기기 등록 삭제(unregisterDevice) → ② 로컬 키 삭제(deleteKeyPair)
        //          → ③ 로컬 등록 정보 삭제(clearAll) → onChangeCompleted() 호출
        //   파라미터:
        //     activity (FragmentActivity): 결과 콜백 runOnUiThread 대상
        //     callback (UserChangeCallback): 동일 콜백 인스턴스 전달
        //   주의: 서버에 기기 없어도(DeviceNotFoundException) 로컬 삭제 후 정상 진행됨
        userChangeHandler.executeChange(BiometricLoginActivity.this, this);
    }

    @Override
    public void onChangeCompleted() {
        // 삭제 완료 → 등록 화면으로 이동
        showProgress(false);
        Toast.makeText(BiometricLoginActivity.this,
                "삭제 완료. 신규 등록 화면으로 이동합니다.", Toast.LENGTH_SHORT).show();

        // 딜레이 후 등록 화면으로 이동
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> navigateToRegister(),
                BiometricLibConstants.UI_REDIRECT_DELAY_MS);
    }

    @Override
    public void onChangeFailed(ErrorCode errorCode) {
        showProgress(false);
        Toast.makeText(BiometricLoginActivity.this,
                "변경 실패. 헬프데스크로 문의해주세요.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCanceled() {
        // 사용자 취소 — 별도 처리 없음
        Log.d("UserChange", "사용자가 담당자 변경을 취소했습니다.");
    }
};
```

---

### 5-3. CASE 12 흐름 요약

```
사용자: "담당자 변경" 버튼 클릭
    ↓
showUserChangeDialog() — AlertDialog 확인
    ↓
UserChangeHandler.verifyDeviceCredential()
    → BiometricPrompt (DEVICE_CREDENTIAL 모드: PIN/패턴/비밀번호)
    → 성공: onVerified()
    ↓
UserChangeHandler.executeChange() [백그라운드]
    → ① authApiClient.unregisterDevice(deviceId, userId)  [DELETE /api/device/unregister]
    → ② ecKeyManager.deleteKeyPair()                       [Android Keystore 키 삭제]
    → ③ tokenStorage.clearAll()                            [EncryptedSharedPreferences 전체 삭제]
    → onChangeCompleted()
    ↓
BiometricRegisterActivity로 이동 (신규 사용자 등록)
```

---

### ✅ STEP 5 완료 확인 체크리스트

- [ ] `BiometricRegistrar.register()` 4개 파라미터 모두 전달됨
- [ ] `RegisterCallback.onSuccess()` / `onError()` 구현됨
- [ ] 등록 성공 후 로그인 화면으로 자동 이동됨
- [ ] `ALREADY_REGISTERED` 오류 처리됨 (재등록 방지 안내)
- [ ] `UserChangeHandler.verifyDeviceCredential()` → `executeChange()` 2단계 순서 준수됨
- [ ] `executeChange()` 콜백에 동일 `UserChangeCallback` 인스턴스 전달됨
- [ ] `onChangeCompleted()` 후 등록 화면으로 이동됨
- [ ] 서버 삭제 실패(`DeviceNotFoundException`)여도 로컬 삭제 후 정상 진행됨 (라이브러리 내부 처리)

---

## STEP 6. 토큰 생성 및 B2 서버 연동

### 6-1. 토큰 생성 흐름

토큰은 앱에서 직접 생성하지 않습니다. B2 서버가 ECDSA 서명 검증 후 발급합니다.

```
[앱] BiometricPrompt 인증 성공
    ↓
[앱 백그라운드] ecKeyManager.signPayload(payload)
    payload = "serverChallenge:clientNonce:deviceId:timestamp" (UTF-8 바이트)
    → ECDSA 서명 (Base64 인코딩)
    ↓
[앱 → 서버] authApiClient.requestToken(tokenReq)
    POST /api/auth/token
    {
        "session_id":    "챌린지 세션 ID",
        "device_id":     "ANDROID_ID",
        "user_id":       "사용자 ID",
        "ec_signature":  "Base64(ECDSA 서명)",
        "client_nonce":  "16바이트 랜덤 Hex",
        "timestamp":     1712345678901
    }
    ↓
[서버 → 앱] TokenResponse
    {
        "access_token":  "JWT 액세스 토큰",
        "refresh_token": "리프레시 토큰",
        "expires_in":    1800
    }
    ↓
[앱] tokenStorage.saveTokens(accessToken, refreshToken)
[앱] AuthCallback.onSuccess(userId, tokenResponse) 호출
```

---

### 6-2. onSuccess에서 토큰 수신 및 저장

```java
@Override
public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
    // tokenResponse.accessToken: B2 서버 발급 JWT 액세스 토큰
    //   - A2 API 호출 시 Authorization 헤더에 포함: "Bearer " + accessToken
    //   - tokenStorage에 이미 자동 저장됨 (laibrary 내부 처리)
    // tokenResponse.refreshToken: 토큰 갱신용 리프레시 토큰
    //   - tokenStorage에 이미 자동 저장됨
    // tokenResponse.expiresIn: 만료 시간(초)
    //   - 0이면 BiometricLibConstants.TOKEN_EXPIRES_IN_DEFAULT_SEC(1800초 = 30분) 사용

    Log.d(TAG, "로그인 성공 userId=" + userId
            + " expiresIn=" + tokenResponse.expiresIn);

    // A2 세션에 토큰 저장 (A2 자체 세션 관리 방식에 맞게 구현)
    A2SessionManager.saveAccessToken(tokenResponse.accessToken);
    A2SessionManager.saveRefreshToken(tokenResponse.refreshToken);

    // 만료 시각 계산 (현재 시각 + expiresIn초)
    int expiresIn = tokenResponse.expiresIn > 0
            ? tokenResponse.expiresIn
            : BiometricLibConstants.TOKEN_EXPIRES_IN_DEFAULT_SEC;
    long expireAt = System.currentTimeMillis() + (expiresIn * 1000L);
    A2SessionManager.saveTokenExpireAt(expireAt);

    // 메인 화면 이동 (1초 딜레이)
    new Handler(Looper.getMainLooper()).postDelayed(
            () -> navigateToMain(userId, tokenResponse),
            BiometricLibConstants.UI_REDIRECT_DELAY_MS);
}
```

---

### 6-3. 토큰 만료 처리 및 갱신

라이브러리는 토큰 갱신 API를 직접 제공하지 않습니다. 만료 시 **재로그인(안면인식 재실행)**이 원칙입니다.

```java
// A2에서 API 호출 전 토큰 유효성 확인 예시
public void callA2Api(String endpoint) {
    // tokenStorage.getAccessToken(): 현재 저장된 액세스 토큰 반환 (없으면 null)
    String accessToken = tokenStorage.getAccessToken();

    if (accessToken == null) {
        // 토큰 없음 → 로그인 화면으로 이동
        navigateToLogin();
        return;
    }

    // 만료 시각 체크 (A2 세션 관리에서 저장한 expireAt 기준)
    long expireAt = A2SessionManager.getTokenExpireAt();
    if (System.currentTimeMillis() > expireAt) {
        // 토큰 만료 → 재로그인 요청
        Toast.makeText(this, "세션이 만료되었습니다. 다시 로그인해주세요.", Toast.LENGTH_SHORT).show();
        navigateToLogin();
        return;
    }

    // 유효한 토큰으로 API 호출
    // Authorization: Bearer {accessToken}
    makeApiCall(endpoint, "Bearer " + accessToken);
}
```

---

### 6-4. 주요 API 엔드포인트 정리

| 용도 | HTTP 메서드 | 경로 |
|------|-------------|------|
| 기기 상태·사용자 조회 | GET | `/api/device/user-id?device_id={id}` |
| 기기 등록 | POST | `/api/device/register` |
| 기기 등록 해제 | DELETE | `/api/device/unregister` |
| 잠금 해제 | PUT | `/api/device/unlock` |
| 키 무효화 상태 업데이트 | PUT | `/api/device/update-key` |
| 공개키 갱신 | PUT | `/api/device/renew-key` |
| 챌린지 발급 | POST | `/api/auth/challenge` |
| 토큰 발급 | POST | `/api/auth/token` |
| 계정 잠금 | POST | `/api/auth/account-lock` |
| 실패 정책 조회 | GET | `/api/policy/failure-config?device_id={id}` |

---

### ✅ STEP 6 완료 확인 체크리스트

- [ ] `onSuccess()` 콜백에서 `tokenResponse.accessToken` 수신 및 A2 세션에 저장됨
- [ ] 토큰 만료 시각(`expiresIn`) 계산 및 저장됨
- [ ] `expiresIn`이 0일 때 기본값(`TOKEN_EXPIRES_IN_DEFAULT_SEC = 1800`) 적용됨
- [ ] A2 API 호출 전 토큰 유효성(null 체크 + 만료 시각) 확인됨
- [ ] 만료 시 재로그인 유도 로직 구현됨
- [ ] API 호출 시 `Authorization: Bearer {accessToken}` 헤더 포함됨

---

## STEP 7. 예외 처리 체크리스트

### 7-1. CASE별 예외 상황 및 처리 방법

| CASE | 예외 클래스 / ErrorCode | 발생 위치 | 처리 방법 |
|------|------------------------|-----------|-----------|
| CASE 1 | — (정상) | — | `onSuccess()` 콜백 정상 처리 |
| CASE 2 | `onAuthenticationFailed()` | BiometricPrompt | `failurePolicyManager.recordFailure()` → `onRetry()` |
| CASE 3 | `TokenVerificationException("SESSION_EXPIRED")` | `requestToken()` | 자동 재시도 (라이브러리 내부 처리) |
| CASE 4 | `isLocallyLocked()` = true | `authenticate()` 사전 체크 | `onLockedOut(remainingSeconds)` |
| CASE 5 | `BIOMETRIC_ERROR_NONE_ENROLLED` | `canAuthenticate()` | `onError(BIOMETRIC_NONE_ENROLLED)` + 설정 유도 |
| CASE 6 | `TokenVerificationException("INVALID_SIGNATURE")` 3회 | `requestToken()` | 자동 키 재발급 (라이브러리 내부 처리) |
| CASE 7 | `DeviceNotFoundException` | `getChallenge()` | `onNotRegistered()` → 등록 화면 이동 |
| CASE 8 | `IOException` | 모든 네트워크 호출 | `onError(NETWORK_ERROR)` |
| CASE 9 | `shouldRequestAccountLock()` = true | `onAuthenticationFailed()` | `lockAccount()` → `onAccountLocked()` |
| CASE 10 | `KeyInvalidatedException` | `getChallenge()` HTTP 409 | `onError(KEY_INVALIDATED)` → `startRenewal()` |
| CASE 11 | `sessionRetryCount >= MAX_SESSION_RETRY` | SESSION_EXPIRED 재시도 중 | `onError(SESSION_EXPIRED)` |
| CASE 12 | `DeviceNotFoundException` (서버 삭제 시) | `unregisterDevice()` | 로컬 삭제 후 정상 진행 (라이브러리 내부 처리) |

---

### 7-2. `runOnUiThread()` 필요 시점

| 상황 | 처리 방법 |
|------|-----------|
| `AuthCallback` 모든 콜백 메서드 | **불필요** — 라이브러리 내부에서 `runOnUiThread()` 처리 후 전달 |
| `RegisterCallback` 모든 콜백 메서드 | **불필요** — 라이브러리 내부에서 `runOnUiThread()` 처리 후 전달 |
| `UserChangeCallback` 모든 콜백 메서드 | **불필요** — 라이브러리 내부에서 `runOnUiThread()` 처리 후 전달 |
| `@JavascriptInterface` 메서드 | **필요** — WebView 브릿지는 백그라운드 스레드에서 호출됨 |
| `biometricExecutor.submit()` 내부 UI 작업 | **필요** — `runOnUiThread()` 또는 `activity.runOnUiThread()` 사용 |
| `AlertDialog` 표시 | **필요** — 반드시 UI 스레드에서 실행 |
| `webView.evaluateJavascript()` 호출 | **필요** — 메인 스레드에서만 동작 |

---

### 7-3. 추가 주의사항

**Activity 생명주기 관련:**
```java
// onDestroy()에서 반드시 처리해야 할 항목
@Override
protected void onDestroy() {
    // 예약된 화면 전환 취소 (Handler.postDelayed 사용 시)
    if (pendingNavigation != null) {
        mainHandler.removeCallbacks(pendingNavigation);
        pendingNavigation = null;
    }

    // 잠금 카운트다운 타이머 해제 (CountDownTimer 사용 시)
    if (lockCountDownTimer != null) {
        lockCountDownTimer.cancel();
        lockCountDownTimer = null;
    }

    // WebView 사용 시 리소스 해제
    if (webView != null) {
        webView.stopLoading();
        webView.destroy();
    }

    // biometricAuthManager.shutdown()은 no-op이므로 생략 가능
    // biometricExecutor.shutdown()은 Application이 관리하므로 절대 호출 금지

    super.onDestroy();
}
```

**AlertDialog 안전 표시:**
```java
// isFinishing() 체크: Activity 종료 중 다이얼로그 표시 방지
private void showSomeDialog() {
    if (isFinishing()) return; // 반드시 체크
    new AlertDialog.Builder(this)
            ...
            .show();
}
```

**`TokenStorage` 초기화 실패 처리:**
```java
// EncryptedSharedPreferences 초기화 실패는 재설치 없이 복구 불가
// 사용자에게 앱 데이터 초기화 안내 필요
try {
    tokenStorage = new TokenStorage(this);
} catch (GeneralSecurityException | IOException e) {
    Log.e(TAG, "TokenStorage 초기화 실패 — 앱 데이터 초기화 필요", e);
    // 실서비스: "앱 데이터를 초기화하고 재설치해 주세요" 다이얼로그 표시
    showFatalError("보안 저장소 초기화 실패. 앱을 재설치해 주세요.");
}
```

---

### 7-4. 실서비스 전환 시 필수 TODO 목록

| 항목 | 내용 |
|------|------|
| 서버 URL | `BuildConfig.BIOMETRIC_SERVER_URL`을 운영 HTTPS 주소로 교체 |
| Certificate Pinning | OkHttp에 SSL 인증서 피닝 적용 (`AuthApiClient` 수정 필요) |
| ID/PW 검증 | `unlockDevice()` 전 MIS 인증 서버에서 userId/password 검증 연동 |
| device_id 마스킹 | 로그 출력 시 ANDROID_ID 마스킹 처리 |
| 정책 원격 관리 | `MAX_SESSION_RETRY`, `INVALID_SIGNATURE_RENEWAL_THRESHOLD` 등을 서버 정책 API로 통일 |
| 키 재발급 감사 로그 | `KeyRenewalHandler.renewAndRetry()` 시 서버 보안 이벤트 기록 |
| DI 프레임워크 | `Application` 전역 싱글턴을 Hilt/Dagger로 교체 검토 |
| `PREFS_NAME` | `BiometricLibConstants.PREFS_NAME`을 A2 패키지명 기반으로 변경 |
| BiometricPrompt 문자열 | 라이브러리 내 하드코딩 문자열을 앱 레이어에서 주입 구조로 개선 |

---

### ✅ STEP 7 완료 확인 체크리스트

- [ ] 모든 `AlertDialog` 표시 전 `isFinishing()` 체크 추가됨
- [ ] `onDestroy()`에서 `Handler.postDelayed` Runnable 취소됨
- [ ] `onDestroy()`에서 `CountDownTimer.cancel()` 호출됨
- [ ] `biometricExecutor.shutdown()`이 Activity에서 호출되지 않음
- [ ] `@JavascriptInterface` 메서드 내 UI 작업에 `runOnUiThread()` 적용됨
- [ ] `TokenStorage` 초기화 실패 예외 처리됨
- [ ] 운영 배포 전 서버 URL이 HTTPS로 교체됨
- [ ] 개발 중 `BuildConfig.DEBUG` 분기로 로그 레벨 조정됨
- [ ] ANDROID_ID를 로그에 출력할 경우 마스킹 처리됨

---

## 참고: 컴포넌트 의존 관계도

```
A2Application (전역 싱글턴)
├── AuthApiClient        — B2 서버 HTTP 통신 (OkHttp)
├── EcKeyManager         — Android Keystore ECDSA 키 관리
└── ExecutorService      — 백그라운드 스레드풀 (최대 4개)

BiometricLoginActivity (로그인 화면)
├── BiometricAuthManager (인증 오케스트레이터)
│   ├── AuthApiClient    — 챌린지·토큰·정책 조회
│   ├── EcKeyManager     — payload 서명
│   ├── TokenStorage     — 토큰 저장·조회
│   ├── FailurePolicyManager — 실패 횟수·잠금 관리
│   └── KeyRenewalHandler   — CASE6/CASE10 키 재발급
├── UserChangeHandler    — CASE12 사용자 변경
└── TokenStorage         — 등록 정보·토큰 저장소

BiometricRegisterActivity (등록 화면)
└── BiometricRegistrar   — 기기 등록 오케스트레이터
    ├── AuthApiClient    — 서버 등록 요청
    ├── EcKeyManager     — EC 키쌍 생성·공개키 추출
    └── TokenStorage     — 등록 완료 후 저장
```

---

*작성일: 2026-04-10 | 기준 소스: biometric-poc (PoC 프로젝트)*
