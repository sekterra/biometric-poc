# A2 Alopex WebView 생체인증 이식 패치 가이드 (Static Injector)

> **전제**: `biometric-lib`·`biometric-bridge` 모듈이 A2에 포함되어 있고,  
> `biometric.properties` + `BuildConfig.BIOMETRIC_SERVER_URL` 설정이 완료된 상태(또는 `biometric-bridge-setup.md`와 병행 적용).  
> 이식은 **VDI 등에서 개발자가 수동**으로 수행한다.

---

## 목차

1. [이식 개요 및 구조](#1-이식-개요-및-구조)
2. [적용 순서](#2-적용-순서)
3. [STEP 1. settings.gradle](#3-step-1-settingsgradle)
4. [STEP 2. app/build.gradle](#4-step-2-appbuildgradle)
5. [STEP 3. JavascriptBridgeInjector 인터페이스 생성 (alopex_blaze)](#5-step-3-javascriptbridgeinjector-인터페이스-생성-alopex_blaze)
6. [STEP 4. DefaultAlopexWebViewScreen 수정 (alopex_blaze)](#6-step-4-defaultalopexwebviewscreen-수정-alopex_blaze)
7. [STEP 5. AndroidBridge.java 생성 (app)](#7-step-5-androidbridgejava-생성-app)
8. [STEP 6. Application 클래스에 Injector 등록 (app)](#8-step-6-application-클래스에-injector-등록-app)
9. [STEP 7. login.html 수정](#9-step-7-loginhtml-수정)
10. [STEP 8. Logcat 검증](#10-step-8-logcat-검증)
11. [알려진 이슈 및 대응](#11-알려진-이슈-및-대응)
12. [완료 체크리스트](#12-완료-체크리스트)

---

## 1. 이식 개요 및 구조

### 이전 가이드의 한계 (요약)

| 방식 | 문제 |
|------|------|
| **BiometricAlopexWebViewScreen 상속** | `NavigationManager` 등이 `DefaultAlopexWebViewScreen.class`를 직접 지정하면 서브클래스가 실행되지 않음 |
| **DefaultAlopexWebViewScreen에 `AndroidBridge` 직접 import** | `AndroidBridge`(app) → `BiometricBridge` → `security-crypto:1.1.0-alpha06` 등으로 **minCompileSdk 상향**이 전이되면 **alopex_blaze(compileSdk 28)** 와 **checkAarMetadata** 충돌 |
| **근본 원인** | **alopex_blaze(compileSdk 28)** 에서 **app 모듈 클래스를 직접 참조할 수 없음** (모듈 방향·메타데이터) |

### 채택 방식: Static Injector

- **alopex_blaze**: `JavascriptBridgeInjector` **인터페이스만** 정의하고, `DefaultAlopexWebViewScreen`에서 **정적 콜백**으로 `inject(webView, pageId, activity)` 호출.
- **app**: `Application.onCreate()`에서 **구현체(람다 또는 익명 클래스)** 를 `registerBridgeInjector`로 등록. 여기서만 `AndroidBridge`·`BiometricBridge`·`PageManager.LOG_PAGE_ID`를 참조.
- **결과**: alopex_blaze는 app을 **import하지 않음** → 순환 참조·AAR 메타데이터를 app 쪽에 가둘 수 있음.

### 동작 흐름

```
[app 모듈]
Application.onCreate()
    └── DefaultAlopexWebViewScreen.registerBridgeInjector(injector)

[alopex_blaze 모듈]
DefaultAlopexWebViewScreen.onCreate()
    └── super.onCreate()   ← initialize() + WebView 준비
    └── sBridgeInjector != null 이면
            └── pageId = getIntent().getStringExtra(PageManager.KEY_NAV_ID)
            └── pageWebView = BlazePageManager.WebViewHandler.instance().getPageWebView()
            └── sBridgeInjector.inject(pageWebView, pageId, this)

[app 모듈 — 등록된 injector 내부]
    if (PageManager.LOG_PAGE_ID.equals(pageId))   // 예: "login"
        webView.addJavascriptInterface(new AndroidBridge(activity, webView), "Android")

[biometric-bridge / biometric-lib]
    AndroidBridge → BiometricBridge → BiometricAuthManager → …
```

---

## 2. 적용 순서

1. `settings.gradle`
2. `app/build.gradle`
3. **Gradle Sync**
4. `JavascriptBridgeInjector.java` 생성 (**alopex_blaze**)
5. `DefaultAlopexWebViewScreen.java` 수정 (**alopex_blaze**)
6. `AndroidBridge.java` 생성 (**app**)
7. **Application** 클래스 `onCreate()` 수정 (**app**)
8. `login.html` 수정
9. 빌드 후 **Logcat** 검증

---

## 3. STEP 1. settings.gradle

```
┌──────────────────────────────────────────┐
│ 수정 대상 모듈: 프로젝트 루트            │
│ 수정 대상 파일: settings.gradle          │
│ 수정하지 않는 모듈: 기존 모듈 전부       │
└──────────────────────────────────────────┘
```

기존 `include` 목록 **하단**에 추가:

```groovy
include ':biometric-lib'
include ':biometric-bridge'
```

---

## 4. STEP 2. app/build.gradle

```
┌──────────────────────────────────────────┐
│ 수정 대상 모듈: app                      │
│ 수정 대상 파일: app/build.gradle         │
│ 수정하지 않는 모듈: alopex_blaze 등      │
└──────────────────────────────────────────┘
```

```groovy
dependencies {
    // ... 기존 의존성 ...

    implementation project(':biometric-lib')
    implementation project(':biometric-bridge')
}
```

**`alopex_blaze/build.gradle`에 `biometric-bridge`를 넣지 않는 이유**

- 생체 AAR 의존성은 **app**에서만 둔다.
- alopex_blaze는 **인터페이스 + 호출 한 줄**만 추가하므로 `security-crypto` 등이 **alopex_blaze 그래프에 올라오지 않음**.
- **checkAarMetadata**(minCompileSdk 불일치)를 alopex_blaze(compileSdk 28)와 분리하기 위함.

**참고**: `app`도 compileSdk 28이면, 충돌 시 `biometric-bridge/build.gradle`에 `appcompat:1.3.1` 등을 명시하는 것은 **`biometric-bridge-setup.md`** 및 §11과 동일.

---

## 5. STEP 3. JavascriptBridgeInjector 인터페이스 생성 (alopex_blaze)

```
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 적용 대상 파일: (신규)                                   │
│   alopex_blaze/src/main/java/com/skcc/alopex/v2/screen/  │
│   JavascriptBridgeInjector.java                          │
│ 패키지: com.skcc.alopex.v2.screen                        │
│ (프로젝트 규칙에 맞게 경로만 조정 가능)                   │
└──────────────────────────────────────────────────────────┘
```

`DefaultAlopexWebViewScreen`과 **동일 패키지**에 두면 추가 `import` 없이 사용하기 쉽다.

```java
package com.skcc.alopex.v2.screen;

import androidx.fragment.app.FragmentActivity;

/**
 * WebView에 JavascriptInterface를 주입하는 콜백 인터페이스.
 * alopex_blaze가 app 모듈을 직접 참조하지 않고
 * 생체인증 브릿지를 연결하기 위해 사용한다.
 */
public interface JavascriptBridgeInjector {

    /**
     * @param webView  JavascriptInterface를 등록할 AlopexWebView
     * @param pageId   현재 페이지 ID (예: Intent extra — {@code PageManager.KEY_NAV_ID})
     * @param activity FragmentActivity (BiometricPrompt 요건)
     */
    void inject(AlopexWebView webView, String pageId, FragmentActivity activity);
}
```

> `AlopexWebView`는 기존 alopex_blaze에서 쓰는 타입 그대로 사용한다. (패키지가 다르면 이 파일에 `import` 추가.)

---

## 6. STEP 4. DefaultAlopexWebViewScreen 수정 (alopex_blaze)

```
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: alopex_blaze                             │
│ 수정 대상 파일: DefaultAlopexWebViewScreen.java          │
│ 수정 범위: 정적 필드 1개 + 정적 메서드 1개 + onCreate 호출 블록 │
└──────────────────────────────────────────────────────────┘
```

### ① 클래스 필드 추가

클래스 본문 상단(다른 static 필드 근처)에 추가:

```java
// 생체인증 브릿지 주입 콜백 (app 모듈에서 등록)
// null이면 기존 동작 유지
private static JavascriptBridgeInjector sBridgeInjector;
```

### ② 정적 등록 메서드 추가

`DefaultAlopexWebViewScreen` 클래스 안, 적절한 public 영역에 추가:

```java
/**
 * 생체인증 브릿지 주입 콜백 등록.
 * app 모듈 Application.onCreate()에서 호출한다.
 *
 * @param injector 주입 구현체 (null 전달 시 해제)
 */
public static void registerBridgeInjector(JavascriptBridgeInjector injector) {
    sBridgeInjector = injector;
}
```

### ③ `onCreate()` 내부 호출

**`initialize()` 직후**, **`loadUrl()` 호출 이전**에 삽입한다.  
(`initialize()`가 조기 return하는 분기가 있으면 그 **아래**에 둘지, **공통으로 WebView가 준비된 뒤**인지 A2 소스에 맞게 조정.)

```java
        // [생체인증 브릿지 주입 시작] ─────────────────────────
        // initialize() 직후 WebView가 준비된 시점에 호출
        // sBridgeInjector가 null이면 기존 동작 그대로 유지
        if (sBridgeInjector != null) {
            String pageId = getIntent()
                .getStringExtra(PageManager.KEY_NAV_ID);
            AlopexWebView pageWebView =
                BlazePageManager.WebViewHandler.instance().getPageWebView();
            if (pageWebView != null) {
                sBridgeInjector.inject(pageWebView, pageId, this);
            }
        }
        // [생체인증 브릿지 주입 끝] ───────────────────────────
```

**주의**

- **`setWebViewClient`로 `AlopexWebViewClient`를 교체하지 않는다.**
- **`pageWebView` null 체크 필수.**
- 로그인 여부 판별(`LOG_PAGE_ID`)은 **app 쪽 injector 구현**에서 수행한다(§8).

---

## 7. STEP 5. AndroidBridge.java 생성 (app)

```
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app                                      │
│ 적용 대상 파일:                                          │
│   app/src/main/java/com/skens/nsms/biometric/            │
│   AndroidBridge.java (신규 생성)                         │
│ 수정하지 않는 모듈: alopex_blaze, biometric-bridge       │
└──────────────────────────────────────────────────────────┘
```

**주의사항**

- `AlopexWebView` **import**는 A2 실제 패키지로 수정한다.
- `BuildConfig` **import**는 app `applicationId`(네임스페이스) 기준으로 맞춘다.
- JS 인터페이스명 **`"Android"`** 가 기존과 충돌하면 **`"BiometricAndroid"`** 로 바꾸고, **`login.html`도 동일하게** 수정한다.

```java
package com.skens.nsms.biometric;

import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.fragment.app.FragmentActivity;

import com.skens.nsms.BuildConfig;
import com.skens.nsms.biometric.bridge.BiometricBridge;
import com.skens.nsms.biometric.bridge.BiometricBridgeCallback;

// 필수: A2 프로젝트에서 AlopexWebView의 실제 패키지로 수정
// import com.skcc.alopex.v2.blaze.AlopexWebView;

/**
 * WebView(AlopexWebView) ↔ 생체인증 Native 브릿지.
 *
 * 역할:
 *   - JS에서 호출한 @JavascriptInterface 메서드를 수신
 *   - BiometricBridge를 통해 AAR 인증 플로우 실행
 *   - 인증 결과를 evaluateJavascript()로 JS에 전달
 *
 * 주의:
 *   - @JavascriptInterface는 백그라운드 스레드에서 호출됨
 *     → evaluateJavascript()는 반드시 runOnUiThread() 안에서 호출
 *   - JS 인터페이스 별칭 "Android"가 기존과 충돌 시
 *     "BiometricAndroid"로 변경하고 login.html도 동일하게 수정
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    /** BiometricPrompt 표시 및 콜백 수신용 Activity */
    private final FragmentActivity activity;

    /**
     * JS 콜백을 전달할 WebView.
     * AlopexWebView가 android.webkit.WebView를 직접 상속하므로
     * evaluateJavascript() 직접 호출 가능.
     */
    private final AlopexWebView alopexWebView;

    /**
     * 안면인식 인증 전체 플로우 오케스트레이터.
     * CASE 1~12 분기 처리 및 BiometricBridgeCallback 호출.
     */
    private final BiometricBridge biometricBridge;

    /**
     * @param activity      FragmentActivity (BiometricPrompt 요건, null 불가)
     * @param alopexWebView JS 콜백을 보낼 AlopexWebView (null 불가)
     */
    public AndroidBridge(FragmentActivity activity, AlopexWebView alopexWebView) {
        this.activity = activity;
        this.alopexWebView = alopexWebView;
        // BuildConfig.BIOMETRIC_SERVER_URL: app/build.gradle의 buildConfigField
        this.biometricBridge = new BiometricBridge(
                activity, BuildConfig.BIOMETRIC_SERVER_URL);
    }

    // ── JS → Native 진입점 ────────────────────────────────────

    /**
     * HTML: Android.startFaceLogin()
     * 안면인식 로그인 시작. BiometricPrompt 표시.
     * @JavascriptInterface는 백그라운드 스레드 → runOnUiThread 필수
     */
    @JavascriptInterface
    public void startFaceLogin() {
        Log.d("BIOMETRIC_BRIDGE",
                "[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신");
        activity.runOnUiThread(
                () -> biometricBridge.startLogin(bridgeLoginCallback));
    }

    /**
     * HTML: Android.requestFaceLogin()
     * startFaceLogin()의 별칭 — A2 login.html 네이밍에 맞춰 추가.
     */
    @JavascriptInterface
    public void requestFaceLogin() {
        startFaceLogin();
    }

    /**
     * HTML: Android.startUserChange()
     * 사용자 변경 시작 (CASE 12).
     * PIN/패턴 인증 → 기존 사용자 제거 → 신규 등록 화면 이동.
     */
    @JavascriptInterface
    public void startUserChange() {
        Log.d("BIOMETRIC_BRIDGE",
                "[BRIDGE] AndroidBridge > startUserChange : JS 호출 수신");
        activity.runOnUiThread(() ->
                biometricBridge.startUserChange(new BiometricBridgeCallback() {

                    @Override
                    public void onLoginSuccess(String userId, String token, int exp) {
                        // 사용자 변경 완료 후 토큰은 사용하지 않음
                        callJs("onLoginSuccess('')");
                    }

                    @Override
                    public void onError(String errorCode) {
                        callJs("onLoginError('" + errorCode + "')");
                    }

                    // 사용자 변경 플로우에서 미사용 콜백 — 빈 구현
                    @Override public void onRetry(int f) {}
                    @Override public void onSessionRetrying(int r, int m) {}
                    @Override public void onLockedOut(int s) {}
                    @Override public void onNotRegistered() {}
                    @Override public void onAccountLocked() {}
                }));
    }

    /**
     * HTML: Android.requestChangeUser()
     * startUserChange()의 별칭.
     */
    @JavascriptInterface
    public void requestChangeUser() {
        startUserChange();
    }

    // ── Native → JS 콜백 ─────────────────────────────────────

    /**
     * BiometricBridgeCallback 구현체.
     * CASE 1~11 결과를 JS 함수로 전달한다.
     * 모든 콜백은 BiometricBridge 내부에서 UI 스레드로 전달됨.
     */
    private final BiometricBridgeCallback bridgeLoginCallback =
            new BiometricBridgeCallback() {

        /** CASE 1: 로그인 성공 */
        @Override
        public void onLoginSuccess(String userId, String accessToken, int expiresIn) {
            Log.d("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
            callJs("onLoginSuccess('" + escapeJs(accessToken) + "')");
        }

        /** CASE 2: 안면 불일치 재시도 가능 */
        @Override
        public void onRetry(int failureCount) {
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onRetry : 재시도 failureCount=" + failureCount);
            callJs("onRetry(" + failureCount + ")");
        }

        /** CASE 3: SESSION_EXPIRED 자동 재시도 중 */
        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onSessionRetrying : 세션 재시도 "
                    + retryCount + "/" + maxRetry);
            callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
        }

        /** CASE 4: 연속 실패로 인한 일시 잠금 */
        @Override
        public void onLockedOut(int remainingSeconds) {
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onLockedOut : 일시잠금 remainingSeconds="
                    + remainingSeconds);
            callJs("onLockedOut(" + remainingSeconds + ")");
        }

        /** CASE 7: 기기 서버 미등록 */
        @Override
        public void onNotRegistered() {
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onNotRegistered : 미등록 기기");
            callJs("onLoginError('NOT_REGISTERED')");
        }

        /** CASE 9: 계정 잠금 → ID/PW 입력 필요 */
        @Override
        public void onAccountLocked() {
            Log.w("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onAccountLocked : 계정 잠금");
            callJs("onLoginError('ACCOUNT_LOCKED')");
        }

        /**
         * CASE 5,6,8,10,11 등 오류
         * errorCode 예: NETWORK_ERROR, KEY_INVALIDATED,
         *               INVALID_SIGNATURE, SESSION_EXPIRED,
         *               BIOMETRIC_NONE_ENROLLED 등
         */
        @Override
        public void onError(String errorCode) {
            Log.e("BIOMETRIC_BRIDGE",
                    "[BRIDGE] callback > onError : 오류 errorCode=" + errorCode);
            callJs("onLoginError('" + errorCode + "')");
        }
    };

    // ── 유틸 ─────────────────────────────────────────────────

    /**
     * JS 함수 호출. evaluateJavascript()는 UI 스레드 필수.
     * @param jsCall 예: "onLoginSuccess('token')"
     */
    private void callJs(String jsCall) {
        Log.d("BIOMETRIC_BRIDGE",
                "[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function="
                + jsCall);
        activity.runOnUiThread(() ->
                alopexWebView.evaluateJavascript(
                        "javascript:" + jsCall, null));
    }

    /**
     * JS 문자열 내 특수문자 이스케이프.
     * 토큰 값에 작은따옴표·역슬래시가 포함될 경우를 대비.
     */
    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
```

---

## 8. STEP 6. Application 클래스에 Injector 등록 (app)

```
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app                                      │
│ 수정 대상 파일: 기존 Application 서브클래스             │
│   (예: app/.../XXXApplication.java)                     │
│ 수정 범위: onCreate()에 registerBridgeInjector 호출 추가  │
└──────────────────────────────────────────────────────────┘
```

**추가 import (예시)**

```java
import androidx.fragment.app.FragmentActivity;

import com.skcc.alopex.v2.screen.DefaultAlopexWebViewScreen;
import com.skens.nsms.biometric.AndroidBridge;
// PageManager: A2 실제 패키지
// import ... .PageManager;
// AlopexWebView 패키지 (람다 파라미터 타입 추론만 쓰면 생략 가능할 수 있음)
```

**`onCreate()`에 추가** (`super.onCreate()` **이후**, 기존 초기화 코드는 **삭제·변경하지 않음**)

```java
    @Override
    public void onCreate() {
        super.onCreate();
        // ... 기존 초기화 코드 유지 ...

        // [생체인증 브릿지 등록] ────────────────────────────
        // DefaultAlopexWebViewScreen이 WebView 준비 후
        // pageId가 로그인(LOG_PAGE_ID)인 경우에만 AndroidBridge를 주입함
        DefaultAlopexWebViewScreen.registerBridgeInjector(
            (webView, pageId, activity) -> {
                if (PageManager.LOG_PAGE_ID.equals(pageId)) {
                    webView.addJavascriptInterface(
                        new AndroidBridge(activity, webView), "Android");
                }
            });
        // [생체인증 브릿지 등록 끝] ──────────────────────────
    }
```

**주의**

- 기존 `Application.onCreate()` 로직을 **덮어쓰지 말고** 블록만 **추가**한다.
- 기존 WebView용 `"Android"` **JavascriptInterface**와 충돌하면, 문자열을 **`"BiometricAndroid"`** 로 바꾸고 **§9 `login.html`도 동일하게** 수정한다.

---

## 9. STEP 7. login.html 수정

```
┌──────────────────────────────────────────────────────────┐
│ 수정 대상 모듈: app (또는 zip/웹 리소스 빌드 소스)         │
│ 수정 대상 파일: login.html                               │
└──────────────────────────────────────────────────────────┘
```

**버튼** (`"BiometricAndroid"` 사용 시 접두어만 치환)

```html
<button type="button" onclick="Android.startFaceLogin()">안면인식 로그인</button>
<button type="button" onclick="Android.startUserChange()">사용자 변경</button>
```

**JS 함수** (Native `evaluateJavascript`와 시그니처 일치)

```javascript
/** CASE 1 — 로그인 성공, token 전달 */
function onLoginSuccess(token) {
  console.log('onLoginSuccess', token ? '(token 있음)' : '');
  // TODO: 토큰 저장, Alopex 다음 화면 등
}

/** CASE 5,6,7,8,9,10,11 — 오류 코드 문자열 */
function onLoginError(errorCode) {
  console.warn('onLoginError', errorCode);
  // NOT_REGISTERED, ACCOUNT_LOCKED, NETWORK_ERROR 등 분기
}

/** CASE 2 — 재시도 가능한 실패 */
function onRetry(failureCount) {
  console.log('onRetry', failureCount);
}

/** CASE 4 — 일시 잠금(초) */
function onLockedOut(seconds) {
  console.log('onLockedOut', seconds);
}

/** CASE 3 — 세션 자동 재시도 중 */
function onSessionRetrying(retryCount, maxRetry) {
  console.log('onSessionRetrying', retryCount, maxRetry);
}
```

---

## 10. STEP 8. Logcat 검증

**필터**

```
tag:BIOMETRIC_BRIDGE | tag:BIOMETRIC_LIB
```

**정상 흐름 (예시 순서)**

1. `[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신`
2. `[BRIDGE] BiometricBridge > startLogin : 로그인 요청 수신`
3. `[AUTH] BiometricAuthManager > authenticate` … (lib 버전에 따라 문구 상이)
4. `BiometricPrompt` 표시
5. `[AUTH] ... signPayload` / `requestToken` …
6. `[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=...`
7. `[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function=onLoginSuccess(...)`

**CASE별 비정상 로그·대응 (요약)**

| 로그 패턴 | CASE | 원인 | 대응 |
|-----------|------|------|------|
| `callback > onRetry` | 2 | 얼굴 불일치 등 | UI 재시도 |
| `callback > onLockedOut` | 4 | 실패 누적 | 잠금 시간 안내 |
| `callback > onSessionRetrying` | 3 | 세션 재시도 | 대기 |
| `callback > onNotRegistered` | 7 | 미등록 기기 | 등록 플로우 |
| `callback > onAccountLocked` | 9 | 계정 잠금 | 정책 안내 |
| `callback > onError` + `NETWORK_ERROR` | 5 | 네트워크 | 연결 확인 |
| `callback > onError` + `INVALID_SIGNATURE` | 6 | 서명 실패 | 재시도·서버 로그 |
| `callback > onError` + `TIMESTAMP_OUT_OF_RANGE` | 8 | 시각 오차 | 기기 시간 |
| `callback > onError` + `KEY_INVALIDATED` | 10 | 키 무효화 | 키 갱신 UX |
| `callback > onError` + `SESSION_EXPIRED` | 11 | 재시도 한도 | 재로그인 |

---

## 11. 알려진 이슈 및 대응

| 이슈 | 수정 대상 파일 | 원인 | 대응 방법 |
|------|---------------|------|-----------|
| TLS 핸드셰이크 실패 | 없음 (JDK) | JDK 11.0.1 `cacerts` 구버전 | JDK 21 `cacerts`로 교체 등 |
| appcompat 버전 충돌 | `biometric-bridge/build.gradle` | jetifier + AndroidX 혼합 | `androidx.appcompat:appcompat:1.3.1` 명시 |
| material 버전 충돌 | `biometric-bridge/build.gradle` | macro 태그 미지원 | `com.google.android.material:material:1.4.0` 명시 |
| 전이 의존성 충돌 | 없음 (명령) | AGP 캐시 | `./gradlew clean --refresh-dependencies` |
| Gradle Sync 캐시 오류 | 없음 (명령) | jetifier 캐시 | `./gradlew clean --refresh-dependencies` |
| Knox / Android 12+ 저장소 오류 | `biometric-lib`·`biometric-bridge` `build.gradle` | `security-crypto` 1.0.0 | **1.1.0-alpha06** 유지 |
| **sBridgeInjector null로 브릿지 미동작** | `Application.java` | `registerBridgeInjector` 미호출 | `Application.onCreate()`에서 등록 확인 |
| **pageWebView null로 브릿지 미등록** | `DefaultAlopexWebViewScreen` | `initialize()` 조기 return 등 | 로그인 진입 경로·삽입 위치 확인 |
| **"Android" 인터페이스 충돌** | `AndroidBridge` 등록부, `login.html` | 기존 JSNI와 동일 별칭 | `"BiometricAndroid"`로 통일 |
| **로그인 외 화면에 브릿지 등록** | `Application` 람다 | `LOG_PAGE_ID` 조건 누락 | `PageManager.LOG_PAGE_ID.equals(pageId)` 유지 |

---

## 12. 완료 체크리스트

- [ ] `settings.gradle`에 `include` 2줄 추가
- [ ] `app/build.gradle`에 `project(':biometric-lib')`·`project(':biometric-bridge')` 추가
- [ ] Gradle Sync 성공
- [ ] `JavascriptBridgeInjector.java` 생성 (alopex_blaze)
- [ ] `DefaultAlopexWebViewScreen`에 정적 필드·`registerBridgeInjector`·`onCreate` 주입 블록 추가
- [ ] `AndroidBridge.java` 생성 (app)
- [ ] `Application.onCreate()`에 `registerBridgeInjector` 추가
- [ ] `login.html` 버튼 및 JS 함수 추가
- [ ] `assembleDebug` 빌드 성공
- [ ] 실기기에서 **로그인 화면** 진입 후 Logcat 정상 흐름 확인
- [ ] **로그인 외** WebView 화면에서 브릿지 **미동작**(또는 `Android` 미노출) 확인
- [ ] CASE별 예외 동작 확인

---

*문서: `biometric-android/biometric-webview-patch.md`*  
*패턴: Static Injector (`JavascriptBridgeInjector` + `registerBridgeInjector`)*
