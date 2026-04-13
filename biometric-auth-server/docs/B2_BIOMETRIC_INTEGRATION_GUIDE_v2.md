# B2 생체인증 이식 가이드 (v2)

> **대상**: Spring Boot **3.5.4**, Oracle **19C**, Spring Boot 초급 개발자  
> **목적**: `biometric-auth-lib`(`com.biometric.poc.lib`)를 B2에 이식하고, **기존 id/pw JWT 발급 코드는 수정하지 않고** 생체 인증 API·영속 계층을 추가한다.

---

## 목차

1. [STEP 0. 이식 개요 및 구조](#step-0-이식-개요-및-구조)
2. [적용 순서](#적용-순서)
3. [STEP 1. 프로젝트 파일 배치 및 lib 의존성](#step-1-프로젝트-파일-배치-및-lib-의존성)
4. [STEP 2. 테이블 생성 (Oracle 19C DDL)](#step-2-테이블-생성-oracle-19c-ddl)
5. [STEP 3. Store 구현체 작성 (MyBatis)](#step-3-store-구현체-작성-mybatis)
6. [STEP 4. Spring Bean 등록](#step-4-spring-bean-등록)
7. [STEP 5. 디바이스 등록 API 추가](#step-5-디바이스-등록-api-추가)
8. [STEP 6. 생체인증 API (Challenge / Verify / RenewKey)](#step-6-생체인증-api-challenge--verify--renewkey)
9. [STEP 7. 기존 JWT 서비스 확장](#step-7-기존-jwt-서비스-확장)
10. [STEP 8. Spring Security 설정](#step-8-spring-security-설정)
11. [STEP 9. application.yml 설정](#step-9-applicationyml-설정)
12. [STEP 10. B2 CASE별 응답 처리 명세](#step-10-b2-case별-응답-처리-명세)
13. [알려진 이슈 및 대응](#알려진-이슈-및-대응)
14. [완료 체크리스트](#완료-체크리스트)

---

## 적용 순서

1. **STEP 0** — 개요·흐름 숙지, 🔴 기존 JWT 수정 금지 원칙 확인  
2. **STEP 1** — `biometric-auth-lib` **확보·반영**(jar / 모듈 / 소스 복사), 패키지 트리·`build.gradle`/`pom.xml`에 lib·**MyBatis·JDBC** 의존성 추가  
3. **STEP 2** — Oracle에 `biometric_device` / `biometric_session` / `biometric_nonce` DDL 실행  
4. **STEP 3** — Mapper·XML·TypeHandler·`MyBatis*StoreImpl`·`ConfigurableFailurePolicyStore` 작성 (nonce 만료는 **범위 DELETE SQL**, 전 테이블 스캔 금지)  
5. **STEP 4** — `BiometricConfig`로 `ChallengeService`, `EcdsaVerifier`, `FailurePolicyService` Bean 등록  
6. **STEP 5** — `POST /biometric/register` 구현 (challenge 전제 조건)  
7. **STEP 6** — Challenge / Verify / RenewKey API·서비스 (Verify 시 **userId 일치**·실패 시 **`fail_count` + `max-retry-before-lockout` 도달 시에만 `lockAccount`**)  
8. **STEP 7** — `JwtTokenService`에 **`issueTokenForBiometric`만 추가**  
9. **STEP 8** — `/biometric/**` Security 설정  
10. **STEP 9** — `application.yml` `biometric.policy` 등  
11. **STEP 10** — Android CASE ↔ HTTP/`error` 코드 매핑, `fail_count` 잠금, `@ControllerAdvice` 응답 통일  
12. **체크리스트** — 통합·회귀 검증  

---

## STEP 0. 이식 개요 및 구조

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: (없음 — 개념 섹션)                        │
│ 수정 대상 파일: (없음)                                    │
│ 수정하지 않는 파일: 기존 id/pw 로그인·기존 JWT 발급 메서드 │
│ 🔴 기존 `issueToken(...)` 등 JWT 발급 메서드 본문 수정 금지 │
└──────────────────────────────────────────────────────────┘
```

### 변경 배경 (B1 PoC vs B2)

| 항목       | B1 PoC (`biometric-auth-app`)       | B2 이식                               |
| -------- | ----------------------------------- | ----------------------------------- |
| Store 구현 | `ConcurrentHashMap` / H2+MyBatis 예시 | **Oracle 19C + MyBatis** (B2와 동일 스택)   |
| REST     | `/api/device`, `/api/auth` 등        | **`/biometric/*`** 네임스페이스 권장        |
| JWT      | 앱 모듈 `JwtIssuerService`             | **B2 기존 `JwtTokenService` 신규 메서드만** |

### 환경 정보

| 구분             | 값                                        |
| -------------- | ---------------------------------------- |
| B2 Spring Boot | 3.5.4                                    |
| JDK            | 팀 표준 (예: 17 또는 21)                       |
| DB             | Oracle 19C                               |
| lib            | `biometric-auth-lib` — **Spring 미의존**, `EcdsaVerifier`·`ChallengeService`·4개 Store **인터페이스** 제공 |

### 인증 흐름 비교

**기존 id/pw (변경 없음)**

```text
[클라이언트] → POST /login (예시)
    → B2 기존 인증
    → B2 기존 JwtTokenService.issueToken(...)   ← 🔴 본문 수정 금지
    ← JWT
```

**신규 생체**

```text
[클라이언트] → POST /biometric/register (STEP 5)
    → DeviceStore.save(DeviceInfo) — ACTIVE + 공개키

    → POST /biometric/challenge
    → ChallengeService.createSession() — lib
    ← sessionId, serverChallenge, expireAt

[클라이언트] ECDSA 서명

    → POST /biometric/verify
    → device 조회 + userId 일치 검증 (lib는 userId 미검증 — 서버에서 필수)
    → EcdsaVerifier.verify() — lib
    → 실패 시 fail_count 증가·임계 도달 시 `FailurePolicyService.lockAccount` (STEP 6·10)
    → 성공 시 JwtTokenService.issueTokenForBiometric(deviceId, userId)  ← 신규 메서드만
    ← JWT

(선택) → POST /biometric/renew-key
    → DeviceStore.renewKey(...)
```

> 🔴 **기존 JWT 발급 흐름(id/pw)은 절대 수정하지 않는다.** 생체 전용 메서드만 추가한다.

**lib 근거**: `EcdsaVerifier.verify(..., String userId, ...)` 는 **userId를 페이로드 검증에 사용하지 않음** (`biometric-auth-lib-analysis.md` 참고). 따라서 B2에서 **device의 userId와 요청 userId 일치**를 반드시 검증한다.

---

## STEP 1. 프로젝트 파일 배치, `biometric-auth-lib` 확보 및 의존성

STEP 1은 **디렉터리·파일 배치**, **lib를 B2에 가져오는 절차(관리자)**, **Gradle/Maven 의존성**을 함께 정리한다.

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 아래 ASCII 트리의 신규 Java/XML/YML (STEP별) │
│ 수정 대상 파일: build.gradle.kts 또는 pom.xml             │
│ 수정하지 않는 파일: 기존 JwtTokenService.issueToken() 본문 │
│ 🔴 기존 JWT 발급 메서드 본문 수정 금지                      │
└──────────────────────────────────────────────────────────┘
```

### B2 프로젝트 내 파일 배치 (예시 패키지: `com.mycompany.b2`)

```text
com.mycompany.b2/
├── biometric/
│   ├── api/
│   │   ├── BiometricAuthController.java      ← STEP 5, 6
│   │   ├── BiometricAuthService.java         ← STEP 5, 6
│   │   └── dto/
│   │       └── BiometricDtos.java            ← STEP 5, 6
│   ├── config/
│   │   ├── BiometricConfig.java              ← STEP 4
│   │   └── BiometricPolicyProperties.java    ← STEP 3~4
│   ├── mapper/                                ← STEP 3 (MyBatis Mapper 인터페이스)
│   │   ├── BiometricDeviceMapper.java
│   │   ├── BiometricSessionMapper.java
│   │   └── BiometricNonceMapper.java
│   ├── mapper/handler/                        ← STEP 3 (TypeHandler)
│   │   ├── InstantTypeHandler.java
│   │   ├── DeviceStatusTypeHandler.java
│   │   └── BooleanNumberTypeHandler.java      ← 세션 used NUMBER(1) ↔ boolean
│   ├── persistence/
│   │   ├── MyBatisDeviceStoreImpl.java        ← STEP 3 — DeviceStore + fail_count 메서드
│   │   ├── MyBatisSessionStoreImpl.java       ← STEP 3 — SessionStore
│   │   ├── MyBatisNonceStoreImpl.java         ← STEP 3 — NonceStore
│   │   └── ConfigurableFailurePolicyStore.java ← STEP 3
└── security/
    └── JwtTokenService.java                   ← 기존 — STEP 7 신규 메서드만

src/main/resources/mapper/   ← 모듈 루트 기준 (STEP 3)
├── BiometricDeviceMapper.xml
├── BiometricSessionMapper.xml
└── BiometricNonceMapper.xml
```

> 💡 참고 구현: 저장소 `biometric-auth-server/biometric-auth-app` 의 `MyBatisDeviceStoreImpl`, `DeviceMapper.xml` 등. **컬럼·테이블명은 STEP 2 본 문서 Oracle DDL에 맞출 것**(PoC H2 `schema.sql` 과 표기가 다를 수 있음).

### `biometric-auth-lib` 확보 및 B2 반영 (인증서버 관리자)

| 방식 | 개요 | 적합한 경우 |
|------|------|-------------|
| **A. jar 의존성** | lib 모듈을 빌드·배포(`publish`/`install`) 후 사내 Nexus/Artifactory에 올리고 B2에서 `implementation`으로 참조 | **권장** — 버전·감사·재현 빌드 |
| **B. 멀티모듈** | PoC에서 `biometric-auth-lib` 폴더를 B2 저장소로 **복사** 또는 Git submodule → `settings.gradle`에 `include` → `implementation(project(":biometric-auth-lib"))` | lib를 앱과 같은 리포에서 함께 태깅할 때 |
| **C. 소스 직접 복사** | `biometric-auth-lib/src/main/java/com/biometric/poc/lib/**` 를 B2 모듈에 붙여넣은 뒤 **패키지 일괄 변경**(예: `com.mycompany.b2.biometric.lib`) | 외부 jar 반입 불가 폐쇄망 등 — 🔴 **업스트림과 코드 분기**되므로 가능하면 A/B로 전환 |

**관리자 체크리스트**

1. JDK/바이트코드 레벨이 B2와 호환되는지 확인(예: Java 17).  
2. B2에 lib **한 경로만** 연결(동일 클래스 중복 방지).  
3. 패키지 변경 시(C) 문서·샘플의 **import 전부** 수정.  
4. 공개 API는 `biometric-auth-lib-analysis.md` 와 대조.

### Gradle (Kotlin DSL) 예시

`b2-app/build.gradle.kts` (모듈명은 실제에 맞게 조정):

```kotlin
dependencies {
    implementation("com.biometric.poc:biometric-auth-lib:0.0.1-SNAPSHOT")
    // 또는: implementation(project(":biometric-auth-lib"))
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11") // 팀 표준 Oracle 드라이버
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

- lib는 **Spring 미의존** → Spring Boot 3.x 와 프레임워크 충돌이 거의 없음.  
- 로컬 jar: `implementation(files("libs/biometric-auth-lib-0.0.1-SNAPSHOT.jar"))`  
- MyBatis starter 버전은 Spring Boot 3 호환 릴리스를 사용한다.

### Maven 예시

```xml
<dependency>
    <groupId>com.biometric.poc</groupId>
    <artifactId>biometric-auth-lib</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.4</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

> ⚠️ lib에 jjwt 의존성이 있어도 **main 소스에서 미사용**일 수 있음. 조직 정책에 따라 lib `build.gradle` 정리 가능.

---

## STEP 2. 테이블 생성 (Oracle 19C DDL)

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: (선택) sql/biometric-oracle-ddl.sql       │
│ 수정 대상 파일: 없음 (DB에 스크립트 실행)                 │
│ 수정하지 않는 파일: B2 기존 스키마 중 생체와 무관한 객체   │
└──────────────────────────────────────────────────────────┘
```

> 🔶 `VARCHAR2(n CHAR)` 는 **문자 수** 기준. `BYTE` 와 혼동 주의.  
> 🔶 `TIMESTAMP(6)` 는 세션 타임존 영향을 받음. 글로벌 서비스는 `TIMESTAMP(6) WITH TIME ZONE` 검토.

```sql
-- =============================================================================
-- B2 생체 인증 — Oracle 19C DDL
-- 실행 전: 대상 스키마·권한(CREATE TABLE, INDEX) 확인. 운영은 Flyway/Liquibase 등으로 버전 관리 권장.
-- 🔶 VARCHAR2(n CHAR): 문자 수 기준. BYTE 와 혼동 주의.
-- 🔶 TIMESTAMP(6): 세션 타임존 영향 — 글로벌 서비스는 WITH TIME ZONE 검토.
-- 🔶 NUMBER(1): 0/1 플래그(BooleanNumberConverter 와 매핑 일치).
-- =============================================================================

-- 등록 기기 마스터. POST /biometric/register 로 최초 적재 후 challenge/verify 가능.
CREATE TABLE biometric_device (
    device_id        VARCHAR2(100 CHAR) NOT NULL,   -- 기기 고유 ID (PK, 클라이언트 생성값)
    user_id          VARCHAR2(100 CHAR) NOT NULL,   -- 소유자 — verify 시 요청 userId 와 서버에서 반드시 일치 검증
    public_key_b64   VARCHAR2(4000 CHAR),            -- ECDSA 공개키(Base64 등) — 팀 키 길이에 맞게 조정
    status           VARCHAR2(30 CHAR)   NOT NULL,  -- ACTIVE | LOCKED | KEY_INVALIDATED (아래 CHECK)
    enrolled_at      TIMESTAMP(6)         NOT NULL, -- 최초 등록 시각
    updated_at       TIMESTAMP(6)         NOT NULL, -- 마지막 갱신(renew-key 등)
    fail_count       NUMBER(3)          DEFAULT 0 NOT NULL, -- 연속 verify 실패 — STEP 10 잠금 정책
    CONSTRAINT pk_biometric_device PRIMARY KEY (device_id),
    CONSTRAINT chk_biometric_device_status CHECK (status IN ('ACTIVE', 'LOCKED', 'KEY_INVALIDATED'))
);

-- 사용자별 등록 기기 조회·운영 점검
CREATE INDEX idx_biometric_device_user ON biometric_device (user_id);

-- Challenge 응답 1회성 세션. 만료/정리는 앱 로직·배치와 조율.
CREATE TABLE biometric_session (
    session_id         VARCHAR2(64 CHAR)  NOT NULL, -- ChallengeService 발급 세션 ID
    device_id          VARCHAR2(100 CHAR) NOT NULL, -- FK → biometric_device.device_id
    user_id            VARCHAR2(100 CHAR) NOT NULL, -- 세션 생성 시 사용자
    server_challenge   VARCHAR2(64 CHAR)  NOT NULL, -- 서버 챌린지(서명 페이로드 구성 요소)
    client_nonce       VARCHAR2(64 CHAR)  NOT NULL, -- 클라이언트 난수
    req_timestamp      NUMBER(19)         NOT NULL, -- 요청 시각(epoch ms 등, SessionData.timestamp Long 과 일치)
    expire_at          TIMESTAMP(6)       NOT NULL, -- 세션 만료 시각 — 이후 verify 불가
    used               NUMBER(1)          DEFAULT 0 NOT NULL, -- 0=미사용, 1=검증 소비됨
    created_at         TIMESTAMP(6)       NOT NULL, -- 행 생성 시각
    CONSTRAINT pk_biometric_session PRIMARY KEY (session_id),
    CONSTRAINT fk_biometric_session_device
        FOREIGN KEY (device_id) REFERENCES biometric_device (device_id)
);

-- 만료 세션 스캔·정리용(인덱스 범위 조회)
CREATE INDEX idx_biometric_session_expire ON biometric_session (expire_at);

-- 재사용 방지 nonce. 만료 행 삭제는 deleteExpiredBefore 등 범위 쿼리만 사용(전 테이블 스캔 금지).
CREATE TABLE biometric_nonce (
    nonce      VARCHAR2(64 CHAR)  NOT NULL, -- 일회용 값(PK)
    device_id  VARCHAR2(100 CHAR) NOT NULL, -- 사용 기기
    used_at    TIMESTAMP(6)       NOT NULL, -- 기록 시각 — 만료 판단 기준
    CONSTRAINT pk_biometric_nonce PRIMARY KEY (nonce)
);

CREATE INDEX idx_biometric_nonce_used_at ON biometric_nonce (used_at);

-- =============================================================================
-- 데이터 사전 메타데이터 (SQL*Developer / ALL_COL_COMMENTS 등에서 조회)
-- 🔶 다른 스키마 소유 시: COMMENT ON ... MYSCHEMA.biometric_device ...
-- 권한: 소유자이거나 COMMENT ANY TABLE 등 (조직 정책에 따름)
-- =============================================================================

COMMENT ON TABLE biometric_device IS '생체 등록 기기 마스터. 공개키·상태·등록/갱신 시각. register 선행.';
COMMENT ON COLUMN biometric_device.device_id IS '기기 고유 ID (PK)';
COMMENT ON COLUMN biometric_device.user_id IS '소유 사용자 ID — verify 요청 userId 와 서버에서 일치 검증';
COMMENT ON COLUMN biometric_device.public_key_b64 IS 'ECDSA 공개키(Base64 등)';
COMMENT ON COLUMN biometric_device.status IS 'ACTIVE | LOCKED | KEY_INVALIDATED';
COMMENT ON COLUMN biometric_device.enrolled_at IS '최초 등록 시각';
COMMENT ON COLUMN biometric_device.updated_at IS '마지막 갱신 시각';
COMMENT ON COLUMN biometric_device.fail_count IS '연속 verify 실패 횟수 — 성공 시 0, 잠금 후 reset';

COMMENT ON TABLE biometric_session IS 'Challenge/Verify 1회성 세션. 만료·used 플래그 관리.';
COMMENT ON COLUMN biometric_session.session_id IS '세션 ID (PK)';
COMMENT ON COLUMN biometric_session.device_id IS 'FK biometric_device.device_id';
COMMENT ON COLUMN biometric_session.user_id IS '세션 생성 시 사용자 ID';
COMMENT ON COLUMN biometric_session.server_challenge IS '서버 챌린지(서명 페이로드 구성)';
COMMENT ON COLUMN biometric_session.client_nonce IS '클라이언트 난수';
COMMENT ON COLUMN biometric_session.req_timestamp IS '요청 시각(epoch ms 등, NUMBER(19))';
COMMENT ON COLUMN biometric_session.expire_at IS '세션 만료 시각';
COMMENT ON COLUMN biometric_session.used IS '0=미사용 1=소비됨 (NUMBER(1))';
COMMENT ON COLUMN biometric_session.created_at IS '행 생성 시각';

COMMENT ON TABLE biometric_nonce IS '재사용 방지 nonce. used_at 기준 만료 삭제는 범위 쿼리로만.';
COMMENT ON COLUMN biometric_nonce.nonce IS '일회용 값 (PK)';
COMMENT ON COLUMN biometric_nonce.device_id IS '사용 기기 ID';
COMMENT ON COLUMN biometric_nonce.used_at IS 'nonce 기록 시각 — 만료 판단 기준';
```

> 💡 인라인 `--` 주석은 스크립트용이고, **`COMMENT ON TABLE` / `COMMENT ON COLUMN`** 은 Oracle 데이터 사전(`USER_TAB_COMMENTS`, `USER_COL_COMMENTS` 등)에 저장되어 툴·ERD에서 조회된다.

### fail_count 컬럼 추가 (이미 적용한 기존 DB 마이그레이션)

위 **`CREATE TABLE biometric_device`에 `fail_count`가 포함**되어 있으면 생략한다. **과거 DDL만 적용된 DB**에 컬럼을 붙일 때만 아래를 실행한다(`max-retry-before-lockout`·STEP 10 연동).

```sql
-- STEP 2 DDL 이후 실행 (기존 테이블에만 추가)
ALTER TABLE biometric_device ADD fail_count NUMBER(3) DEFAULT 0 NOT NULL;

COMMENT ON COLUMN biometric_device.fail_count IS '연속 verify 실패 횟수 — 성공 시 0, 잠금 후 reset';
```

---

## STEP 3. Store 구현체 작성 (MyBatis)

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: mapper/*.java, resources/mapper/*.xml,   │
│                 mapper/handler/*TypeHandler.java,       │
│                 MyBatis*StoreImpl.java                   │
│ 수정 대상 파일: 없음                                     │
│ 수정하지 않는 파일: biometric-auth-lib 소스, 기존 JWT    │
└──────────────────────────────────────────────────────────┘
```

> ⚠️ **`markUsed()` 안에서 nonce/session 전 테이블 `SELECT` 후 애플리케이션에서 삭제하면 운영 장애로 이어진다.** 만료 분만 **`DELETE ... WHERE used_at < ?`** (또는 B1처럼 `expire_at` 컬럼이 있으면 그 기준) **단일 범위 삭제 SQL**로 처리한다.

### 설계 원칙

- **도메인 모델은 lib 그대로**: `DeviceInfo`, `SessionData` 를 MyBatis `resultMap` / `parameterType`에 직접 매핑한다(B1 `DeviceMapper.xml` 패턴).
- **테이블·컬럼명은 STEP 2 DDL과 일치**시킨다. PoC `biometric-auth-app` 의 H2 `schema.sql` 은 컬럼 표기(`CHALLENGE_HEX`, `USED_YN`, `EXPIRE_AT` 등)가 다를 수 있으므로 **XML을 복사만 하지 말고 DDL 기준으로 수정**한다.
- **트랜잭션**: Store 구현체의 `save` / `markUsed` / `incrementFailCount` 등에는 `@Transactional` 을 붙인다.

### TypeHandler (Instant, DeviceStatus, boolean↔NUMBER(1))

`InstantTypeHandler`·`DeviceStatusTypeHandler` 는 PoC `biometric-auth-app` 의 `com.biometric.poc.mapper.handler` 패키지 구현을 **패키지명만 B2로 바꿔 복사**하면 된다.

`BooleanNumberTypeHandler` — 세션 `used` 컬럼이 `NUMBER(1)` 인 경우:

```java
// 경로: com/mycompany/b2/biometric/mapper/handler/BooleanNumberTypeHandler.java
package com.mycompany.b2.biometric.mapper.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(Boolean.class)
public class BooleanNumberTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, Boolean.TRUE.equals(parameter) ? 1 : 0);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int v = rs.getInt(columnName);
        return rs.wasNull() ? null : v != 0;
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int v = rs.getInt(columnIndex);
        return rs.wasNull() ? null : v != 0;
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int v = cs.getInt(columnIndex);
        return cs.wasNull() ? null : v != 0;
    }
}
```

### BiometricDeviceMapper.java

```java
// 경로: com/mycompany/b2/biometric/mapper/BiometricDeviceMapper.java
package com.mycompany.b2.biometric.mapper;

import com.biometric.poc.lib.model.DeviceInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface BiometricDeviceMapper {

    void insert(DeviceInfo row);

    DeviceInfo selectByDeviceId(@Param("deviceId") String deviceId);

    int countByDeviceId(@Param("deviceId") String deviceId);

    void updateStatus(
            @Param("deviceId") String deviceId,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt);

    void updateKeyInvalidated(@Param("deviceId") String deviceId, @Param("updatedAt") Instant updatedAt);

    void updatePublicKey(
            @Param("deviceId") String deviceId,
            @Param("publicKeyBase64") String publicKeyBase64,
            @Param("updatedAt") Instant updatedAt);

    void reRegister(DeviceInfo row);

    void renewKey(
            @Param("deviceId") String deviceId,
            @Param("publicKeyBase64") String publicKeyBase64,
            @Param("updatedAt") Instant updatedAt);

    void deleteByDeviceId(@Param("deviceId") String deviceId);

    int incrementFailCount(@Param("deviceId") String deviceId, @Param("updatedAt") Instant updatedAt);

    int resetFailCount(@Param("deviceId") String deviceId, @Param("updatedAt") Instant updatedAt);

    Integer selectFailCount(@Param("deviceId") String deviceId);
}
```

### BiometricDeviceMapper.xml (요약 — namespace·컬럼은 DDL에 맞출 것)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.mycompany.b2.biometric.mapper.BiometricDeviceMapper">

  <resultMap id="DeviceInfoMap" type="com.biometric.poc.lib.model.DeviceInfo">
    <id     property="deviceId"        column="device_id"/>
    <result property="userId"          column="user_id"/>
    <result property="publicKeyBase64" column="public_key_b64"/>
    <result property="status"          column="status"
        typeHandler="com.mycompany.b2.biometric.mapper.handler.DeviceStatusTypeHandler"/>
    <result property="enrolledAt"      column="enrolled_at"
        typeHandler="com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler"/>
    <result property="updatedAt"       column="updated_at"
        typeHandler="com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler"/>
  </resultMap>

  <insert id="insert" parameterType="com.biometric.poc.lib.model.DeviceInfo">
    INSERT INTO biometric_device
      (device_id, user_id, public_key_b64, status, enrolled_at, updated_at)
    VALUES
      (#{deviceId}, #{userId}, #{publicKeyBase64},
       #{status, typeHandler=com.mycompany.b2.biometric.mapper.handler.DeviceStatusTypeHandler},
       #{enrolledAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler},
       #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler})
  </insert>

  <select id="selectByDeviceId" resultMap="DeviceInfoMap">
    SELECT device_id, user_id, public_key_b64, status, enrolled_at, updated_at
    FROM biometric_device WHERE device_id = #{deviceId}
  </select>

  <select id="countByDeviceId" resultType="int">
    SELECT COUNT(*) FROM biometric_device WHERE device_id = #{deviceId}
  </select>

  <update id="updateStatus">
    UPDATE biometric_device SET status = #{status},
      updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <update id="updateKeyInvalidated">
    UPDATE biometric_device SET status = 'KEY_INVALIDATED', public_key_b64 = NULL,
      updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <update id="updatePublicKey">
    UPDATE biometric_device SET public_key_b64 = #{publicKeyBase64},
      updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <update id="reRegister" parameterType="com.biometric.poc.lib.model.DeviceInfo">
    UPDATE biometric_device SET public_key_b64 = #{publicKeyBase64}, user_id = #{userId},
      status = 'ACTIVE',
      enrolled_at = #{enrolledAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler},
      updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <update id="renewKey">
    UPDATE biometric_device SET public_key_b64 = #{publicKeyBase64}, status = 'ACTIVE',
      updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <delete id="deleteByDeviceId">
    DELETE FROM biometric_device WHERE device_id = #{deviceId}
  </delete>

  <update id="incrementFailCount">
    UPDATE biometric_device
    SET fail_count = fail_count + 1,
        updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <update id="resetFailCount">
    UPDATE biometric_device
    SET fail_count = 0,
        updated_at = #{updatedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
    WHERE device_id = #{deviceId}
  </update>

  <select id="selectFailCount" resultType="int">
    SELECT fail_count FROM biometric_device WHERE device_id = #{deviceId}
  </select>
</mapper>
```

### MyBatisDeviceStoreImpl.java

PoC `MyBatisDeviceStoreImpl` 와 동일하게 **삭제 순서: nonce → session → device** 를 지킨다.

```java
// 경로: com/mycompany/b2/biometric/persistence/MyBatisDeviceStoreImpl.java
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.mapper.BiometricDeviceMapper;
import com.mycompany.b2.biometric.mapper.BiometricNonceMapper;
import com.mycompany.b2.biometric.mapper.BiometricSessionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
public class MyBatisDeviceStoreImpl implements DeviceStore {

    private final BiometricDeviceMapper deviceMapper;
    private final BiometricNonceMapper nonceMapper;
    private final BiometricSessionMapper sessionMapper;

    public MyBatisDeviceStoreImpl(
            BiometricDeviceMapper deviceMapper,
            BiometricNonceMapper nonceMapper,
            BiometricSessionMapper sessionMapper) {
        this.deviceMapper = deviceMapper;
        this.nonceMapper = nonceMapper;
        this.sessionMapper = sessionMapper;
    }

    @Override @Transactional
    public void save(DeviceInfo d) {
        d.setUpdatedAt(Instant.now());
        deviceMapper.insert(d);
    }

    @Override @Transactional(readOnly = true)
    public Optional<DeviceInfo> findByDeviceId(String deviceId) {
        return Optional.ofNullable(deviceMapper.selectByDeviceId(deviceId));
    }

    @Override @Transactional(readOnly = true)
    public boolean existsByDeviceId(String deviceId) {
        return deviceMapper.countByDeviceId(deviceId) > 0;
    }

    @Override @Transactional
    public void updateStatus(String deviceId, DeviceStatus status) {
        deviceMapper.updateStatus(deviceId, status.name(), Instant.now());
    }

    @Override @Transactional
    public void invalidateKey(String deviceId) {
        deviceMapper.updateKeyInvalidated(deviceId, Instant.now());
    }

    @Override @Transactional
    public void updatePublicKey(String deviceId, String publicKeyBase64) {
        deviceMapper.updatePublicKey(deviceId, publicKeyBase64, Instant.now());
    }

    @Override @Transactional
    public void reRegister(DeviceInfo d) {
        if (d.getUpdatedAt() == null) {
            d.setUpdatedAt(Instant.now());
        }
        deviceMapper.reRegister(d);
    }

    @Override @Transactional
    public void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt) {
        deviceMapper.renewKey(deviceId, newPublicKeyBase64, updatedAt);
    }

    @Override @Transactional
    public void delete(String deviceId) {
        nonceMapper.deleteByDeviceId(deviceId);
        sessionMapper.deleteByDeviceId(deviceId);
        deviceMapper.deleteByDeviceId(deviceId);
    }

    /** lib `DeviceStore`에 없음 — `BiometricAuthService.verify` 전용 */
    @Transactional
    public int incrementFailCount(String deviceId) {
        Instant now = Instant.now();
        deviceMapper.incrementFailCount(deviceId, now);
        Integer n = deviceMapper.selectFailCount(deviceId);
        return n != null ? n : 0;
    }

    @Transactional
    public void resetFailCount(String deviceId) {
        deviceMapper.resetFailCount(deviceId, Instant.now());
    }

    @Transactional(readOnly = true)
    public int getFailCount(String deviceId) {
        Integer n = deviceMapper.selectFailCount(deviceId);
        return n != null ? n : 0;
    }
}
```

### BiometricSessionMapper / XML / MyBatisSessionStoreImpl

**인터페이스**

```java
// 경로: com/mycompany/b2/biometric/mapper/BiometricSessionMapper.java
package com.mycompany.b2.biometric.mapper;

import com.biometric.poc.lib.model.SessionData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BiometricSessionMapper {
    void insert(SessionData row);
    SessionData selectBySessionId(@Param("sessionId") String sessionId);
    void markUsed(@Param("sessionId") String sessionId);
    void deleteByDeviceId(@Param("deviceId") String deviceId);
}
```

**XML 핵심** — `server_challenge` ↔ `SessionData.serverChallengeHex`, `used` ↔ boolean:

```xml
<resultMap id="SessionDataMap" type="com.biometric.poc.lib.model.SessionData">
  <id     property="sessionId" column="session_id"/>
  <result property="deviceId" column="device_id"/>
  <result property="userId" column="user_id"/>
  <result property="serverChallengeHex" column="server_challenge"/>
  <result property="clientNonce" column="client_nonce"/>
  <result property="timestamp" column="req_timestamp"/>
  <result property="expireAt" column="expire_at"
      typeHandler="com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler"/>
  <result property="used" column="used"
      typeHandler="com.mycompany.b2.biometric.mapper.handler.BooleanNumberTypeHandler"/>
  <result property="createdAt" column="created_at"
      typeHandler="com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler"/>
</resultMap>

<insert id="insert" parameterType="com.biometric.poc.lib.model.SessionData">
  INSERT INTO biometric_session
    (session_id, device_id, user_id, server_challenge, client_nonce,
     req_timestamp, expire_at, used, created_at)
  VALUES
    (#{sessionId}, #{deviceId}, #{userId}, #{serverChallengeHex}, #{clientNonce},
     #{timestamp},
     #{expireAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler},
     #{used, typeHandler=com.mycompany.b2.biometric.mapper.handler.BooleanNumberTypeHandler},
     #{createdAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler})
</insert>

<update id="markUsed">
  UPDATE biometric_session SET used = 1 WHERE session_id = #{sessionId}
</update>
```

**MyBatisSessionStoreImpl** — PoC `MyBatisSessionStoreImpl` 과 동일: 조회 시 만료·used 이면 `Optional.empty()`.

### BiometricNonceMapper / XML / MyBatisNonceStoreImpl

STEP 2 DDL에 **`expire_at` 없음** — 만료 삭제는 **`used_at < cutoff`** 만 사용한다(B1 PoC는 `expire_at` 컬럼이 있음 ⚠️).

```java
// 경로: com/mycompany/b2/biometric/mapper/BiometricNonceMapper.java
package com.mycompany.b2.biometric.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface BiometricNonceMapper {
    void insert(
            @Param("nonce") String nonce,
            @Param("deviceId") String deviceId,
            @Param("usedAt") Instant usedAt);

    int countByNonce(@Param("nonce") String nonce);

    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    void deleteByDeviceId(@Param("deviceId") String deviceId);
}
```

```xml
<insert id="insert">
  INSERT INTO biometric_nonce (nonce, device_id, used_at)
  VALUES (
    #{nonce}, #{deviceId},
    #{usedAt, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
  )
</insert>

<select id="countByNonce" resultType="int">
  SELECT COUNT(*) FROM biometric_nonce WHERE nonce = #{nonce}
</select>

<delete id="deleteExpiredBefore">
  DELETE FROM biometric_nonce
  WHERE used_at &lt; #{cutoff, typeHandler=com.mycompany.b2.biometric.mapper.handler.InstantTypeHandler}
</delete>
```

```java
// 경로: com/mycompany/b2/biometric/persistence/MyBatisNonceStoreImpl.java
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.auth.AuthConstants;
import com.biometric.poc.lib.store.NonceStore;
import com.mycompany.b2.biometric.mapper.BiometricNonceMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class MyBatisNonceStoreImpl implements NonceStore {

    private final BiometricNonceMapper nonceMapper;

    public MyBatisNonceStoreImpl(BiometricNonceMapper nonceMapper) {
        this.nonceMapper = nonceMapper;
    }

    @Override @Transactional(readOnly = true)
    public boolean isUsed(String nonce) {
        return nonceMapper.countByNonce(nonce) > 0;
    }

    @Override @Transactional
    public void markUsed(String nonce, String deviceId) {
        Instant now = Instant.now();
        nonceMapper.insert(nonce, deviceId, now);
        Instant cutoff = now.minus(AuthConstants.NONCE_TTL_MINUTES, ChronoUnit.MINUTES);
        nonceMapper.deleteExpiredBefore(cutoff);
    }
}
```

### BiometricPolicyProperties · ConfigurableFailurePolicyStore

(내용 동일 — 패키지 경로만 B2에 맞출 것)

```java
// 경로: com/mycompany/b2/biometric/config/BiometricPolicyProperties.java
package com.mycompany.b2.biometric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "biometric.policy")
public class BiometricPolicyProperties {
    private int maxRetryBeforeLockout = 3;
    private int lockoutSeconds = 30;
    private int accountLockThreshold = 5;
    private boolean fallbackPasswordEnabled = true;
}
```

```java
// 경로: com/mycompany/b2/biometric/persistence/ConfigurableFailurePolicyStore.java
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.model.FailurePolicyConfig;
import com.biometric.poc.lib.store.FailurePolicyStore;
import com.mycompany.b2.biometric.config.BiometricPolicyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigurableFailurePolicyStore implements FailurePolicyStore {

    private final BiometricPolicyProperties props;

    @Override
    public FailurePolicyConfig getPolicy(String deviceId) {
        return FailurePolicyConfig.builder()
                .maxRetryBeforeLockout(props.getMaxRetryBeforeLockout())
                .lockoutSeconds(props.getLockoutSeconds())
                .accountLockThreshold(props.getAccountLockThreshold())
                .fallbackPasswordEnabled(props.isFallbackPasswordEnabled())
                .build();
    }
}
```

---
## STEP 4. Spring Bean 등록

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: .../biometric/config/BiometricConfig.java   │
│ 수정 대상 파일: 메인 애플리케이션에 @MapperScan (mapper 패키지) │
│ 수정하지 않는 파일: MyBatis*StoreImpl 등 @Component 본문  │
│ 🔴 기존 JWT 관련 설정/빈 정의 임의 변경 금지                │
└──────────────────────────────────────────────────────────┘
```

**이 단계에서 할 일**: lib의 `ChallengeService`, `EcdsaVerifier`, `FailurePolicyService`를 **생성자 주입 가능한 Bean**으로 등록한다.

```java
// 경로: com/mycompany/b2/biometric/config/BiometricConfig.java
// 한 줄 요약: biometric-auth-lib 핵심 타입을 Spring Bean으로 노출한다.
package com.mycompany.b2.biometric.config;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.policy.FailurePolicyService;
import com.biometric.poc.lib.store.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BiometricPolicyProperties.class)
public class BiometricConfig {

    @Bean
    public ChallengeService challengeService(SessionStore sessionStore) {
        return new ChallengeService(sessionStore);
    }

    @Bean
    public EcdsaVerifier ecdsaVerifier(
            ChallengeService challengeService,
            NonceStore nonceStore,
            DeviceStore deviceStore) {
        return new EcdsaVerifier(challengeService, nonceStore, deviceStore);
    }

    @Bean
    public FailurePolicyService failurePolicyService(
            FailurePolicyStore failurePolicyStore,
            DeviceStore deviceStore) {
        return new FailurePolicyService(failurePolicyStore, deviceStore);
    }
}
```

> ⚠️ **이중 등록**: `MyBatisDeviceStoreImpl` 등에 `@Component`를 달았다면, 위 Store 인터페이스에 **별도 `@Bean`으로 동일 구현체를 또 등록하지 않는다.**

`@EnableConfigurationProperties(BiometricPolicyProperties.class)` 는 **`BiometricConfig`에 두는 것이 일반적**이다.

**`@MapperScan`**: Spring Boot가 Mapper 인터페이스를 빈으로 등록하도록 메인 클래스(또는 `@Configuration`)에 다음을 추가한다.

```java
import org.mybatis.spring.annotation.MapperScan;

@MapperScan("com.mycompany.b2.biometric.mapper")
@SpringBootApplication
public class B2Application { /* ... */ }
```

> 💡 Mapper XML 위치는 `application.yml` 의 `mybatis.mapper-locations` 로 지정한다(STEP 9).

---

## STEP 5. 디바이스 등록 API 추가

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 없음 (STEP 5~6에서 DTO/Controller/Service 확장) │
│ 수정 대상 파일: BiometricDtos.java, BiometricAuthController.java, │
│                 BiometricAuthService.java                  │
│ 수정하지 않는 파일: 기존 JwtTokenService.issueToken() 본문 │
└──────────────────────────────────────────────────────────┘
```

> ⚠️ **이 STEP을 생략하면 `biometric_device`에 행이 없어 STEP 6의 challenge가 실패한다** (또는 DEVICE_NOT_FOUND).

### Register DTO (BiometricDtos.java에 추가)

```java
// RegisterRequest / RegisterResponse — BiometricDtos.java 내부에 추가
public record RegisterRequest(
        @NotBlank @JsonProperty("device_id") String deviceId,
        @NotBlank @JsonProperty("user_id") String userId,
        @NotBlank @JsonProperty("public_key") String publicKey,
        @NotBlank @JsonProperty("enrolled_at") String enrolledAt) {}

public record RegisterResponse(@JsonProperty("status") String status) {}
```

### 서비스 메서드 (BiometricAuthService.java)

**한 줄 요약**: 공개키·사용자·등록 시각을 받아 `DeviceInfo`를 만들고 `DeviceStore.save`로 넣는다.

```java
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.api.dto.BiometricDtos.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;

// 필드
private final DeviceStore deviceStore;

public RegisterResponse register(RegisterRequest req) {
    Instant enrolled;
    try {
        enrolled = Instant.parse(req.enrolledAt());
    } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ENROLLED_AT");
    }
    if (deviceStore.existsByDeviceId(req.deviceId())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_REGISTERED");
    }
    Instant now = Instant.now();
    DeviceInfo info = DeviceInfo.builder()
            .deviceId(req.deviceId())
            .userId(req.userId())
            .publicKeyBase64(req.publicKey())
            .enrolledAt(enrolled)
            .updatedAt(now)
            .status(DeviceStatus.ACTIVE)
            .build();
    deviceStore.save(info);
    return new RegisterResponse("REGISTERED");
}
```

> 💡 운영 정책에 따라 **KEY_INVALIDATED 재등록**·**LOCKED 거부** 등은 B1 `DeviceController` 수준으로 확장 가능.

### 컨트롤러 (BiometricAuthController.java)

```java
@PostMapping("/register")
public RegisterResponse register(@Valid @RequestBody RegisterRequest body) {
    return biometricAuthService.register(body);
}
```

---

## STEP 6. 생체인증 API (Challenge / Verify / RenewKey)

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: (STEP 5와 동일 파일 확장)                  │
│ 수정 대상 파일: BiometricDtos.java, BiometricAuthController.java, │
│                 BiometricAuthService.java                  │
│ 수정하지 않는 파일: 기존 JwtTokenService.issueToken() 본문 │
└──────────────────────────────────────────────────────────┘
```

### BiometricDtos.java (전문 예시 — Register 포함)

```java
package com.mycompany.b2.biometric.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class BiometricDtos {

    public record RegisterRequest(
            @NotBlank @JsonProperty("device_id") String deviceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @NotBlank @JsonProperty("public_key") String publicKey,
            @NotBlank @JsonProperty("enrolled_at") String enrolledAt) {}

    public record RegisterResponse(@JsonProperty("status") String status) {}

    public record ChallengeRequest(
            @NotBlank @JsonProperty("device_id") String deviceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @NotBlank @JsonProperty("client_nonce") String clientNonce,
            @Min(0) long timestamp) {}

    public record ChallengeResponse(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("server_challenge") String serverChallenge,
            @JsonProperty("expire_at") long expireAtEpochMillis) {}

    public record VerifyRequest(
            @NotBlank @JsonProperty("session_id") String sessionId,
            @NotBlank @JsonProperty("device_id") String deviceId,
            @NotBlank @JsonProperty("user_id") String userId,
            @NotBlank @JsonProperty("ec_signature") String ecSignatureBase64,
            @NotBlank @JsonProperty("client_nonce") String clientNonce,
            @Min(0) long timestamp) {}

    public record VerifyResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {}

    public record RenewKeyRequest(
            @NotBlank @JsonProperty("device_id") String deviceId,
            @NotBlank @JsonProperty("new_public_key") String newPublicKeyBase64) {}
}
```

### BiometricAuthController.java (전문)

```java
package com.mycompany.b2.biometric.api;

import com.mycompany.b2.biometric.api.dto.BiometricDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/biometric")
@RequiredArgsConstructor
public class BiometricAuthController {

    private final BiometricAuthService biometricAuthService;

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body) {
        return biometricAuthService.register(body);
    }

    @PostMapping("/challenge")
    public ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest body) {
        return biometricAuthService.challenge(body);
    }

    @PostMapping("/verify")
    public VerifyResponse verify(@Valid @RequestBody VerifyRequest body) {
        return biometricAuthService.verify(body);
    }

    @PostMapping("/renew-key")
    public Map<String, String> renewKey(@Valid @RequestBody RenewKeyRequest body) {
        return biometricAuthService.renewKey(body);
    }
}
```

### BiometricAuthService.java (전문)

```java
package com.mycompany.b2.biometric.api;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.ecdsa.VerificationResult;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.FailurePolicyConfig;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.policy.FailurePolicyService;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.api.dto.BiometricDtos.*;
import com.mycompany.b2.biometric.persistence.MyBatisDeviceStoreImpl;
import com.mycompany.b2.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BiometricAuthService {

    private final ChallengeService challengeService;
    private final EcdsaVerifier ecdsaVerifier;
    private final DeviceStore deviceStore;
    private final MyBatisDeviceStoreImpl myBatisDeviceStore;
    private final FailurePolicyService failurePolicyService;
    private final JwtTokenService jwtTokenService;

    public RegisterResponse register(RegisterRequest req) {
        Instant enrolled;
        try {
            enrolled = Instant.parse(req.enrolledAt());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ENROLLED_AT");
        }
        if (deviceStore.existsByDeviceId(req.deviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_REGISTERED");
        }
        Instant now = Instant.now();
        DeviceInfo info = DeviceInfo.builder()
                .deviceId(req.deviceId())
                .userId(req.userId())
                .publicKeyBase64(req.publicKey())
                .enrolledAt(enrolled)
                .updatedAt(now)
                .status(DeviceStatus.ACTIVE)
                .build();
        deviceStore.save(info);
        return new RegisterResponse("REGISTERED");
    }

    public ChallengeResponse challenge(ChallengeRequest req) {
        DeviceInfo device = deviceStore.findByDeviceId(req.deviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND"));
        if (device.getStatus() == DeviceStatus.LOCKED) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "ACCOUNT_LOCKED");
        }
        if (device.getStatus() == DeviceStatus.KEY_INVALIDATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KEY_INVALIDATED");
        }
        SessionData session = challengeService.createSession(
                req.deviceId(), req.userId(), req.clientNonce(), req.timestamp());
        return new ChallengeResponse(
                session.getSessionId(),
                session.getServerChallengeHex(),
                session.getExpireAt().toEpochMilli());
    }

    public VerifyResponse verify(VerifyRequest req) {
        // 1) device 조회
        DeviceInfo device = deviceStore.findByDeviceId(req.deviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND"));

        // 2) userId 일치 (EcdsaVerifier는 userId를 검증하지 않음 — lib 특성)
        if (!device.getUserId().equals(req.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_DEVICE_MISMATCH");
        }

        // 3) ECDSA 검증
        VerificationResult result = ecdsaVerifier.verify(
                req.sessionId(),
                req.deviceId(),
                req.userId(),
                req.ecSignatureBase64(),
                req.clientNonce(),
                req.timestamp());

        // 4) 실패 시 fail_count 증가 — 임계 도달 시에만 lockAccount (lib는 횟수 미추적)
        if (result != VerificationResult.SUCCESS) {
            FailurePolicyConfig policy = failurePolicyService.getPolicy(req.deviceId());
            int failCount = myBatisDeviceStore.incrementFailCount(req.deviceId());
            if (failCount >= policy.getMaxRetryBeforeLockout()) {
                failurePolicyService.lockAccount(req.deviceId());
                myBatisDeviceStore.resetFailCount(req.deviceId());
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, result.name());
        }

        myBatisDeviceStore.resetFailCount(req.deviceId());

        // ✅ 여기서 JWT를 발급합니다.
        // B2의 기존 JwtTokenService(또는 동등한 클래스)를 호출하세요.
        // Claims에 deviceId와 userId를 포함시켜야 합니다.
        var token = jwtTokenService.issueTokenForBiometric(req.deviceId(), req.userId());
        return new VerifyResponse(token.accessToken(), token.expiresInSeconds());
    }

    public Map<String, String> renewKey(RenewKeyRequest req) {
        var opt = deviceStore.findByDeviceId(req.deviceId());
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND");
        }
        if (opt.get().getStatus() == DeviceStatus.LOCKED) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "ACCOUNT_LOCKED");
        }
        deviceStore.renewKey(req.deviceId(), req.newPublicKeyBase64(), Instant.now());
        return Map.of("status", "RENEWED");
    }
}
```

> 💡 `FailurePolicyService.lockAccount` 는 lib에서 `deviceStore.updateStatus(..., LOCKED)` 로 구현됨. **`incrementFailCount` / `resetFailCount` 는 `DeviceStore`에 없으므로 `MyBatisDeviceStoreImpl`을 타입으로 별도 주입**한다(이 클래스가 `DeviceStore` 구현체라면 한 Bean이 두 타입으로 주입 가능).

> ⚠️ `ResponseStatusException`만 쓰면 본문이 B1과 다를 수 있음 — STEP 10의 `@RestControllerAdvice`로 `{"error":"..."}` 통일.

---

## STEP 7. 기존 JWT 서비스 확장

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 없음                                       │
│ 수정 대상 파일: com/.../security/JwtTokenService.java    │
│ 수정하지 않는 파일: 기존 issueToken(String loginId) 등 본문 │
│ 🔴 기존 JWT 발급 메서드 본문 수정 금지 — 신규 메서드만 추가 │
└──────────────────────────────────────────────────────────┘
```

```java
// JwtTokenService.java 내 신규 추가 예시 (패키지·키 로딩은 B2 실제 코드에 맞출 것)

// 🔴 기존 메서드 (수정 금지)
// public String issueToken(String loginId) { ... }

public record BiometricTokenResult(String accessToken, long expiresInSeconds) {}

public BiometricTokenResult issueTokenForBiometric(String deviceId, String userId) {
    // 기존과 동일 SecretKey·만료 정책 사용 (existingSecretKey 등은 기존 issueToken 과 공유)
    // Claims: subject = userId, custom claim device_id (팀 표준 키명으로 통일)
    java.time.Instant now = java.time.Instant.now();
    String accessToken = io.jsonwebtoken.Jwts.builder()
            .subject(userId)
            .claim("device_id", deviceId)
            .claim("user_id", userId)
            .issuedAt(java.util.Date.from(now))
            .expiration(java.util.Date.from(now.plusSeconds(1800)))
            .signWith(existingSecretKey)
            .compact();
    return new BiometricTokenResult(accessToken, 1800L);
}
```

> `BiometricTokenResult` 는 `public record BiometricTokenResult(String accessToken, long expiresInSeconds) {}` 를 **JwtTokenService.java 상단**에 두거나 `BiometricTokenResult.java` 로 분리한다.

> ⚠️ `existingSecretKey`·만료 초는 **기존 id/pw 발급과 동일 정책**으로 맞춘다.

---

## STEP 8. Spring Security 설정

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 없음 (기존 Security 설정 클래스 수정)       │
│ 수정 대상 파일: SecurityFilterChain 정의 클래스            │
│ 수정하지 않는 파일: 생체와 무관한 다른 matcher 로직        │
└──────────────────────────────────────────────────────────┘
```

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/biometric/**").permitAll()
    // ... 기존 permitAll / authenticated 규칙
    .anyRequest().authenticated())
```

> ⚠️ `SecurityFilterChain` Bean이 **여러 개**이면 `Order`·`securityMatcher` 충돌 가능 — **가능하면 단일 체인**에 matcher 추가.

---

## STEP 9. application.yml 설정

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 없음                                       │
│ 수정 대상 파일: application.yml (또는 profile별 yml)       │
│ 수정하지 않는 파일: 기존 datasource URL/계정 (그대로 사용) │
└──────────────────────────────────────────────────────────┘
```

```yaml
# 기존 spring.datasource.* 는 변경하지 않음 (B2 기존 Oracle 연결 재사용)

biometric:
  policy:
    max-retry-before-lockout: 3
    lockout-seconds: 30
    account-lock-threshold: 5
    fallback-password-enabled: true

mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true

# 🔴 운영: 스키마는 STEP 2 DDL·마이그레이션 도구로만 관리 (자동 DDL 생성에 의존하지 않음)
```

---

## STEP 10. B2 CASE별 응답 처리 명세

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


```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: (선택) .../BiometricApiExceptionHandler.java │
│ 수정 대상 파일: BiometricAuthService, MyBatisDeviceStoreImpl, DDL │
│ 수정하지 않는 파일: biometric-auth-lib 소스(DeviceStore IF) │
│ 🔴 기존 id/pw JWT·타 도메인 ControllerAdvice와 응답 키 충돌 시 통합 검토 │
└──────────────────────────────────────────────────────────┘
```

### 개요

- **Android 근거**: `biometric-android/biometric-lib/.../BiometricAuthManager.java` 주석의 **CASE 1~12** 정의, `AuthApiClient.java`의 HTTP 코드→예외 매핑.
- **B1 근거**: `biometric-auth-app` — `AuthController` (`/api/auth/challenge`, `/api/auth/token`), `ApiErrorBodies` (`Map.of("error", code)`), `GlobalExceptionHandler` (검증 오류 시 `error`·`field` 등).
- **lib 근거**: `VerificationResult`·`DeviceStatus`, `ChallengeService.validateSession` → `IllegalStateException("SESSION_EXPIRED")` (내부적으로 `EcdsaVerifier`가 `SESSION_EXPIRED` enum으로 치환), `FailurePolicyService.lockAccount` → `DeviceStore.updateStatus(LOCKED)` (`biometric-auth-lib-analysis.md`).

**원칙**: B2 엔드포인트 경로는 `/biometric/**`(이 문서)와 B1의 `/api/auth/**`·`/api/device/**`가 **다를 수 있다**. Android는 **기본적으로 B1 경로**를 호출하므로, **동일 HTTP 상태·JSON 키(`error`)·본문 코드 문자열**을 맞추려면 **API 게이트웨이 경로 rewrite** 또는 **앱 `baseUrl`/경로 설정 변경**이 필요하다. ⚠️ 경로만 바꾸고 상태·본문이 다르면 CASE 분기가 어긋난다.

**B1 검증 실패 응답**: `POST /api/auth/token` → HTTP **401**, body **`{"error":"SESSION_EXPIRED"}`** 등 (`VerificationResult.name()`). B2 `verify`도 **동일 스키마**를 권장한다. `ResponseStatusException`만 사용할 때 스프링 기본 본문은 B1과 다를 수 있으므로 💡 아래 **`@RestControllerAdvice`** 로 `{ "error": "<reason>" }` 를 강제한다.

### CASE 매핑표 (lib · B1 HTTP · Android 교차)

| CASE | 시나리오 | 발생 조건 | lib 결과값 | B1 HTTP | B1 응답(`error` 등) | B2 처리 방법 | Android 콜백 |
|------|----------|-----------|------------|---------|---------------------|--------------|----------------|
| 1 | 인증·토큰 성공 | `EcdsaVerifier.verify` → `SUCCESS` | `SUCCESS` | 200 | — (본문에 `access_token` 등) | `issueTokenForBiometric` 후 200·JWT | `AuthCallback.onSuccess` |
| 2 | 생체 프롬프트 실패(재시도 가능) | `BiometricPrompt.onAuthenticationFailed` — **서버 무관** | — | — | — | 해당 없음 | `onRetry(failureCount)` |
| 3 | 세션 만료 자동 재챌린지 | `POST /api/auth/token` → 401, `error=SESSION_EXPIRED` | `SESSION_EXPIRED` | 401 | `SESSION_EXPIRED` | 동일 응답 유지 | `onSessionRetrying` |
| 4 | 로컬 일시 잠금 | `FailurePolicyManager` 로컬 카운트 — **서버 무관** | — | — | — | 해당 없음 (앱 정책) | `onLockedOut(remainingSeconds)` |
| 5 | 서명 오류(임계 미만) | 401 `INVALID_SIGNATURE` — 연속 횟수가 앱 임계 미만 | `INVALID_SIGNATURE` | 401 | `INVALID_SIGNATURE` | B2도 401·동일 코드 ⚠️ 앱은 `onError(INVALID_SIGNATURE)` (javadoc상 5/6/8과 `onError` 공유) | `onError(INVALID_SIGNATURE)` |
| 6 | 자동 키 재발급 | 위와 동일 코드가 **연속 임계 이상** | `INVALID_SIGNATURE` | 401 | `INVALID_SIGNATURE` | 서버는 동일; 앱이 횟수 카운트 후 `KeyRenewalHandler` | `KeyRenewalHandler.renewAndRetry` |
| 7 | 기기 미등록 | `POST .../challenge` → 404 또는 로컬 미등록 | — | 404 | `DEVICE_NOT_FOUND` | `challenge`에서 `NOT_FOUND`·동일 바디 권장 | `onNotRegistered` |
| 8 | 기타 검증 실패 | `TIMESTAMP_OUT_OF_RANGE` / `NONCE_REPLAY` / `MISSING_SIGNATURE` | 각 enum | 401 | enum 이름과 동일 문자열 | `verify` 실패 분기에서 `result.name()` 유지 | `onError` (매핑된 `ErrorCode`) |
| 9 | 계정 잠금 | (A) `POST .../challenge` **423** `ACCOUNT_LOCKED` 또는 (B) 로컬 `accountLockThreshold` 후 `POST .../account-lock` | — | 423 / 200 | `ACCOUNT_LOCKED` / 잠금 API `status` | `challenge`·`renew-key`에서 `LOCKED`·잠금 API는 B1과 동일 계약 유지 ⚠️ B2 문서는 `/biometric/**` — 경로만 다를 수 있음 | `onAccountLocked` |
| 10 | 키 무효 | `challenge` **409** `KEY_INVALIDATED` 또는 Keystore `KeyPermanentlyInvalidatedException` | — | 409 | `KEY_INVALIDATED` | `challenge`에서 `CONFLICT`·동일 코드 | `onError(KEY_INVALIDATED)` |
| 11 | 세션 재시도 한계 | CASE 3 재시도 횟수 초과 후에도 실패 | `SESSION_EXPIRED` 등 | 401 | — | 서버는 개별 요청만 처리; 한계는 **클라이언트** | `onError(SESSION_EXPIRED)` |
| 12 | 사용자 변경 | `UserChangeHandler` / `BiometricBridge` — **서버 플로우는 앱 정의** | — | — | — | B2에서 별도 API 정책 시 문서화 💡 | (앱 전용) |

⚠️ **403 `USER_DEVICE_MISMATCH`**: B2 `verify`에서 사용 시, Android `AuthApiClient.requestToken`은 **401만** `TokenVerificationException`으로 파싱한다. **403은 `RuntimeException("HTTP 403: ...")`** 로 떨어져 CASE 매핑이 어긋날 수 있다. B1 `AuthController`는 해당 코드를 쓰지 않음. 💡 **401 + `USER_DEVICE_MISMATCH`** 로 맞추거나, 앱 클라이언트에 403 파싱을 추가한다.

⚠️ **B1 `GlobalExceptionHandler`**: 400 응답은 `{"error","field"}` 등으로 **`/api/auth/*` 컨트롤러의 401 본문과 형식이 다르다**. Validation 실패와 인증 실패를 구분해 처리한다.

### B2 공통 에러 응답 형식 (B1 `ApiErrorBodies` 정렬)

B1 성공이 아닌 비즈니스 오류(컨트롤러가 직접 반환):

```json
{ "error": "DEVICE_NOT_FOUND" }
```

검증 실패(B1 `GlobalExceptionHandler`):

```json
{ "error": "INVALID_DEVICE_ID", "field": "deviceId" }
```

💡 B2에서 `ResponseStatusException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND")` 등을 쓸 때 **본문을 B1과 같이** 주려면 전역 예외 처리기에서 `reason`을 `error` 값으로 넣는다.

```java
package com.mycompany.b2.biometric.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(assignableTypes = BiometricAuthController.class)
public class BiometricApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String code = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : "ERROR";
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
```

⚠️ `assignableTypes`를 쓰면 **해당 컨트롤러 패키지에만** 적용된다. B2 전역으로 통일하려면 `assignableTypes`를 제거하고 기존 `GlobalExceptionHandler`와 **중복 등록·응답 키 불일치**를 검토한다.

### CASE별 B2 구현 포인트

**CASE 1 — 인증 성공**  
발생 위치: `BiometricAuthService.verify()` 마지막.  
B2 처리: `myBatisDeviceStore.resetFailCount`, `jwtTokenService.issueTokenForBiometric`.  
반환: HTTP 200 + `VerifyResponse`(문서의 DTO).  
Android: `onSuccess`.

**CASE 2 / 4 — 로컬 전용**  
B2 처리: 없음.

**CASE 3 / 8 / 11 — `verify` 실패·세션 만료**  
발생 위치: `EcdsaVerifier.verify` 반환값 ≠ `SUCCESS`.  
B2 처리: `fail_count` 증가·임계 시 `lockAccount`·`resetFailCount`(STEP 6), `401` + `{"error":"<VerificationResult.name()>"}`.  
Android: `TokenVerificationException(error)` → CASE 3/8/11 분기.

**CASE 5 / 6 — INVALID_SIGNATURE**  
B2 처리: CASE 8과 동일 응답; **횟수 기반 키 갱신은 Android** (`INVALID_SIGNATURE_RENEWAL_THRESHOLD`).  
💡 서버만으로 CASE 6을 구현하려면 별도 정책 API가 필요하다.

**CASE 7 — 미등록**  
발생 위치: `challenge()`에서 `deviceStore.findByDeviceId` empty.  
반환: HTTP 404 + `{"error":"DEVICE_NOT_FOUND"}`.

**CASE 9 — 잠금**  
발생 위치: `challenge`/`renewKey`에서 `DeviceStatus.LOCKED`; 또는 Android가 `POST .../account-lock` 호출.  
B2 처리: HTTP 423(또는 B1과 동일 상태)·`ACCOUNT_LOCKED`; 잠금 API는 B1과 동일 JSON 권장.

**CASE 10**  
발생 위치: `challenge`에서 `KEY_INVALIDATED`.  
반환: HTTP 409 + `{"error":"KEY_INVALIDATED"}`.

**CASE 12**  
B2 처리: 조직 정책에 따른 별도 엔드포인트·문서화.

### fail_count 기반 잠금 흐름 (텍스트 시퀀스)

```text
[verify 요청]
    → userId 일치 검증 실패? → (정책에 따라) 즉시 403/401 — fail_count 정책은 선택
    → EcdsaVerifier.verify
        → SUCCESS → resetFailCount(deviceId) → JWT 발급
        → 실패 enum → incrementFailCount(deviceId) → n = 반환값
            → n >= maxRetryBeforeLockout?
                → YES: lockAccount(deviceId) → resetFailCount(deviceId) → 401 { error: enum 이름 }
                → NO: 401 { error: enum 이름 } (잠금 없음)
```

> ⚠️ `maxRetryBeforeLockout`은 **서버 DB `fail_count`** 와 짝을 이룬다. Android `FailurePolicyManager`의 로컬 잠금·`accountLockThreshold`와 **별개**이므로 운영 정책을 문서화한다.

---

## 알려진 이슈 및 대응

| 이슈                           | 수정 대상 파일             | 원인                | 대응 방법                                    |
| ---------------------------- | -------------------- | ----------------- | ---------------------------------------- |
| challenge 시 DEVICE_NOT_FOUND | —                    | 미등록 기기            | STEP 5 `POST /biometric/register` 선행     |
| verify 직후 항상 UNAUTHORIZED    | Android/서명 페이로드      | 페이로드 형식 불일치       | lib 규칙: `serverChallenge:clientNonce:deviceId:timestamp` (`EcdsaVerifier.buildPayload`) |
| USER_DEVICE_MISMATCH         | BiometricAuthService | 요청 userId ≠ DB    | 클라이언트·DB 데이터 정합; ⚠️ 403이면 Android 토큰 예외 매핑 깨짐 → STEP 10 |
| verify 실패 시 즉시 LOCKED       | BiometricAuthService / DDL | 매 요청마다 lockAccount | STEP 6·10: `fail_count` + `maxRetryBeforeLockout` |
| Nonce DELETE 안 됨             | MyBatisNonceStoreImpl / XML | 트랜잭션 밖에서 `deleteExpiredBefore` 호출 | `markUsed` 전체에 `@Transactional`                 |
| JWT Claims 누락                | JwtTokenService      | 신규 메서드 미구현        | STEP 7 `issueTokenForBiometric` 확인       |
| FilterChain 충돌               | Security 설정 클래스      | Bean 중복           | 단일 체인으로 통합                               |
| B1과 본문 불일치                | ExceptionHandler     | ResponseStatusException 기본 본문 | STEP 10 `BiometricApiExceptionHandler` |

---

## 완료 체크리스트

- [ ] STEP 1: `biometric-auth-lib` **jar·모듈·소스 복사** 중 조직 절차에 맞는 방식으로 반영
- [ ] `biometric-auth-lib` 의존성 추가 및 빌드 성공
- [ ] `mybatis-spring-boot-starter`·`spring-boot-starter-jdbc` 추가
- [ ] `@MapperScan("com.mycompany.b2.biometric.mapper")` (패키지는 실제에 맞게) 등록
- [ ] Oracle DDL 3종 실행 완료
- [ ] Mapper 인터페이스·XML·TypeHandler·`MyBatis*StoreImpl` 작성
- [ ] `BiometricNonceMapper.deleteExpiredBefore` + XML `DELETE ... WHERE used_at < ?` 동작 확인 (**전 테이블 select 금지**)
- [ ] `BiometricConfig` Bean 3종 등록
- [ ] `POST /biometric/register` → DB `biometric_device` INSERT 확인
- [ ] `POST /biometric/challenge` → sessionId·challenge 반환
- [ ] `POST /biometric/verify` → SUCCESS 시 JWT, Claims에 deviceId·userId
- [ ] verify 실패 시 401·`{"error":"<VerificationResult>"}` 확인; **임계 도달 시에만** `lockAccount`(STEP 10)
- [ ] `POST /biometric/renew-key` 동작 확인
- [ ] 🔴 기존 id/pw 로그인 JWT 발급 **회귀 테스트** (기존 메서드 본문 미변경)
- [ ] `/biometric/**` Security permitAll (또는 팀 보안 정책 반영)
- [ ] `biometric.policy.*` yml 로딩 확인
- [ ] `mybatis.mapper-locations` 및 운영 DB 마이그레이션(DDL) 반영
- [ ] `biometric_device.fail_count` 컬럼 추가 완료(신규 DDL 또는 ALTER)
- [ ] `MyBatisDeviceStoreImpl`·`BiometricDeviceMapper`에 `incrementFailCount` / `resetFailCount` / `selectFailCount` 추가
- [ ] `verify()` 실패 시 횟수 기반 잠금 동작 확인 (`max-retry-before-lockout` 값 반영)
- [ ] `verify()` 성공 시 `fail_count` 0 초기화 확인
- [ ] B2 `@RestControllerAdvice` 에러 응답 형식(B1 `{"error"}`) 통일 확인
- [ ] CASE별 Android 콜백 동작 E2E 확인

---

**문서 경로**: `biometric-auth-server/docs/B2_BIOMETRIC_INTEGRATION_GUIDE_v2.md`  
**참고**: MyBatis 구현 상세는 PoC `biometric-auth-app`(`MyBatisDeviceStoreImpl`, `mapper/*.xml`)을 우선 참고한다. v2는 **MyBatis·lib 확보 절차·fail_count·STEP 10 CASE**를 중심으로 정리하였다.
