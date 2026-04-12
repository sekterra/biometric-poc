# MIS 모바일 앱 프로젝트 — biometric-lib 직접 이식 가이드 (WebView 연동)

> **목적**: `alopex_blaze` 모듈에 **biometric-lib**를 **직접** 연동하여, 로그인 WebView에서 안면인식 로그인을 수행한다.  
> **폐기**: 기존 **biometric-bridge** 간접 참조 방식, **Static Injector** 방식은 본 문서 범위에서 사용하지 않는다.  
> **전제**: `alopex_blaze`의 **compileSdk가 33**으로 올라가 biometric-lib와 Gradle/의존성 정합이 맞는 환경이다.  
> **보안**: 서버 URL, 토큰 값 등 **민감 정보는 본 문서에 실제 값을 적지 않는다.** VDI에서 수동 설정한다.

---

## 목차

1. [이식 개요 및 구조](#1-이식-개요-및-구조)
2. [적용 순서](#2-적용-순서)
3. [STEP 1. 모듈 폴더 복사](#3-step-1-모듈-폴더-복사)
4. [STEP 2. settings.gradle 등록](#4-step-2-settingsgradle-등록)
5. [STEP 3. alopex_blaze/build.gradle 수정](#5-step-3-alopex_blazebuildgradle-수정)
6. [STEP 4. 민감 정보 설정](#6-step-4-민감-정보-설정)
7. [STEP 5. AndroidBridge.java 생성 (alopex_blaze)](#7-step-5-androidbridgejava-생성-alopex_blaze)
8. [STEP 6. AbstractBlazeWebViewDefaultContainerScreen 수정 (alopex_blaze)](#8-step-6-abstractblazewebviewdefaultcontainerscreen-수정-alopex_blaze)
9. [STEP 7. login.html 수정](#9-step-7-loginhtml-수정)
10. [STEP 8. Logcat 검증](#10-step-8-logcat-검증)
11. [알려진 이슈 및 대응](#11-알려진-이슈-및-대응)
12. [버전 업데이트 방법](#12-버전-업데이트-방법)
13. [완료 체크리스트](#13-완료-체크리스트)

--
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
| **MIS 모바일 앱 대응** | 등록 화면(`RegisterActivity` 대응 화면)으로 이동. `deviceId`(ANDROID_ID)를 Intent Extra로 전달 |

---

### CASE 2 — 등록 완료 기기 (ACTIVE)

| 항목 | 내용 |
|------|------|
| **상태명** | `ACTIVE` |
| **발생 조건** | 서버 기기 상태 = `"ACTIVE"` |
| **호출 흐름** | `getUserId()` → 응답 `status = "ACTIVE"` → `tokenStorage.saveRegistration()` → 로그인 화면 이동 |
| **MIS 모바일 앱 대응** | 로컬 등록 플래그 저장 후 안면인식 로그인 화면으로 라우팅 |

---

### CASE 3 — 챌린지 세션 만료 (SESSION_EXPIRED) 자동 재시도

| 항목 | 내용 |
|------|------|
| **상태명** | `SESSION_EXPIRED` |
| **발생 조건** | 챌린지 발급 후 60초 이내 서명 미완료 → 서버가 `SESSION_EXPIRED` 반환 |
| **호출 흐름** | `requestToken()` → `TokenVerificationException("SESSION_EXPIRED")` → `sessionRetryCount < 2` 이면 `onSessionRetrying()` 콜백 → Challenge 재요청 → BiometricPrompt 재실행 |
| **MIS 모바일 앱 대응** | `onSessionRetrying(retryCount, maxRetry)` 수신 시 "재시도 중..." 메시지 표시. 2회 초과 시 `onError(SESSION_EXPIRED)` |

---

### CASE 4 — 안면인식 실패 (재시도 가능)

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_AUTH_FAILED` |
| **발생 조건** | `BiometricPrompt.onAuthenticationFailed()` 호출 (얼굴 불일치, 잠금 임계 미도달) |
| **호출 흐름** | `onAuthenticationFailed()` → `failurePolicyManager.recordFailure()` → `onRetry(failureCount)` 콜백 |
| **MIS 모바일 앱 대응** | 실패 횟수 UI 표시 ("N회 실패 / 최대 5회") |

---

### CASE 5 — 로컬 잠금 (일시적)

| 항목 | 내용 |
|------|------|
| **상태명** | `LOCAL_LOCKOUT` |
| **발생 조건** | 인증 실패 횟수 ≥ 서버 정책 `maxRetryBeforeLockout` → `isLocallyLocked()` = true |
| **호출 흐름** | `onAuthenticationFailed()` → `isLocallyLocked()` 체크 → `onLockedOut(remainingSeconds)` 콜백 |
| **MIS 모바일 앱 대응** | 카운트다운 타이머 UI 표시. 시간 경과 후 자동 재시도 활성화 |

---

### CASE 6 — INVALID_SIGNATURE 자동 키 재발급

| 항목 | 내용 |
|------|------|
| **상태명** | `INVALID_SIGNATURE` → 자동 `KEY_RENEWAL` |
| **발생 조건** | 서버가 `INVALID_SIGNATURE` 3회 연속 반환 (`invalidSignatureCount >= 3`) |
| **호출 흐름** | `TokenVerificationException("INVALID_SIGNATURE")` → 카운터 증가 → 3회 도달 시 `KeyRenewalHandler.renewAndRetry()` → 새 EC 키쌍 생성 → 서버 공개키 갱신 → BiometricPrompt 재실행 |
| **MIS 모바일 앱 대응** | 자동 처리 (별도 UI 불필요). BiometricPrompt가 자동 재표시됨 |

---

### CASE 7 — 안면인식 미등록

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_NONE_ENROLLED` |
| **발생 조건** | `canAuthenticate(BIOMETRIC_WEAK)` = `BIOMETRIC_ERROR_NONE_ENROLLED` |
| **호출 흐름** | `authenticate()` → `canAuthenticate()` 체크 → `onError(BIOMETRIC_NONE_ENROLLED)` 콜백 |
| **MIS 모바일 앱 대응** | "안면인식 미등록" 다이얼로그 → "설정으로 이동" 버튼으로 생체인식 설정 화면 유도 |

---

### CASE 8 — 생체인식 하드웨어 불가

| 항목 | 내용 |
|------|------|
| **상태명** | `BIOMETRIC_HW_UNAVAILABLE` |
| **발생 조건** | `canAuthenticate()` 결과가 `BIOMETRIC_SUCCESS`도 `NONE_ENROLLED`도 아닌 경우, 또는 API < 28 |
| **호출 흐름** | `authenticate()` → `onError(BIOMETRIC_HW_UNAVAILABLE)` 콜백 |
| **MIS 모바일 앱 대응** | "생체인식 기능 사용 불가" 오류 메시지 표시 |

---

### CASE 9 — 서버 잠금 (LOCKED)

| 항목 | 내용 |
|------|------|
| **상태명** | `LOCKED` |
| **발생 조건** | 서버 기기 상태 = `"LOCKED"` (관리자 잠금 또는 서버 측 다중 실패) |
| **호출 흐름** | `getUserId()` → `status = "LOCKED"` → ID/PW 잠금 해제 UI 표시 → `AuthApiClient.unlockDevice(deviceId)` 호출 |
| **MIS 모바일 앱 대응** | ID/PW 입력 폼 표시. 인증 성공 후 `unlockDevice()` 호출 → 등록 플래그 저장 → 로그인 화면 이동 |

---

### CASE 10 — Keystore 키 무효화 (KEY_INVALIDATED)

| 항목 | 내용 |
|------|------|
| **상태명** | `KEY_INVALIDATED` |
| **발생 조건** | ① 서버 기기 상태 = `"KEY_INVALIDATED"` (기기 상태 조회 시) ② 로그인 시 서버 409 KEY_INVALIDATED ③ 로컬 `KeyPermanentlyInvalidatedException` |
| **호출 흐름** | `onError(KEY_INVALIDATED)` → "보안키 재설정" 다이얼로그 → `biometricAuthManager.startRenewal()` → `KeyRenewalHandler.renewAndRetry()` → 키 재발급 → BiometricPrompt 재실행 |
| **MIS 모바일 앱 대응** | AlertDialog 표시 필수. 확인 시 `startRenewal()` 호출. 자동 복구 흐름 |

---

### CASE 11 — 계정 잠금 (ACCOUNT_LOCKED)

| 항목 | 내용 |
|------|------|
| **상태명** | `ACCOUNT_LOCKED` |
| **발생 조건** | 인증 실패 횟수 ≥ 서버 정책 `accountLockThreshold` → 서버 `lockAccount()` 호출 완료 |
| **호출 흐름** | `onAuthenticationFailed()` → `shouldRequestAccountLock()` = true → `authApiClient.lockAccount()` → `onAccountLocked()` 콜백 |
| **MIS 모바일 앱 대응** | ID/PW 입력 UI 표시 (안면인식 버튼 숨김). ID/PW 인증 성공 후 `unlockDevice()` 호출 |

---

### CASE 12 — 사용자 변경 (담당자 변경)

| 항목 | 내용 |
|------|------|
| **상태명** | `USER_CHANGE` |
| **발생 조건** | 담당자 변경 버튼 클릭 |
| **호출 흐름** | 확인 다이얼로그 → `userChangeHandler.verifyDeviceCredential()` → PIN/패턴 인증 → `onVerified()` → `userChangeHandler.executeChange()` → 서버 `unregisterDevice()` → 로컬 키 삭제 → `tokenStorage.clearAll()` → `onChangeCompleted()` → 등록 화면 이동 |
| **MIS 모바일 앱 대응** | 버튼 클릭 → AlertDialog → PIN 인증 → 서버/로컬 초기화 → 등록 화면 |

## 1. 이식 개요 및 구조

### 변경 배경

| 항목 | 설명 |
|------|------|
| 기존 | biometric-bridge 모듈을 통한 **간접** 참조, Static Injector 등 별도 주입 경로 |
| 변경 이유 | `alopex_blaze` **compileSdk 33** 업그레이드로 biometric-lib와의 빌드 정합이 확보됨 → bridge·Injector **불필요** |
| 변경 방식 | **biometric-lib** 소스 모듈(AAR 빌드 원천)을 MIS 모바일 앱 루트에 두고 **`implementation project(':biometric-lib')`** 로 **직접** 참조 |

### 참고 환경 (문서 작성 기준)

| 구분 | 값 |
|------|-----|
| MIS 모바일 앱 AGP | 7.0.4 |
| MIS 모바일 앱 Gradle | 7.2 |
| MIS 모바일 앱 JDK | 11 |
| alopex_blaze | compileSdk **33**, minSdk 23, targetSdk 28, appcompat **v7:28.0.0**, **jetifier=true** |
| gson | 로컬 jar **2.2.4** |
| biometric-lib | compileSdk 31, minSdk 23, targetSdk 31, Java 11 |
| WebView | `AlopexWebView` — `android.webkit.WebView` 직접 상속 |
| WebView 접근 | `BlazePageManager.WebViewHandler.instance().getPageWebView()` |
| 로그인 페이지 식별 | `PageManager.LOG_PAGE_ID = "login"`, `PageManager.KEY_NAV_ID = "id"` |
| 수행 주체 | 개발자가 **VDI에서 수동** 이식 |

### 기존 방식 vs 변경 방식

| 항목 | 기존 방식 | 변경 방식 |
|------|-----------|-----------|
| biometric-bridge | 필요 | **불필요** |
| Static Injector | 필요 | **불필요** |
| alopex_blaze compileSdk | 28 | **33** |
| AAR/모듈 참조 | 간접(bridge 경유) | **직접** (`:biometric-lib`) |

### 전체 동작 흐름

```text
[login.html]
    └── Android.startFaceLogin() 버튼 클릭
            ↓
[alopex_blaze — AndroidBridge]
    └── @JavascriptInterface 수신
    └── BiometricAuthManager.authenticate() 직접 호출
            ↓
[biometric-lib]
    └── BiometricPrompt 표시
    └── ECDSA 서명
    └── B2 서버 토큰 발급
            ↓
[alopex_blaze — AndroidBridge]
    └── AuthCallback.onSuccess() 수신
    └── evaluateJavascript("onLoginSuccess('" + token + "')")  (토큰은 escapeJs 처리)
            ↓
[login.html]
    └── onLoginSuccess(token) 처리
```

---

## 2. 적용 순서

1. **biometric-lib** 폴더 복사 (MIS 모바일 앱 프로젝트 루트)
2. **settings.gradle**에 `include ':biometric-lib'` 등록
3. **alopex_blaze/build.gradle** 의존성·Java 11·(필요 시) buildConfig 설정
4. **Gradle Sync**
5. **민감 정보** — 프로젝트 루트 `biometric.properties` + `.gitignore` + `buildConfigField`
6. **AndroidBridge.java** 생성 (`alopex_blaze`, 패키지 예: `com.skcc.alopex.v2.screen.biometric`)
7. AbstractBlazeWebViewDefaultContainerScreen — 로그인 페이지에서만 addJavascriptInterface 등록
8. **login.html** — 버튼·JS 콜백 함수
9. **빌드** 후 **Logcat** 검증

---

## 3. STEP 1. 모듈 폴더 복사

```text
┌──────────────────────────────────────────────────────────┐
│ 복사 원본: {로컬저장소}/biometric-lib/                   │
│ 복사 대상: MIS 모바일 앱 프로젝트 루트/biometric-lib/               │
│ 수정하지 않는 모듈: 기존 모듈 전부                        │
└──────────────────────────────────────────────────────────┘
```

- **제외**: `biometric-lib/build/` (로컬 빌드 산출물 — 복사하지 않음)
- **복사하지 않음**: **biometric-bridge** 폴더 전체
- 복사 후 디렉터리 예시:

```text
MIS 모바일 앱-Project-Root/
├── app/
├── alopex_blaze/
├── alopex_blaze_core/
├── biometric-lib/          ← 추가
│   ├── build.gradle
│   └── src/
├── settings.gradle
└── ...
```

---

## 4. STEP 2. settings.gradle 등록

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: 프로젝트 루트                            │
│ 수정 대상 파일: settings.gradle                          │
│ 수정하지 않는 모듈: 기존 모듈 전부                        │
└──────────────────────────────────────────────────────────┘
```

**추가 예시** (기존 `include` 목록에 한 줄 추가):

```gradle
// 추가 전 (예시)
include ':app', ':alopex_blaze', ':alopex_blaze_core'

// 추가 후 (예시)
include ':app', ':alopex_blaze', ':alopex_blaze_core', ':biometric-lib'
```

- **biometric-bridge**는 `include` 하지 않는다.
- 저장 후 Android Studio에서 **File → Sync Project with Gradle Files** 실행.

---

## 5. STEP 3. alopex_blaze/build.gradle 수정

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 수정 대상 파일: alopex_blaze/build.gradle                │
│ 수정하지 않는 모듈: app 및 나머지 모듈 전부              │
└──────────────────────────────────────────────────────────┘
```

### dependencies

```gradle
dependencies {
    // 안면인식 핵심 라이브러리 (ECDSA, 토큰 발급)
    implementation project(':biometric-lib')
}
```

### compileOptions (Java 11)

```gradle
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}
```

### app/build.gradle 수정이 불필요한 이유

- **biometric-lib**는 **`alopex_blaze`에서만** 직접 참조한다.
- 앱 모듈(`app`)은 기존처럼 **alopex_blaze**를 의존하면, 런타임에 biometric-lib 클래스가 **전이(transitive)** 로 classpath에 포함된다 (단일 APK/Multi-module 구성에 따라 최종 병합 확인).

### Gradle Sync 후 빌드 오류 대응

```text
┌──────────────────────────────────────────────────────┐
│ 오류: appcompat 버전 충돌                            │
│ 수정 대상 파일: alopex_blaze/build.gradle            │
│ 조치: androidx.appcompat:appcompat:1.3.1 등 명시       │
├──────────────────────────────────────────────────────┤
│ 오류: gson 충돌                                      │
│ 수정 대상 파일: alopex_blaze/build.gradle            │
│ 조치: compileOnly 'com.google.code.gson:gson:2.2.4'  │
├──────────────────────────────────────────────────────┤
│ 오류: 전이 의존성/캐시 꼬임                          │
│ 수정 대상 파일: 없음 (명령어 실행)                   │
│ 조치: ./gradlew clean --refresh-dependencies         │
└──────────────────────────────────────────────────────┘
```

> Jetifier·구형 support 라이브러리와 AndroidX 혼용 시 충돌 패턴이 다를 수 있으므로, 실제 에러 메시지의 **중복 클래스/강제 해상도** 안내를 우선한다.

---

## 6. STEP 4. 민감 정보 설정

```text
┌──────────────────────────────────────────────────────────┐
│ 생성 대상 파일: MIS 모바일 앱 프로젝트 루트/biometric.properties    │
│ 수정 대상 파일 1: .gitignore                             │
│ 수정 대상 파일 2: alopex_blaze/build.gradle              │
└──────────────────────────────────────────────────────────┘
```

### biometric.properties (프로젝트 루트, Git에 올리지 않음)

```properties
# 실제 B2 인증 베이스 URL을 VDI에서만 입력 (본 문서에 실제 값 기재 금지)
BIOMETRIC_SERVER_URL=https://여기에-실제-서버-주소
```

### .gitignore

```gitignore
biometric.properties
```

### alopex_blaze/build.gradle — Properties 로드 및 BuildConfig

`android {` 블록 **상단**(또는 `defaultConfig` 위)에서 로드:

```gradle
def biometricProps = new Properties()
def biometricPropsFile = rootProject.file('biometric.properties')
if (biometricPropsFile.exists()) {
    biometricProps.load(biometricPropsFile.newDataInputStream())
} else {
    throw new GradleException("biometric.properties 가 없습니다. 프로젝트 루트에 생성하세요.")
}
```

`defaultConfig` 내부:

```gradle
defaultConfig {
    buildConfigField "String", "BIOMETRIC_SERVER_URL",
            "\"${biometricProps['BIOMETRIC_SERVER_URL']}\""
}
```

### buildFeatures

- AGP 7.x에서 `BuildConfig` 생성이 꺼져 있으면 `buildConfigField`가 동작하지 않는다.
- 필요 시 `android` 블록에 다음 추가:

```gradle
buildFeatures {
    buildConfig true
}
```

> 각 프로젝트 템플릿마다 기본값이 다르므로, **Build** 탭 오류 또는 `BuildConfig` 클래스 미생성 시 본 항목을 확인한다.

---

## 7. STEP 5. AndroidBridge.java 생성 (alopex_blaze)

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 생성 대상 파일:                                          │
│   alopex_blaze/src/main/java/com/skcc/alopex/v2/screen/ │
│   biometric/AndroidBridge.java (신규, 패키지는 팀 표준에 맞게 조정) │
│ 수정하지 않는 모듈: app, biometric-lib                   │
│ (AndroidBridge 생성자의 WebView 파라미터 타입은           │
│  AlopexWebView가 아닌 WebView 사용)                      │
└──────────────────────────────────────────────────────────┘
```

### 설계 요약 (A1 `biometric-demo-app` + `LoginActivity` 패턴 기반)

| 구성요소 | 역할 |
|----------|------|
| 생성자 인자 | `FragmentActivity` + **페이지 WebView** (`AlopexWebView`는 `WebView` 상속 가정 → 필드 타입은 `WebView`로 두고 인스턴스만 전달해도 됨) |
| `BiometricRuntime` (예시) | `AuthApiClient`, `ExecutorService`, `EcKeyManager` **앱 단위 1회 초기화** (데모의 `BiometricApplication`과 동일 목적) |
| `BiometricAuthManager` | `authenticate()`, `startRenewal()` |
| `TokenStorage` | 기기/토큰 저장 |
| `UserChangeHandler` | 담당자 변경(CASE 12) |
| `BuildConfig` | **`alopex_blaze` 모듈**의 `BIOMETRIC_SERVER_URL` (패키지는 해당 모듈 `applicationId`/namespace에 맞는 `BuildConfig` import) |

### import 관련 안내

- **`AlopexWebView`**: 실제 FQCN은 MIS 모바일 앱 소스 기준으로 확인한다. 본 예제는 **`WebView`** 필드에 `getPageWebView()` 반환 인스턴스를 넣는 방식으로 호환한다.
- **`BuildConfig`**: `import …alopex_blaze…BuildConfig` 형태는 **모듈 패키지**에 맞게 조정한다.

### 예시 코드 — `AndroidBridge.java` (전체)

> 아래 코드는 **가이드용 예시**이다. 등록 화면·메인 화면 Intent, 설정 화면 이동 등은 MIS 모바일 앱 실제 Activity/라우팅에 맞게 TODO를 채운다.

```java
package com.skcc.alopex.v2.screen.biometric;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.skcc.biometric.lib.BiometricLibConstants;
import com.skcc.biometric.lib.ErrorCode;
import com.skcc.biometric.lib.auth.BiometricAuthManager;
import com.skcc.biometric.lib.auth.UserChangeHandler;
import com.skcc.biometric.lib.crypto.EcKeyManager;
import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.policy.FailurePolicyManager;
import com.skcc.biometric.lib.storage.TokenStorage;

// import com.skcc.alopex.v2.BuildConfig; // 실제: alopex_blaze 모듈의 BuildConfig FQCN으로 교체

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebView ↔ Native 생체인증 브릿지 (MIS 모바일 앱 / alopex_blaze).
 *
 * <p>JS에서 {@code Android.*} 로 호출하며, Native에서는 {@link #callJs(String)} 으로 JS 콜백을 보낸다.
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    /** BiometricPrompt 및 다이얼로그용 Activity (FragmentActivity 이상). */
    private final FragmentActivity activity;

    /** {@code evaluateJavascript} 대상 — AlopexWebView 인스턴스를 전달한다. */
    private final WebView webView;

    private final BiometricAuthManager biometricAuthManager;
    private final UserChangeHandler userChangeHandler;
    private final TokenStorage tokenStorage;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    Runnable pendingNavigation = null;

    @Nullable
    CountDownTimer lockCountDownTimer = null;

    boolean isCountingDown = false;

    boolean isAccountLocked = false;

    private boolean isNotEnrolledDialogShowing = false;

    /**
     * AuthApiClient / Executor / EcKeyManager 를 앱 프로세스당 1세트만 두기 위한 헬퍼.
     * (A1 의 BiometricApplication 과 동일 역할을 alopex_blaze 쪽에서 최소 구현)
     */
    private static final class BiometricRuntime {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);
        private static volatile ExecutorService executor;
        private static volatile AuthApiClient authApiClient;
        private static volatile EcKeyManager ecKeyManager;

        static void ensureInit(Application app) {
            if (initialized.get()) {
                return;
            }
            synchronized (BiometricRuntime.class) {
                if (initialized.get()) {
                    return;
                }
                executor = Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("MIS 모바일 앱-biometric-io");
                    return t;
                });
                authApiClient = new AuthApiClient(BuildConfig.BIOMETRIC_SERVER_URL);
                ecKeyManager = new EcKeyManager(app.getPackageName() + ".biometric_ec_key");
                initialized.set(true);
            }
        }

        static ExecutorService executor() {
            return executor;
        }

        static AuthApiClient authApiClient() {
            return authApiClient;
        }

        static EcKeyManager ecKeyManager() {
            return ecKeyManager;
        }
    }

    public AndroidBridge(FragmentActivity activity, WebView pageWebView) {
        this.activity = activity;
        this.webView = pageWebView;

        BiometricRuntime.ensureInit(activity.getApplication());

        try {
            this.tokenStorage = new TokenStorage(activity);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("TokenStorage 초기화 실패", e);
        }

        FailurePolicyManager failurePolicyManager = new FailurePolicyManager();
        this.biometricAuthManager = new BiometricAuthManager(
                activity,
                BiometricRuntime.authApiClient(),
                BiometricRuntime.ecKeyManager(),
                tokenStorage,
                failurePolicyManager,
                BiometricRuntime.executor());

        this.userChangeHandler = new UserChangeHandler(
                activity,
                BiometricRuntime.ecKeyManager(),
                tokenStorage,
                BiometricRuntime.authApiClient(),
                BiometricRuntime.executor());
    }

    // ── JS → Native ─────────────────────────────────────────

    /**
     * 안면인식 로그인 시작 — {@link BiometricAuthManager#authenticate(FragmentActivity, BiometricAuthManager.AuthCallback)} 호출.
     */
    @JavascriptInterface
    public void startFaceLogin() {
        Log.d(TAG, "startFaceLogin() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신");
        activity.runOnUiThread(() -> {
            if (lockCountDownTimer != null) {
                lockCountDownTimer.cancel();
                lockCountDownTimer = null;
                isCountingDown = false;
            }
            biometricAuthManager.authenticate(activity, authCallback);
        });
    }

    /** {@link #startFaceLogin()} 별칭 (기존 HTML 호환). */
    @JavascriptInterface
    public void requestFaceLogin() {
        startFaceLogin();
    }

    /** 담당자 변경 — 확인 다이얼로그 후 {@link UserChangeHandler} 플로우. */
    @JavascriptInterface
    public void startUserChange() {
        activity.runOnUiThread(this::showUserChangeDialog);
    }

    /** {@link #startUserChange()} 별칭. */
    @JavascriptInterface
    public void requestChangeUser() {
        startUserChange();
    }

    /**
     * CASE 9 이후 ID/PW 잠금 해제 (서버 unlock API). 비밀번호는 로그에 남기지 않는다.
     */
    @JavascriptInterface
    public void startIdPwUnlock(String userId, String password) {
        Log.d(TAG, "startIdPwUnlock() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > unlockWithIdPw : 잠금 해제 요청 수신");
        String deviceId = tokenStorage.getDeviceId();
        BiometricRuntime.executor().submit(() -> {
            try {
                BiometricRuntime.authApiClient().unlockDevice(deviceId);
                tokenStorage.saveRegistration(deviceId, userId);
                activity.runOnUiThread(() -> {
                    isAccountLocked = false;
                    callJs("onUnlockSuccess()");
                });
            } catch (Exception e) {
                Log.e(TAG, "unlockWithIdPw 실패", e);
                activity.runOnUiThread(() -> callJs("onUnlockFailed()"));
            }
        });
    }

    // ── AuthCallback (CASE 1~11 요약 매핑) ─────────────────

    private final BiometricAuthManager.AuthCallback authCallback =
            new BiometricAuthManager.AuthCallback() {

                /** CASE 1: 로그인 성공 — accessToken 을 JS 로 전달. */
                @Override
                public void onSuccess(String userId, AuthApiClient.TokenResponse tokenResponse) {
                    Log.d(TAG, "[authCallback] onSuccess userId=" + userId);
                    Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
                    String token = tokenResponse.accessToken != null ? tokenResponse.accessToken : "";
                    callJs("onLoginSuccess('" + escapeJs(token) + "')");
                    // 네이티브 화면 전환이 필요하면 여기서 Intent 처리 (MIS 모바일 앱 라우팅에 맞게 구현)
                }

                /** CASE 7: 미등록 */
                @Override
                public void onNotRegistered() {
                    Log.d(TAG, "[authCallback] onNotRegistered");
                    Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onNotRegistered");
                    isAccountLocked = false;
                    callJs("onLoginError('NOT_REGISTERED')");
                    Toast.makeText(activity, "서버에 등록 정보가 없습니다.", Toast.LENGTH_LONG).show();
                }

                /** CASE 4: 잠금(초) */
                @Override
                public void onLockedOut(int remainingSeconds) {
                    Log.d(TAG, "[authCallback] onLockedOut: " + remainingSeconds);
                    Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLockedOut: " + remainingSeconds + "초");
                    callJs("onLockedOut(" + remainingSeconds + ")");
                    startCountdown(remainingSeconds);
                }

                /** CASE 2: 재시도 */
                @Override
                public void onRetry(int failureCount) {
                    Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onRetry: failureCount=" + failureCount);
                    callJs("onRetry(" + failureCount + ")");
                }

                /** CASE 9: 계정 잠금 */
                @Override
                public void onAccountLocked() {
                    Log.w(TAG, "[authCallback] onAccountLocked");
                    Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onAccountLocked");
                    isAccountLocked = true;
                    callJs("onLoginError('ACCOUNT_LOCKED')");
                }

                /** CASE 3: 세션 재시도 중 */
                @Override
                public void onSessionRetrying(int retryCount, int maxRetry) {
                    Log.d("BIOMETRIC_BRIDGE",
                            "[BRIDGE] callback > onSessionRetrying: " + retryCount + "/" + maxRetry);
                    callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
                }

                /**
                 * CASE 5,6,8,10,11 등 — 오류 코드를 JS 로 통일 전달.
                 * 일부 코드는 사용자 안내용 Native 다이얼로그를 추가로 띄운다.
                 */
                @Override
                public void onError(ErrorCode errorCode) {
                    Log.w(TAG, "[authCallback] onError: " + errorCode);
                    Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onError: " + errorCode.name());
                    callJs("onLoginError('" + errorCode.name() + "')");
                    switch (errorCode) {
                        case BIOMETRIC_NONE_ENROLLED:
                            callJs("setFaceLoginEnabled(false)");
                            showNotEnrolledDialog();
                            break;
                        case KEY_INVALIDATED:
                            showKeyInvalidatedDialog();
                            break;
                        case DEVICE_NOT_FOUND:
                            showDeviceNotFoundDialog();
                            break;
                        case TIMESTAMP_OUT_OF_RANGE:
                            showTimestampErrorDialog();
                            break;
                        case MISSING_SIGNATURE:
                            showMissingSignatureDialog();
                            break;
                        default:
                            break;
                    }
                }
            };

    // ── Native 다이얼로그 (필요 시 MIS 모바일 앱 UI 정책에 맞게 수정) ─────

    private void showKeyInvalidatedDialog() {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("보안키 재설정 필요")
                        .setMessage("생체 정보 변경으로 보안키를 다시 설정해야 합니다.")
                        .setPositiveButton("확인", (d, w) -> {
                            callJs("showProgress(true)");
                            callJs("setFaceLoginEnabled(false)");
                            biometricAuthManager.startRenewal(activity, authCallback);
                        })
                        .setNegativeButton("취소", null)
                        .setCancelable(false)
                        .show());
    }

    private void showDeviceNotFoundDialog() {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("기기 미등록")
                        .setMessage("등록된 기기를 찾을 수 없습니다.")
                        .setPositiveButton("확인", (d, w) -> {
                            tokenStorage.clearRegistration();
                            // TODO: MIS 모바일 앱 기기 등록 화면 Intent
                        })
                        .setCancelable(false)
                        .show());
    }

    private void showTimestampErrorDialog() {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("기기 시간 확인 필요")
                        .setMessage("설정에서 날짜·시간 자동 설정을 켜 주세요.")
                        .setPositiveButton("설정", (d, w) -> {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
                            } catch (ActivityNotFoundException e) {
                                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        })
                        .setNegativeButton("닫기", null)
                        .show());
    }

    private void showMissingSignatureDialog() {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("로그인 오류")
                        .setMessage("앱을 다시 시작한 뒤 로그인해 주세요.")
                        .setPositiveButton("확인", null)
                        .show());
    }

    private void showNotEnrolledDialog() {
        if (isNotEnrolledDialogShowing || activity.isFinishing()) {
            return;
        }
        isNotEnrolledDialogShowing = true;
        activity.runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                        .setTitle("안면인식 미등록")
                        .setMessage("설정에서 안면인식을 등록해 주세요.")
                        .setPositiveButton("설정으로 이동", (d, w) -> {
                            isNotEnrolledDialogShowing = false;
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            } catch (ActivityNotFoundException e) {
                                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        })
                        .setNegativeButton("나중에", (d, w) -> isNotEnrolledDialogShowing = false)
                        .setOnDismissListener(d -> isNotEnrolledDialogShowing = false)
                        .show());
    }

    private void showUserChangeDialog() {
        if (activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("담당자 변경")
                .setMessage("이 기기에 저장된 로그인·인증 정보가 삭제됩니다. 계속하시겠습니까?")
                .setPositiveButton("확인", (dialog, which) ->
                        userChangeHandler.verifyDeviceCredential(
                                activity,
                                new UserChangeHandler.UserChangeCallback() {

                                    @Override
                                    public void onVerified() {
                                        callJs("showProgress(true)");
                                        userChangeHandler.executeChange(activity, this);
                                    }

                                    @Override
                                    public void onChangeCompleted() {
                                        callJs("showProgress(false)");
                                        Toast.makeText(activity, "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
                                        // TODO: MIS 모바일 앱 신규 등록 WebView/Activity 로 이동
                                    }

                                    @Override
                                    public void onChangeFailed(ErrorCode errorCode) {
                                        callJs("showProgress(false)");
                                        callJs("onLoginError('" + errorCode.name() + "')");
                                    }

                                    @Override
                                    public void onCanceled() { /* no-op */ }
                                }))
                .setNegativeButton("취소", null)
                .show();
    }

    // ── 카운트다운 (잠금 UI) ─────────────────────────────────

    void startCountdown(int seconds) {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            isCountingDown = false;
        }
        isCountingDown = true;
        lockCountDownTimer = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) Math.ceil(millisUntilFinished / 1000.0);
                callJs("onCountdownTick(" + sec + ")");
            }

            @Override
            public void onFinish() {
                isCountingDown = false;
                lockCountDownTimer = null;
                callJs("onCountdownFinish()");
            }
        };
        lockCountDownTimer.start();
    }

    /** Activity onDestroy 에서 호출 권장. */
    public void cancelCountdown() {
        if (lockCountDownTimer != null) {
            lockCountDownTimer.cancel();
            lockCountDownTimer = null;
            isCountingDown = false;
        }
    }

    public void cancelPendingNavigation() {
        if (pendingNavigation != null) {
            mainHandler.removeCallbacks(pendingNavigation);
            pendingNavigation = null;
        }
    }

    // ── JS 유틸 ─────────────────────────────────────────────

    /**
     * JS 문자열 인자용 이스케이프 (작은따옴표, 역슬래시).
     */
    private static String escapeJs(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Native → JS. {@code evaluateJavascript} 는 반드시 메인 스레드에서 호출.
     */
    private void callJs(String jsCall) {
        Log.d("BIOMETRIC_BRIDGE",
                "[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function=" + jsCall);
        activity.runOnUiThread(() ->
                webView.evaluateJavascript("javascript:" + jsCall, null));
    }
}
```

### userId 전달 및 등록 정보 저장

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 수정 대상 파일: AndroidBridge.java                       │
│ 수정 범위:                                               │
│   ① currentUserId 필드 추가                              │
│   ② setUserId() @JavascriptInterface 메서드 추가         │
└──────────────────────────────────────────────────────────┘
```

**추가 이유**

- `login.html`의 `input id="USERID"` 값이 Native로 전달되지 않으면 `TokenStorage`에 **deviceId + userId**가 저장되지 않는다.
- `BiometricAuthManager` 로그인 시도 시 `TokenStorage.getDeviceId()` 가 `null` 인 경우가 생길 수 있다.
- 결과: **NOT_REGISTERED** 등 기대와 다른 오류가 발생할 수 있다.

**① 클래스 필드 추가** (기존 필드 선언부 근처)

```java
/** login.html의 input id="USERID" 로 전달받은 사용자 ID */
private String currentUserId = "";
```

**② `@JavascriptInterface` 메서드 추가**

```java
/**
 * login.html의 input id="USERID" 값을 Native로 전달.
 * startFaceLogin() 호출 전에 반드시 먼저 호출되어야 함.
 *
 * 역할:
 *   1. currentUserId 저장
 *   2. ANDROID_ID 기반 deviceId 생성
 *   3. TokenStorage.saveRegistration(deviceId, userId) 저장
 *
 * @param userId 사용자 ID 입력값 (null 또는 빈값 입력 시 빈문자열 처리)
 */
@JavascriptInterface
public void setUserId(String userId) {
    Log.d("BIOMETRIC_BRIDGE",
            "[BRIDGE] AndroidBridge > setUserId : userId=" + userId);
    this.currentUserId = userId != null ? userId.trim() : "";

    // ANDROID_ID 기반 deviceId 생성
    // 주의: 동일 기기에서 항상 동일한 값 반환
    String deviceId = Settings.Secure.getString(
            activity.getContentResolver(),
            Settings.Secure.ANDROID_ID);

    // TokenStorage에 deviceId + userId 저장
    // BiometricAuthManager 로그인 시 이 값을 참조함
    tokenStorage.saveRegistration(deviceId, currentUserId);

    Log.d("BIOMETRIC_BRIDGE",
            "[BRIDGE] AndroidBridge > setUserId : 등록 정보 저장 완료"
            + " deviceId=" + deviceId.substring(0, 4) + "****");
}
```

**호출 순서 주의사항**

```text
setUserId(userId)    ← 반드시 먼저 호출
        ↓
startFaceLogin()     ← 이후 호출
```

순서가 바뀌면 `TokenStorage` 저장 전에 인증이 시작되어 **NOT_REGISTERED** 등 문제가 발생할 수 있다.

---

## 8. STEP 6. AbstractBlazeWebViewDefaultContainerScreen 수정 (alopex_blaze)

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 수정 대상 파일: AbstractBlazeWebViewDefaultContainerScreen.java │
│ 수정 범위: onCreate()에 브릿지 등록 블록 추가            │
│            (setContentView() 및 printWebView() 직후)      │
│ 수정하지 않는 모듈: app 및 나머지 모듈 전부              │
└──────────────────────────────────────────────────────────┘
```

### 클래스 상속 구조

```text
AbstractBlazeWebViewDefaultContainerScreen
    → AbstractBlazeWebViewContainerScreen
        → AbstractScreen
            → AppCompatActivity
                → FragmentActivity (BiometricPrompt 요건 충족 ✅)
```

### 기존 DefaultAlopexWebViewScreen과의 차이점

| 항목 | DefaultAlopexWebViewScreen | AbstractBlazeWebViewDefaultContainerScreen |
|------|---------------------------|---------------------------------------------|
| WebView 접근 | `getPageWebView()` | `getWebViewWithoutContainer()` |
| WebView 타입 | `AlopexWebView` | `WebView` |
| 삽입 위치 | `initialize()` 직후 | `setContentView()` 이후 |

### onCreate() 기존 구조

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    RelativeLayout layout = new RelativeLayout(this);
    layout.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    WebView webView = WebViewHandler.instance().getWebViewWithoutContainer();

    layout.addView(webView);
    setContentView(layout);
    BlazePageManager.WebViewHandler.instance().printWebView();

    // ← 여기에 브릿지 등록 블록 삽입
}
```

### 삽입 위치: `setContentView()` 및 `printWebView()` 직후

- 기존 **`setContentView()`**, **`printWebView()`** 등 **코드 수정·삭제 금지** — 아래 블록만 **추가**한다.
- **`webView` 변수**는 위 `onCreate()`에서 이미 선언된 것을 그대로 사용한다.
- **`AndroidBridge` 생성자**: 첫 인자 `this` → `FragmentActivity` 요건 충족(`AppCompatActivity` 상속), 둘째 인자 → **`WebView`** 타입 (`AlopexWebView` 아님).
- **`setWebViewClient` 교체/삭제 금지** — Blaze 라우팅·로딩 정책 유지.

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    RelativeLayout layout = new RelativeLayout(this);
    layout.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    WebView webView =
            WebViewHandler.instance().getWebViewWithoutContainer();

    layout.addView(webView);
    setContentView(layout);
    BlazePageManager.WebViewHandler.instance().printWebView();

    // [생체인증 브릿지 추가 시작] ─────────────────────────
    // setContentView() 이후 WebView가 준비된 시점에 삽입
    // 로그인 페이지(LOG_PAGE_ID)인 경우에만 브릿지 등록
    // 다른 WebView 화면에는 브릿지가 추가되지 않음
    String pageId = getIntent()
            .getStringExtra(PageManager.KEY_NAV_ID);

    Log.d("BIOMETRIC_BRIDGE",
            "[BRIDGE] AbstractBlazeWebViewDefaultContainerScreen"
            + " > onCreate : pageId=" + pageId);

    if (PageManager.LOG_PAGE_ID.equals(pageId)) {
        if (webView != null) {
            // 기존 "Android" 인터페이스와 충돌 시
            // "BiometricAndroid"로 변경하고 login.html도 동일하게 수정
            webView.addJavascriptInterface(
                    new AndroidBridge(this, webView), "Android");
            Log.d("BIOMETRIC_BRIDGE",
                    "[BRIDGE] AbstractBlazeWebViewDefaultContainerScreen"
                    + " > onCreate : 브릿지 등록 완료");
        } else {
            // webView null — 원인 추적용 로그
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] AbstractBlazeWebViewDefaultContainerScreen"
                    + " > onCreate : webView == null");
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] 예상 원인 1 — getWebViewWithoutContainer() 반환값 null");
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] 예상 원인 2 — WebViewHandler 초기화 순서 문제");
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] 조치 — WebViewHandler 초기화 순서 및 Stack 상태 확인 필요");
        }
    } else {
        // 로그인 페이지가 아닌 경우 — 브릿지 미등록 정상 케이스
        Log.d("BIOMETRIC_BRIDGE",
                "[BRIDGE] AbstractBlazeWebViewDefaultContainerScreen"
                + " > onCreate : 로그인 페이지 아님 → 브릿지 미등록 (정상)");
    }
    // [생체인증 브릿지 추가 끝] ───────────────────────────
}
```

### `"Android"` 인터페이스 이름 충돌

- 기존 Alopex/Blaze가 이미 `addJavascriptInterface(..., "Android")` 를 쓰는 경우 **이름 충돌**이 난다.
- 대응: 인터페이스명을 **`BiometricAndroid`** 로 통일하고, **STEP 7** 의 HTML에서도 `BiometricAndroid.startFaceLogin()` 형태로 맞춘다.

---

## 9. STEP 7. login.html 수정

```text
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app (또는 웹 리소스가 위치한 모듈)       │
│ 수정 대상 파일: login.html (실제 경로는 MIS 모바일 앱 자산 구조 따름) │
│ 수정하지 않는 모듈: (웹 자산만 수정 시) alopex_blaze 등  │
└──────────────────────────────────────────────────────────┘
```

### 버튼 예시

```html
<!-- 안면인식 로그인 버튼 — Android.startFaceLogin() 직접 호출 대신
     onFaceLoginClick()을 거쳐 setUserId() 먼저 호출 -->
<button type="button" onclick="onFaceLoginClick()">
    안면인식 로그인
</button>
<button type="button" onclick="Android.startUserChange()">
    사용자 변경
</button>
```

> 인터페이스명을 `BiometricAndroid` 로 바꾼 경우 `Android.setUserId` / `Android.startFaceLogin` / `Android.startUserChange` 를 `BiometricAndroid.*` 로 치환한다.

### login.js 추가 내용

기존 `login.js` 파일에 아래 내용을 추가한다.

```javascript
// ── 안면인식 로그인 진입점 ────────────────────────────
/**
 * 안면인식 로그인 버튼 클릭 시 호출.
 * setUserId() → startFaceLogin() 순서를 보장한다.
 */
function onFaceLoginClick() {
    var userId = document.getElementById('USERID').value;
    if (!userId || userId.trim() === '') {
        alert('사용자 ID를 입력해주세요.');
        return;
    }
    // ① userId를 Native로 전달 및 TokenStorage 저장
    Android.setUserId(userId.trim());
    // ② 안면인식 로그인 시작
    Android.startFaceLogin();
}

// ── 생체인증 Native 콜백 ──────────────────────────────

// CASE 1 — 로그인 성공 (accessToken 문자열)
function onLoginSuccess(token) {
    console.log('onLoginSuccess', token);
    // TODO: 세션 저장, 메인 화면 전환 등
}

// CASE 5,6,7,8,9,10,11 — 오류 코드 문자열
// 예: NOT_REGISTERED, ACCOUNT_LOCKED, NETWORK_ERROR 등
function onLoginError(errorCode) {
    console.warn('onLoginError', errorCode);
    // TODO: 메시지 매핑 및 UI 표시
}

// CASE 2 — 재시도 가능 실패 (실패 횟수)
function onRetry(failureCount) {
    console.log('onRetry', failureCount);
}

// CASE 4 — 일시 잠금(초)
function onLockedOut(seconds) {
    console.log('onLockedOut', seconds);
}

// CASE 3 — 세션 만료 자동 재시도 중
function onSessionRetrying(retryCount, maxRetry) {
    console.log('onSessionRetrying', retryCount, maxRetry);
}

// 카운트다운 타이머 연동 (선택)
function onCountdownTick(sec) { console.log('tick', sec); }
function onCountdownFinish() { console.log('countdown finish'); }

// Native 보조 훅 (선택)
function setFaceLoginEnabled(enabled) { }
function showProgress(show) { }
```

---

## 10. STEP 8. Logcat 검증

### 필터 예시 (Android Studio Logcat)

```text
tag:BIOMETRIC_LIB | tag:BIOMETRIC_BRIDGE
```

### 정상 흐름에서 기대하는 로그 (예시)

순서는 네트워크·사용자 입력에 따라 약간 달라질 수 있다.

1. `[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신`
2. `[AUTH] BiometricAuthManager > authenticate : 인증 시작`
3. `[AUTH] BiometricAuthManager > showPrompt : BiometricPrompt 표시`
4. `[AUTH] BiometricAuthManager > signPayload : 서명 완료`
5. `[AUTH] BiometricAuthManager > requestToken : 토큰 요청 시작`
6. `[AUTH] BiometricAuthManager > requestToken : 토큰 수신 완료 …`
7. `[AUTH] BiometricAuthManager > onSuccess : userId=...`
8. `[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function=onLoginSuccess('…')`

### CASE별 비정상 패턴 (요약)

| 현상 | 가능 원인 |
|------|-----------|
| `CASE7` / `onNotRegistered` | 서버에 기기·사용자 미등록 |
| `CASE9` / `ACCOUNT_LOCKED` | 실패 횟수 정책에 의한 잠금 |
| `SESSION_EXPIRED`, `CASE11` | 챌린지 TTL 초과·재시도 한도 |
| `KEY_INVALIDATED` | 생체 데이터 변경 등으로 Keystore 키 무효 |
| `NETWORK_ERROR` | 단말 네트워크·서버 가용성 |

---

## 11. 알려진 이슈 및 대응

| 이슈 | 수정 대상 파일 | 원인 | 대응 방법 |
|------|----------------|------|-----------|
| TLS 핸드셰이크 실패 | 없음 (JDK 설정) | 구 JDK cacerts | 조직 정책에 따른 JDK/cacerts 갱신 (예: 최신 LTS JDK의 cacerts 참고) |
| appcompat 버전 충돌 | alopex_blaze/build.gradle | jetifier + AndroidX 혼합 | androidx appcompat 명시 버전으로 정렬 |
| gson 충돌 | alopex_blaze/build.gradle | 로컬 gson 2.2.4 vs 전이 의존성 | `compileOnly` 등으로 중복 제거 |
| 전이 의존성 충돌 | 없음 (명령어) | AGP/Gradle 캐시 | `./gradlew clean --refresh-dependencies` |
| `setAppCacheEnabled` 오류 | alopex_blaze 내 WebView 설정 코드 | API 33에서 제거 | 해당 호출 제거 또는 SDK 버전 분기 |
| `webView` null | AbstractBlazeWebViewDefaultContainerScreen | `getWebViewWithoutContainer()` null·WebViewHandler 순서 | Logcat 원인 로그 참고, 초기화·Stack 확인 |
| VDI 커서 분석 오류로 인한 클래스 불일치 | AbstractBlazeWebViewDefaultContainerScreen.java | `DefaultAlopexWebViewScreen` 으로 잘못 분석됨 | `AbstractBlazeWebViewDefaultContainerScreen.onCreate()` 에 브릿지 등록 블록 삽입 |
| NOT_REGISTERED 오류 (로그인 시) | AndroidBridge.java, login.js | TokenStorage에 deviceId·userId 미저장 | `setUserId()` 호출 후 `startFaceLogin()` 순서 확인. `login.js`의 `onFaceLoginClick()` 구현 확인 |
| `"Android"` 인터페이스 충돌 | AndroidBridge 등록부, login.html | 기존 JS Bridge와 이름 충돌 | `BiometricAndroid` 로 통일 |

---

## 12. 버전 업데이트 방법

biometric-lib 새 버전을 반영할 때:

1. 로컬 **biometric-lib** 저장소에서 `git pull` (또는 배포된 소스 수령)
2. MIS 모바일 앱 프로젝트 내 **`biometric-lib/` 폴더 삭제**
3. 최신 소스를 다시 복사 (**`build/` 제외**)
4. Android Studio **Gradle Sync**
5. **Logcat**으로 로그인·오류 CASE 재검증

- **settings.gradle** 과 **alopex_blaze/build.gradle** 의 `implementation project(':biometric-lib')` 는 **최초 이식 후 보통 변경 없음** (lib 쪽 `minSdk`·의존성 변경 시만 재점검).

---

## 13. 완료 체크리스트

- [ ] `biometric-lib` 폴더 복사 완료 (MIS 모바일 앱 루트 `/biometric-lib/`, `build/` 제외)
- [ ] `settings.gradle`에 `include ':biometric-lib'` 추가 완료
- [ ] `alopex_blaze/build.gradle`에 `implementation project(':biometric-lib')` 추가 완료
- [ ] `alopex_blaze/build.gradle`에 `compileOptions` Java 11 추가 완료
- [ ] Gradle Sync 성공
- [ ] `biometric.properties` 생성 완료 (Git 미추적)
- [ ] `.gitignore`에 `biometric.properties` 추가 완료
- [ ] `alopex_blaze/build.gradle`에 `BIOMETRIC_SERVER_URL` `buildConfigField` 추가 완료
- [ ] 필요 시 `buildFeatures { buildConfig true }` 확인
- [ ] Merged Manifest에서 biometric-lib 병합 권한·uses-feature 확인
- [ ] `AndroidBridge.java` 생성 완료 (`alopex_blaze`)
- [ ] `AndroidBridge.java`에 `setUserId()` 메서드 추가 완료
- [ ] `login.js`에 `onFaceLoginClick()` 함수 추가 완료
- [ ] `login.html` 안면인식 버튼 `onclick="onFaceLoginClick()"` 으로 수정 완료
- [ ] `AbstractBlazeWebViewDefaultContainerScreen` `onCreate()` 브릿지 등록 블록 추가 완료 (`setContentView()` 직후)
- [ ] `login.html` 버튼 및 JS 콜백 함수 추가 완료
- [ ] `./gradlew :app:assembleDebug` (또는 팀 표준 모듈) 빌드 성공
- [ ] 실기기에서 안면인식 로그인 동작 확인
- [ ] Logcat `BIOMETRIC_LIB` / `BIOMETRIC_BRIDGE` 정상 흐름 확인
- [ ] 로그인 외 WebView 화면에서 브릿지 미동작(미등록) 확인
- [ ] 주요 CASE별 예외 동작 확인

---

**문서 위치**: `biometric-android/biometric-webview-patch.md`
