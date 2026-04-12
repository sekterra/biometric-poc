# biometric-auth-lib 소스코드 분석

> **실제 경로**: `biometric-auth-server/biometric-auth-lib`  
> ⚠️ 사용자 메모의 `D:\biometric-poc\biometric-auth-lib` 는 단돈 모듈 경로로 보이며, 본 저장소에서는 **멀티모듈 하위** `biometric-auth-server/biometric-auth-lib` 에 해당한다.

---

## 1. 패키지 및 클래스 구조

```text
com.biometric.poc.lib
├── auth
│   └── AuthConstants                    [final class, 유틸 상수]
├── challenge
│   └── ChallengeService                 [class]
├── ecdsa
│   ├── EcdsaVerifier                    [class]
│   └── VerificationResult               [enum]
├── model
│   ├── DeviceInfo                       [class, Lombok @Data @Builder]
│   ├── SessionData                      [class, Lombok @Data @Builder]
│   ├── JwtTokenPair                     [class, Lombok @Data]
│   ├── FailurePolicyConfig              [class, Lombok @Data @Builder]
│   └── DeviceStatus                     [enum]
├── policy
│   └── FailurePolicyService             [class]
└── store
    ├── DeviceStore                      [interface]
    ├── DeviceStoreImpl                  [class]
    ├── SessionStore                     [interface]
    ├── SessionStoreImpl                 [class]
    ├── NonceStore                       [interface]
    ├── NonceStoreImpl                   [class]
    ├── FailurePolicyStore               [interface]
    └── FailurePolicyStoreImpl           [class]
```

| 클래스 | 종류 | 역할 요약 |
|--------|------|-----------|
| `AuthConstants` | final class | 세션 TTL, 타임스탬프 허용 오차, nonce TTL, 기본 잠금 시간 등 **상수** |
| `ChallengeService` | class | 서버 챌린지·세션 ID 생성, `SessionStore`에 세션 저장·검증·소비 위임 |
| `EcdsaVerifier` | class | 챌린지 응답 **ECDSA(SHA256withECDSA)** 검증, nonce/세션 소비 |
| `VerificationResult` | enum | 검증 결과 코드 (SUCCESS, SESSION_EXPIRED, …) |
| `DeviceInfo` | class | 기기 등록 정보 DTO (deviceId, userId, 공개키, 상태, 시각) |
| `SessionData` | class | 챌린지 세션 DTO |
| `JwtTokenPair` | class | access/refresh 토큰 + 만료(초) **보관용 DTO** (본 모듈에서 JWT 발급 안 함) |
| `FailurePolicyConfig` | class | 실패 정책 설정값 DTO |
| `DeviceStatus` | enum | ACTIVE, LOCKED, KEY_INVALIDATED |
| `FailurePolicyService` | class | 정책 조회·계정 잠금·상태 조회 (Store 위임) |
| `DeviceStore` | interface | 기기 영속 추상화 |
| `DeviceStoreImpl` | class | `ConcurrentHashMap` 기반 **인메모리** 구현 |
| `SessionStore` | interface | 세션 영속 추상화 |
| `SessionStoreImpl` | class | 인메모리 세션 저장 |
| `NonceStore` | interface | 클라이언트 nonce 중복 방지 추상화 |
| `NonceStoreImpl` | class | 인메모리 nonce 맵 |
| `FailurePolicyStore` | interface | 실패 정책 조회 추상화 |
| `FailurePolicyStoreImpl` | class | **항상 동일 기본 정책** 반환 |

---

## 2. 외부 의존성

`biometric-auth-lib/build.gradle.kts` 기준:

| 의존성 | 용도 |
|--------|------|
| `org.projectlombok:lombok:1.18.32` (compileOnly + annotationProcessor) | 보일러플레이트 (`@Data`, `@Builder`, `@Slf4j`) |
| `org.slf4j:slf4j-api:2.0.13` | 로깅 API (`EcdsaVerifier`의 `@Slf4j`) |
| `io.jsonwebtoken:jjwt-api:0.12.6` (implementation) | ⚠️ **main 소스에서 미사용** (아래 💡) |
| `io.jsonwebtoken:jjwt-impl:0.12.6` (runtimeOnly) | ⚠️ 동상 |
| `io.jsonwebtoken:jjwt-jackson:0.12.6` (runtimeOnly) | ⚠️ 동상 |
| 테스트: JUnit 5, Mockito | 단위 테스트 전용 |

💡 **`src/main/java` 에 `jjwt` import/호출이 없다.** JWT 발급은 **`biometric-auth-app`** 의 `JwtIssuerService` 에서 수행한다. lib의 jjwt 의존성은 **제거 가능**하거나, 향후 lib으로 토큰 발급을 옮길 계획이 있다면 예비용으로 보인다.

JDK: **표준 `java.security`** (`SecureRandom`, `Signature`, `KeyFactory`, `X509EncodedKeySpec` 등) — 별도 BouncyCastle 의존성 없음.

---

## 3. 공개 API (public 메서드 / 상수)

### 3.1 인터페이스

#### `DeviceStore`

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| DeviceStore | `void save(DeviceInfo deviceInfo)` | 기기 저장/덮어쓰기 | `DeviceInfo` | void |
| DeviceStore | `Optional<DeviceInfo> findByDeviceId(String deviceId)` | deviceId 조회 | deviceId | Optional |
| DeviceStore | `boolean existsByDeviceId(String deviceId)` | 존재 여부 | deviceId | boolean |
| DeviceStore | `void updateStatus(String deviceId, DeviceStatus status)` | 상태 변경 | deviceId, status | void |
| DeviceStore | `void invalidateKey(String deviceId)` | 키 무효화 | deviceId | void |
| DeviceStore | `void updatePublicKey(String deviceId, String publicKeyBase64)` | 공개키만 갱신 | deviceId, 키 | void |
| DeviceStore | `void reRegister(DeviceInfo deviceInfo)` | 재등록 | `DeviceInfo` | void |
| DeviceStore | `void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt)` | 갱신 + ACTIVE | deviceId, 키, 시각 | void |
| DeviceStore | `void delete(String deviceId)` | 삭제 | deviceId | void |

출처: ```9:45:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/store/DeviceStore.java```

#### `SessionStore`

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| SessionStore | `void save(SessionData sessionData)` | 세션 저장 | `SessionData` | void |
| SessionStore | `Optional<SessionData> findBySessionId(String sessionId)` | 미만료·미사용 세션 조회 | sessionId | Optional |
| SessionStore | `void markUsed(String sessionId)` | 일회용 처리 | sessionId | void |

출처: ```7:17:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/store/SessionStore.java```

#### `NonceStore`

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| NonceStore | `boolean isUsed(String nonce)` | nonce 사용 여부 | nonce | boolean |
| NonceStore | `void markUsed(String nonce, String deviceId)` | 사용 처리 | nonce, deviceId | void |

출처: ```3:8:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/store/NonceStore.java```

#### `FailurePolicyStore`

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| FailurePolicyStore | `FailurePolicyConfig getPolicy(String deviceId)` | 정책 조회 | deviceId | `FailurePolicyConfig` |

출처: ```5:8:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/store/FailurePolicyStore.java```

### 3.2 구현체·서비스 (public 생성자 / 메서드)

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| ChallengeService | `ChallengeService(SessionStore sessionStore)` | 생성자 | SessionStore | — |
| ChallengeService | `String generateChallenge()` | 32바이트 랜덤 hex | — | String |
| ChallengeService | `String generateSessionId()` | UUID(하이픈 제거) | — | String |
| ChallengeService | `SessionData createSession(String deviceId, String userId, String clientNonce, long timestamp)` | 세션 생성·저장 | 식별자·nonce·시각 | SessionData |
| ChallengeService | `SessionData validateSession(String sessionId)` | 세션 조회 | sessionId | SessionData |
| ChallengeService | `void markSessionUsed(String sessionId)` | 사용 완료 표시 | sessionId | void |

`validateSession` 은 없으면 `IllegalStateException("SESSION_EXPIRED")` — ```64:68:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/challenge/ChallengeService.java```

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| EcdsaVerifier | `EcdsaVerifier(ChallengeService, NonceStore, DeviceStore)` | 생성자 | 3종 Store/서비스 | — |
| EcdsaVerifier | `VerificationResult verify(String sessionId, String deviceId, String userId, String ecSignatureBase64, String clientNonce, long timestamp)` | 전체 검증 파이프라인 | 다수 | enum |

출처: ```26:33:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/ecdsa/EcdsaVerifier.java```, ```41:47:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/ecdsa/EcdsaVerifier.java```

| 클래스명 | 메서드 시그니처 | 역할 | 입력 | 반환 |
|----------|-----------------|------|------|------|
| FailurePolicyService | `FailurePolicyService(FailurePolicyStore, DeviceStore)` | 생성자 | 2종 | — |
| FailurePolicyService | `FailurePolicyConfig getPolicy(String deviceId)` | 정책 | deviceId | config |
| FailurePolicyService | `void lockAccount(String deviceId)` | LOCKED 로 변경 | deviceId | void |
| FailurePolicyService | `boolean isLocked(String deviceId)` | 잠금 여부 | deviceId | boolean |
| FailurePolicyService | `boolean isKeyInvalidated(String deviceId)` | 키 무효 상태 여부 | deviceId | boolean |

출처: ```13:38:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/policy/FailurePolicyService.java```

`DeviceStoreImpl`, `SessionStoreImpl`, `NonceStoreImpl`, `FailurePolicyStoreImpl` 는 위 인터페이스 **public 메서드를 모두 구현**한다. (`DeviceStoreImpl` 등은 `public class` + `@Override` 메서드들.)

### 3.3 상수·열거형

- `AuthConstants`: `SESSION_TTL_SECONDS`, `TIMESTAMP_TOLERANCE_MS`, `NONCE_TTL_MINUTES`, `DEFAULT_LOCKOUT_SECONDS` (모두 `public static final`) — ```8:18:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/auth/AuthConstants.java```
- `DeviceStatus`: `ACTIVE`, `LOCKED`, `KEY_INVALIDATED`
- `VerificationResult`: `SUCCESS`, `SESSION_EXPIRED`, `TIMESTAMP_OUT_OF_RANGE`, `NONCE_REPLAY`, `MISSING_SIGNATURE`, `INVALID_SIGNATURE`

### 3.4 모델 (`@Data` 등)

`DeviceInfo`, `SessionData`, `FailurePolicyConfig`, `JwtTokenPair` 는 Lombok으로 **public getter/setter, equals/hashCode, toString** (및 Builder 일부) 가 생성된다. 외부 API로는 **필드 단위 접근**이 주된 계약이다.

---

## 4. 핵심 로직 상세 분석

### `ChallengeService`

1. `createSession`: `generateChallenge()` → `generateSessionId()` → `now`, `expireAt = now + SESSION_TTL_SECONDS` → `SessionData` 빌드 → `sessionStore.save`.
2. `validateSession`: `sessionStore.findBySessionId` → empty 시 `IllegalStateException("SESSION_EXPIRED")`.
3. `markSessionUsed`: `sessionStore.markUsed(sessionId)`.
- **알고리즘**: 챌린지 = `SecureRandom` 32바이트 → `HexFormat` hex.
- **의존**: `SessionStore`, `AuthConstants`.
- **예외**: `validateSession` 에서만 `IllegalStateException` (명시적).

출처: ```12:73:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/challenge/ChallengeService.java```

### `EcdsaVerifier`

1. `resolveSessionOrExpired`: `challengeService.validateSession` — 실패 시 null → `SESSION_EXPIRED`.
2. 타임스탬프: `|now - timestamp| <= TIMESTAMP_TOLERANCE_MS` 아니면 `TIMESTAMP_OUT_OF_RANGE`.
3. `nonceStore.isUsed(clientNonce)` → `NONCE_REPLAY`.
4. 서명 문자열 공백 → `MISSING_SIGNATURE`.
5. `deviceStore.findByDeviceId` 없거나 공개키 비어 있음 → `INVALID_SIGNATURE`.
6. `verifySignatureAndConsume`: Base64 디코드 → `KeyFactory.getInstance("EC")` + `X509EncodedKeySpec` → 페이로드 `challenge:nonce:deviceId:timestamp` UTF-8 → `Signature.getInstance("SHA256withECDSA")` 검증 → 성공 시 `nonceStore.markUsed`, `challengeService.markSessionUsed`, `SUCCESS`.
7. catch 전부 → `INVALID_SIGNATURE` 로 흡수 (로그만).
- **알고리즘**: **SHA256withECDSA**, 공개키 **X.509 DER Base64**.
- **의존**: `ChallengeService`, `NonceStore`, `DeviceStore`, `AuthConstants`.
- **예외**: 검증 경로에서 **checked 예외는 catch 후 enum 반환**; `validateSession`의 `IllegalStateException`은 내부에서 삼킴.

출처: ```41:132:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/ecdsa/EcdsaVerifier.java```

💡 `verify(..., String userId, ...)` 의 **userId 는 본 클래스 본문에서 사용되지 않음** — 세션/디바이스와의 일치 검증은 호출자 책임으로 보임.

### `FailurePolicyService`

1. `getPolicy`: `failurePolicyStore.getPolicy(deviceId)`.
2. `lockAccount`: `deviceStore.updateStatus(..., LOCKED)`.
3. `isLocked` / `isKeyInvalidated`: `findByDeviceId` + status 비교.
- **의존**: `FailurePolicyStore`, `DeviceStore`.
- **예외**: 명시적 throw 없음.

### `DeviceStoreImpl` / `SessionStoreImpl` / `NonceStoreImpl` / `FailurePolicyStoreImpl`

- **인메모리** `ConcurrentHashMap` 기반 PoC 구현.
- `SessionStoreImpl.findBySessionId`: null / used / 만료 시 empty.
- `NonceStoreImpl.markUsed` 후 오래된 항목 eviction.
- `FailurePolicyStoreImpl`: `deviceId` 무시하고 동일 `DEFAULT` 반환.

---

## 5. 설정 및 Bean 구성

| 항목 | 내용 |
|------|------|
| `@Configuration` / `@Bean` / `@Component` | **본 모듈 `src/main/java` 에 없음.** 조립은 **`biometric-auth-app`** 의 `AppConfig` 등에서 수행. |
| `@Value` / `@ConfigurationProperties` | **lib 내 없음.** 상수는 `AuthConstants` 하드코딩. |
| Auto-configuration | ⚠️ `src/main/resources` **디렉터리 자체 없음.** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` / `spring.factories` **없음** — **Spring Boot 자동구성 라이브러리 아님**. |

---

## 6. 데이터 모델

| 클래스 | 성격 | 필드 (타입) | DB 접근 |
|--------|------|-------------|---------|
| `DeviceInfo` | DTO/VO | deviceId String, userId String, publicKeyBase64 String, enrolledAt Instant, updatedAt Instant, status DeviceStatus | **없음** (Store 구현체가 담당) |
| `SessionData` | DTO | sessionId, deviceId, userId, serverChallengeHex, clientNonce, timestamp long, expireAt Instant, used boolean, createdAt Instant | 없음 |
| `JwtTokenPair` | DTO | accessToken, refreshToken, expiresIn int | 없음 |
| `FailurePolicyConfig` | DTO | maxRetryBeforeLockout int, lockoutSeconds int, accountLockThreshold int, fallbackPasswordEnabled boolean | 없음 |
| `DeviceStatus` | enum | 3상수 | 없음 |
| `VerificationResult` | enum | 6상수 | 없음 |

- **JPA Entity / Repository**: **없음.**
- Oracle/MyBatis 매핑은 **`biometric-auth-app`** 과 `schema.sql` 측 책임.

---

## 이식 가능성 평가

### 1. B2에 **그대로 의존성(jar)으로 추가** 가능한가?

**가능에 가깝다** — 단, 다음을 전제로 한다.

- **순수 Java 라이브러리**이며 Spring에 **직접 의존하지 않음** (Spring Boot 3.5.4 와 **버전 충돌 이슈 거의 없음**).
- **DB에 직접 접근하지 않음** — Oracle 19C 호환은 **B2 쪽 Store 구현·DDL** 문제이지 lib 자체 문제는 아님.
- **패키지** `com.biometric.poc.lib` — B2 네이밍 정책과 맞지 않으면 **리패키징** 또는 소스 복사 후 패키지 변경이 필요할 수 있음 ⚠️.

**불가/주의로 볼 수 있는 경우**

- 조직 정책상 **Lombok·jjwt(미사용) 의존성** 제거를 요구할 때 → lib `build.gradle` 정리 후 재배포.
- **자동 설정 기대** 시 → 이 jar는 auto-config **없음**; B2에서 **수동 `@Bean` 등록** 필수.

### 2. 소스 **복사 이식** 시 수정이 필요한 클래스·이유

| 대상 | 이유 |
|------|------|
| `AuthConstants` | TTL·허용오차를 **application 설정**으로 빼려면 상수 → 주입 가능 구조로 변경 |
| `FailurePolicyStoreImpl` | PoC 기본값 고정 → B2에서 **DB/설정 기반** 구현으로 교체 |
| `*StoreImpl` (인메모리) | 운영에서는 **미사용**; B2는 Oracle/MyBatis·Redis 구현으로 **대체** (인터페이스 유지) |
| 패키지명 전체 | `com.biometric.poc` → B2 도메인 패키지로 변경 시 |
| `build.gradle.kts` | jjwt 제거 검토, Java 버전(B2 JDK) 정렬 |
| (선택) `EcdsaVerifier` | `userId` 검증 추가, 디바이스 없을 때 `INVALID_SIGNATURE` 대신 별도 결과 💡 |

### 3. 이식 난이도

**중 (Medium)**

- **근거**: 도메인 로직이 **얇고 명확**하고 Spring에 묶이지 않음. 다만 **Store 구현·DDL·보안(인가)·설정 외부화**는 전부 B2에서 새로/이전 앱에서 이어받아야 하며, 인메모리 구현을 **Oracle 기반으로 바꾸는 작업**이 병행된다. 알고리즘(ECDSA·챌린지) 자체는 **JDK 표준만** 사용해 **하 난이도**이나, **운영 요건 반영 시 중**으로 본다.

---

**산출물 경로**: `biometric-auth-server/docs/biometric-auth-lib-analysis.md`
