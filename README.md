# biometric-poc

## 프로젝트 개요

생체 인증(안면·지문 등)과 ECDSA 기반 디바이스 등록·챌린지·응답을 통해 **MIS JWT 연동 PoC**를 구성한 저장소입니다. **Spring Boot** 백엔드(`biometric-auth-server`)와 **Android** 클라이언트(`biometric-android`)로 나뉘며, 서버는 디바이스·세션·nonce 저장소를 **인메모리**로 두어 빠른 검증에 맞춰 있습니다.

## 기술 스택

| 영역 | 항목 |
|------|------|
| 서버 | Java 21, Gradle 8.10.2, Spring Boot 3.3.5, Spring Security, Tomcat(Embedded), JJWT 0.12.6, Lombok |
| 서버 라이브러리 | JUnit 5, Mockito |
| Android | Gradle 8.4, Android Gradle Plugin 8.2.2, `compileSdk` / `targetSdk` 34, `minSdk` 28, Java 11 |
| Android 라이브러리 | AndroidX Biometric, Security Crypto, OkHttp 4.12, Gson 2.10 |

## 실행 방법

### 사전 준비

- JDK 21 (서버), Android SDK 및 `local.properties` (Android 모듈)

### 서버

```text
cd D:\biometric-poc\biometric-auth-server
.\gradlew :biometric-auth-app:bootRun
```

- 기본 포트는 **8080**입니다.
- **8080(또는 지정한 포트)이 이미 사용 중이면** 기동에 실패할 수 있습니다. 해당 프로세스를 종료하거나 다음처럼 포트를 바꿉니다.

```text
.\gradlew :biometric-auth-app:bootRun --args="--server.port=8081"
```

### Windows 환경 주의사항

Windows 사용자 계정명에 한글이 포함된 경우, Gradle 테스트 실행 시 아래 오류가 발생할 수 있습니다.

```
Error: Could not find or load main class GradleWorkerMain
```

**원인**: Gradle 사용자 홈(`%USERPROFILE%\.gradle`) 경로에 한글이 포함되면 Worker JVM이 클래스패스를 인식하지 못합니다.

**해결 방법**: 테스트 실행 전 아래 명령을 먼저 실행합니다.

```powershell
# PowerShell
$env:GRADLE_USER_HOME = "C:\gradle-home"
```

```cmd
:: CMD
set GRADLE_USER_HOME=C:\gradle-home
```

이후 `.\gradlew :biometric-auth-app:test` 가 정상적으로 실행됩니다.

### Android 데모 앱

1. **서버를 먼저 기동**한 뒤 앱을 실행합니다.
2. **에뮬레이터**: 호스트 PC의 `localhost`는 `10.0.2.2`로 매핑되므로 기본값 `baseUrl = http://10.0.2.2:8080` 을 사용합니다. (`biometric-demo-app`의 `BuildConfig.SERVER_URL`)
3. **실제 기기**: PC와 동일 네트워크에서 PC의 IP를 사용합니다.  
   `biometric-demo-app/build.gradle`의 `buildConfigField "String", "SERVER_URL", ...` 을 `http://{서버IP}:8080` 형태로 수정한 뒤 다시 빌드합니다.

### 빌드 산출물 경로 (참고)

- Release AAR: `biometric-android\biometric-lib\build\outputs\aar\biometric-lib-release.aar`
- Debug APK: `biometric-android\biometric-demo-app\build\outputs\apk\debug\biometric-demo-app-debug.apk`

## 실서비스 전환 가이드

- **biometric-auth-lib** JAR를 기존 MIS JWT 서버의 `build.gradle`에 의존성으로 추가합니다.
- **DeviceController**, **AuthController**, **PolicyController**, **AppConfig**를 기존 서버 소스에 맞게 추가·통합합니다.
- **저장소 구현체 교체** (인터페이스 `DeviceStore` 등은 유지, 구현체만 교체):
  - `DeviceStoreImpl` → `MyBatisDeviceStoreImpl`
  - `SessionStoreImpl` → `MyBatisSessionStoreImpl`
  - `NonceStoreImpl` → `MyBatisNonceStoreImpl` (또는 Redis)
- PoC용 실행 모듈 **biometric-auth-app** 은 실서비스에서는 **폐기**하고, 기존 배포 애플리케이션에 흡수합니다.

## PoC 제약사항

- 서버 재시작 시 **인메모리 데이터가 초기화**되므로 **등록부터 다시** 진행해야 합니다.
- **ECDSA 서명**은 **실제 Android Keystore** 환경에서 검증하는 것을 전제로 합니다(에뮬레이터에는 제한이 있을 수 있음).
- **NonceStore**의 5분 TTL은 인메모리 기준이며, **서버 재시작 시 nonce 상태도 초기화**됩니다.
- **시연 순서**: 서버 기동 → 앱 설치 → 등록 → 로그인  
  (서버 재시작 시 인메모리 초기화되므로 등록부터 재실행 필요)
- **PUT /api/device/update-key**는 새 얼굴 등록으로 Keystore 키가 무효화된 경우에만 앱이 자동 호출합니다(수동 호출 불필요).

## 갤럭시 탭 실기기 주의사항

- 안면 인식은 **BIOMETRIC_WEAK(Class 2)** 등급으로 다루어질 수 있습니다.
- `setUserAuthenticationValidityDurationSeconds(10)` 설정을 사용합니다.  
  (**-1**로 두면 매 서명마다 **BIOMETRIC_STRONG** 인증이 필요해지는 값이며, 갤럭시 탭에서 키 생성 실패를 유발할 수 있음)
- **KeyPermanentlyInvalidatedException**: 새 얼굴 등록 후 발생할 수 있으며, 이 경우 **재등록**이 필요합니다.

## E2E 시나리오 확인 목록

- [ ] 등록 → 로그인 → 메인 화면 정상 흐름
- [ ] `ERROR_CANCELED`: 실패 카운트 미증가 확인
- [ ] 3회 실패: 30초 로컬 잠금 + 카운트다운 UI
- [ ] 5회 실패: `account_lock` + ID/PW 폴백 버튼 표시
- [ ] 새 얼굴 등록 후 앱 재실행: 재등록 안내 화면

## 전체 폴더 구조

```text
D:\biometric-poc\
+--- biometric-auth-server\
|   +--- biometric-auth-lib\     # 인증 핵심 로직 (순수 Java, Spring 의존 없음)
|   |   +--- src\main\java\com\biometric\poc\lib\
|   |       +--- model\          # DeviceInfo, SessionData, FailurePolicyConfig 등
|   |       +--- store\          # DeviceStore(interface) + Impl, SessionStore, NonceStore
|   |       +--- challenge\      # ChallengeService
|   |       +--- ecdsa\          # EcdsaVerifier, VerificationResult
|   |       +--- policy\         # FailurePolicyService
|   +--- biometric-auth-app\     # PoC 전용 실행 모듈 (실서비스 미사용)
|       +--- src\main\java\com\biometric\poc\
|       |   +--- config\         # AppConfig, SecurityConfig
|       |   +--- controller\     # DeviceController, AuthController, PolicyController
|       |   +--- service\        # JwtIssuerService
|       +--- src\main\resources\
|           +--- application.yml
+--- biometric-android\
|   +--- biometric-lib\          # Android 생체인증 라이브러리 (AAR)
|   |   +--- src\main\java\com\biometric\poc\lib\
|   |       +--- auth\           # BiometricRegistrar, BiometricAuthManager
|   |       +--- crypto\         # EcKeyManager, KeyInvalidationHandler
|   |       +--- network\        # AuthApiClient
|   |       +--- policy\         # FailurePolicyManager
|   |       +--- storage\        # TokenStorage
|   +--- biometric-demo-app\     # PoC 데모 앱
|       +--- src\main\java\com\biometric\poc\demo\
|           +--- RegisterActivity.java
|           +--- LoginActivity.java
|           +--- MainAfterLoginActivity.java
+--- docs\
|   +--- architecture.md
|   +--- api-spec.md
+--- README.md
```
