# B2 생체인증 이식 가이드 (v2)

> **대상**: Spring Boot **3.5.4**, Oracle **19C**, Spring Boot 초급 개발자  
> **목적**: `biometric-auth-lib`(`com.biometric.poc.lib`)를 B2에 이식하고, **기존 id/pw JWT 발급 코드는 수정하지 않고** 생체 인증 API·영속 계층을 추가한다.

---

## 목차

1. [STEP 0. 이식 개요 및 구조](#step-0-이식-개요-및-구조)
2. [적용 순서](#적용-순서)
3. [STEP 1. 프로젝트 파일 배치 및 lib 의존성](#step-1-프로젝트-파일-배치-및-lib-의존성)
4. [STEP 2. 테이블 생성 (Oracle 19C DDL)](#step-2-테이블-생성-oracle-19c-ddl)
5. [STEP 3. Store 구현체 작성 (JPA)](#step-3-store-구현체-작성-jpa)
6. [STEP 4. Spring Bean 등록](#step-4-spring-bean-등록)
7. [STEP 5. 디바이스 등록 API 추가](#step-5-디바이스-등록-api-추가)
8. [STEP 6. 생체인증 API (Challenge / Verify / RenewKey)](#step-6-생체인증-api-challenge--verify--renewkey)
9. [STEP 7. 기존 JWT 서비스 확장](#step-7-기존-jwt-서비스-확장)
10. [STEP 8. Spring Security 설정](#step-8-spring-security-설정)
11. [STEP 9. application.yml 설정](#step-9-applicationyml-설정)
12. [알려진 이슈 및 대응](#알려진-이슈-및-대응)
13. [완료 체크리스트](#완료-체크리스트)

---

## 적용 순서

1. **STEP 0** — 개요·흐름 숙지, 🔴 기존 JWT 수정 금지 원칙 확인  
2. **STEP 1** — 패키지 트리에 맞춰 디렉터리 생성, `build.gradle`/`pom.xml`에 lib·JPA 의존성 추가  
3. **STEP 2** — Oracle에 `biometric_device` / `biometric_session` / `biometric_nonce` DDL 실행  
4. **STEP 3** — Converter·Entity·Repository·`Jpa*Store`·`ConfigurableFailurePolicyStore` 작성 (nonce 만료 삭제는 **@Query 전용**)  
5. **STEP 4** — `BiometricConfig`로 `ChallengeService`, `EcdsaVerifier`, `FailurePolicyService` Bean 등록  
6. **STEP 5** — `POST /biometric/register` 구현 (challenge 전제 조건)  
7. **STEP 6** — Challenge / Verify / RenewKey API·서비스 (Verify 시 **userId 일치 검증**·실패 시 **lockAccount**)  
8. **STEP 7** — `JwtTokenService`에 **`issueTokenForBiometric`만 추가**  
9. **STEP 8** — `/biometric/**` Security 설정  
10. **STEP 9** — `application.yml` `biometric.policy` 등  
11. **체크리스트** — 통합·회귀 검증  

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
| Store 구현 | `ConcurrentHashMap` / H2+MyBatis 예시 | **Oracle 19C + JPA** (또는 팀 표준 영속)   |
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
    → 실패 시 FailurePolicyService.lockAccount(deviceId)
    → 성공 시 JwtTokenService.issueTokenForBiometric(deviceId, userId)  ← 신규 메서드만
    ← JWT

(선택) → POST /biometric/renew-key
    → DeviceStore.renewKey(...)
```

> 🔴 **기존 JWT 발급 흐름(id/pw)은 절대 수정하지 않는다.** 생체 전용 메서드만 추가한다.

**lib 근거**: `EcdsaVerifier.verify(..., String userId, ...)` 는 **userId를 페이로드 검증에 사용하지 않음** (`biometric-auth-lib-analysis.md` 참고). 따라서 B2에서 **device의 userId와 요청 userId 일치**를 반드시 검증한다.

---

## STEP 1. 프로젝트 파일 배치 및 lib 의존성

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: 아래 ASCII 트리의 신규 Java/YML (STEP별)   │
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
│   │   ├── BiometricAuthController.java    ← STEP 5, 6에서 생성·확장
│   │   ├── BiometricAuthService.java       ← STEP 5, 6에서 생성·확장
│   │   └── dto/
│   │       └── BiometricDtos.java          ← STEP 5, 6에서 생성·확장
│   ├── config/
│   │   ├── BiometricConfig.java            ← STEP 4에서 생성
│   │   └── BiometricPolicyProperties.java  ← STEP 3~4에서 생성
│   └── persistence/
│       ├── converter/
│       │   └── BooleanNumberConverter.java   ← STEP 3에서 생성
│       ├── entity/
│       │   ├── BiometricDeviceEntity.java    ← STEP 3에서 생성
│       │   ├── BiometricSessionEntity.java   ← STEP 3에서 생성
│       │   └── BiometricNonceEntity.java     ← STEP 3에서 생성
│       ├── repository/
│       │   ├── BiometricDeviceRepository.java    ← STEP 3에서 생성
│       │   ├── BiometricSessionRepository.java   ← STEP 3에서 생성
│       │   └── BiometricNonceRepository.java     ← STEP 3에서 생성 (deleteExpired 포함)
│       ├── JpaDeviceStore.java               ← STEP 3에서 생성
│       ├── JpaSessionStore.java              ← STEP 3에서 생성
│       ├── JpaNonceStore.java                ← STEP 3에서 생성
│       └── ConfigurableFailurePolicyStore.java ← STEP 3에서 생성
└── security/
    └── JwtTokenService.java                  ← 기존 파일 — STEP 7에서 신규 메서드만 추가
```

### Gradle (Kotlin DSL) 예시

`b2-app/build.gradle.kts` (모듈명은 실제에 맞게 조정):

```kotlin
dependencies {
    implementation("com.biometric.poc:biometric-auth-lib:0.0.1-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

- lib는 **Spring에 의존하지 않음** → Spring Boot 3.5.4와 **직접적인 프레임워크 버전 충돌은 없음**.  
- 로컬 jar: `implementation(files("libs/biometric-auth-lib-0.0.1-SNAPSHOT.jar"))`

### Maven 예시

```xml
<dependency>
    <groupId>com.biometric.poc</groupId>
    <artifactId>biometric-auth-lib</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

> ⚠️ lib에 jjwt 의존성이 있어도 **main 소스에서 미사용**일 수 있음. 조직 정책에 따라 lib 정리 가능.

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
    req_timestamp      NUMBER(19)         NOT NULL, -- 요청 시각(epoch ms 등, JPA Long 과 일치)
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

---

## STEP 3. Store 구현체 작성 (JPA)

```text
┌──────────────────────────────────────────────────────────┐
│ 신규 생성 파일: converter/entity/repository/Jpa*Store 등 │
│ 수정 대상 파일: 없음                                     │
│ 수정하지 않는 파일: biometric-auth-lib 소스, 기존 JWT    │
└──────────────────────────────────────────────────────────┘
```

> ⚠️ **`JpaNonceStore.markUsed()` 안에서 `findAll()`로 전체 조회·삭제하면 운영에서 장애로 이어진다.** 반드시 **`BiometricNonceRepository.deleteExpiredBefore(...)` + `@Modifying @Query`** 만 사용한다.

### BooleanNumberConverter

```java
// 경로: com/mycompany/b2/biometric/persistence/converter/BooleanNumberConverter.java
// 한 줄 요약: 세션 used 플래그를 DB NUMBER(1)와 매핑한다.
package com.mycompany.b2.biometric.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class BooleanNumberConverter implements AttributeConverter<Boolean, Integer> {
    @Override
    public Integer convertToDatabaseColumn(Boolean a) {
        return Boolean.TRUE.equals(a) ? 1 : 0;
    }
    @Override
    public Boolean convertToEntityAttribute(Integer db) {
        return db != null && db != 0;
    }
}
```

### BiometricDeviceEntity

```java
// 경로: com/mycompany/b2/biometric/persistence/entity/BiometricDeviceEntity.java
// 한 줄 요약: biometric_device 테이블 한 행을 나타낸다.
package com.mycompany.b2.biometric.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "biometric_device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BiometricDeviceEntity {
    @Id
    @Column(name = "device_id", length = 100, nullable = false)
    private String deviceId;
    @Column(name = "user_id", length = 100, nullable = false)
    private String userId;
    @Column(name = "public_key_b64", length = 4000)
    private String publicKeyB64;
    @Column(name = "status", length = 30, nullable = false)
    private String status;
    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### BiometricDeviceRepository

```java
// 경로: com/mycompany/b2/biometric/persistence/repository/BiometricDeviceRepository.java
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricDeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiometricDeviceRepository extends JpaRepository<BiometricDeviceEntity, String> {
    boolean existsByDeviceId(String deviceId);
}
```

### JpaDeviceStore

```java
// 경로: com/mycompany/b2/biometric/persistence/JpaDeviceStore.java
// 한 줄 요약: lib DeviceStore — 기기 CRUD·상태·키 갱신을 JPA로 수행한다.
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.persistence.entity.BiometricDeviceEntity;
import com.mycompany.b2.biometric.persistence.repository.BiometricDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaDeviceStore implements DeviceStore {

    private final BiometricDeviceRepository repo;

    @Override @Transactional
    public void save(DeviceInfo d) {
        repo.save(toEntity(d));
    }

    @Override @Transactional(readOnly = true)
    public Optional<DeviceInfo> findByDeviceId(String deviceId) {
        return repo.findById(deviceId).map(this::toDto);
    }

    @Override @Transactional(readOnly = true)
    public boolean existsByDeviceId(String deviceId) {
        return repo.existsByDeviceId(deviceId);
    }

    @Override @Transactional
    public void updateStatus(String deviceId, DeviceStatus status) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setStatus(status.name());
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override @Transactional
    public void invalidateKey(String deviceId) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(null);
            e.setStatus(DeviceStatus.KEY_INVALIDATED.name());
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override @Transactional
    public void updatePublicKey(String deviceId, String publicKeyBase64) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(publicKeyBase64);
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override @Transactional
    public void reRegister(DeviceInfo d) {
        save(d);
    }

    @Override @Transactional
    public void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(newPublicKeyBase64);
            e.setStatus(DeviceStatus.ACTIVE.name());
            e.setUpdatedAt(updatedAt);
        });
    }

    @Override @Transactional
    public void delete(String deviceId) {
        repo.deleteById(deviceId);
    }

    private BiometricDeviceEntity toEntity(DeviceInfo d) {
        return BiometricDeviceEntity.builder()
                .deviceId(d.getDeviceId())
                .userId(d.getUserId())
                .publicKeyB64(d.getPublicKeyBase64())
                .status(d.getStatus().name())
                .enrolledAt(d.getEnrolledAt())
                .updatedAt(d.getUpdatedAt() != null ? d.getUpdatedAt() : Instant.now())
                .build();
    }

    private DeviceInfo toDto(BiometricDeviceEntity e) {
        return DeviceInfo.builder()
                .deviceId(e.getDeviceId())
                .userId(e.getUserId())
                .publicKeyBase64(e.getPublicKeyB64())
                .status(DeviceStatus.valueOf(e.getStatus()))
                .enrolledAt(e.getEnrolledAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
```

> ⚠️ FK 때문에 `delete` 전에 session·nonce 삭제 순서가 필요할 수 있다.

### BiometricSessionEntity

```java
// 경로: com/mycompany/b2/biometric/persistence/entity/BiometricSessionEntity.java
// 한 줄 요약: 챌린지 세션 한 건을 저장한다.
package com.mycompany.b2.biometric.persistence.entity;

import com.mycompany.b2.biometric.persistence.converter.BooleanNumberConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "biometric_session")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BiometricSessionEntity {
    @Id
    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;
    @Column(name = "device_id", length = 100, nullable = false)
    private String deviceId;
    @Column(name = "user_id", length = 100, nullable = false)
    private String userId;
    @Column(name = "server_challenge", length = 64, nullable = false)
    private String serverChallenge;
    @Column(name = "client_nonce", length = 64, nullable = false)
    private String clientNonce;
    @Column(name = "req_timestamp", nullable = false)
    private Long reqTimestamp;
    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;
    @Convert(converter = BooleanNumberConverter.class)
    @Column(name = "used", nullable = false)
    private Boolean used;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

### BiometricSessionRepository

```java
// 경로: com/mycompany/b2/biometric/persistence/repository/BiometricSessionRepository.java
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiometricSessionRepository extends JpaRepository<BiometricSessionEntity, String> {
}
```

### JpaSessionStore

```java
// 경로: com/mycompany/b2/biometric/persistence/JpaSessionStore.java
// 한 줄 요약: lib SessionStore — 만료·used 세션은 조회 시 empty 처리한다.
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.SessionStore;
import com.mycompany.b2.biometric.persistence.entity.BiometricSessionEntity;
import com.mycompany.b2.biometric.persistence.repository.BiometricSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaSessionStore implements SessionStore {

    private final BiometricSessionRepository repo;

    @Override @Transactional
    public void save(SessionData s) {
        repo.save(BiometricSessionEntity.builder()
                .sessionId(s.getSessionId())
                .deviceId(s.getDeviceId())
                .userId(s.getUserId())
                .serverChallenge(s.getServerChallengeHex())
                .clientNonce(s.getClientNonce())
                .reqTimestamp(s.getTimestamp())
                .expireAt(s.getExpireAt())
                .used(s.isUsed())
                .createdAt(s.getCreatedAt() != null ? s.getCreatedAt() : Instant.now())
                .build());
    }

    @Override @Transactional(readOnly = true)
    public Optional<SessionData> findBySessionId(String sessionId) {
        return repo.findById(sessionId).flatMap(this::toValidSession);
    }

    @Override @Transactional
    public void markUsed(String sessionId) {
        repo.findById(sessionId).ifPresent(e -> e.setUsed(true));
    }

    private Optional<SessionData> toValidSession(BiometricSessionEntity e) {
        if (Boolean.TRUE.equals(e.getUsed())) return Optional.empty();
        if (e.getExpireAt() != null && e.getExpireAt().isBefore(Instant.now())) return Optional.empty();
        return Optional.of(SessionData.builder()
                .sessionId(e.getSessionId())
                .deviceId(e.getDeviceId())
                .userId(e.getUserId())
                .serverChallengeHex(e.getServerChallenge())
                .clientNonce(e.getClientNonce())
                .timestamp(e.getReqTimestamp())
                .expireAt(e.getExpireAt())
                .used(e.getUsed())
                .createdAt(e.getCreatedAt())
                .build());
    }
}
```

### BiometricNonceEntity

```java
// 경로: com/mycompany/b2/biometric/persistence/entity/BiometricNonceEntity.java
// 한 줄 요약: 사용된 client_nonce 한 건을 저장한다.
package com.mycompany.b2.biometric.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "biometric_nonce")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BiometricNonceEntity {
    @Id
    @Column(name = "nonce", length = 64, nullable = false)
    private String nonce;
    @Column(name = "device_id", length = 100, nullable = false)
    private String deviceId;
    @Column(name = "used_at", nullable = false)
    private Instant usedAt;
}
```

### BiometricNonceRepository / JpaNonceStore

**Repository — 만료 삭제 쿼리 필수** (`findAll()` 사용 금지)

```java
// 경로: com/mycompany/b2/biometric/persistence/repository/BiometricNonceRepository.java
// 한 줄 요약: nonce 저장 및 used_at 기준 배치 삭제 쿼리를 제공한다.
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricNonceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BiometricNonceRepository extends JpaRepository<BiometricNonceEntity, String> {

    @Modifying(clearAutomatically = true)
    @Query("delete from BiometricNonceEntity n where n.usedAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
```

**JpaNonceStore**

```java
// 경로: com/mycompany/b2/biometric/persistence/JpaNonceStore.java
// 한 줄 요약: lib NonceStore 구현 — insert 후 만료 행만 단일 DELETE로 정리한다.
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.auth.AuthConstants;
import com.biometric.poc.lib.store.NonceStore;
import com.mycompany.b2.biometric.persistence.entity.BiometricNonceEntity;
import com.mycompany.b2.biometric.persistence.repository.BiometricNonceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class JpaNonceStore implements NonceStore {

    private final BiometricNonceRepository repo;

    @Override
    @Transactional(readOnly = true)
    public boolean isUsed(String nonce) {
        return repo.existsById(nonce);
    }

    @Override
    @Transactional
    public void markUsed(String nonce, String deviceId) {
        Instant now = Instant.now();
        repo.save(BiometricNonceEntity.builder()
                .nonce(nonce)
                .deviceId(deviceId)
                .usedAt(now)
                .build());
        Instant cutoff = now.minus(AuthConstants.NONCE_TTL_MINUTES, ChronoUnit.MINUTES);
        repo.deleteExpiredBefore(cutoff);
    }
}
```

> ⚠️ `@Modifying` 쿼리는 **트랜잭션 안**에서 호출해야 한다. 위처럼 `markUsed` 전체에 `@Transactional` 적용.

### BiometricPolicyProperties

```java
// 경로: com/mycompany/b2/biometric/config/BiometricPolicyProperties.java
// 한 줄 요약: application.yml 의 biometric.policy.* 를 바인딩한다.
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

### ConfigurableFailurePolicyStore

```java
// 경로: com/mycompany/b2/biometric/persistence/ConfigurableFailurePolicyStore.java
// 한 줄 요약: lib FailurePolicyStore — deviceId별 DB 정책 없이 yml 기본값을 반환한다.
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
│ 수정 대상 파일: (선택) 메인 애플리케이션에 @EnableJpaRepositories │
│ 수정하지 않는 파일: Jpa*Store 등 @Component 구현체 본문    │
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

> ⚠️ **이중 등록**: `JpaDeviceStore` 등에 `@Component`를 달았다면, 위 Store 인터페이스에 **별도 `@Bean`으로 구현체를 또 등록하지 않는다.** 한 가지 방식만 택한다.

`@EnableConfigurationProperties(BiometricPolicyProperties.class)` 는 **`BiometricConfig`에 두는 것이 일반적**이다(메인 클래스에 중복 부여하지 않는다).

> 💡 `@EnableJpaRepositories`: `@SpringBootApplication` 이 `com.mycompany.b2` 이고 Repository 가 그 하위 패키지(`...biometric.persistence.repository`)에 있으면 **기본 컴포넌트 스캔으로 등록**되어 별도 어노테이션이 없어도 된다. 메인 패키지와 **분리**되어 스캔 밖이면 메인 클래스 등에 `@EnableJpaRepositories(basePackages = "com.mycompany.b2.biometric.persistence.repository")` 를 추가한다.

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
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.policy.FailurePolicyService;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.api.dto.BiometricDtos.*;
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

        // 4) 실패 시 계정 잠금 후 예외
        if (result != VerificationResult.SUCCESS) {
            // yml의 maxRetryBeforeLockout에 도달했을 때만 잠금
            FailurePolicyConfig policy = failurePolicyService.getPolicy(req.deviceId());
            // 실패 횟수 카운팅 로직 필요 (별도 컬럼 또는 캐시)
            // 횟수 >= maxRetryBeforeLockout 시에만 lockAccount() 호출
            failurePolicyService.lockAccount(req.deviceId());
            throw new ResponseStatusException(...);
        }

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

> 💡 `FailurePolicyService.lockAccount` 는 lib에서 `deviceStore.updateStatus(..., LOCKED)` 로 구현됨.

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
# 기존 spring.datasource.* 는 변경하지 않음

biometric:
  policy:
    max-retry-before-lockout: 3
    lockout-seconds: 30
    account-lock-threshold: 5
    fallback-password-enabled: true

spring:
  jpa:
    hibernate:
      ddl-auto: none   # 🔴 운영: DDL은 STEP 2 스크립트로만 관리
```

---

## 알려진 이슈 및 대응

| 이슈                           | 수정 대상 파일             | 원인                | 대응 방법                                    |
| ---------------------------- | -------------------- | ----------------- | ---------------------------------------- |
| challenge 시 DEVICE_NOT_FOUND | —                    | 미등록 기기            | STEP 5 `POST /biometric/register` 선행     |
| verify 직후 항상 UNAUTHORIZED    | Android/서명 페이로드      | 페이로드 형식 불일치       | lib 규칙: `serverChallenge:clientNonce:deviceId:timestamp` (`EcdsaVerifier.buildPayload`) |
| USER_DEVICE_MISMATCH         | BiometricAuthService | 요청 userId ≠ DB    | 클라이언트·DB 데이터 정합                          |
| verify 실패 시 곧바로 LOCKED       | BiometricAuthService | 정책상 매 실패 잠금       | 잠금 조건을 횟수 기반으로 바꾸려면 FailurePolicyService 활용 확장 💡 |
| Nonce DELETE 안 됨             | JpaNonceStore        | @Modifying 트랜잭션 밖 | 호출 메서드에 `@Transactional`                 |
| JWT Claims 누락                | JwtTokenService      | 신규 메서드 미구현        | STEP 7 `issueTokenForBiometric` 확인       |
| FilterChain 충돌               | Security 설정 클래스      | Bean 중복           | 단일 체인으로 통합                               |

---

## 완료 체크리스트

- [ ] `biometric-auth-lib` 의존성 추가 및 빌드 성공
- [ ] `spring-boot-starter-data-jpa` 추가
- [ ] Oracle DDL 3종 실행 완료
- [ ] `BooleanNumberConverter`·Entity·Repository·`Jpa*Store` 작성
- [ ] `BiometricNonceRepository.deleteExpiredBefore` + `@Modifying @Query` 동작 확인 (**findAll 금지**)
- [ ] `BiometricConfig` Bean 3종 등록
- [ ] `POST /biometric/register` → DB `biometric_device` INSERT 확인
- [ ] `POST /biometric/challenge` → sessionId·challenge 반환
- [ ] `POST /biometric/verify` → SUCCESS 시 JWT, Claims에 deviceId·userId
- [ ] verify 실패 시 `lockAccount`·401 응답 확인 (정책 검토)
- [ ] `POST /biometric/renew-key` 동작 확인
- [ ] 🔴 기존 id/pw 로그인 JWT 발급 **회귀 테스트** (기존 메서드 본문 미변경)
- [ ] `/biometric/**` Security permitAll (또는 팀 보안 정책 반영)
- [ ] `biometric.policy.*` yml 로딩 확인
- [ ] `spring.jpa.hibernate.ddl-auto: none` 운영 반영

---

**문서 경로**: `biometric-auth-server/docs/B2_BIOMETRIC_INTEGRATION_GUIDE_v2.md`  
**참고**: 상세 JPA 엔티티·`JpaDeviceStore`/`JpaSessionStore` 전문은 `B2_BIOMETRIC_INTEGRATION_GUIDE.md`(v1)와 동일하며, v2는 **형식·STEP 5·Verify 보강·Nonce 쿼리**를 중심으로 정리하였다.
