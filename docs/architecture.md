# AOS 기반 MIS 생체인증 PoC 아키텍처 (ECDSA 방식)

## 핵심 설계 원칙
- HMAC(대칭키) 대신 ECDSA(비대칭키) 사용
- 개인키는 Android Keystore 외부로 절대 유출되지 않음
- 서버는 등록 시 수신한 공개키로만 서명을 검증
- 생체인증 성공 시에만 Signature 객체 잠금 해제
- 저장소는 interface + 구현체 분리
  → PoC: ConcurrentHashMap 구현체
  → 실서비스: MyBatis 구현체로 교체 (인터페이스 계약 유지)

## 시스템 구성
[MIS 데모 앱 (Android, Java 11)]
  └── biometric-lib (AAR)
        ├── EcKeyManager          — EC 키쌍 생성/서명 (AndroidKeystore, secp256r1)
        ├── BiometricRegistrar    — 최초 등록
        ├── BiometricAuthManager  — 인증 오케스트레이션
        ├── AuthApiClient         — OkHttp REST 클라이언트
        ├── FailurePolicyManager  — 로컬 실패 카운트 + 서버 정책 적용
        └── TokenStorage          — EncryptedSharedPreferences
  └── biometric-demo-app
        ├── RegisterActivity      — 등록 UI (BiometricRegistrar 호출)
        ├── LoginActivity         — 로그인 UI (BiometricAuthManager 호출)
        └── MainAfterLoginActivity

[biometric-auth-server (Spring Boot 3.x, Java 21)]
  └── biometric-auth-lib (JAR)  ← 핵심 인증 로직, Spring 의존 없음
        ├── EcdsaVerifier
        ├── ChallengeService
        ├── FailurePolicyService
        ├── DeviceStore (interface) + DeviceStoreImpl (ConcurrentHashMap)
        │     # 실서비스: MyBatisDeviceStoreImpl로 교체
        ├── SessionStore (interface) + SessionStoreImpl (ConcurrentHashMap)
        │     # 실서비스: MyBatisSessionStoreImpl로 교체
        └── NonceStore (interface) + NonceStoreImpl (ConcurrentHashMap)
              # 실서비스: MyBatisNonceStoreImpl 또는 Redis로 교체
  └── biometric-auth-app  ← PoC 전용 (실서비스 미사용, 폐기)
        ├── DeviceController
        ├── AuthController
        └── PolicyController

## 실서비스 전환 시 작업 범위
  biometric-auth-lib JAR → 기존 MIS JWT 서버 build.gradle에 의존성 추가
  컨트롤러 3종 + AppConfig → 기존 MIS JWT 서버에 직접 추가
  저장소 구현체 → ConcurrentHashMap → MyBatis 구현체로 교체
  biometric-auth-app → PoC 전용이므로 폐기

## 등록 흐름
  앱: canAuthenticate(BIOMETRIC_WEAK) 확인
  앱: EC 키쌍 생성 (secp256r1, AndroidKeystore)
      setUserAuthenticationRequired(true)
      setUserAuthenticationValidityDurationSeconds(10)  ← 갤럭시 탭 호환
      setInvalidatedByBiometricEnrollment(true)
  앱: 공개키 추출 → Base64 인코딩
  앱 → 서버: POST /api/device/register { device_id, user_id, public_key, enrolled_at }
  서버: DeviceStore에 공개키 포함 저장 (status=ACTIVE)
  앱: EncryptedSharedPreferences 등록 완료 플래그 저장

## 인증 흐름
  앱 → 서버: POST /api/auth/challenge { device_id, user_id, client_nonce, timestamp }
  서버 → 앱: { session_id, server_challenge(32B hex), expire_at }
  앱: payload = server_challenge + ":" + client_nonce + ":" + device_id + ":" + timestamp
  앱: Signature sig = Signature.getInstance("SHA256withECDSA")
      sig.initSign(privateKey)
      CryptoObject cryptoObj = new CryptoObject(sig)
  앱: BiometricPrompt 실행 → 안면인식 성공 → Signature 잠금 해제
  앱: sig.update(payload) → ecSignature = sig.sign()
  앱 → 서버: POST /api/auth/token
      { session_id, device_id, user_id, ec_signature(Base64), client_nonce, timestamp }
  서버 4단계 검증:
    ① session_id 유효·만료 확인
    ② timestamp ±30초
    ③ client_nonce 중복 확인
    ④ ECDSA 검증: verifier.initVerify(publicKey) → verifier.update(payload) → verifier.verify(ecSignature)
  서버: JWT 발급 → 앱: 토큰 저장 → 메인 화면

## 키 무효화 처리
  KeyPermanentlyInvalidatedException 발생 시:
    앱: keyStore.deleteEntry(KEY_ALIAS)
    앱: EncryptedSharedPreferences 등록 플래그 초기화
    앱 → 서버: PUT /api/device/update-key { device_id, status: "KEY_INVALIDATED" }
    앱: 재등록 안내 화면

## 실패 처리
  onAuthenticationError(ERROR_CANCELED) → 실패 카운트 증가 없이 종료
  onAuthenticationFailed                → 실패 카운트 +1
  1~2회: 재시도 안내
  3회~:  GET /api/policy/failure-config → 로컬 30초 잠금
  5회~:  POST /api/auth/account-lock → ID/PW 폴백
