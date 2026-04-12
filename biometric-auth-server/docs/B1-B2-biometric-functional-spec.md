# [B1 → B2] biometric-auth-lib 기능 분석 및 이식 준비 명세서

> **분석 대상 경로**: 워크스페이스 기준 `biometric-auth-server/` (사용자 환경 `D:\biometric-poc\biometric-auth-server` 와 동일 구조로 가정)  
> **분석 일자 기준 소스**: `biometric-auth-app`, `biometric-auth-lib` 의 `src/main/java` 및 `application.yml`, `schema.sql`, MyBatis XML

---

## Step 1. 프로젝트 구조 파악

### 1.1 멀티 모듈

```text
biometric-auth-server/
├── build.gradle.kts                 # 루트 — Java 21 서브프로젝트 공통
├── settings.gradle.kts
├── biometric-auth-lib/              # 순수 라이브러리 (Spring 미의존, slf4j + jjwt-api)
└── biometric-auth-app/              # Spring Boot 앱 — REST, MyBatis, Security, JWT 발급
```

### 1.2 패키지 계층 (요약 트리)

```text
com.biometric.poc                          [app] 진입점
├── BiometricAuthApplication.java          Spring Boot main, @MapperScan
├── SecurityConfig.java                    SecurityFilterChain (전부 permitAll)

com.biometric.poc.controller               [app] REST
├── AuthController.java                    /api/auth — challenge, token, account-lock
├── DeviceController.java                  /api/device — register, key, unlock, unregister
├── PolicyController.java                  /api/policy — failure-config
└── TestFlowController.java                /test/full-flow (@Profile test-flow)

com.biometric.poc.config
├── AppConfig.java                         ChallengeService, EcdsaVerifier, FailurePolicyService @Bean
├── GlobalExceptionHandler.java            @RestControllerAdvice — 400 통일
└── SchedulingConfig.java                  @EnableScheduling

com.biometric.poc.service
└── JwtIssuerService.java                  HS256 access/refresh JWT 발급

com.biometric.poc.store                  [app] MyBatis Store 구현
├── MyBatisDeviceStoreImpl.java            DeviceStore
├── MyBatisSessionStoreImpl.java           SessionStore
└── MyBatisNonceStoreImpl.java             NonceStore

com.biometric.poc.mapper                   [app] MyBatis Mapper 인터페이스
├── DeviceMapper.java
├── SessionMapper.java
├── NonceMapper.java
└── handler/                               Instant, Boolean Y/N, DeviceStatus 타입 핸들러

com.biometric.poc.schedule
└── SessionCleanupScheduler.java           만료 SESSION/NONCE 삭제 (@Profile !test)

com.biometric.poc.util
└── ApiErrorBodies.java                    { "error": "CODE" } 헬퍼

com.biometric.poc.lib.*                    [lib] 도메인 로직 (패키지 com.biometric.poc.lib)
├── auth/AuthConstants.java                세션 TTL, 타임스탬프 허용, nonce TTL
├── challenge/ChallengeService.java        챌린지·세션 생성
├── ecdsa/EcdsaVerifier.java, VerificationResult.java
├── model/                                 DeviceInfo, SessionData, JwtTokenPair, DeviceStatus, FailurePolicyConfig
├── policy/FailurePolicyService.java
└── store/                                 인터페이스 + 인메모리 구현체(DeviceStoreImpl 등, 앱에서는 MyBatis로 대체)
```

### 1.3 클래스 역할 한 줄 요약 (주요)

| 클래스 | 역할 |
|--------|------|
| `BiometricAuthApplication` | 부트스트랩, MyBatis 매퍼 스캔 |
| `SecurityConfig` | CSRF 비활성, **모든 요청 permitAll**, H2 콘솔만 명시 |
| `AuthController` | 챌린지 발급, 토큰 발급, 계정 잠금 API |
| `DeviceController` | 기기 등록·키 무효·갱신·조회·잠금해제·삭제 |
| `PolicyController` | 실패 정책 설정 조회 |
| `TestFlowController` | PoC 전체 플로우 자동 테스트 (프로파일 `test-flow`) |
| `JwtIssuerService` | JJWT로 access/refresh 토큰 생성 (HS256) |
| `ChallengeService` | 32바이트 랜덤 hex 챌린지, 세션 ID, DB 저장 |
| `EcdsaVerifier` | 세션·시간·nonce·ECDSA 서명 검증 파이프라인 |
| `FailurePolicyService` | 정책 조회, 계정 LOCKED 반영 |
| `MyBatis*StoreImpl` | 세션·디바이스·nonce 영속화 |
| `SessionCleanupScheduler` | 만료 레코드 주기 삭제 |
| `GlobalExceptionHandler` | Validation·JSON 파싱 오류 → 400 |

### 1.4 의존성 목록

**루트** (`biometric-auth-server/build.gradle.kts`): Java **21** (서브프로젝트 `sourceCompatibility`).

**biometric-auth-app** (`biometric-auth-app/build.gradle.kts`):

| 구분 | 내용 |
|------|------|
| Spring Boot | **3.3.5** (`org.springframework.boot` 플러그인) |
| Spring | `spring-boot-starter-web`, `validation`, **security** |
| Persistence | **MyBatis** `mybatis-spring-boot-starter:3.0.3` |
| DB (런타임) | **H2** `runtimeOnly` |
| JWT | **jjwt** 0.12.6 (api + impl + jackson) |
| 기타 | Lombok compileOnly |

**biometric-auth-lib** (`biometric-auth-lib/build.gradle.kts`): `java-library`, slf4j, jjwt (테스트: JUnit5, Mockito). **JPA 없음**.

⚠️ 사용자 표의 B2는 Spring Boot **3.5.4** — B1은 **3.3.5** 이미 Spring Security **6.x** (`authorizeHttpRequests`). 🔷 B2 이식 시 **마이그레이션 부담은 작고**, 버전 정합·의존성 갱신 위주.

**없음 (명시)**: Firebase, HSM SDK, JPA/Hibernate (런타임), 별도 crypto 라이브러리 — ECDSA는 **JDK `java.security`**.

---

## Step 2. API Endpoint 명세

> **인증**: `SecurityConfig` 에서 **`anyRequest().permitAll()`** — 실질적으로 **모든 REST가 무인증**.  
> `@PreAuthorize` **미사용**.  
> 🔶 B2에서는 반드시 **API 보호 정책** 재설계 필요.

| No | Method | URI | 설명 | 인증 | 주요 입력 | 주요 응답 |
|----|--------|-----|------|------|-----------|-----------|
| 1 | POST | `/api/device/register` | 기기·공개키 등록 | permitAll | JSON: `device_id`, `user_id`, `public_key`, `enrolled_at` | 200 `REGISTERED` / `RE_REGISTERED`, 400 `INVALID_ENROLLED_AT`, 409 `ALREADY_REGISTERED` |
| 2 | PUT | `/api/device/update-key` | 공개키 무효화(KEY_INVALIDATED) 트리거 | permitAll | `device_id`, `status`(요청에 있으나 서비스에서 미사용) | 200 `OK`, 404 `DEVICE_NOT_FOUND` |
| 3 | PUT | `/api/device/renew-key` | 새 공개키로 갱신 + ACTIVE | permitAll | `device_id`, `new_public_key` | 200 `RENEWED`, 404 `DEVICE_NOT_FOUND`, 423 `ACCOUNT_LOCKED` |
| 4 | GET | `/api/device/user-id` | device_id로 user_id·status 조회 | permitAll | Query `device_id` | 200 `{user_id, status}`, 404 `DEVICE_NOT_FOUND` |
| 5 | PUT | `/api/device/unlock` | LOCKED → ACTIVE | permitAll | `device_id` | 200 `ACTIVE`, 404, 400 `NOT_LOCKED` |
| 6 | DELETE | `/api/device/unregister` | 기기 등록 완전 삭제 | permitAll | `device_id`, `user_id` | 200 `UNREGISTERED`, 404, 403 `USER_MISMATCH` |
| 7 | POST | `/api/auth/challenge` | 챌린지·세션 발급 | permitAll | `device_id`, `user_id`, `client_nonce`, `timestamp` | 200 `session_id`, `server_challenge`, `expire_at` / 404 `DEVICE_NOT_FOUND`, 423 `ACCOUNT_LOCKED`, 409 `KEY_INVALIDATED` |
| 8 | POST | `/api/auth/token` | 서명 검증 후 JWT 발급 | permitAll | `session_id`, `device_id`, `user_id`, `ec_signature`, `client_nonce`, `timestamp` | 200 `access_token`, `refresh_token`, `expires_in` / 401 `VerificationResult.name()` |
| 9 | POST | `/api/auth/account-lock` | 계정 잠금 | permitAll | `device_id`, `user_id`(userId 미사용) | 200 `LOCKED` |
| 10 | GET | `/api/policy/failure-config` | 실패 정책 조회 | permitAll | Query `device_id` | 200 정책 필드 맵 |
| 11 | POST | `/test/full-flow` | PoC 자동 플로우 | permitAll (프로파일 `test-flow` 전용) | `device_id`, `user_id` | 통합 응답 또는 오류 |

**참고 코드**

- 보안: ```14:25:biometric-auth-server/biometric-auth-app/src/main/java/com/biometric/poc/SecurityConfig.java
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/h2-console/**")
                                        .permitAll()
                                        .anyRequest()
                                        .permitAll());
```

---

## Step 3. 핵심 도메인 모델

> B1은 **JPA Entity 없음**. 테이블 정의는 `schema.sql`, 매핑은 **MyBatis + POJO**.

### 3.1 `DeviceInfo` (`com.biometric.poc.lib.model.DeviceInfo`)

| 항목 | 내용 |
|------|------|
| 테이블 | `BIOMETRIC_DEVICE` (`schema.sql`) |
| 컬럼 매핑 | `DEVICE_ID`(PK), `USER_ID`, `PUBLIC_KEY_B64`, `STATUS`, `ENROLLED_AT`, `UPDATED_AT` |
| 연관관계 | 논리적 FK — `BIOMETRIC_SESSION.DEVICE_ID` → `BIOMETRIC_DEVICE.DEVICE_ID` |
| 제약 | PK `DEVICE_ID`; 인덱스 `IDX_BIOMETRIC_DEVICE_USER(USER_ID)` |
| 🔶 Oracle | 이미 `VARCHAR2`, `TIMESTAMP` — **이식 용이**. AUTO_INCREMENT 없음. |
| 🔶 | `PUBLIC_KEY_B64 VARCHAR2(512)` — 긴 키 인코딩 시 부족 가능 💡 |

### 3.2 `SessionData` → `BIOMETRIC_SESSION`

| 컬럼 | 타입(스키마) | 비고 |
|------|--------------|------|
| SESSION_ID | VARCHAR2(64) PK | UUID hex |
| DEVICE_ID | VARCHAR2(100) FK | |
| USER_ID | VARCHAR2(100) | |
| CHALLENGE_HEX | VARCHAR2(64) | 32바이트 hex |
| CLIENT_NONCE | VARCHAR2(64) | |
| REQ_TIMESTAMP | NUMBER(13) | ms |
| EXPIRE_AT | TIMESTAMP | |
| USED_YN | CHAR(1) | Y/N — `BooleanYnTypeHandler` |
| CREATED_AT | TIMESTAMP | |

🔶 `USED_YN` — 이미 **CHAR(1)**. Boolean 매핑 핸들러 유지 또는 `NUMBER(1)`로 통일 검토.

### 3.3 Nonce (테이블만 존재, 별도 POJO 없음) — `BIOMETRIC_NONCE`

| 컬럼 | 설명 |
|------|------|
| NONCE (PK) | 클라이언트 nonce |
| DEVICE_ID | 감사용 |
| USED_AT, EXPIRE_AT | TIMESTAMP |

### 3.4 `FailurePolicyConfig` / `JwtTokenPair`

- **DB 테이블 없음** — 정책은 `FailurePolicyStoreImpl` 인메모리 기본값.
- `JwtTokenPair` — 응답 DTO.

### 3.5 `DeviceStatus` enum

- 값: `ACTIVE`, `LOCKED`, `KEY_INVALIDATED` — DB `STATUS` VARCHAR2와 문자열 매핑 (`DeviceStatusTypeHandler`).

### 3.6 Oracle 19C 체크리스트 요약

| 포인트 | B1 현황 | B2 이식 |
|--------|---------|---------|
| ID 전략 | 문자열 PK / UUID — **IDENTITY 불필요** | 동일 유지 |
| CLOB/BLOB | 공키는 VARCHAR2(512) | 필요 시 **CLOB** 검토 🔶 |
| Boolean | CHAR(1) Y/N | 유지 또는 NUMBER(1) |
| 예약어 | `USER_ID`, `LEVEL` 등 스키마에 `LEVEL` 없음 | 신규 컬럼 시 Oracle 예약어 조회 |
| H2 → Oracle | `MODE=Oracle` 로 스키마 유사 | `schema.sql` 거의 그대로 적용 가능 |

---

## Step 4. 생체인증 핵심 플로우

### 4.1 디바이스 등록 (Device Registration)

**텍스트 시퀀스**

```text
Client                    DeviceController              MyBatisDeviceStoreImpl           DB
  | POST /register               |                              |                      |
  | device_id,user_id,...  -----> | findByDeviceId               | selectByDeviceId     |
  |                        |      | (분기: ACTIVE/LOCKED/KEY_*)   | insert / reRegister  |
  |                        -----> | save / reRegister            | ------------------> |
  | <----- 200 REGISTERED  |      |                              |                      |
```

- **device_id 생성**: **서버가 생성하지 않음**. 클라이언트(앱)가 전달 — 스키마 주석상 Android ID 등.
- **저장**: `DeviceMapper.insert` / `reRegister` (`DeviceMapper.xml` 18–26, 63–73행).
- **공개키**: Base64 X.509 EC 공개키.

**참고**: ```46:95:biometric-auth-server/biometric-auth-app/src/main/java/com/biometric/poc/controller/DeviceController.java
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
```

---

### 4.2 인증 챌린지 발급 (Challenge Issue)

**알고리즘**

- 서버 챌린지: `SecureRandom` **32바이트** → **hex 64자** (`ChallengeService.generateChallenge`).
- 세션 ID: `UUID` 하이픈 제거.
- TTL: **`AuthConstants.SESSION_TTL_SECONDS` = 60초** (`AuthConstants.java` 8–9행).
- 저장: `SessionStore.save` → `BIOMETRIC_SESSION` insert.

**참고**: ```23:61:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/challenge/ChallengeService.java
    public String generateChallenge() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
```

**시퀀스 (요약)**

```text
Client -> AuthController.challenge -> deviceStore.findByDeviceId
  -> (상태 검사) -> challengeService.createSession -> sessionStore.save -> 응답
```

---

### 4.3 서명 검증 (Signature Verification)

- **알고리즘**: **SHA256withECDSA**, 곡선은 공개키 인코딩에 따름(클라이언트는 **secp256r1** — `TestFlowController` 참고).
- **페이로드 문자열** (`EcdsaVerifier.buildPayload`):

  `serverChallengeHex + ":" + clientNonce + ":" + deviceId + ":" + timestamp`

- **위치**: `com.biometric.poc.lib.ecdsa.EcdsaVerifier#verify` 및 `verifySignatureAndConsume`.
- **실패 시**: `VerificationResult` enum 반환 → `AuthController`에서 **401** + body `error: <enum.name()>`.

**검증 순서** (요약): 세션 존재·미만료·미사용 → 타임스탬프 ±30s → nonce 미사용 → 서명 검증 → 성공 시 nonce 기록 + 세션 used.

**참고**: ```41:132:biometric-auth-server/biometric-auth-lib/src/main/java/com/biometric/poc/lib/ecdsa/EcdsaVerifier.java
    public VerificationResult verify(
            String sessionId,
            String deviceId,
            ...
```

💡 **디바이스 미존재** 시 `findByDeviceId` empty → **`INVALID_SIGNATURE`** 반환 (404가 아님). 클라이언트는 `DEVICE_NOT_FOUND`와 구분 어려울 수 있음.

---

### 4.4 키 갱신 (Key Renewal / `renewKey`)

**트리거**

- API: `PUT /api/device/renew-key` — 클라이언트가 새 공개키 제출.
- 선행 조건: `DeviceController`에서 `KEY_INVALIDATED` 후 재등록 플로우 또는 정책적 갱신.

**device_id 조회 (상세)**

| 항목 | 내용 |
|------|------|
| **Repository/Mapper 메서드** | `DeviceStore.findByDeviceId` → `DeviceMapper.selectByDeviceId` |
| **실행 SQL** | `DeviceMapper.xml` `selectByDeviceId` (28–32행): `SELECT ... FROM BIOMETRIC_DEVICE WHERE DEVICE_ID = #{deviceId}` |
| **파라미터 타입** | Java `String` — JSON `device_id`에서 역직렬화; **길이 제한은 빈 값 검증(@NotBlank)만**, DB는 VARCHAR2(100) |
| **DB 컬럼** | `DEVICE_ID VARCHAR2(100) NOT NULL` PK |
| **0건 시** | `deviceOpt.isEmpty()` → **HTTP 404** + `ApiErrorBodies.error("DEVICE_NOT_FOUND")` |
| **코드 경로** | ```108:114:biometric-auth-server/biometric-auth-app/src/main/java/com/biometric/poc/controller/DeviceController.java
    @PutMapping("/renew-key")
    public ResponseEntity<Map<String, String>> renewKey(@Valid @RequestBody RenewKeyRequest request) {
        Optional<DeviceInfo> deviceOpt = deviceStore.findByDeviceId(request.deviceId());
        if (deviceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }
```

**갱신 처리**

- `deviceStore.renewKey` → `DeviceMapper.renewKey` XML 75–82행: `PUBLIC_KEY_B64` 갱신, `STATUS='ACTIVE'`, `UPDATED_AT` 갱신.
- **`update-key`**: 키 **무효화** — `updateKeyInvalidated` 로 `STATUS=KEY_INVALIDATED`, `PUBLIC_KEY_B64=NULL` (`DeviceMapper.xml` 46–53행).

---

### 4.5 토큰 발급 (JWT Issue)

- **위치**: `AuthController.token` 성공 시 `JwtIssuerService.issueTokenPair(userId)`.
- **알고리즘**: **HS256** 대칭키 (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`).
- **Access token claims**: **`sub`** (= userId), **`iat`**, **`exp`** (발급 + `jwt.access-token-expiry-minutes`).
- **Refresh token**: **`sub`**, **`exp`** (`jwt.refresh-token-expiry-days`) — issuedAt 명시 없음.
- **응답**: `access_token`, `refresh_token`, `expires_in`(초, access 기준).

**참고**: ```33:57:biometric-auth-server/biometric-auth-app/src/main/java/com/biometric/poc/service/JwtIssuerService.java
    public JwtTokenPair issueTokenPair(String userId) {
        ...
        String accessToken =
                Jwts.builder()
                        .subject(userId)
                        .issuedAt(issuedAt)
                        .expiration(accessExp)
                        .signWith(key)
                        .compact();
```

⚠️ **검증 필터 없음** — 발급만 하고 리소스 서버 검증은 B2에서 별도 구현.

---

## Step 5. 보안 설정 분석

| 항목 | B1 현황 |
|------|---------|
| FilterChain | 단일 `SecurityFilterChain` Bean |
| CSRF | 비활성 |
| 인증 | httpBasic·formLogin 비활성 |
| 인가 | **`permitAll()` 전역** (H2 콘솔만 명시적 matcher) |
| JWT 필터 | **없음** — 요청 헤더 JWT 검증 로직 없음 |
| CORS | **미설정** (기본만 동작) |
| 예외 핸들러 | `GlobalExceptionHandler` — **인증/인가 예외 전용 아님** (Validation 등) |

🔷 **Spring Security 6.x**: B1은 이미 `authorizeHttpRequests` 사용 — `WebSecurityConfigurerAdapter` **없음**.  
B2 (3.5.4) 이식 시: **동일 패턴 유지 + 실제 `authenticated()` / OAuth2 / API Key 등으로 교체**.

---

## Step 6. 설정값 목록 (`application.yml`)

| 분류 | 키 | B1 현재 값 (예시) | B2 이식 시 조치 |
|------|-----|-------------------|----------------|
| Server | `server.port` | 8080 | 환경별 |
| DB | `spring.datasource.url` | `jdbc:h2:mem:biometric;MODE=Oracle;...` | **Oracle 19C JDBC URL** (Thin 또는 Thin Wallet) |
| DB | `spring.datasource.driver-class-name` | `org.h2.Driver` | `oracle.jdbc.OracleDriver` |
| DB | `spring.sql.init` | `schema.sql` always | Oracle에서는 **Flyway/Liquibase** 또는 수동 DDL 권장 🔶 |
| MyBatis | `mybatis.mapper-locations` | `classpath:mapper/*.xml` | 유지 |
| JWT | `jwt.secret` | 평문 문자열 (≥32자 권장 주석) | **비밀 저장소·환경변수** 필수 |
| JWT | `jwt.access-token-expiry-minutes` | 30 | 정책에 맞게 |
| JWT | `jwt.refresh-token-expiry-days` | 7 | 정책에 맞게 |
| H2 | `spring.h2.console` | enabled | **운영 비활성** |
| Profile | `test-flow` | TestFlowController | 운영 **비활성** |

**Oracle 의존성 (B2)**  
`build.gradle` 에 예: `runtimeOnly("com.oracle.database.jdbc:ojdbc11")` (또는 조직 표준 BOM).

---

## Step 7. biometric-auth-lib 추출 대상 선별

### 🟢 Core (반드시 이식)

| 클래스 | 역할 | B2 수정 |
|--------|------|---------|
| `AuthConstants` | TTL·허용오차 | 외부 설정화 권장 |
| `ChallengeService` | 챌린지/세션 생성 | `SessionStore` 구현만 맞춤 |
| `EcdsaVerifier` | 서명 검증 | 동일 |
| `VerificationResult` | 결과 코드 | API 오류 매핑 정책과 정렬 |
| `DeviceStore` (인터페이스) | 디바이스 영속 추상화 | Oracle MyBatis 구현 |
| `SessionStore`, `NonceStore` | 세션·nonce | Oracle/Redis |
| `DeviceInfo`, `SessionData`, `DeviceStatus` | 모델 | 컬럼 길이·타입 검증 |
| `FailurePolicyService` / `FailurePolicyStore` | 잠금 정책 | DB화 여부 결정 |
| `FailurePolicyConfig` | DTO | — |
| `AuthController` / `DeviceController` (로직 분리 시 Service 레이어 추가) | REST | 보안·검증 강화 |
| `JwtIssuerService` | 토큰 발급 | RS256·키로테이션 검토 |

### 🟡 Optional

| 클래스 | 역할 | 조건 |
|--------|------|------|
| `SessionCleanupScheduler` | 배치 삭제 | 트래픽·DB 부하에 따라 |
| `PolicyController` | 정책 API | 앱이 정책 동기화할 때 |
| `ApiErrorBodies` | 에러 포맷 | RFC7807 통일 시 대체 |
| `TestFlowController` | 개발용 | B2 개발 단계에서만 |

### 🔴 B1 전용 / 재작성

| 클래스/설정 | 제외·재작성 이유 | B2 대안 |
|-------------|------------------|---------|
| `SecurityConfig` (현 상태) | 전면 permitAll | 리소스별 인가 |
| H2 전용 `application.yml` 블록 | 인메모리 DB | Oracle + 연결 풀 |
| `NonceStoreImpl` (ConcurrentHashMap) | 단일 인스턴스만 일관 | **MyBatisNonceStoreImpl** 또는 Redis |
| `FailurePolicyStoreImpl` 인메모리 | 재기동 시 초기화 | DB 또는 설정 서버 |

---

## Step 8. B2 이식 체크리스트 (Spring Boot 3.5.4 + Oracle 19C)

### Phase 1. 환경 준비 — 난이도: **중**

- [ ] `build.gradle` 에 **ojdbc11**(또는 조직 표준) 추가, Spring Boot **3.5.4** 로 정렬
- [ ] `application.yml` Oracle 접속·풀 설정
- [ ] H2 의존성 제거 또는 `test` scope 전용

### Phase 2. 도메인 / DDL — 난이도: **중**

- [ ] `schema.sql` Oracle 19C에서 재실행 검증 (FK 순서, 인덱스)
- [ ] `PUBLIC_KEY_B64` 길이 한도 재검토 🔶
- [ ] 타임존(`TIMESTAMP WITH TIME ZONE` 여부) 정책 결정 🔶

### Phase 3. Core 기능 — 난이도: **상**

- [ ] `DeviceMapper` / `SessionMapper` / `NonceMapper` XML Oracle 호환 확인 (`&lt;` 이스케이프 등 이미 XML에 적용됨)
- [ ] `ChallengeService` + `EcdsaVerifier` + Store 연동 테스트
- [ ] `renew-key` / `register` / `challenge` / `token` E2E

### Phase 4. Spring Security — 난이도: **상**

- [ ] 🔷 `permitAll` 제거, 생체 API·관리 API 분리
- [ ] (선택) JWT **리소스 서버** 필터 또는 게이트웨이 위임
- [ ] CORS 명시

### Phase 5. 통합 테스트 — 난이도: **중**

- [ ] 등록 → 챌린지 → 서명 → 토큰 E2E
- [ ] `renewKey` 시 `device_id` 불일치·404 경로
- [ ] Oracle 실행 계획·인덱스(`SESSION_ID`, `DEVICE_ID`, `EXPIRE_AT`) 점검

---

## 부록: 잠재 이슈·개선 (요약)

| 표시 | 내용 |
|------|------|
| 💡 | 토큰 단계에서 디바이스 없음 → `INVALID_SIGNATURE` (의미 모호) |
| 💡 | `JwtIssuerService` refresh에 `issuedAt` 없음 |
| 💡 | `/api/device/unlock` ID/PW 없이 잠금 해제 가능 — 주석에 보안 취약 명시됨 |
| 🔶 | 스키마는 이미 Oracle 친화; DB만 교체 시 MyBatis 위주 이식 |
| 🔷 | B1은 이미 Security 6 스타일 — B2는 **정책 강화**가 핵심 |

---

**문서 경로**: `biometric-auth-server/docs/B1-B2-biometric-functional-spec.md`
