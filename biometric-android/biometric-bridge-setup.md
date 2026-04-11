# A2 프로젝트 안면인식 기능 이식 가이드
# (biometric-lib + biometric-bridge)

| 항목 | 내용 |
|------|------|
| 대상 독자 | A2 프로젝트 담당 개발자 (수동 이식, Cursor AI 미사용 전제) |
| 이식 절차 | (1) 노트북 로컬 저장소 `git pull` → (2) `biometric-lib`·`biometric-bridge` 폴더 통째로 복사 → (3) A2 프로젝트 루트에 붙여넣기 → (4) A2 설정 수동 수정 |
| A2 저장소 | 이 작업으로 **A2 프로젝트는 git pull 하지 않음** |
| 민감 정보 | 실제 `serverUrl` 등은 문서에 적지 않음. `biometric.properties` 등으로만 관리 |

**참고 환경**

| 항목 | 값 |
|------|-----|
| A2 AGP | 7.0.4 |
| A2 Gradle | 7.2 |
| A2 JDK (Android Studio) | 11 |
| A2 `app` | compileSdk 28, minSdk 23, targetSdk 28 |
| A2 기타 모듈 | alopex_blaze, alopex_blaze_core, blaze_app_init, blaze_app_db, blaze_extension, blaze_secure_extension, CorsLib (compileSdk 28), connection (compileSdk 29) |
| A2 appcompat | `appcompat-v7:28.0.0` (build.gradle에 없을 수 있음) + `android.enableJetifier=true` |
| A2 gson | 로컬 jar 2.2.4 |
| biometric-lib | compileSdk 31, minSdk 23, targetSdk 31, Java 11 |
| biometric-bridge | compileSdk 31, minSdk 23, targetSdk 31, Java 11 |

**A1(biometric-android) 저장소 기준 권장 상태**  
`biometric-bridge` 모듈은 다음이 반영된 최신본을 복사하는 것을 권장한다.

- `androidx.security:security-crypto:1.1.0-alpha06` (Samsung Knox·Android 12+ 호환, `1.0.0` 사용 금지)
- `namespace 'com.skens.nsms.biometric.bridge'`
- Java 소스 패키지 `com.skens.nsms.biometric.bridge` (`BiometricBridge`, `BiometricBridgeCallback`)

구버전 폴더를 복사했다면 **복사 직후** `biometric-bridge/build.gradle`과 `src/main/java/...` 패키지 구조를 위와 같이 맞춘 뒤 빌드해 본다.

---

## 목차

1. [사전 확인 사항](#1-사전-확인-사항)
2. [STEP 1. 최신 소스 준비 (git pull)](#2-step-1-최신-소스-준비-git-pull)
3. [STEP 2. 모듈 폴더 복사](#3-step-2-모듈-폴더-복사)
4. [STEP 3. settings.gradle 등록](#4-step-3-settingsgradle-등록)
5. [STEP 4. app/build.gradle 수정](#5-step-4-appbuildgradle-수정)
6. [STEP 5. 민감 정보 설정](#6-step-5-민감-정보-설정)
7. [STEP 6. AndroidManifest.xml 확인](#7-step-6-androidmanifestxml-확인)
8. [STEP 7. AndroidBridge 구현](#8-step-7-androidbridge-구현)
9. [STEP 8. A2 로그인 화면 연결](#9-step-8-a2-로그인-화면-연결)
10. [STEP 9. Logcat 검증 방법](#10-step-9-logcat-검증-방법)
11. [STEP 10. 알려진 이슈 및 대응 방법](#11-step-10-알려진-이슈-및-대응-방법)
12. [STEP 11. 버전 업데이트 방법](#12-step-11-버전-업데이트-방법)
13. [완료 체크리스트](#13-완료-체크리스트)

---

## 1. 사전 확인 사항

### A2 JDK 버전 확인

**Android Studio → File → Project Structure → JDK Location** 에서 JDK 11 사용 여부를 확인한다.

### JDK 11.0.1 TLS 핸드셰이크 문제

- **증상**: B2 인증 서버와 HTTPS 통신 실패(핸드셰이크 오류)
- **원인**: JDK 11.0.1에 포함된 `cacerts`가 구버전인 경우
- **해결**: JDK 21 설치 경로의 `lib/security/cacerts` 파일을 백업 후, JDK 11의 동일 경로(`{JDK11_HOME}/lib/security/cacerts`)에 복사·교체(조직 보안 정책 준수)

```
┌──────────────────────────────────────────┐
│ 확인 대상 모듈: 프로젝트 루트            │
│ 확인 대상 파일: gradle.properties        │
└──────────────────────────────────────────┘
```

다음이 없으면 **추가**한다.

```properties
android.useAndroidX=true
android.enableJetifier=true
```

### Git 저장소

`biometric-lib`, `biometric-bridge` 로컬 클론에 대해 `git pull` 할 수 있는지 확인한다.

### ✅ 완료 확인 체크리스트

- [ ] JDK 경로·버전 확인
- [ ] `gradle.properties`에 `android.useAndroidX=true` 존재
- [ ] `gradle.properties`에 `android.enableJetifier=true` 존재
- [ ] 두 저장소 접근 가능

---

## 2. STEP 1. 최신 소스 준비 (git pull)

```
┌──────────────────────────────────────────┐
│ 작업 위치: 노트북 로컬 저장소            │
│ A2 프로젝트 수정 없음                    │
└──────────────────────────────────────────┘
```

```bash
cd {로컬저장소}/biometric-lib && git pull
cd {로컬저장소}/biometric-bridge && git pull
```

`{로컬저장소}`는 실제 경로로 바꾼다.

**복사 대상 폴더**

- `{로컬저장소}/biometric-lib/`
- `{로컬저장소}/biometric-bridge/`

### ✅ 완료 확인 체크리스트

- [ ] `biometric-lib` pull 완료
- [ ] `biometric-bridge` pull 완료
- [ ] 각 폴더에 `src/`, `build.gradle` 존재

---

## 3. STEP 2. 모듈 폴더 복사

```
┌──────────────────────────────────────────────────────┐
│ 복사 원본 1: {로컬저장소}/biometric-lib/             │
│ 복사 대상 1: A2 프로젝트 루트/biometric-lib/         │
├──────────────────────────────────────────────────────┤
│ 복사 원본 2: {로컬저장소}/biometric-bridge/          │
│ 복사 대상 2: A2 프로젝트 루트/biometric-bridge/      │
│ 수정하지 않는 모듈: app 및 기존 모듈 전부            │
└──────────────────────────────────────────────────────┘
```

**복사 시 제외**

- `biometric-lib/build/`
- `biometric-bridge/build/`

**복사 후 A2 프로젝트 전체 구조 예시**

```
A2-project/
├── biometric-lib/        ← 신규
├── biometric-bridge/     ← 신규
├── app/
├── alopex_blaze/
├── alopex_blaze_core/
├── blaze_app_init/
├── blaze_app_db/
├── blaze_extension/
├── blaze_secure_extension/
├── CorsLib/
├── connection/
└── settings.gradle
```

**복사 직후 확인 (권장)**

- `biometric-bridge/build.gradle`에 `implementation 'androidx.security:security-crypto:1.1.0-alpha06'` 인지
- `namespace 'com.skens.nsms.biometric.bridge'` 인지
- Java 소스가 `biometric-bridge/src/main/java/com/skens/nsms/biometric/bridge/` 아래에 있는지

### ✅ 완료 확인 체크리스트

- [ ] `biometric-lib/` 복사 완료 (`build/` 제외)
- [ ] `biometric-bridge/` 복사 완료 (`build/` 제외)
- [ ] (권장) bridge `security-crypto`·namespace·Java 패키지가 최신 기준과 일치

---

## 4. STEP 3. settings.gradle 등록

```
┌──────────────────────────────────────────┐
│ 수정 대상 모듈: 프로젝트 루트            │
│ 수정 대상 파일: settings.gradle          │
│ 수정하지 않는 모듈: 기존 모듈 전부       │
└──────────────────────────────────────────┘
```

기존 `include` 목록 **최하단**에 추가한다.

**추가 전 예시**

```groovy
include ':app'
include ':alopex_blaze'
// ... 기존 모듈들 ...
include ':connection'
```

**추가 후 예시**

```groovy
include ':app'
include ':alopex_blaze'
// ... 기존 모듈들 ...
include ':connection'

include ':biometric-lib'
include ':biometric-bridge'
```

**Gradle Sync**: Android Studio → **File → Sync Project with Gradle Files**

### ✅ 완료 확인 체크리스트

- [ ] 두 줄 `include` 추가
- [ ] Sync 성공

---

## 5. STEP 4. app/build.gradle 수정

```
┌──────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app                                  │
│ 수정 대상 파일: app/build.gradle                     │
│ 수정하지 않는 모듈:                                  │
│   alopex_blaze, alopex_blaze_core, blaze_app_init,  │
│   blaze_app_db, blaze_extension,                     │
│   blaze_secure_extension, CorsLib, connection        │
└──────────────────────────────────────────────────────┘
```

`dependencies { }`에 추가:

```groovy
// 안면인식 핵심 라이브러리 (ECDSA, 토큰 발급)
implementation project(':biometric-lib')
// WebView ↔ 안면인식 브릿지
implementation project(':biometric-bridge')
```

### compileOptions를 app에서 올리지 않는 이유

- app은 **compileSdk 28, Java 8** 등 **기존 설정 유지**.
- `biometric-lib`·`biometric-bridge`는 **별도 모듈**로 각자 `build.gradle`의 `compileOptions`(Java 11)에서 컴파일된다.
- 따라서 app의 `compileOptions`는 **변경하지 않아도** 되며, 불필요하게 올리면 A2 기존 코드와 충돌할 수 있다.

### 빌드 오류 시

```
┌──────────────────────────────────────────────────────┐
│ 오류: appcompat 버전 충돌                            │
│ 수정 대상 파일: biometric-bridge/build.gradle        │
│ 조치: implementation 'androidx.appcompat:appcompat:1.3.1' 추가 │
├──────────────────────────────────────────────────────┤
│ 오류: material 버전 충돌 (macro 태그 등)             │
│ 수정 대상 파일: biometric-bridge/build.gradle        │
│ 조치: implementation 'com.google.android.material:material:1.4.0' 추가 │
├──────────────────────────────────────────────────────┤
│ 오류: 전이 의존성 충돌                               │
│ 수정 대상 파일: 없음 (명령 실행)                     │
│ 조치: ./gradlew clean --refresh-dependencies         │
└──────────────────────────────────────────────────────┘
```

### ✅ 완료 확인 체크리스트

- [ ] `project(':biometric-lib')` 추가
- [ ] `project(':biometric-bridge')` 추가
- [ ] Sync 후 필요 시 위 표 조치

---

## 6. STEP 5. 민감 정보 설정

```
┌──────────────────────────────────────────────────────┐
│ 생성 대상 파일: A2 프로젝트 루트/biometric.properties│
│ 수정 대상 파일 1: .gitignore                         │
│ 수정 대상 파일 2: app/build.gradle                   │
└──────────────────────────────────────────────────────┘
```

**`biometric.properties`** (루트에 신규 생성)

```properties
BIOMETRIC_SERVER_URL=https://실제서버주소입력
```

실제 URL은 보안 채널로만 공유하고, 문서에는 적지 않는다.

**`.gitignore`**

```gitignore
biometric.properties
```

**`app/build.gradle`** — `android { }` 블록 **상단** 근처:

```groovy
def biometricProps = new Properties()
biometricProps.load(rootProject.file('biometric.properties')
    .newDataInputStream())
```

**`defaultConfig { }`** 안:

```groovy
buildConfigField "String", "BIOMETRIC_SERVER_URL",
    "\"${biometricProps['BIOMETRIC_SERVER_URL']}\""
```

`buildFeatures { buildConfig true }`가 없으면 추가한다.

### ✅ 완료 확인 체크리스트

- [ ] `biometric.properties` 생성·실제 값 입력
- [ ] `.gitignore` 등록
- [ ] `buildConfigField` 추가·Sync 후 `BuildConfig.BIOMETRIC_SERVER_URL` 사용 가능

---

## 7. STEP 6. AndroidManifest.xml 확인

```
┌──────────────────────────────────────────────────────┐
│ 자동 병합 원본 파일:                                 │
│   biometric-lib/src/main/AndroidManifest.xml         │
│   biometric-bridge/src/main/AndroidManifest.xml      │
│ 병합 결과 반영 위치: app (자동)                      │
│ 개발자가 수동 수정할 파일: 없음                      │
└──────────────────────────────────────────────────────┘
```

**자동 병합되는 항목 예**

- `USE_BIOMETRIC` 권한
- `android.hardware.biometrics.face` `uses-feature`
- `BiometricRegisterActivity` 등 (라이브러리 Manifest 정의에 따름)

라이브러리 Manifest가 이미 선언하므로 **app/AndroidManifest.xml에 동일 항목을 중복 선언할 필요는 없다.** 중복 시 Merger 경고가 날 수 있다.

**확인**: Android Studio → **app** → **manifests** → **AndroidManifest.xml** → 하단 **Merged Manifest** 탭

### ✅ 완료 확인 체크리스트

- [ ] Merged Manifest에서 생체 관련 권한·컴포넌트 확인

---

## 8. STEP 7. AndroidBridge 구현

```
┌──────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app                                  │
│ 생성 대상 파일:                                      │
│   app/src/main/java/com/skens/nsms/biometric/        │
│   AndroidBridge.java (신규 생성)                     │
│ 수정하지 않는 모듈: biometric-lib, biometric-bridge  │
└──────────────────────────────────────────────────────┘
```

> A2 앱의 패키지 구조가 `com.skens.nsms`와 다르면, **디렉터리·패키지 선언·`BuildConfig` import**만 실제 app 패키지에 맞게 조정한다. 아래는 가이드용 예시이다.

### `@JavascriptInterface` 메서드

| 메서드 | 설명 |
|--------|------|
| `startFaceLogin()` | `biometricBridge.startLogin(loginCallback)` 호출 |
| `startUserChange()` | `biometricBridge.startUserChange(...)` 호출 |

### `evaluateJavascript`와 `runOnUiThread`

`@JavascriptInterface`는 **JavaBridge 백그라운드 스레드**에서 호출된다. `WebView.evaluateJavascript()`는 **메인(UI) 스레드**에서만 안전하므로, 반드시 `runOnUiThread` 안에서 호출한다.

### CASE별 JS 콜백 매핑

| CASE | Native 콜백 | JS 함수 |
|------|-------------|---------|
| CASE 1 | onLoginSuccess | onLoginSuccess(token) |
| CASE 2 | onRetry | onRetry(failureCount) |
| CASE 3 | onSessionRetrying | onSessionRetrying(retry,max) |
| CASE 4 | onLockedOut | onLockedOut(seconds) |
| CASE 7 | onNotRegistered | onLoginError('NOT_REGISTERED') |
| CASE 9 | onAccountLocked | onLoginError('ACCOUNT_LOCKED') |
| CASE 5,6,8,10,11 | onError | onLoginError(errorCode) |

### AndroidBridge.java 전체 예시

```java
package com.skens.nsms.biometric;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.skens.nsms.biometric.bridge.BiometricBridge;
import com.skens.nsms.biometric.bridge.BiometricBridgeCallback;

/** 앱 모듈 BuildConfig 패키지는 applicationId와 동일하게 생성된다. 필요 시 IDE 자동 import 사용. */
import com.skens.nsms.BuildConfig;

public class AndroidBridge {

    private static final String TAG = "BIOMETRIC_BRIDGE";

    private final AppCompatActivity activity;
    private final WebView webView;
    private final BiometricBridge biometricBridge;

    public AndroidBridge(AppCompatActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.biometricBridge = new BiometricBridge(
                activity, BuildConfig.BIOMETRIC_SERVER_URL);
    }

    @JavascriptInterface
    public void startFaceLogin() {
        Log.d(TAG, "[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신");
        activity.runOnUiThread(() -> biometricBridge.startLogin(loginCallback));
    }

    @JavascriptInterface
    public void startUserChange() {
        Log.d(TAG, "[BRIDGE] AndroidBridge > startUserChange : JS 호출 수신");
        activity.runOnUiThread(() ->
                biometricBridge.startUserChange(new BiometricBridgeCallback() {
                    @Override
                    public void onLoginSuccess(String userId, String token, int exp) {
                        callJs("onLoginSuccess('')");
                    }

                    @Override
                    public void onError(String errorCode) {
                        callJs("onLoginError('" + errorCode + "')");
                    }

                    @Override public void onRetry(int f) {}
                    @Override public void onSessionRetrying(int r, int m) {}
                    @Override public void onLockedOut(int s) {}
                    @Override public void onNotRegistered() {}
                    @Override public void onAccountLocked() {}
                }));
    }

    private final BiometricBridgeCallback loginCallback = new BiometricBridgeCallback() {
        @Override
        public void onLoginSuccess(String userId, String accessToken, int expiresIn) {
            Log.d(TAG, "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
            callJs("onLoginSuccess('" + escapeJs(accessToken) + "')");
        }

        @Override
        public void onRetry(int failureCount) {
            Log.w(TAG, "[BRIDGE] callback > onRetry : failureCount=" + failureCount);
            callJs("onRetry(" + failureCount + ")");
        }

        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
        }

        @Override
        public void onLockedOut(int remainingSeconds) {
            Log.w(TAG, "[BRIDGE] callback > onLockedOut : remainingSeconds="
                    + remainingSeconds);
            callJs("onLockedOut(" + remainingSeconds + ")");
        }

        @Override
        public void onNotRegistered() {
            callJs("onLoginError('NOT_REGISTERED')");
        }

        @Override
        public void onAccountLocked() {
            callJs("onLoginError('ACCOUNT_LOCKED')");
        }

        @Override
        public void onError(String errorCode) {
            Log.e(TAG, "[BRIDGE] callback > onError : " + errorCode);
            callJs("onLoginError('" + errorCode + "')");
        }
    };

    private void callJs(String jsCall) {
        Log.d(TAG, "[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function="
                + jsCall);
        activity.runOnUiThread(() ->
                webView.evaluateJavascript("javascript:" + jsCall, null));
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
```

### ✅ 완료 확인 체크리스트

- [ ] `AndroidBridge.java` 생성·패키지·`BuildConfig` import 정리
- [ ] `com.skens.nsms.biometric.bridge` import 오류 없음

---

## 9. STEP 8. A2 로그인 화면 연결

```
┌──────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app                                  │
│ 수정 대상 파일 1: A2 로그인 Activity.java            │
│ 수정 대상 파일 2: app/src/main/assets/login.html     │
│   (실제 경로는 A2 자산 구조에 맞출 것)               │
│ 수정하지 않는 모듈: 나머지 모든 모듈                 │
└──────────────────────────────────────────────────────┘
```

**로그인 Activity**

```java
webView.getSettings().setJavaScriptEnabled(true);
webView.addJavascriptInterface(
        new AndroidBridge(this, webView), "Android");
```

**login.html 버튼 예시**

```html
<button onclick="Android.startFaceLogin()">안면인식 로그인</button>
<button onclick="Android.startUserChange()">사용자 변경</button>
```

**login.html — JS 함수 (본문은 A2 정책에 맞게 구현)**

```javascript
function onLoginSuccess(token) {
  // 토큰 저장, 화면 전환 등
}

function onLoginError(errorCode) {
  // NOT_REGISTERED, ACCOUNT_LOCKED, NETWORK_ERROR 등 분기
}

function onRetry(failureCount) {
}

function onLockedOut(seconds) {
}

function onSessionRetrying(retryCount, maxRetry) {
}
```

### ✅ 완료 확인 체크리스트

- [ ] WebView JS 활성화·`Android` 인터페이스 등록
- [ ] HTML 버튼·JS 콜백 함수 추가

---

## 10. STEP 9. Logcat 검증 방법

**필터**

```
tag:BIOMETRIC_LIB | tag:BIOMETRIC_BRIDGE
```

**정상 시 예상 로그 흐름**

1. `[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신`
2. `[BRIDGE] BiometricBridge > startLogin : 로그인 요청 수신`
3. `[AUTH] BiometricAuthManager > authenticate : 인증 시작`
4. `[AUTH] BiometricAuthManager > showPrompt : BiometricPrompt 표시`
5. `[AUTH] BiometricAuthManager > signPayload : 서명 완료`
6. `[AUTH] BiometricAuthManager > requestToken : 토큰 요청 시작`
7. `[AUTH] BiometricAuthManager > requestToken : 토큰 수신 완료`
8. `[AUTH] BiometricAuthManager > onSuccess : userId=...`
9. `[BRIDGE] callback > onLoginSuccess : 로그인 성공`
10. `[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송`

> lib 쪽 `[AUTH]` 로그 문구는 버전에 따라 약간 다를 수 있다. `BIOMETRIC_LIB`·`BIOMETRIC_BRIDGE` 태그 위주로 본다.

**CASE별 비정상 로그·대응**

| 로그 패턴 | CASE | 원인 | 대응 |
|-----------|------|------|------|
| `callback > onRetry` | 2 | 얼굴 불일치 등 | 재시도 UI |
| `callback > onLockedOut` | 4 | 실패 누적 잠금 | 잠금 시간 안내 |
| `callback > onSessionRetrying` | 3 | 세션 재시도 중 | 대기·네트워크 확인 |
| `callback > onNotRegistered` | 7 | 미등록 기기 | 등록 플로우 |
| `callback > onAccountLocked` | 9 | 계정 잠금 | 정책에 따른 해제 UI |
| `callback > onError` + `NETWORK_ERROR` | 5 | 네트워크 | 연결 확인 |
| `callback > onError` + `INVALID_SIGNATURE` | 6 | 서명 검증 실패 | 재시도·서버 로그 |
| `callback > onError` + `TIMESTAMP_OUT_OF_RANGE` | 8 | 시각 불일치 | 기기 시간 자동 설정 |
| `callback > onError` + `KEY_INVALIDATED` | 10 | 키 무효화 | 키 갱신 안내 |
| `callback > onError` + `SESSION_EXPIRED` | 11 | 재시도 한도 | 재로그인 유도 |

### ✅ 완료 확인 체크리스트

- [ ] 성공 플로우 로그 확인
- [ ] 필요 시 실패 CASE 로그 확인

---

## 11. STEP 10. 알려진 이슈 및 대응 방법

| 이슈 | 수정 대상 파일 | 원인 | 대응 방법 |
|------|---------------|------|-----------|
| TLS 핸드셰이크 실패 | 없음 (JDK 설정) | JDK 11.0.1 cacerts 구버전 | JDK 21 cacerts로 교체 |
| appcompat 버전 충돌 | biometric-bridge/build.gradle | jetifier + AndroidX 혼합 | appcompat:1.3.1 명시 |
| material 버전 충돌 | biometric-bridge/build.gradle | macro 태그 미지원 | material:1.4.0 명시 |
| 전이 의존성 충돌 | 없음 (명령) | AGP 캐시 | `./gradlew clean --refresh-dependencies` |
| Gradle Sync 캐시 오류 | 없음 (명령) | jetifier 캐시 | `./gradlew clean --refresh-dependencies` |
| Knox / Android 12+ 저장소 오류 | biometric-lib/build.gradle, biometric-bridge/build.gradle | security-crypto 1.0.0 | **1.1.0-alpha06**으로 통일 |

---

## 12. STEP 11. 버전 업데이트 방법

1. 로컬 저장소에서 `biometric-lib`, `biometric-bridge` 각각 `git pull`
2. A2 프로젝트에서 삭제: `biometric-lib/`, `biometric-bridge/`
3. 최신 폴더 다시 복사 (`build/` 제외)
4. Android Studio **Gradle Sync**
5. [STEP 9](#10-step-9-logcat-검증-방법)로 Logcat 재검증

**주의**: 모듈 경로·이름이 같으면 `settings.gradle`·app의 `project(':…')`는 보통 **재수정 불필요**.  
업스트림에서 `namespace`·패키지·의존성이 바뀌었으면 릴리스 노트를 보고 `build.gradle` 등을 다시 맞춘다.

---

## 13. 완료 체크리스트

- [ ] biometric-lib 폴더 복사 완료 (`biometric-lib/`)
- [ ] biometric-bridge 폴더 복사 완료 (`biometric-bridge/`)
- [ ] settings.gradle에 두 모듈 include 추가 완료
- [ ] app/build.gradle에 project() 참조 추가 완료
- [ ] biometric.properties 생성 완료
- [ ] .gitignore에 biometric.properties 추가 완료
- [ ] app/build.gradle에 BIOMETRIC_SERVER_URL buildConfigField 추가 완료
- [ ] AndroidManifest Merged Manifest 탭에서 권한 병합 확인
- [ ] AndroidBridge.java 생성 완료
- [ ] A2 로그인 Activity WebView 설정 추가 완료
- [ ] assets/login.html 버튼 및 JS 함수 추가 완료
- [ ] assembleDebug 빌드 성공
- [ ] 실기기 설치 후 안면인식 로그인 동작 확인
- [ ] Logcat 정상 흐름 로그 확인
- [ ] CASE별 예외 상황 동작 확인

---

*본 문서 경로: `biometric-android/biometric-bridge-setup.md`*
