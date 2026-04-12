# biometric-bridge 모듈 생성 결과

## 개요

`biometric-bridge` Android Library 모듈을 `biometric-android` 프로젝트에 신규 생성하고 빌드 성공 여부를 확인하였다.
실제 AAR 연결 및 기능 구현은 이번 작업 범위에 포함되지 않는다.

---

## 생성된 파일 구조

```
biometric-android/
├── biometric-bridge/
│   ├── build.gradle
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/
│               └── com/
│                   └── skens/
│                       └── nsms/
│                           └── BiometricBridge.java
├── settings.gradle                  ← ':biometric-bridge' 추가
└── biometric-demo-app/
    └── build.gradle                 ← implementation project(':biometric-bridge') 추가
```

---

## 파일별 내용

### biometric-bridge/build.gradle

```groovy
plugins {
    id 'com.android.library'
}

android {
    namespace 'com.skens.nsms'
    compileSdk 31

    defaultConfig {
        minSdk 23
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    implementation 'androidx.fragment:fragment:1.5.7'
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.0.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    compileOnly 'com.google.code.gson:gson:2.2.4'
}
```

### biometric-bridge/src/main/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

### biometric-bridge/src/main/java/com/skens/nsms/BiometricBridge.java

```java
package com.skens.nsms;

/**
 * BiometricBridge - 생체인증 라이브러리와 A2 앱을 연결하는 브릿지 모듈
 *
 * 현재는 빌드 검증용 플레이스홀더 클래스입니다.
 * AAR 연결 및 기능 구현은 이후 단계에서 수행합니다.
 */
public class BiometricBridge {

    private static final String TAG = "BiometricBridge";

    private BiometricBridge() {
        // 인스턴스화 방지 — 향후 싱글턴 또는 팩토리 패턴으로 대체 예정
    }

    /**
     * 라이브러리 버전 반환 (플레이스홀더)
     */
    public static String getVersion() {
        return "1.0.0-placeholder";
    }
}
```

---

## 수정된 기존 파일

### settings.gradle (변경 전 → 후)

```groovy
// 변경 전
include ':biometric-lib', ':biometric-demo-app'

// 변경 후
include ':biometric-lib', ':biometric-demo-app', ':biometric-bridge'
```

### biometric-demo-app/build.gradle (변경 전 → 후)

```groovy
// 변경 전
dependencies {
    implementation project(':biometric-lib')
    ...
}

// 변경 후
dependencies {
    implementation project(':biometric-lib')
    implementation project(':biometric-bridge')   // 추가
    ...
}
```

---

## 빌드 결과

```
BUILD SUCCESSFUL in 6m 17s
83 actionable tasks: 45 executed, 38 up-to-date
```

| 태스크 | 결과 |
|--------|------|
| `:biometric-bridge:compileDebugJavaWithJavac` | 성공 |
| `:biometric-bridge:bundleDebugAar` | 성공 |
| `:biometric-bridge:assembleDebug` | 성공 |
| `:biometric-demo-app:assembleDebug` | 성공 |

> **참고:** Android SDK Platform 31이 기존에 설치되어 있지 않아 빌드 중 자동으로 다운로드 및 설치되었다.

---

## A2 프로젝트 이식 방법

A2 프로젝트에 `biometric-bridge` 모듈을 이식할 때는 아래 절차를 따른다.

### 1단계 — 모듈 디렉토리 복사

`biometric-bridge/` 디렉토리 전체를 A2 프로젝트 루트에 복사한다.

```
A2-project/
├── app/
├── biometric-bridge/    ← 여기에 복사
└── settings.gradle
```

### 2단계 — settings.gradle 등록

A2 프로젝트의 `settings.gradle`에 아래 라인을 추가한다.

```groovy
include ':biometric-bridge'
```

### 3단계 — app/build.gradle 의존성 추가

A2 프로젝트의 `app/build.gradle` dependencies 블록에 아래를 추가한다.

```groovy
dependencies {
    implementation project(':biometric-bridge')
}
```

### 4단계 — 빌드 확인

```bash
./gradlew assembleDebug
```

---

## 다음 단계 (이번 작업 범위 외)

- `biometric-lib` AAR 파일을 `biometric-bridge` 모듈에 연결
- `BiometricBridge.java` 플레이스홀더를 실제 브릿지 구현체로 교체
- A2 WebView JavascriptInterface 연동
