# 안면인식 AAR 이식 가이드 — A2 프로젝트 적용

> **대상 AAR:** `biometric-lib-release.aar` (패키지: `com.skcc.biometric.lib`)  
> **대상 환경:** A2 (AGP 7.x, compileSdk 28/29, minSdk 23, Java 8, Android 9.0+)  
> **원칙:** A2 기존 코드 최소 변경. AAR이 인증 로직 전체를 캡슐화.

---

## 목차

1. [전체 CASE 목록](#전체-case-목록)
2. [STEP 1. 사전 준비](#step-1-사전-준비)
3. [STEP 2. 초기화 코드 이식](#step-2-초기화-코드-이식)
4. [STEP 3. 로그인 화면 버튼 추가](#step-3-로그인-화면-버튼-추가)
5. [STEP 4. CASE별 분기 처리 이식](#step-4-case별-분기-처리-이식)
6. [STEP 5. 등록 프로세스 이식](#step-5-등록-프로세스-이식)
7. [STEP 6. 토큰 생성 및 B2 서버 연동](#step-6-토큰-생성-및-b2-서버-연동)
8. [STEP 7. 예외 처리 체크리스트](#step-7-예외-처리-체크리스트)

---

## 전체 CASE 목록

### 인증 흐름 전체 구조

```
앱 진입 (기기 상태 조회)
 ├─ CASE 1  : 미등록 기기 → 등록 화면
 ├─ CASE 2  : ACTIVE → 로그인 화면
 ├─ CASE 9  : LOCKED(서버) → ID/PW 잠금 해제
 └─ CASE 10 : KEY_INVALIDATED(기기 상태) → 키 갱신 다이얼로그

로그인 화면 (BiometricAuthManager.authenticate)
 ├─ CASE 7  : 안면인식 미등록 → 설정 유도 다이얼로그
 ├─ CASE 8  : 생체인식 HW 불가 → 오류 메시지
 ├─ CASE 5  : 로컬 잠금 진행 중 → 카운트다운
 ├─ CASE 4  : 인증 실패 (재시도) → 실패 횟수 표시
 ├─ CASE 3  : SESSION_EXPIRED → 자동 재시도
 ├─ CASE 6  : INVALID_SIGNATURE 3회 → 자동 키 재발급
 ├─ CASE 10 : KEY_INVALIDATED(서버 감지) → 키 갱신 다이얼로그
 └─ CASE 11 : ACCOUNT_LOCKED → ID/PW 잠금 해제 UI

사용자 변경
 └─ CASE 12 : 담당자 변경 → PIN 인증 → 서버/로컬 삭제 → 등록 화면
```

---

### CASE 1 — 신규(미등록) 기기

| 항목 | 내용 |
|------|------|
| **상태명** | `DEVICE_NOT_FOUND` |
| **발생 조건** | 앱 최초 실행 또는 서버에 기기 등록 정보 없음 |
| **호출 흐름** | `AuthApiClient.getUserId(deviceId)` → HTTP 404 → `DeviceNotFoundException` 발생 |
| **A2 대응** | 등록 화면(`RegisterActivity` 대응 화면)으로 이동. `deviceId`(ANDROID_ID)를 Intent Extra로 전달 |

---

### CASE 2 — 등록 완료 기기 (ACTIVE)

| 항목 | 내용 |
|------|------|
| **상태명** | `ACTIVE` |
| **발생 조건** | 서버 기기 상태 = `"ACTIVE"` |
| **호출 흐름** | `getUserId()` → 응답 `status = "ACTIVE"` → `tokenStorage.saveRegistration()` → 로그인 화면 이동 |
| **A2 대응** | 로컬 등록 플래그 저장 후 안면인식 로그인 화면으로 라우팅 |

---

### CASE 3 — 챌린지 세션 만료 (SESSION_EXPIRED) 자동 재시도

| 항목 | 내용 |
|------|------|
| **상태명** | `SESSION_EXPIRED` |
| **발생 조건** | 챌린지 발급 후 60초 이내 서명 미완료 → 서버가 `SESSION_EXPIRED` 반환 |
| **호출 흐름** | `requestToken()` → `TokenVerificationException("SESSION_EXPIRED")` → `sessionRetryCount < 2` 이면 `onSessionRetrying()` 콜백 → Challenge 재요청 → BiometricPrompt 재실행 |
| **A2 대응** | `onSessionRetrying(retryCount, maxRetry)` 수신 시 "재시도 중..." 메시지 표시. 2회 초과 시 `onError(SESSION_EXPIRED)` |

---

### CASE 4 — 안면인식 실패 (재시도 가능)

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_AUTH_FAILED` |
| **발생 조건** | `BiometricPrompt.onAuthenticationFailed()` 호출 (얼굴 불일치, 잠금 임계 미도달) |
| **호출 흐름** | `onAuthenticationFailed()` → `failurePolicyManager.recordFailure()` → `onRetry(failureCount)` 콜백 |
| **A2 대응** | 실패 횟수 UI 표시 ("N회 실패 / 최대 5회") |

---

### CASE 5 — 로컬 잠금 (일시적)

| 항목 | 내용 |
|------|------|
| **상태명** | `LOCAL_LOCKOUT` |
| **발생 조건** | 인증 실패 횟수 ≥ 서버 정책 `maxRetryBeforeLockout` → `isLocallyLocked()` = true |
| **호출 흐름** | `onAuthenticationFailed()` → `isLocallyLocked()` 체크 → `onLockedOut(remainingSeconds)` 콜백 |
| **A2 대응** | 카운트다운 타이머 UI 표시. 시간 경과 후 자동 재시도 활성화 |

---

### CASE 6 — INVALID_SIGNATURE 자동 키 재발급

| 항목 | 내용 |
|------|------|
| **상태명** | `INVALID_SIGNATURE` → 자동 `KEY_RENEWAL` |
| **발생 조건** | 서버가 `INVALID_SIGNATURE` 3회 연속 반환 (`invalidSignatureCount >= 3`) |
| **호출 흐름** | `TokenVerificationException("INVALID_SIGNATURE")` → 카운터 증가 → 3회 도달 시 `KeyRenewalHandler.renewAndRetry()` → 새 EC 키쌍 생성 → 서버 공개키 갱신 → BiometricPrompt 재실행 |
| **A2 대응** | 자동 처리 (별도 UI 불필요). BiometricPrompt가 자동 재표시됨 |

---

### CASE 7 — 안면인식 미등록

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_NONE_ENROLLED` |
| **발생 조건** | `canAuthenticate(BIOMETRIC_WEAK)` = `BIOMETRIC_ERROR_NONE_ENROLLED` |
| **호출 흐름** | `authenticate()` → `canAuthenticate()` 체크 → `onError(BIOMETRIC_NONE_ENROLLED)` 콜백 |
| **A2 대응** | "안면인식 미등록" 다이얼로그 → "설정으로 이동" 버튼으로 생체인식 설정 화면 유도 |

---

### CASE 8 — 생체인식 하드웨어 불가

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_HW_UNAVAILABLE` |
| **발생 조건** | `canAuthenticate()` 결과가 `BIOMETRIC_SUCCESS`도 `NONE_ENROLLED`도 아닌 경우, 또는 API < 28 |
| **호출 흐름** | `authenticate()` → `onError(BIOMETRIC_HW_UNAVAILABLE)` 콜백 |
| **A2 대응** | "생체인식 기능 사용 불가" 오류 메시지 표시 |

---

### CASE 9 — 서버 잠금 (LOCKED)

| 항목 | 내용 |
|------|------|
| **상태명** | `LOCKED` |
| **발생 조건** | 서버 기기 상태 = `"LOCKED"` (관리자 잠금 또는 서버 측 다중 실패) |
| **호출 흐름** | `getUserId()` → `status = "LOCKED"` → ID/PW 잠금 해제 UI 표시 → `AuthApiClient.unlockDevice(deviceId)` 호출 |
| **A2 대응** | ID/PW 입력 폼 표시. 인증 성공 후 `unlockDevice()` 호출 → 등록 플래그 저장 → 로그인 화면 이동 |

---

### CASE 10 — Keystore 키 무효화 (KEY_INVALIDATED)

| 항목 | 내용 |
|------|------|
| **상태명** | `KEY_INVALIDATED` |
| **발생 조건** | ① 서버 기기 상태 = `"KEY_INVALIDATED"` (기기 상태 조회 시) ② 로그인 시 서버 409 KEY_INVALIDATED ③ 로컬 `KeyPermanentlyInvalidatedException` |
| **호출 흐름** | `onError(KEY_INVALIDATED)` → "보안키 재설정" 다이얼로그 → `biometricAuthManager.startRenewal()` → `KeyRenewalHandler.renewAndRetry()` → 키 재발급 → BiometricPrompt 재실행 |
| **A2 대응** | AlertDialog 표시 필수. 확인 시 `startRenewal()` 호출. 자동 복구 흐름 |

---

### CASE 11 — 계정 잠금 (ACCOUNT_LOCKED)

| 항목 | 내용 |
|------|------|
| **상태명** | `ACCOUNT_LOCKED` |
| **발생 조건** | 인증 실패 횟수 ≥ 서버 정책 `accountLockThreshold` → 서버 `lockAccount()` 호출 완료 |
| **호출 흐름** | `onAuthenticationFailed()` → `shouldRequestAccountLock()` = true → `authApiClient.lockAccount()` → `onAccountLocked()` 콜백 |
| **A2 대응** | ID/PW 입력 UI 표시 (안면인식 버튼 숨김). ID/PW 인증 성공 후 `unlockDevice()` 호출 |

---

### CASE 12 — 사용자 변경 (담당자 변경)

| 항목 | 내용 |
|------|------|
| **상태명** | `USER_CHANGE` |
| **발생 조건** | 담당자 변경 버튼 클릭 |
| **호출 흐름** | 확인 다이얼로그 → `userChangeHandler.verifyDeviceCredential()` → PIN/패턴 인증 → `onVerified()` → `userChangeHandler.executeChange()` → 서버 `unregisterDevice()` → 로컬 키 삭제 → `tokenStorage.clearAll()` → `onChangeCompleted()` → 등록 화면 이동 |
| **A2 대응** | 버튼 클릭 → AlertDialog → PIN 인증 → 서버/로컬 초기화 → 등록 화면 |

---

## STEP 1. 사전 준비

### 1-1. AAR 파일 복사

```
[biometric-android 빌드 결과]
biometric-lib/build/outputs/aar/biometric-lib-release.aar

[A2 프로젝트 배치 위치]
A2_PROJECT_ROOT/
  └── app/
       └── libs/
            └── biometric-lib-release.aar   ← 여기에 복사
```

### 1-2. `app/build.gradle` 수정

```groovy
android {
    // A2의 기존 compileSdk 유지 (28 또는 29)
    compileSdk 29

    defaultConfig {
        minSdk 23       // A2 기존값 유지
        targetSdk 29    // A2 기존값 유지
    }

    compileOptions {
        // AAR이 Java 8 바이트코드로 빌드되어 있으므로 Java 8 이상 필요
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

repositories {
    // libs 폴더의 AAR을 인식하기 위한 로컬 저장소 선언
    flatDir {
        dirs 'libs'
    }
}

dependencies {
    // ── biometric-lib AAR ─────────────────────────────────────────────
    // name: AAR 파일명에서 확장자 제외, ext: 'aar' 필수
    implementation(name: 'biometric-lib-release', ext: 'aar')

    // ── AAR 전이 의존성 (AAR이 직접 번들링하지 않으므로 A2에서 선언 필요) ──
    // 생체인증 — AndroidX BiometricPrompt 래퍼
    implementation 'androidx.biometric:biometric:1.1.0'

    // 암호화 저장소 — EncryptedSharedPreferences (Android 12+ 호환)
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'

    // Fragment — BiometricPrompt 내부 의존
    implementation 'androidx.fragment:fragment:1.4.1'

    // 네트워크 — OkHttp 4.x (Kotlin stdlib 포함)
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'

    // JSON — A2 기존 gson 버전 사용 (compileOnly이므로 A2 버전 우선)
    // A2에 gson이 없다면 아래 줄 추가
    // implementation 'com.google.code.gson:gson:2.8.9'
}
```

> **주의:** `flatDir`을 사용할 경우 전이 의존성이 자동 해석되지 않으므로  
> 위 `dependencies` 블록에 AAR의 런타임 의존성을 반드시 직접 선언해야 합니다.

### 1-3. `AndroidManifest.xml` 추가 항목

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 안면인식 사용 권한 (Android 9.0+) -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <!-- 네트워크 (B2 서버 통신) — A2에 이미 있으면 생략 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 안면인식 HW 선언 — required="false": 없어도 설치 가능 -->
    <uses-feature
        android:name="android.hardware.biometrics.face"
        android:required="false" />

    <application
        ...
        <!-- HTTP 개발 서버 접근 허용 시 — 운영에서는 반드시 제거 -->
        android:usesCleartextTraffic="true">

        <!-- 안면인식 로그인을 사용하는 기존 Activity에 추가 설정 불필요 -->
        <!-- BiometricPrompt는 FragmentActivity 기반이면 동작 -->

    </application>
</manifest>
```

> **주의:** `android:usesCleartextTraffic="true"` 는 개발 환경 전용입니다.  
> 운영 배포 시 `network_security_config.xml`로 HTTPS 도메인만 허용하도록 교체하세요.

### ✅ STEP 1 완료 확인 체크리스트

- [ ] `app/libs/biometric-lib-release.aar` 파일 존재 확인
- [ ] `build.gradle` `flatDir` + AAR `implementation` 선언
- [ ] 전이 의존성 5종 (`biometric`, `security-crypto`, `fragment`, `okhttp`, `gson`) 선언
- [ ] `AndroidManifest.xml` `USE_BIOMETRIC` 권한 추가
- [ ] `uses-feature android.hardware.biometrics.face` 추가
- [ ] Gradle Sync 성공 확인

---

## STEP 2. 초기화 코드 이식

### 2-1. Application 클래스 초기화

A2의 `Application` 클래스(또는 App-level singleton)에 아래 객체를 초기화합니다.

```java
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.network.AuthApiClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class A2Application extends Application {

    // ── 싱글턴 인스턴스 ───────────────────────────────────────────────
    // 앱 생명주기 동안 한 번만 생성. Activity마다 재생성하면 연결 풀 낭비.
    private static AuthApiClient authApiClient;
    private static EcKeyManager  ecKeyManager;
    private static ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();

        // 백그라운드 스레드 풀 — 생체인증 API 호출에 사용
        // 스레드 수는 앱 부하에 맞게 조정 (기본 4개)
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);           // 앱 종료 시 자동 정리
            t.setName("biometric-io");
            return t;
        });

        // B2 인증 서버 URL — BuildConfig 또는 상수로 관리
        // TODO: [실서비스] 운영 서버 HTTPS URL로 교체
        authApiClient = new AuthApiClient("https://your-b2-server.com");

        // Android Keystore EC 키 별칭 — 앱 패키지명 기반으로 설정
        // 다른 앱의 Keystore 항목과 충돌 방지
        ecKeyManager = new EcKeyManager(
            BuildConfig.APPLICATION_ID + ".biometric_ec_key");
    }

    /** 앱 전역 공유 AuthApiClient — B2 서버 통신 전용 */
    public static AuthApiClient getAuthApiClient() { return authApiClient; }

    /** 앱 전역 공유 EcKeyManager — EC 키쌍 생성·서명 담당 */
    public static EcKeyManager getEcKeyManager() { return ecKeyManager; }

    /** 앱 전역 공유 ExecutorService — 생체인증 비동기 작업용 */
    public static ExecutorService getExecutor() { return executor; }
}
```

> **AndroidManifest.xml 등록 필수:**
> ```xml
> <application
>     android:name=".A2Application"
>     ...>
> ```

### 2-2. 로그인 Activity에서 컴포넌트 초기화

```java
import com.skcc.biometric.lib.auth.BiometricAuthManager;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.policy.FailurePolicyManager;
import com.skcc.biometric.lib.storage.TokenStorage;

public class A2LoginActivity extends AppCompatActivity {
    // AppCompatActivity 필수 — FragmentActivity 상속, BiometricPrompt 요건

    private BiometricAuthManager biometricAuthManager;
    // 안면인식 인증 전체를 오케스트레이션. Challenge 발급 → BiometricPrompt → 토큰 발급.

    private UserChangeHandler userChangeHandler;
    // 사용자 변경(CASE 12): PIN 인증 → 서버/로컬 데이터 삭제 → 등록 화면 이동.

    private TokenStorage tokenStorage;
    // EncryptedSharedPreferences 기반 토큰·등록 정보 암호화 저장소.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── TokenStorage 초기화 ──────────────────────────────────────
        // GeneralSecurityException, IOException 처리 필수
        // 실패 시 앱 크래시 방지를 위해 반드시 try-catch 처리
        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            // 초기화 실패 시 사용자에게 안내 후 종료 (토스트 또는 AlertDialog)
            Toast.makeText(this, "보안 저장소 초기화 실패. 앱을 재설치해 주세요.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // ── FailurePolicyManager 초기화 ──────────────────────────────
        // 로컬 실패 카운트·잠금 시간 관리. 서버 정책은 첫 authenticate() 시 자동 로드.
        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();

        // ── BiometricAuthManager 초기화 ──────────────────────────────
        // context: AppCompatActivity (FragmentActivity 필수)
        // 나머지 인자는 Application 싱글턴에서 주입
        biometricAuthManager = new BiometricAuthManager(
            this,                               // context (FragmentActivity)
            A2Application.getAuthApiClient(),   // B2 서버 통신 클라이언트
            A2Application.getEcKeyManager(),    // EC 키쌍 관리자
            tokenStorage,                       // 암호화 저장소
            failurePolicyManager,               // 실패 정책 관리자
            A2Application.getExecutor()         // 백그라운드 스레드 풀
        );

        // ── UserChangeHandler 초기화 ─────────────────────────────────
        // CASE 12 담당자 변경 처리. PIN 인증 → 서버/로컬 삭제.
        userChangeHandler = new UserChangeHandler(
            this,
            A2Application.getEcKeyManager(),
            tokenStorage,
            A2Application.getAuthApiClient(),
            A2Application.getExecutor()
        );

        // 이후 UI 초기화 및 버튼 이벤트 설정
        setupUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // BiometricAuthManager의 executor는 Application이 관리하므로
        // Activity에서 shutdown() 호출 금지 — 앱 프로세스 종료 시 OS가 정리
    }
}
```

### ✅ STEP 2 완료 확인 체크리스트

- [ ] `A2Application` 에 `authApiClient`, `ecKeyManager`, `executor` 싱글턴 초기화
- [ ] `AndroidManifest.xml` `android:name=".A2Application"` 등록
- [ ] 로그인 Activity가 `AppCompatActivity` 상속 확인
- [ ] `TokenStorage` 초기화 try-catch 처리 및 실패 시 graceful 처리
- [ ] `BiometricAuthManager`, `UserChangeHandler` 생성자 인자 6종 확인

---

## STEP 3. 로그인 화면 버튼 추가

A2 로그인 화면에 아래 2개 버튼을 추가합니다.

### 3-1. 버튼 레이아웃 (XML)

```xml
<!-- A2 로그인 화면 레이아웃에 추가 -->

<!-- 안면인식 로그인 버튼 -->
<Button
    android:id="@+id/btn_face_login"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="안면인식 로그인"
    android:enabled="false" />
<!-- 최초에는 비활성화. onResume에서 canAuthenticate() 결과에 따라 활성화 -->

<!-- 담당자 변경 버튼 -->
<Button
    android:id="@+id/btn_user_change"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="담당자 변경" />
```

### 3-2. 버튼 클릭 이벤트

```java
private void setupUi() {
    // ── 안면인식 로그인 버튼 ─────────────────────────────────────────
    Button btnFaceLogin = findViewById(R.id.btn_face_login);
    btnFaceLogin.setOnClickListener(v -> startFaceLogin());

    // ── 담당자 변경 버튼 ─────────────────────────────────────────────
    Button btnUserChange = findViewById(R.id.btn_user_change);
    btnUserChange.setOnClickListener(v -> showUserChangeDialog());
}

/**
 * 안면인식 로그인 시작.
 * 내부적으로 백그라운드에서 Challenge 발급 → UI 스레드에서 BiometricPrompt 표시.
 *
 * @주의 반드시 UI 스레드에서 호출. BiometricPrompt는 메인 스레드 필요.
 */
private void startFaceLogin() {
    // BiometricAuthManager.authenticate():
    //   파라미터 1 - activity: FragmentActivity (AppCompatActivity 포함)
    //   파라미터 2 - callback: AuthCallback (CASE 3~8, 10~11 분기)
    //   반환값: 없음 (결과는 콜백으로 수신)
    biometricAuthManager.authenticate(this, authCallback);
}

/**
 * 담당자 변경 확인 다이얼로그 표시.
 * 확인 후 UserChangeHandler.verifyDeviceCredential() → PIN 인증 → executeChange().
 *
 * @주의 반드시 UI 스레드에서 호출.
 */
private void showUserChangeDialog() {
    new AlertDialog.Builder(this)
        .setTitle("담당자 변경")
        .setMessage("이 기기에 저장된 로그인·인증 정보가 삭제됩니다.\n계속하시겠습니까?")
        .setPositiveButton("확인", (dialog, which) ->
            // UserChangeHandler.verifyDeviceCredential():
            //   파라미터 1 - activity: FragmentActivity
            //   파라미터 2 - callback: UserChangeCallback (onVerified/onChangeCompleted/onChangeFailed/onCanceled)
            userChangeHandler.verifyDeviceCredential(this, userChangeCallback))
        .setNegativeButton("취소", null)
        .show();
}
```

### 3-3. onResume — 안면인식 등록 여부 실시간 확인

```java
@Override
protected void onResume() {
    super.onResume();

    // 안면인식 등록 여부를 onResume마다 확인 — 설정 화면 복귀 후 상태 반영
    Button btnFaceLogin = findViewById(R.id.btn_face_login);
    if (btnFaceLogin == null) return; // 조기 finish() 후 null 가드

    int canAuth = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);

    if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
        // 안면인식 등록됨 → 버튼 활성화
        btnFaceLogin.setEnabled(true);
    } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
        // 안면인식 미등록 → 버튼 비활성화 (CASE 7 예방)
        btnFaceLogin.setEnabled(false);
        showNotEnrolledDialog(); // 아래 STEP 7 참고
    }
}
```

### ✅ STEP 3 완료 확인 체크리스트

- [ ] 안면인식 로그인 버튼 (`btn_face_login`) 레이아웃 추가 및 초기 비활성화
- [ ] 담당자 변경 버튼 (`btn_user_change`) 레이아웃 추가
- [ ] `onResume()` 에서 `canAuthenticate()` 호출 및 버튼 상태 동적 제어
- [ ] `null` 가드 추가 (`webView == null` 또는 `btnFaceLogin == null` 시 return)

---

## STEP 4. CASE별 분기 처리 이식

### 4-1. 앱 진입 시 기기 상태 조회 (CASE 1, 2, 9, 10)

앱 첫 진입 화면의 `onCreate()` 또는 `onResume()`에서 서버 상태를 조회합니다.

```java
import android.provider.Settings;
import com.skcc.biometric.lib.network.AuthApiClient.DeviceStatusResponse;
import com.skcc.biometric.lib.network.DeviceNotFoundException;

/**
 * 기기 상태 서버 조회 — 백그라운드 스레드에서 실행.
 * 결과에 따라 CASE 1/2/9/10 분기.
 */
private void checkDeviceStatus() {
    // ANDROID_ID: 기기 고유 식별자. 공장 초기화 시 변경됨.
    String deviceId = Settings.Secure.getString(
        getContentResolver(), Settings.Secure.ANDROID_ID);

    A2Application.getExecutor().submit(() -> {
        try {
            // AuthApiClient.getUserId(): 동기 호출 — 반드시 백그라운드 스레드에서 실행
            // 반환값: DeviceStatusResponse (userId, status 필드)
            DeviceStatusResponse response =
                A2Application.getAuthApiClient().getUserId(deviceId);

            // UI 업데이트는 반드시 메인 스레드에서
            runOnUiThread(() -> handleDeviceStatus(response, deviceId));

        } catch (DeviceNotFoundException e) {
            // CASE 1: 서버에 기기 등록 없음 → 등록 화면 이동
            runOnUiThread(() -> navigateToRegister(deviceId));

        } catch (Exception e) {
            // 네트워크 오류 등
            runOnUiThread(() -> showError("서버 연결 오류: " + e.getMessage()));
        }
    });
}

/**
 * 서버 상태 응답에 따라 CASE 2/9/10 분기.
 *
 * @param response 서버 응답 (status: "ACTIVE" | "LOCKED" | "KEY_INVALIDATED")
 * @param deviceId ANDROID_ID
 */
private void handleDeviceStatus(DeviceStatusResponse response, String deviceId) {
    switch (response.status) {

        case "ACTIVE":
            // CASE 2: 정상 등록 기기 → 로컬 등록 플래그 저장 후 로그인 화면 이동
            // tokenStorage.saveRegistration(): deviceId와 userId를 EncryptedSharedPreferences에 저장
            tokenStorage.saveRegistration(deviceId, response.userId);
            navigateToLogin(); // A2 로그인 화면으로 이동
            break;

        case "LOCKED":
            // CASE 9: 서버 잠금 상태 → ID/PW 잠금 해제 UI 표시
            // pendingDeviceId, pendingUserId 필드에 저장 후 잠금 해제 처리
            pendingDeviceId = deviceId;
            pendingUserId   = response.userId;
            showLockedUi(response.userId); // ID/PW 입력 폼 표시
            break;

        case "KEY_INVALIDATED":
            // CASE 10: Keystore 키 무효화 → 알림 후 로컬 키 삭제 → 등록 화면
            showKeyInvalidatedAtEntryDialog(response.userId, deviceId);
            break;

        default:
            showError("알 수 없는 기기 상태: " + response.status);
            break;
    }
}
```

### 4-2. CASE 9 — 서버 잠금 해제

```java
/**
 * CASE 9: LOCKED 상태에서 ID/PW 입력 후 잠금 해제.
 *
 * @param password A2 로그인 화면에서 입력받은 비밀번호
 * @주의 실서비스에서는 MIS 인증 서버에서 ID/PW 검증 후 unlock 요청해야 함
 */
private void submitLockedLogin(String password) {
    String deviceId = pendingDeviceId;
    String userId   = pendingUserId;

    A2Application.getExecutor().submit(() -> {
        try {
            // AuthApiClient.unlockDevice(): 서버 잠금 해제 API 호출
            // HTTP PUT /api/device/unlock
            A2Application.getAuthApiClient().unlockDevice(deviceId);

            // 잠금 해제 성공 → 로컬 등록 플래그 저장
            tokenStorage.saveRegistration(deviceId, userId);

            runOnUiThread(() -> {
                Toast.makeText(this, "잠금이 해제되었습니다.", Toast.LENGTH_SHORT).show();
                navigateToLogin();
            });

        } catch (Exception e) {
            runOnUiThread(() -> showError("잠금 해제 실패: " + e.getMessage()));
        }
    });
}
```

### 4-3. BiometricAuthManager.AuthCallback 구현 (CASE 3~8, 10~11)

```java
import com.skcc.biometric.lib.auth.BiometricAuthManager.AuthCallback;
import com.skcc.biometric.lib.network.AuthApiClient.TokenResponse;

/**
 * 안면인식 인증 결과 콜백.
 * 모든 메서드는 BiometricAuthManager 내부에서 runOnUiThread 후 호출됨.
 * → 별도 runOnUiThread 래핑 불필요.
 */
private final AuthCallback authCallback = new AuthCallback() {

    /**
     * CASE 2(성공): 인증 + 토큰 발급 완료.
     *
     * @param userId        인증된 사용자 ID
     * @param tokenResponse B2 서버 발급 토큰 (accessToken, refreshToken, expiresIn)
     */
    @Override
    public void onSuccess(String userId, TokenResponse tokenResponse) {
        // 토큰 저장 (TokenStorage.saveTokens()는 AAR 내부에서 이미 호출됨)
        // A2 메인 화면으로 이동
        navigateToMain(userId, tokenResponse.accessToken);
    }

    /**
     * CASE 1(로그인 화면 진입 시): 미등록 상태.
     * TokenStorage에 등록 정보 없거나 서버에서 기기를 찾지 못한 경우.
     */
    @Override
    public void onNotRegistered() {
        Toast.makeText(A2LoginActivity.this,
            "등록 정보가 없습니다. 다시 등록해주세요.", Toast.LENGTH_LONG).show();
        navigateToRegister(null);
    }

    /**
     * CASE 5: 로컬 잠금.
     *
     * @param remainingSeconds 잠금 해제까지 남은 초
     */
    @Override
    public void onLockedOut(int remainingSeconds) {
        // 카운트다운 타이머 UI 표시
        // 예: "30초 후 재시도 가능"
        showLockCountdown(remainingSeconds);
    }

    /**
     * CASE 4: 안면인식 실패 (잠금 임계 미도달, 재시도 가능).
     *
     * @param failureCount 현재까지 누적된 실패 횟수
     */
    @Override
    public void onRetry(int failureCount) {
        // 실패 횟수 UI 표시
        // 예: "인식 실패 (3/5회)"
        showRetryMessage(failureCount);
    }

    /**
     * CASE 11: 계정 잠금 — 관리자 또는 다중 실패로 서버 측 잠금.
     * ID/PW 잠금 해제 UI를 표시해야 함.
     */
    @Override
    public void onAccountLocked() {
        // 안면인식 버튼 숨기고 ID/PW 입력 폼 표시
        showAccountLockedUi();
    }

    /**
     * CASE 3: SESSION_EXPIRED 자동 재시도 중 상태 알림.
     * AAR이 자동으로 재시도하므로 UI에 진행 중 메시지만 표시.
     *
     * @param retryCount 현재 재시도 횟수 (1부터 시작)
     * @param maxRetry   최대 재시도 횟수 (기본 2)
     */
    @Override
    public void onSessionRetrying(int retryCount, int maxRetry) {
        // 진행 중 메시지 표시 (재시도 완료 후 자동으로 다른 콜백 호출됨)
        showStatusMessage("인증 재시도 중... (" + retryCount + "/" + maxRetry + ")");
    }

    /**
     * CASE 6~8, 10~11 등 오류 상황.
     * ErrorCode에 따라 UI 분기 처리.
     *
     * @param errorCode AAR이 전달하는 오류 코드 (ErrorCode enum)
     */
    @Override
    public void onError(ErrorCode errorCode) {
        switch (errorCode) {

            case BIOMETRIC_NONE_ENROLLED:
                // CASE 7: 안면인식 미등록 → 설정 유도 다이얼로그
                showNotEnrolledDialog();
                break;

            case BIOMETRIC_HW_UNAVAILABLE:
                // CASE 8: 생체인식 HW 불가
                showError("생체인식 기능을 사용할 수 없습니다.");
                break;

            case KEY_INVALIDATED:
                // CASE 10: Keystore 키 무효화 → 키 갱신 다이얼로그
                showKeyInvalidatedDialog();
                break;

            case DEVICE_NOT_FOUND:
                // 서버에 기기 없음 → 등록 화면 이동
                showDeviceNotFoundDialog();
                break;

            case SESSION_EXPIRED:
                // CASE 3 최대 재시도 초과
                showError("인증 시간이 초과되었습니다. 다시 시도해주세요.");
                break;

            case INVALID_SIGNATURE:
                // CASE 6: 3회 미만 발생 시 (이후 자동 키 재발급)
                showError("기기 인증 정보가 맞지 않습니다. 다시 시도해주세요.");
                break;

            case TIMESTAMP_OUT_OF_RANGE:
                // 기기 시간 불일치 → 날짜 설정 유도
                showTimestampErrorDialog();
                break;

            case NETWORK_ERROR:
                showError("네트워크 연결을 확인 후 다시 시도해주세요.");
                break;

            default:
                showError("오류가 발생했습니다. 앱을 재시작하거나 헬프데스크로 문의해주세요.");
                break;
        }
    }
};
```

### 4-4. CASE 10 — KEY_INVALIDATED 다이얼로그 및 키 갱신

```java
/**
 * CASE 10: Keystore 키 무효화 다이얼로그.
 * 확인 클릭 시 AAR의 startRenewal()이 자동으로 키 재발급 → BiometricPrompt 재실행.
 */
private void showKeyInvalidatedDialog() {
    if (isFinishing()) return;
    new AlertDialog.Builder(this)
        .setTitle("보안키 재설정 필요")
        .setMessage("얼굴 등 생체 정보가 변경되어 보안키를 다시 설정해야 합니다.\n확인 버튼을 누르면 키를 다시 설정합니다.")
        .setPositiveButton("확인", (d, w) -> {
            // BiometricAuthManager.startRenewal():
            //   Challenge 재요청 없이 키 재발급 → BiometricPrompt 재실행
            //   성공 시 authCallback.onSuccess() 자동 호출
            biometricAuthManager.startRenewal(this, authCallback);
        })
        .setNegativeButton("취소", null)
        .setCancelable(false)
        .show();
}
```

### ✅ STEP 4 완료 확인 체크리스트

- [ ] 앱 진입 시 `AuthApiClient.getUserId()` 백그라운드 호출
- [ ] `DeviceStatusResponse.status` 에 따라 CASE 1/2/9/10 분기 처리
- [ ] `AuthCallback` 7개 메서드 전부 구현 (누락 시 컴파일 오류)
- [ ] CASE 10 다이얼로그에서 `biometricAuthManager.startRenewal()` 호출
- [ ] CASE 9 `unlockDevice()` 백그라운드 호출

---

## STEP 5. 등록 프로세스 이식

### 5-1. 신규 등록 화면 (CASE 1 대응)

```java
import com.skcc.biometric.lib.auth.BiometricRegistrar;

public class A2RegisterActivity extends AppCompatActivity {

    private BiometricRegistrar registrar;
    private TokenStorage tokenStorage;
    private String deviceId; // Intent Extra로 수신
    private String userId;   // 사용자가 입력하거나 서버에서 조회

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Intent Extra 수신
        deviceId = getIntent().getStringExtra("device_id");
        userId   = getIntent().getStringExtra("user_id"); // 없을 수 있음

        if (deviceId == null || deviceId.isEmpty()) {
            // Intent로 받지 못한 경우 ANDROID_ID 폴백
            deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        try {
            tokenStorage = new TokenStorage(this);
        } catch (GeneralSecurityException | IOException e) {
            Toast.makeText(this, "보안 저장소 초기화 실패", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // BiometricRegistrar 초기화
        // 역할: EC 키쌍 생성 → 서버 기기 등록 → 로컬 등록 플래그 저장
        registrar = new BiometricRegistrar(
            this,
            A2Application.getAuthApiClient(),
            A2Application.getEcKeyManager(),
            tokenStorage,
            A2Application.getExecutor()
        );

        // 등록 버튼 이벤트
        Button btnRegister = findViewById(R.id.btn_register);
        btnRegister.setOnClickListener(v -> startRegister());
    }

    /**
     * 안면인식 등록 시작.
     * 내부 흐름: canAuthenticate() 체크 → EC 키쌍 생성 → 서버 등록 API 호출.
     *
     * @주의 반드시 UI 스레드에서 호출. 내부에서 백그라운드 전환됨.
     */
    private void startRegister() {
        // userId는 화면에서 입력받거나 Intent Extra 사용
        String inputUserId = getUserIdFromInput(); // EditText 등에서 수신

        // BiometricRegistrar.register():
        //   파라미터 1 - activity: FragmentActivity
        //   파라미터 2 - deviceId: ANDROID_ID 기반 기기 식별자
        //   파라미터 3 - userId: 사용자 ID (MIS 직원 번호 등)
        //   파라미터 4 - callback: RegisterCallback (onSuccess/onError)
        registrar.register(this, deviceId, inputUserId, registerCallback);
    }

    private final BiometricRegistrar.RegisterCallback registerCallback =
        new BiometricRegistrar.RegisterCallback() {

        /**
         * 등록 성공.
         *
         * @param userId 등록된 사용자 ID
         */
        @Override
        public void onSuccess(String userId) {
            // 등록 완료 → 로그인 화면으로 이동
            Toast.makeText(A2RegisterActivity.this,
                "등록이 완료되었습니다.", Toast.LENGTH_SHORT).show();
            navigateToLogin();
        }

        /**
         * 등록 실패.
         *
         * @param errorCode 실패 원인 (BIOMETRIC_NONE_ENROLLED, ALREADY_REGISTERED, NETWORK_ERROR 등)
         */
        @Override
        public void onError(ErrorCode errorCode) {
            switch (errorCode) {
                case BIOMETRIC_NONE_ENROLLED:
                    // 안면인식 미등록 → 설정 화면 유도
                    showNotEnrolledDialog();
                    break;
                case ALREADY_REGISTERED:
                    // 이미 등록된 기기 → 로그인 화면으로 안내
                    showAlreadyRegisteredDialog();
                    break;
                case NETWORK_ERROR:
                    showError("네트워크 오류. 다시 시도해주세요.");
                    break;
                default:
                    showError("등록 실패. 헬프데스크로 문의해주세요.");
                    break;
            }
        }
    };
}
```

### 5-2. CASE 12 — 사용자 변경 (UserChangeHandler)

```java
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.auth.UserChangeHandler.UserChangeCallback;

/**
 * CASE 12: 사용자 변경 플로우.
 * 1단계: PIN/패턴 인증 (verifyDeviceCredential)
 * 2단계: 서버 기기 삭제 + 로컬 키/등록정보 삭제 (executeChange)
 */
private final UserChangeCallback userChangeCallback = new UserChangeCallback() {

    /**
     * 1단계 성공: PIN/패턴 인증 완료.
     * 곧바로 2단계(executeChange)를 호출.
     */
    @Override
    public void onVerified() {
        showProgress(true); // 로딩 표시
        // UserChangeHandler.executeChange():
        //   ① 서버 unregisterDevice() → ② 로컬 EC 키 삭제 → ③ TokenStorage 전체 삭제
        userChangeHandler.executeChange(A2LoginActivity.this, this);
    }

    /**
     * 2단계 성공: 서버/로컬 데이터 삭제 완료.
     * 등록 화면으로 이동하여 신규 사용자 등록 진행.
     */
    @Override
    public void onChangeCompleted() {
        showProgress(false);
        Toast.makeText(A2LoginActivity.this,
            "담당자 변경이 완료되었습니다. 신규 등록을 진행해주세요.", Toast.LENGTH_SHORT).show();
        // 등록 화면으로 이동 (button_label로 화면 타이틀 구분)
        Intent intent = new Intent(A2LoginActivity.this, A2RegisterActivity.class);
        intent.putExtra("button_label", "신규 사용자 등록");
        startActivity(intent);
        finish();
    }

    /**
     * 실패.
     *
     * @param errorCode 실패 원인 (NETWORK_ERROR, UNKNOWN_ERROR 등)
     */
    @Override
    public void onChangeFailed(ErrorCode errorCode) {
        showProgress(false);
        Toast.makeText(A2LoginActivity.this,
            "사용자 변경 실패. 네트워크를 확인 후 다시 시도해주세요.", Toast.LENGTH_LONG).show();
    }

    /**
     * 사용자가 PIN 인증 다이얼로그를 취소.
     * 별도 처리 없음 (다이얼로그 자동 닫힘).
     */
    @Override
    public void onCanceled() {
        // 취소 — UI 복원 불필요 (BiometricPrompt가 자동으로 닫힘)
    }
};
```

### ✅ STEP 5 완료 확인 체크리스트

- [ ] `BiometricRegistrar` 생성자 인자 5종 확인
- [ ] `RegisterCallback` 2개 메서드 구현 (`onSuccess`, `onError`)
- [ ] `ALREADY_REGISTERED` 케이스 처리 (이미 등록된 기기 대응)
- [ ] `UserChangeCallback` 4개 메서드 구현 (`onVerified`, `onChangeCompleted`, `onChangeFailed`, `onCanceled`)
- [ ] `onVerified()` 에서 곧바로 `executeChange()` 호출 확인
- [ ] `onChangeCompleted()` 에서 등록 화면 이동 확인

---

## STEP 6. 토큰 생성 및 B2 서버 연동

### 6-1. 토큰 발급 흐름 (AAR 자동 처리)

```
[안면인식 인증 성공 후 자동 흐름 — A2 코드 불필요]

BiometricPrompt.onAuthenticationSucceeded()
  └─ (백그라운드) EcKeyManager.signPayload(payload)
       └─ AuthApiClient.requestToken(TokenRequest)
            POST /api/auth/token
            Body: { sessionId, deviceId, userId, ecSignature, clientNonce, timestamp }
            └─ TokenStorage.saveTokens(accessToken, refreshToken)
                 └─ authCallback.onSuccess(userId, tokenResponse) 호출
```

**A2 코드는 `onSuccess()` 콜백 수신 후 처리만 담당합니다.**

### 6-2. onSuccess 콜백에서 토큰 수신

```java
@Override
public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
    // tokenResponse 필드:
    //   .accessToken  — API 요청 시 Authorization 헤더에 사용
    //   .refreshToken — 토큰 갱신 시 사용
    //   .expiresIn    — 만료 시간(초), 없으면 기본 1800초(30분)

    // 토큰은 AAR 내부(TokenStorage)에 이미 저장됨
    // 추가로 A2 자체 저장소에 저장이 필요하면 아래처럼 처리
    String accessToken  = tokenResponse.accessToken;
    String refreshToken = tokenResponse.refreshToken;
    int    expiresIn    = tokenResponse.expiresIn; // 초 단위

    // A2 메인 화면으로 이동 (토큰 Intent Extra 전달)
    Intent intent = new Intent(this, A2MainActivity.class);
    intent.putExtra("user_id",      userId);
    intent.putExtra("access_token", accessToken);
    intent.putExtra("expires_in",   expiresIn);
    startActivity(intent);
    finish();
}
```

### 6-3. 저장된 토큰 조회

```java
// 필요 시 TokenStorage를 통해 기존 저장 토큰 직접 조회 가능
// (단, TokenStorage 인스턴스 재사용 권장 — 매번 새로 생성 금지)

String accessToken  = tokenStorage.getAccessToken();
String refreshToken = tokenStorage.getRefreshToken();
String deviceId     = tokenStorage.getDeviceId();
String userId       = tokenStorage.getUserId();
boolean isReg       = tokenStorage.isRegistered();
```

### 6-4. B2 서버 API 호출 시 토큰 사용

```java
// A2의 기존 HTTP 클라이언트(OkHttp 또는 Retrofit)를 사용하여 B2 API 호출 시
// accessToken을 Authorization 헤더에 포함
Request request = new Request.Builder()
    .url("https://your-b2-server.com/api/some-endpoint")
    .header("Authorization", "Bearer " + tokenStorage.getAccessToken())
    .build();
```

### 6-5. 토큰 만료 처리

```java
// 토큰 만료 여부 확인 예시
// (AAR에 만료 시각 자동 체크 기능은 없음 — A2에서 직접 관리)

// 방법 1: expiresIn으로 로컬 만료 계산
long issuedAt      = System.currentTimeMillis(); // onSuccess 수신 시각 저장
long expiresAtMs   = issuedAt + (tokenResponse.expiresIn * 1000L);
boolean isExpired  = System.currentTimeMillis() > expiresAtMs;

if (isExpired) {
    // 방법 A: refreshToken으로 토큰 갱신 (B2 서버 refresh API 호출)
    refreshAccessToken();
    // 방법 B: 재로그인 유도 (안면인식 버튼 재활성화)
    navigateToLogin();
}

// 방법 2: B2 API 응답 401 수신 시 재로그인 유도
// (API 인터셉터 또는 onResponse 처리에서 401 체크)
```

### ✅ STEP 6 완료 확인 체크리스트

- [ ] `onSuccess()` 에서 `tokenResponse.accessToken` 수신 확인
- [ ] 토큰을 B2 API 요청 `Authorization: Bearer {token}` 헤더에 사용
- [ ] 토큰 만료 처리 전략 결정 (로컬 만료 계산 또는 401 인터셉터)
- [ ] `tokenStorage.getAccessToken()` 으로 저장된 토큰 재조회 가능 확인

---

## STEP 7. 예외 처리 체크리스트

### 7-1. CASE별 예외 상황 및 처리 방법

| CASE | 예외/상황 | 처리 방법 | `runOnUiThread` 필요 |
|------|-----------|-----------|----------------------|
| CASE 1 | `DeviceNotFoundException` | 등록 화면 이동 | ✅ 필요 |
| CASE 3 | `SESSION_EXPIRED` 2회 초과 | `onError(SESSION_EXPIRED)` → 오류 메시지 | ✅ AAR이 처리 |
| CASE 4 | `onAuthenticationFailed()` | `onRetry(count)` → 실패 횟수 표시 | ✅ AAR이 처리 |
| CASE 5 | 실패 횟수 ≥ `maxRetryBeforeLockout` | `onLockedOut(sec)` → 카운트다운 | ✅ AAR이 처리 |
| CASE 6 | `INVALID_SIGNATURE` 3회 | 자동 키 재발급 (AAR 내부 처리) | ✅ AAR이 처리 |
| CASE 7 | `BIOMETRIC_ERROR_NONE_ENROLLED` | 설정 화면 유도 다이얼로그 | ✅ AAR이 처리 |
| CASE 8 | `canAuthenticate()` 실패 | 오류 메시지 | ✅ AAR이 처리 |
| CASE 9 | `status = "LOCKED"` | ID/PW 잠금 해제 UI | ✅ 필요 |
| CASE 10 | `KEY_INVALIDATED` | 키 갱신 다이얼로그 + `startRenewal()` | ✅ 필요 |
| CASE 11 | `onAccountLocked()` | ID/PW 잠금 해제 UI | ✅ AAR이 처리 |
| CASE 12 | `DeviceNotFoundException` (사용자 변경 시) | 로컬만 삭제 후 등록 화면 | ✅ AAR이 처리 |

### 7-2. runOnUiThread 필요 시점 정리

```java
// ── A2에서 직접 runOnUiThread가 필요한 경우 ─────────────────────

// 1. AuthApiClient 직접 호출 후 (CASE 1, 9)
A2Application.getExecutor().submit(() -> {
    try {
        DeviceStatusResponse response = client.getUserId(deviceId);
        runOnUiThread(() -> handleDeviceStatus(response, deviceId)); // ← 필요
    } catch (DeviceNotFoundException e) {
        runOnUiThread(() -> navigateToRegister(deviceId));           // ← 필요
    }
});

// 2. @JavascriptInterface 메서드 내부 (WebView 사용 시)
@JavascriptInterface
public void startFaceLogin() {
    // @JavascriptInterface는 백그라운드 스레드 — runOnUiThread 필수
    activity.runOnUiThread(() -> biometricAuthManager.authenticate(activity, callback));
}

// ── AAR 콜백 (runOnUiThread 불필요) ─────────────────────────────
// AuthCallback, RegisterCallback, UserChangeCallback 의 모든 메서드는
// AAR 내부에서 이미 runOnUiThread 처리 후 호출됨
// → A2 콜백 구현체에서 추가 runOnUiThread 래핑 불필요
```

### 7-3. Activity 생명주기 — null 가드

```java
// onCreate에서 finish() 후 return하는 경우 webView, bridge 등이 null일 수 있음
// onResume/onPause/onDestroy에서 반드시 null 가드 추가

@Override
protected void onResume() {
    super.onResume();
    if (loginView == null || bridge == null) return; // ← null 가드 필수
    loginView.onResume();
    // ... 나머지 로직
}

@Override
protected void onPause() {
    super.onPause();
    if (loginView == null) return; // ← null 가드 필수
    loginView.onPause();
}

@Override
protected void onDestroy() {
    if (bridge != null) {
        bridge.cancelPendingNavigation();
        bridge.cancelCountdown();
    }
    if (loginView != null) {
        loginView.stopLoading();
        loginView.destroy();
    }
    super.onDestroy();
}
```

### 7-4. 보안 주의사항

```java
// ① TokenStorage 초기화는 try-catch 필수
// → GeneralSecurityException: Keystore 오류
// → IOException: 파일 I/O 오류
// → 실패 시 RuntimeException으로 re-throw 금지 — 사용자에게 안내 후 graceful 처리

// ② BiometricAuthManager.authenticate()는 UI 스레드에서만 호출
// → 내부적으로 BiometricPrompt를 UI 스레드에서 실행하기 때문

// ③ executor.shutdown() 금지 — Activity.onDestroy()에서 호출 금지
// → executor는 Application 생명주기와 동일. Activity 종료 시 shutdown() 하면
//    다른 Activity에서 재사용 불가

// ④ TokenStorage 인스턴스 재사용 — Activity마다 new TokenStorage() 지양
// → EncryptedSharedPreferences 초기화 비용이 크므로 Application이나
//    ViewModel에서 단일 인스턴스 관리 권장

// ⑤ deviceId(ANDROID_ID)는 백그라운드 스레드에서도 조회 가능
// → Settings.Secure.getString()은 스레드 안전
// → 공장 초기화 시 변경됨 — 재설치 감지 로직 필요 시 별도 구현
```

### 7-5. 자주 발생하는 오류 및 해결 방법

| 오류 | 원인 | 해결 |
|------|------|------|
| `RuntimeException: TokenStorage 초기화 실패` | Samsung Knox/Android 12+ Keystore 이슈 | security-crypto 1.1.0-alpha06 이상 사용 확인 |
| `BiometricPrompt ERROR_HW_NOT_PRESENT` | 생체인식 센서 없음 | `canAuthenticate()` 로 사전 체크 필수 |
| `NullPointerException` in `onResume()` | `webView == null` (조기 `finish()`) | null 가드 추가 (STEP 7-3 참고) |
| `DeviceNotFoundException` | 서버에 기기 미등록 | 등록 화면으로 이동 |
| `AccountLockedException` | 계정 잠금 상태에서 키 재발급 시도 | `onAccountLocked()` 처리 후 ID/PW 해제 |
| `NoClassDefFoundError: kotlin.*` | OkHttp 전이 의존성 미선언 | `okhttp:4.9.3` `implementation` 추가 |
| `minCompileSdk` 빌드 오류 | appcompat:1.7.0 → compileSdk 34 요구 | biometric-lib은 compileSdk 29 사용 — demo-app appcompat 버전 하향 불필요 (A2 코드 변경 없음) |

### ✅ STEP 7 완료 확인 체크리스트 (최종)

- [ ] CASE 1~12 모든 분기 코드 구현 확인
- [ ] `AuthCallback` 7개 메서드 전부 구현
- [ ] `RegisterCallback` 2개 메서드 전부 구현
- [ ] `UserChangeCallback` 4개 메서드 전부 구현
- [ ] `TokenStorage` 초기화 try-catch + graceful 처리
- [ ] `onResume/onPause/onDestroy` null 가드 추가
- [ ] `executor.shutdown()` 호출 없음 확인
- [ ] `biometricAuthManager.authenticate()` UI 스레드 호출 확인
- [ ] `@JavascriptInterface` 메서드 내 `runOnUiThread` 래핑 확인 (WebView 사용 시)
- [ ] B2 API 요청 시 `Authorization: Bearer {accessToken}` 헤더 적용 확인
- [ ] 운영 빌드에서 `android:usesCleartextTraffic` 제거 확인
- [ ] `BiometricLibConstants.PREFS_NAME` = `"com.skcc.biometric.lib.prefs"` 확인 (앱 데이터 마이그레이션 시 주의)

---

## 부록 — B2 서버 API 엔드포인트 요약

| 메서드 | 경로 | 설명 | AAR 호출 클래스 |
|--------|------|------|-----------------|
| `GET` | `/api/device/user-id` | 기기 상태 조회 (ACTIVE/LOCKED/KEY_INVALIDATED) | `AuthApiClient.getUserId()` |
| `POST` | `/api/device/register` | 기기 등록 (EC 공개키 전송) | `AuthApiClient.registerDevice()` |
| `DELETE` | `/api/device/unregister` | 기기 등록 삭제 (CASE 12) | `AuthApiClient.unregisterDevice()` |
| `PUT` | `/api/device/unlock` | 계정 잠금 해제 (CASE 9/11) | `AuthApiClient.unlockDevice()` |
| `PUT` | `/api/device/renew-key` | 공개키 갱신 (CASE 6/10) | `AuthApiClient.renewKey()` |
| `POST` | `/api/auth/challenge` | Challenge 발급 | `AuthApiClient.getChallenge()` |
| `POST` | `/api/auth/token` | ECDSA 서명 검증 후 토큰 발급 | `AuthApiClient.requestToken()` |
| `POST` | `/api/auth/account-lock` | 계정 잠금 요청 (CASE 11) | `AuthApiClient.lockAccount()` |
| `GET` | `/api/policy/failure-config` | 실패 정책 조회 (5분 캐시) | `AuthApiClient.getFailurePolicy()` |
