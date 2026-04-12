# A2 Alopex WebView 생체인증 이식 패치 가이드  
(BiometricAlopexWebViewScreen 확장 · alopex_blaze 비수정)

> **전제**: `biometric-lib`, `biometric-bridge` 모듈이 A2에 이미 복사·등록되어 있고,  
> `biometric.properties` + `BuildConfig.BIOMETRIC_SERVER_URL` 설정이 완료된 상태(또는 동시에 적용).

**적용 순서 (권장)**

1. [5. settings.gradle](#5-settingsgradle)  
2. [4. app/build.gradle](#4-appbuildgradle)  
3. **Gradle Sync** 실행 (Android Studio)  
4. [1. AndroidBridge.java](#1-androidbridgejava-신규)  
5. [2. BiometricAlopexWebViewScreen.java](#2-biometricalopexwebviewscreenjava-신규-생성)  
6. [2-1. AndroidManifest.xml](#2-1-androidmanifestxml-수정)  
7. [3. login.html](#3-loginhtml-추가--수정)  
8. 빌드 후 [검증](#검증) (Logcat)

---

## A1 기준: JS 인터페이스 이름 `"Android"` 충돌 여부

A1(`biometric-android`)에서는 **화면마다 별도의 `WebView` 인스턴스**에 `addJavascriptInterface(..., "Android")`를 붙이므로, **동일 WebView에 두 번 등록하지 않는 한** 이름이 같아도 서로 다른 화면에서는 충돌하지 않습니다.

- `LoginActivity` → `"Android"` (`AndroidBridge`)
- `RegisterActivity` / `MainActivity` / `MainAfterLoginActivity` → 각각 다른 Bridge 클래스, 동일 별칭 `"Android"`

**A2**에서는 **하나의 `AlopexWebView`에 이미 `JavascriptInterface`가 등록**되어 있을 수 있습니다.  
그 경우 **같은 WebView에 `"Android"`를 두 번 등록하면 안 됩니다.**  
→ 기존 delegator와 충돌 시 아래를 모두 **`"BiometricAndroid"`** 로 통일합니다.

| 변경 위치 | 원래 값 | 충돌 시 값 |
|-----------|---------|------------|
| `addJavascriptInterface(..., "???")` | `"Android"` | `"BiometricAndroid"` |
| HTML / JS | `Android.requestFaceLogin()` | `BiometricAndroid.requestFaceLogin()` |

---

## 1. AndroidBridge.java (신규)

```
┌─────────────────────────────────────────┐
│ 적용 대상 모듈: app                     │
│ 적용 대상 파일: app/src/main/java/com/skens/nsms/biometric/AndroidBridge.java │
│ 적용 위치: 신규 파일 생성 (디렉터리 없으면 생성) │
└─────────────────────────────────────────┘
```

**주의**

- `AlopexWebView`는 A2 모듈에 정의된 타입입니다. **아래 import 줄을 A2 프로젝트의 실제 패키지로 바꿉니다.**
- `AlopexWebView`가 `android.webkit.WebView`를 **상속하지 않으면** `evaluateJavascript`를 쓸 수 있는 **실제 `WebView` 참조**(예: `getWebView()`, `getRealWebView()` 등)로 바꿔야 합니다. (A2 API에 맞게 수정)

```java
package com.skens.nsms.biometric;

import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.fragment.app.FragmentActivity;

import com.skens.nsms.BuildConfig;
import com.skens.nsms.biometric.bridge.BiometricBridge;
import com.skens.nsms.biometric.bridge.BiometricBridgeCallback;

// 필수: A2 프로젝트에서 AlopexWebView의 실제 패키지로 import 추가
// import … .AlopexWebView;

/**
 * WebView(AlopexWebView) ↔ 생체인증 Native 브릿지.
 * <p>A1 LoginActivity의 AndroidBridge 중 BiometricBridge 경로를 축약한 형태입니다.
 * <p>JS 별칭은 기본 "Android" — 기존 Alopex와 충돌 시 "BiometricAndroid" 사용.
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    private final FragmentActivity activity;
    /** AlopexWebView가 WebView를 상속하면 그대로 사용; 아니면 내부 WebView로 교체 */
    private final AlopexWebView alopexWebView;
    private final BiometricBridge biometricBridge;

    /**
     * @param activity       FragmentActivity (BiometricPrompt 요건)
     * @param alopexWebView  JS 콜백을 보낼 AlopexWebView (WebView 상속 가정)
     */
    public AndroidBridge(FragmentActivity activity, AlopexWebView alopexWebView) {
        this.activity = activity;
        this.alopexWebView = alopexWebView;
        this.biometricBridge = new BiometricBridge(activity, BuildConfig.BIOMETRIC_SERVER_URL);
    }

    /**
     * HTML: {@code Android.requestFaceLogin()} — A1 {@code startFaceLogin()}과 동일 역할.
     */
    @JavascriptInterface
    public void requestFaceLogin() {
        startFaceLogin();
    }

    /**
     * HTML: {@code Android.requestChangeUser()} — A1 담당자 변경 진입과 동일 역할.
     */
    @JavascriptInterface
    public void requestChangeUser() {
        startUserChange();
    }

    /**
     * A1과 동일한 JS 메서드명을 쓰는 페이지가 있으면 이 메서드도 노출됩니다.
     */
    @JavascriptInterface
    public void startFaceLogin() {
        Log.d(TAG, "startFaceLogin() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신");
        activity.runOnUiThread(() -> biometricBridge.startLogin(bridgeLoginCallback));
    }

    /**
     * A1 {@code openUserChangeDialog()} 경로 없이 직접 사용자 변경 플로우 진입.
     */
    @JavascriptInterface
    public void startUserChange() {
        Log.d(TAG, "startUserChange() 호출");
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > startUserChange : JS 호출 수신");
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

    private final BiometricBridgeCallback bridgeLoginCallback = new BiometricBridgeCallback() {
        @Override
        public void onLoginSuccess(String userId, String accessToken, int expiresIn) {
            Log.d(TAG, "bridgeLoginCallback onLoginSuccess: userId=" + userId);
            Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLoginSuccess : 로그인 성공 userId=" + userId);
            callJs("onLoginSuccess('" + escapeJs(accessToken) + "')");
        }

        @Override
        public void onRetry(int failureCount) {
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onRetry : 재시도 failureCount=" + failureCount);
            callJs("onRetry(" + failureCount + ")");
        }

        @Override
        public void onSessionRetrying(int retryCount, int maxRetry) {
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onSessionRetrying : 세션 재시도 "
                    + retryCount + "/" + maxRetry);
            callJs("onSessionRetrying(" + retryCount + "," + maxRetry + ")");
        }

        @Override
        public void onLockedOut(int remainingSeconds) {
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onLockedOut : 일시잠금 remainingSeconds="
                    + remainingSeconds);
            callJs("onLockedOut(" + remainingSeconds + ")");
        }

        @Override
        public void onNotRegistered() {
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onNotRegistered : 미등록 기기");
            callJs("onLoginError('NOT_REGISTERED')");
        }

        @Override
        public void onAccountLocked() {
            Log.w("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onAccountLocked : 계정 잠금");
            callJs("onLoginError('ACCOUNT_LOCKED')");
        }

        @Override
        public void onError(String errorCode) {
            Log.e("BIOMETRIC_BRIDGE", "[BRIDGE] callback > onError : 오류 errorCode=" + errorCode);
            callJs("onLoginError('" + errorCode + "')");
        }
    };

    /**
     * evaluateJavascript는 메인 스레드에서만 호출해야 하므로 runOnUiThread 필수.
     */
    private void callJs(String jsCall) {
        Log.d("BIOMETRIC_BRIDGE", "[BRIDGE] AndroidBridge > evaluateJavascript : JS 콜백 전송 function=" + jsCall);
        activity.runOnUiThread(() ->
                alopexWebView.evaluateJavascript("javascript:" + jsCall, null));
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
```

**`AlopexWebView`가 `WebView`를 상속하지 않는 경우**  
`callJs`에서 `evaluateJavascript`를 호출할 수 있는 **내부 `WebView` 참조**를 사용하세요. (예: `alopexWebView.getWebView()` 등 — A2 API명에 맞게 수정.)

**`BuildConfig` 패키지**: `applicationId`와 동일한 패키지에 생성됩니다.  
`com.skens.nsms`가 아니면 `import com.skens.nsms.BuildConfig`를 실제 앱 패키지로 수정하세요.

---

## 2. BiometricAlopexWebViewScreen.java (신규 생성)

```
┌─────────────────────────────────────────────────────────┐
│ 적용 대상 모듈: app                                     │
│ 적용 대상 파일:                                         │
│   app/src/main/java/com/skens/nsms/                     │
│   BiometricAlopexWebViewScreen.java (신규 생성)         │
│ DefaultAlopexWebViewScreen 직접 수정 불필요              │
└─────────────────────────────────────────────────────────┘
```

**이유**

- `DefaultAlopexWebViewScreen`은 `com.skcc.alopex.v2.screen` 패키지(**alopex_blaze** 모듈)에 있다.
- `AndroidBridge`는 `com.skens.nsms.biometric` 패키지(**app** 모듈)에 둔다.
- **alopex_blaze → app** 방향 의존성을 추가하면 **순환 참조**가 될 수 있어, alopex_blaze 원본에 `AndroidBridge`를 import 할 수 없다.
- **app** 모듈에서 `DefaultAlopexWebViewScreen`을 **상속**한 `BiometricAlopexWebViewScreen`을 두면, app은 이미 alopex_blaze에 의존하므로 브릿지 등록만 app에서 처리할 수 있다.

**주의**

- `super.onCreate()` 안에서 `initialize()` 등이 이미 호출되는 구조라면, **하위 클래스 `onCreate`에서 `super.onCreate()` 직후**에 브릿지를 붙인 시점이 **loadUrl 이전**인지 A2 실제 소스로 한 번 더 확인한다.
- `initialize()`가 조기 return하면 `pageWebView`가 **null**일 수 있으므로 **반드시 null 체크** 후 `addJavascriptInterface` 실행.
- **`setWebViewClient`로 AlopexWebViewClient를 교체하지 않는다.** (부모 클래스 동작 유지)

```java
package com.skens.nsms;

import android.os.Bundle;

// DefaultAlopexWebViewScreen: A2 실제 패키지로 확인 후 수정
import com.skcc.alopex.v2.screen.DefaultAlopexWebViewScreen;
import com.skens.nsms.biometric.AndroidBridge;

// AlopexWebView: A2 실제 패키지로 확인 후 수정
// import ... .AlopexWebView;

// BlazePageManager: A2 실제 패키지로 확인 후 수정
// import ... .BlazePageManager;

/**
 * DefaultAlopexWebViewScreen을 확장하여 안면인식 브릿지를 추가한 클래스.
 * alopex_blaze 모듈을 수정하지 않고 app 모듈에서 기능을 확장한다.
 *
 * 적용 이유:
 *   DefaultAlopexWebViewScreen(alopex_blaze)에서 AndroidBridge(app)를
 *   직접 import하면 순환 참조가 발생하므로 상속으로 우회한다.
 */
public class BiometricAlopexWebViewScreen
        extends DefaultAlopexWebViewScreen {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // super.onCreate()에서 initialize() 및 기존 WebView 설정 완료됨
        // initialize() 직후이므로 loadUrl() 이전 보장

        // [생체인증 브릿지 추가 시작] ─────────────────────────────
        // initialize()가 조기 return한 경우 pageWebView가 null일 수 있음
        // → 반드시 null 체크 후 addJavascriptInterface 실행
        AlopexWebView pageWebView =
            BlazePageManager.WebViewHandler.instance().getPageWebView();

        if (pageWebView != null) {
            // 기존 "Android" JS 인터페이스와 충돌 시
            // "BiometricAndroid"로 변경하고 login.html도 동일하게 수정
            pageWebView.addJavascriptInterface(
                new AndroidBridge(this, pageWebView), "Android");
        }
        // [생체인증 브릿지 추가 끝] ──────────────────────────────
    }
}
```

---

## 2-1. AndroidManifest.xml 수정

```
┌─────────────────────────────────────────────────────────┐
│ 적용 대상 모듈: app                                     │
│ 적용 대상 파일: app/src/main/AndroidManifest.xml        │
│ 적용 위치: DefaultAlopexWebViewScreen 선언 부분         │
└─────────────────────────────────────────────────────────┘
```

로그인(또는 해당 웹 화면)에 사용하던 **`DefaultAlopexWebViewScreen` Activity 선언**을 **`BiometricAlopexWebViewScreen`으로 교체**한다.

**변경 전 (예시)**

```xml
<activity
    android:name="com.skcc.alopex.v2.screen.DefaultAlopexWebViewScreen"
    ... />
```

**변경 후 (예시)**

- 기존 `DefaultAlopexWebViewScreen`의 `android:theme`, `android:configChanges` 등 **모든 속성을 그대로** `BiometricAlopexWebViewScreen` 쪽에 복사한다.
- **원래 `DefaultAlopexWebViewScreen` 블록은 삭제하지 않고 XML 주석으로 보존**한다.

```xml
<!--
<activity
    android:name="com.skcc.alopex.v2.screen.DefaultAlopexWebViewScreen"
    android:theme="@style/..."
    android:configChanges="keyboard|keyboardHidden|orientation|screenSize"
    ... />
-->
<activity
    android:name="com.skens.nsms.BiometricAlopexWebViewScreen"
    android:theme="@style/..."
    android:configChanges="keyboard|keyboardHidden|orientation|screenSize"
    ... />
```

**주의**

- `Intent`나 Alopex 내부에서 **클래스 이름 문자열**으로 `DefaultAlopexWebViewScreen`을 지정하는 코드가 있으면, 동일하게 `BiometricAlopexWebViewScreen`으로 바꾸는지 별도 검색이 필요할 수 있다.

---

## 3. login.html 추가 / 수정

```
┌─────────────────────────────────────────┐
│ 적용 대상 모듈: app (또는 zip에 포함되어 배포되는 웹 리소스 빌드 소스) │
│ 적용 대상 파일: login.html (실제 경로는 A2 웹 패키징 구조에 따름) │
│ 적용 위치: <body> 내 버튼 영역 + <script> 블록 │
└─────────────────────────────────────────┘
```

**버튼 (JS 별칭이 `BiometricAndroid`이면 `Android` → `BiometricAndroid`로 변경)**

```html
<button type="button" onclick="Android.requestFaceLogin()">안면인식 로그인</button>
<button type="button" onclick="Android.requestChangeUser()">사용자 변경</button>
```

**JS 함수 전체 (A1 login.html 흐름을 토큰·에러 코드 중심으로 정리)**

```javascript
/**
 * CASE 1 — Native에서 evaluateJavascript("onLoginSuccess('...')") 호출
 * @param {string} token 액세스 토큰
 */
function onLoginSuccess(token) {
  console.log('onLoginSuccess token length=' + (token ? token.length : 0));
  // TODO: A2 정책에 따라 토큰 저장, 다음 화면 전환, Alopex 네비게이션 연동
}

/**
 * CASE 7,9 및 CASE 5,6,8,10,11 — Native에서 onLoginError('코드') 호출
 * @param {string} errorCode 예: NOT_REGISTERED, ACCOUNT_LOCKED, NETWORK_ERROR …
 */
function onLoginError(errorCode) {
  console.warn('onLoginError', errorCode);
  // TODO: 메시지 매핑 후 토스트/다이얼로그
}

/**
 * CASE 2 — 재시도 가능한 실패
 */
function onRetry(failureCount) {
  console.log('onRetry', failureCount);
}

/**
 * CASE 4 — 일시 잠금(초)
 */
function onLockedOut(seconds) {
  console.log('onLockedOut', seconds);
}

/**
 * CASE 3 — 세션 재시도 중
 */
function onSessionRetrying(retryCount, maxRetry) {
  console.log('onSessionRetrying', retryCount, maxRetry);
}
```

> A1 `login.html`에는 `onLoginSuccess()` 인자 없음·`onRetry(failureCount, maxCount)`·`onError(message)` 등 **다른 시그니처**가 있습니다.  
> 위 스니펫은 **본 패치의 Native(`callJs`)와 1:1로 맞춘 형태**입니다. 기존 A1 스타일 페이지와 병합할 때는 **함수 시그니처 충돌**을 확인하세요.

---

## 4. app/build.gradle

```
┌─────────────────────────────────────────┐
│ 적용 대상 모듈: app                     │
│ 적용 대상 파일: app/build.gradle        │
│ 적용 위치: dependencies { } 블록 안     │
└─────────────────────────────────────────┘
```

```groovy
dependencies {
    // ... 기존 의존성 ...

    implementation project(':biometric-lib')
    implementation project(':biometric-bridge')
}
```

(충돌 시 `biometric-bridge/build.gradle`에 `appcompat:1.3.1` / `material:1.4.0` 등 — `biometric-bridge-setup.md` 참고.)

---

## 5. settings.gradle

```
┌─────────────────────────────────────────┐
│ 적용 대상 모듈: 프로젝트 루트            │
│ 적용 대상 파일: settings.gradle         │
│ 적용 위치: 기존 include 목록 하단       │
└─────────────────────────────────────────┘
```

```groovy
include ':biometric-lib'
include ':biometric-bridge'
```

---

## 검증

- **Gradle Sync** 후 앱 빌드.
- Logcat 필터: `tag:BIOMETRIC_BRIDGE`  
  버튼 탭 시 `[BRIDGE] AndroidBridge > startFaceLogin : JS 호출 수신` 등 A1과 동일 태그 메시지 확인.

---

*본 문서는 A2 저장소를 직접 수정하지 않으며, 수동 적용용 패치 가이드입니다.*  
*저장 위치: `biometric-android/biometric-webview-patch.md`*
