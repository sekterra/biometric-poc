# B2 생체인증(biometric-auth-lib) 이식 실무 가이드

> **대상**: Spring Boot **3.5.4**, Oracle **19C**, 독자는 Spring Boot 초급  
> **목적**: `biometric-auth-lib`를 B2에 붙이고, **기존 id/pw JWT 발급 로직은 건드리지 않은 채** 생체 인증 후 JWT를 추가로 발급한다.

---

## 0. 개요

### 0.1 이 문서의 범위

| 포함 | 제외 |
|------|------|
| lib 의존성 추가, Oracle DDL, Store JPA 구현, Bean 등록 | Android 앱 상세 |
| 생체 전용 REST 3종 (`/biometric/*`) 예시 | 기존 id/pw 로그인 **코드 변경** |
| 기존 JWT 서비스에 **신규 메서드 추가** 가이드 | HSM·외부 IdP 연동 |

### 0.2 최종 인증 흐름 비교

#### 기존: id / pw 로그인 (변경 없음)

```text
[클라이언트]
    → POST /login (예시) id, password
[ B2 기존 인증 ]
    → 검증 성공
[ B2 기존 JwtTokenService ]
    → JWT 발급 (기존 Claims 그대로)
    ← accessToken 등 반환
```

#### 신규: 생체 인증 로그인

```text
[Android 앱]
    → POST /biometric/challenge
        body: deviceId, userId, clientNonce, timestamp
[ B2 BiometricAuthService + ChallengeService(lib) ]
    → 세션·챌린지 생성 → SessionStore(Oracle) 저장
    ← sessionId, serverChallenge, expireAt

[Android 앱] ECDSA 서명 생성

    → POST /biometric/verify
        body: sessionId, deviceId, userId, ecSignatureBase64, clientNonce, timestamp
[ B2 BiometricAuthService + EcdsaVerifier(lib) ]
    → 검증 SUCCESS 시
[ B2 기존 JwtTokenService — 신규 메서드만 호출 ]  ← deviceId, userId Claims 포함 JWT
    ← accessToken(·refresh 등 B2 정책에 따름)

(선택) 키 갱신
    → POST /biometric/renew-key
        body: deviceId, newPublicKeyBase64
[ DeviceStore(lib 인터페이스) Oracle 구현 ]
    ← 갱신 결과
```

> ⚠️ **디바이스 등록**(공개키 최초 저장) API는 이 문서의 3개 엔드포인트에 포함하지 않았다. 운영에서는 등록 API를 별도로 두거나, 기존 배치/관리 화면에서 `biometric_device`에 행을 넣는 절차가 먼저 있어야 `/biometric/challenge`가 성공한다.

---

## 1. 사전 준비 — lib jar 추가

**이 단계에서 할 일**: B2 빌드에 `biometric-auth-lib`를 의존성으로 넣는다.

### 1.1 Gradle (Kotlin DSL) 예시

`build.gradle.kts` (B2 애플리케이션 모듈):

```kotlin
dependencies {
    // 로컬 멀티모듈에서 복사한 jar 또는 Maven 게시 후 좌표 사용
    implementation("com.biometric.poc:biometric-auth-lib:0.0.1-SNAPSHOT")

    // 이 가이드의 Store 구현은 JPA 기준이므로 (B2에 없다면) 추가
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // lib는 Spring 미사용 — Spring Boot 3.5.4 와 직접적인 버전 충돌은 없음
    // Lombok은 B2와 동일 버전 맞추기 권장
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

로컬 파일로 넣는 경우:

```kotlin
dependencies {
    implementation(files("libs/biometric-auth-lib-0.0.1-SNAPSHOT.jar"))
}
```

### 1.2 Maven 예시

```xml
<dependency>
    <groupId>com.biometric.poc</groupId>
    <artifactId>biometric-auth-lib</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

> ⚠️ lib `build.gradle.kts`에 **jjwt**가 들어 있으나 **소스 미사용**일 수 있다. 조직 정책에 따라 lib를 정리해 재배포하거나, 그대로 두어도 런타임 충돌 가능성은 낮다.

---

## 2. DDL — 테이블 생성 (Oracle 19C)

**이 단계에서 할 일**: DBA 또는 스키마 계정으로 아래 스크립트를 실행한다.

> 🔶 **Oracle 호환**: 예시는 `TIMESTAMP(6)` (DB 세션 타임존 기준). 글로벌 서비스면 `TIMESTAMP(6) WITH TIME ZONE` + 애플리케이션에서 `Instant` 일관 사용을 권장한다.  
> 🔶 `USED` 플래그는 예시에서 `NUMBER(1)` (0/1). `CHAR(1) 'Y'/'N'`도 가능하나 JPA 매핑 시 `AttributeConverter`가 필요하다.

```sql
-- ============================================================
-- 1) 디바이스
-- ============================================================
CREATE TABLE biometric_device (
    device_id        VARCHAR2(100 CHAR) NOT NULL,
    user_id          VARCHAR2(100 CHAR) NOT NULL,
    public_key_b64   VARCHAR2(4000 CHAR),  -- 키 길이에 따라 CLOB 검토
    status           VARCHAR2(30 CHAR)   NOT NULL,
    enrolled_at      TIMESTAMP(6)         NOT NULL,
    updated_at       TIMESTAMP(6)         NOT NULL,
    CONSTRAINT pk_biometric_device PRIMARY KEY (device_id),
    CONSTRAINT chk_biometric_device_status CHECK (status IN ('ACTIVE', 'LOCKED', 'KEY_INVALIDATED'))
);

CREATE INDEX idx_biometric_device_user ON biometric_device (user_id);

COMMENT ON TABLE biometric_device IS '생체인증 디바이스';

-- ============================================================
-- 2) 세션 (챌린지)
-- ============================================================
CREATE TABLE biometric_session (
    session_id         VARCHAR2(64 CHAR)  NOT NULL,
    device_id          VARCHAR2(100 CHAR) NOT NULL,
    user_id            VARCHAR2(100 CHAR) NOT NULL,
    server_challenge   VARCHAR2(64 CHAR)  NOT NULL,
    client_nonce       VARCHAR2(64 CHAR)  NOT NULL,
    req_timestamp      NUMBER(19)         NOT NULL,
    expire_at          TIMESTAMP(6)       NOT NULL,
    used               NUMBER(1)          DEFAULT 0 NOT NULL,
    created_at         TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_biometric_session PRIMARY KEY (session_id),
    CONSTRAINT fk_biometric_session_device
        FOREIGN KEY (device_id) REFERENCES biometric_device (device_id)
);

CREATE INDEX idx_biometric_session_expire ON biometric_session (expire_at);

-- ============================================================
-- 3) Nonce (재전송 방지)
-- ============================================================
CREATE TABLE biometric_nonce (
    nonce      VARCHAR2(64 CHAR)  NOT NULL,
    device_id  VARCHAR2(100 CHAR) NOT NULL,
    used_at    TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_biometric_nonce PRIMARY KEY (nonce)
);

CREATE INDEX idx_biometric_nonce_used_at ON biometric_nonce (used_at);
```

> 💡 만료 nonce 정리는 **스케줄러**로 `used_at` 기준 5분 이전 행 삭제 등으로 구현한다 (lib의 `AuthConstants.NONCE_TTL_MINUTES`와 맞출 것).

---

## 3. Store 인터페이스 — Oracle JPA 구현체

**이 단계에서 할 일**: 패키지 예시 `com.mycompany.b2.biometric.persistence` 아래에 Entity·Repository·Store 구현을 둔다.

> 패키지명은 B2 표준에 맞게 바꾼다. 아래 코드는 **구조 복사 후 엔티티 테이블명·컬럼명**만 프로젝트 규칙에 맞게 조정하면 된다.

### 3.1 공통: 엔티티 예시 (요약 필드)

- `BiometricDeviceEntity` → `biometric_device`
- `BiometricSessionEntity` → `biometric_session`
- `BiometricNonceEntity` → `biometric_nonce`

`used` 컬럼은 `boolean` ↔ `NUMBER(1)` 변환용 Converter를 둔다.

```java
// 파일: com/mycompany/b2/biometric/persistence/converter/BooleanNumberConverter.java
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

### 3.2 `BiometricDeviceEntity` + `BiometricDeviceRepository`

```java
// 파일: .../persistence/entity/BiometricDeviceEntity.java
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

```java
// 파일: .../persistence/repository/BiometricDeviceRepository.java
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricDeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiometricDeviceRepository extends JpaRepository<BiometricDeviceEntity, String> {
    boolean existsByDeviceId(String deviceId);
}
```

### 3.3 `JpaDeviceStore` — `com.biometric.poc.lib.store.DeviceStore` 구현

```java
// 파일: .../persistence/JpaDeviceStore.java
// 한 줄 요약: lib의 DeviceStore를 JPA로 구현해 기기 등록·상태·키 갱신을 DB에 반영한다.
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

    @Override
    @Transactional
    public void save(DeviceInfo d) {
        repo.save(toEntity(d));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceInfo> findByDeviceId(String deviceId) {
        return repo.findById(deviceId).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByDeviceId(String deviceId) {
        return repo.existsByDeviceId(deviceId);
    }

    @Override
    @Transactional
    public void updateStatus(String deviceId, DeviceStatus status) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setStatus(status.name());
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override
    @Transactional
    public void invalidateKey(String deviceId) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(null);
            e.setStatus(DeviceStatus.KEY_INVALIDATED.name());
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override
    @Transactional
    public void updatePublicKey(String deviceId, String publicKeyBase64) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(publicKeyBase64);
            e.setUpdatedAt(Instant.now());
        });
    }

    @Override
    @Transactional
    public void reRegister(DeviceInfo d) {
        save(d); // 동일 PK면 update 성격 — 필요 시 merge 정책 조정
    }

    @Override
    @Transactional
    public void renewKey(String deviceId, String newPublicKeyBase64, Instant updatedAt) {
        repo.findById(deviceId).ifPresent(e -> {
            e.setPublicKeyB64(newPublicKeyBase64);
            e.setStatus(DeviceStatus.ACTIVE.name());
            e.setUpdatedAt(updatedAt);
        });
    }

    @Override
    @Transactional
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

> ⚠️ `delete(deviceId)` 호출 전에 **FK** 때문에 `biometric_session`·`biometric_nonce`를 먼저 지워야 할 수 있다. 사용자 변경·삭제 플로우가 있으면 **삭제 순서**를 서비스 레이어에서 보장한다.

### 3.4 세션 엔티티·Repository·`JpaSessionStore`

```java
// 파일: .../entity/BiometricSessionEntity.java
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

```java
// 파일: .../repository/BiometricSessionRepository.java
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiometricSessionRepository extends JpaRepository<BiometricSessionEntity, String> {
}
```

```java
// 파일: .../persistence/JpaSessionStore.java
// 한 줄 요약: lib SessionStore — 만료·사용된 세션은 find 시 비어 있게 반환한다.
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

    @Override
    @Transactional
    public void save(SessionData s) {
        BiometricSessionEntity e = BiometricSessionEntity.builder()
                .sessionId(s.getSessionId())
                .deviceId(s.getDeviceId())
                .userId(s.getUserId())
                .serverChallenge(s.getServerChallengeHex())
                .clientNonce(s.getClientNonce())
                .reqTimestamp(s.getTimestamp())
                .expireAt(s.getExpireAt())
                .used(s.isUsed())
                .createdAt(s.getCreatedAt() != null ? s.getCreatedAt() : Instant.now())
                .build();
        repo.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionData> findBySessionId(String sessionId) {
        return repo.findById(sessionId).flatMap(this::toValidSession);
    }

    @Override
    @Transactional
    public void markUsed(String sessionId) {
        repo.findById(sessionId).ifPresent(e -> e.setUsed(true));
    }

    private Optional<SessionData> toValidSession(BiometricSessionEntity e) {
        if (Boolean.TRUE.equals(e.getUsed())) {
            return Optional.empty();
        }
        if (e.getExpireAt() != null && e.getExpireAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        SessionData s = SessionData.builder()
                .sessionId(e.getSessionId())
                .deviceId(e.getDeviceId())
                .userId(e.getUserId())
                .serverChallengeHex(e.getServerChallenge())
                .clientNonce(e.getClientNonce())
                .timestamp(e.getReqTimestamp())
                .expireAt(e.getExpireAt())
                .used(e.getUsed())
                .createdAt(e.getCreatedAt())
                .build();
        return Optional.of(s);
    }
}
```

### 3.5 Nonce 엔티티·`JpaNonceStore`

```java
// 파일: .../entity/BiometricNonceEntity.java
// 한 줄 요약: 이미 사용된 client_nonce 기록 (재전송 방지).
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

```java
// 파일: .../repository/BiometricNonceRepository.java
package com.mycompany.b2.biometric.persistence.repository;

import com.mycompany.b2.biometric.persistence.entity.BiometricNonceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiometricNonceRepository extends JpaRepository<BiometricNonceEntity, String> {
}
```

```java
// 파일: .../persistence/JpaNonceStore.java
// 한 줄 요약: lib NonceStore — nonce PK 중복 시 이미 사용된 것으로 본다.
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
        repo.deleteAllInBatch(
                repo.findAll().stream()
                        .filter(e -> e.getUsedAt().isBefore(cutoff))
                        .toList());
        // ↑ 단순 예시 — 운영에서는 @Query DELETE 로 배치 삭제 권장
    }
}
```

> 💡 `markUsed` 내 전체 조회는 **PoC 수준**. 운영에서는 `DELETE FROM biometric_nonce WHERE used_at < :cutoff` 같은 **단일 쿼리**로 바꾼다.

### 3.6 `ConfigurableFailurePolicyStore` — `FailurePolicyStore` + yml 주입

```java
// 파일: .../persistence/ConfigurableFailurePolicyStore.java
// 한 줄 요약: deviceId별 DB 정책이 없을 때 application.yml의 기본 실패 정책을 돌려준다.
package com.mycompany.b2.biometric.persistence;

import com.biometric.poc.lib.model.FailurePolicyConfig;
import com.biometric.poc.lib.store.FailurePolicyStore;
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

```java
// 파일: .../config/BiometricPolicyProperties.java
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

`@EnableConfigurationProperties(BiometricPolicyProperties.class)` 를 `BiometricConfig`에 추가한다.

---

## 4. Spring Bean 등록

**이 단계에서 할 일**: lib의 `ChallengeService`, `EcdsaVerifier`, `FailurePolicyService`를 조립한다.  
**3번에서 만든** `JpaDeviceStore`, `JpaSessionStore`, `JpaNonceStore`, `ConfigurableFailurePolicyStore`는 **Spring이 `@Component`로 이미 빈 등록**했다고 가정한다. 아래는 **명시적 `@Bean`** 패턴 예시다.

```java
// 파일: com/mycompany/b2/biometric/config/BiometricConfig.java
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

> ⚠️ `JpaDeviceStore` 등을 `@Component`와 동시에 `@Bean`에서 `new` 하면 **이중 빈**이 된다. **한 가지만** 선택한다: 전부 `@Component` + 위 `@Bean` 3개만 두거나, Store는 `@Bean` 메서드로만 등록한다.

---

## 5. 생체인증 API — Controller + Service

**이 단계에서 할 일**: REST 3개와 DTO, 서비스 로직을 추가한다.

### 5.1 DTO (record 예시)

> ⚠️ `@Valid`를 쓰려면 각 필드에 `jakarta.validation.constraints.*` (`@NotBlank` 등)를 record 컴포넌트에 다는 것이 안전하다.

```java
// 파일: com/mycompany/b2/biometric/api/dto/BiometricDtos.java (분리해도 됨)
package com.mycompany.b2.biometric.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class BiometricDtos {

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

### 5.2 Service

```java
// 파일: com/mycompany/b2/biometric/api/BiometricAuthService.java
// 한 줄 요약: 챌린지 발급, ECDSA 검증 후 JWT 위임, 키 갱신을 orchestration 한다.
package com.mycompany.b2.biometric.api;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.ecdsa.VerificationResult;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.DeviceStore;
import com.mycompany.b2.biometric.api.dto.BiometricDtos.*;
import com.mycompany.b2.security.JwtTokenService; // 🔴 B2 실제 패키지/클래스명으로 교체
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BiometricAuthService {

    private final ChallengeService challengeService;
    private final EcdsaVerifier ecdsaVerifier;
    private final DeviceStore deviceStore;
    private final JwtTokenService jwtTokenService;

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
        VerificationResult result = ecdsaVerifier.verify(
                req.sessionId(),
                req.deviceId(),
                req.userId(),
                req.ecSignatureBase64(),
                req.clientNonce(),
                req.timestamp());

        if (result != VerificationResult.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, result.name());
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

위 예시의 `JwtTokenService.issueTokenForBiometric` 반환 타입은 **6절에서 정의한 DTO**로 맞춘다.

### 5.3 Controller

```java
// 파일: com/mycompany/b2/biometric/api/BiometricAuthController.java
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

---

## 6. 기존 JWT 서비스 확장 방법

> 🔴 **기존 id/pw 로그인에서 호출하는 JWT 발급 메서드 본문은 수정하지 않는다.**  
> 생체 전용 API에서만 사용할 **새 메서드**를 추가한다.

### 6.1 원칙

| 구분 | 조치 |
|------|------|
| 기존 | `issueToken(String loginId)` 등 — **그대로 유지** |
| 신규 | `issueTokenForBiometric(String deviceId, String userId)` (이름은 팀 규칙에 맞게) |

### 6.2 예시 (JJWT / 팀 표준에 맞게 조정)

```java
// 파일: B2 프로젝트의 기존 JwtTokenService.java (신규 메서드만 추가)
// 한 줄 요약: 생체 인증 성공 시에만 호출되며, Claims에 deviceId·userId를 넣는다.

// 기존 메서드 (수정 금지)
// public String issueToken(String loginId) { ... }

public BiometricTokenResult issueTokenForBiometric(String deviceId, String userId) {
    // 기존과 동일한 서명 키·만료 정책을 사용하되, subject는 userId, 커스텀 클레임 추가
    // 예: .claim("device_id", deviceId).claim("user_id", userId) 또는 팀 표준 claim 키명
    String accessToken = "... jjwt 빌더 ...";
    long expiresInSeconds = 1800L;
    return new BiometricTokenResult(accessToken, expiresInSeconds);
}

public record BiometricTokenResult(String accessToken, long expiresInSeconds) {}
```

> ⚠️ Refresh 토큰을 생체에서도 줄지 여부는 **B2 보안 정책**에 따른다. 기존 로그인과 동일한 페어를 발급하려면 기존 private 헬퍼를 **중복 호출하지 말고**, 공통 `buildClaims(deviceId, userId)` 만 추출하는 정도는 허용되는 **최소 리팩터**로 논의한다.

---

## 7. Spring Security 설정 (6.x)

**이 단계에서 할 일**: `/biometric/**` 를 **인증 없이** 열지 여부를 결정한다. (모바일이 직접 호출하는 경우가 많아 `permitAll` 예시를 둔다.)

> ⚠️ `SecurityFilterChain` Bean이 **여러 개**이면 `Order`·`securityMatcher` 충돌이 나기 쉽다. **가장 단순한 방법은 체인을 하나만 두고** 아래처럼 matcher만 추가하는 것이다.

기존 앱의 **메인** `SecurityFilterChain`에 합친다:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/biometric/**").permitAll()
    .requestMatchers("/login", "/public/**").permitAll()
    .anyRequest().authenticated())
```

생체 API만 CSRF를 끄는 것이 필요하면 (세션 쿠키 기반 앱이 아닌 경우가 많음) 전역 `csrf.disable()` 여부는 B2 기존 정책을 따른다.

> ⚠️ 실서비스에서는 **mTLS, API Key, 내부망 제한, 또는 앱 attestation** 등으로 대체하는 것이 일반적이다. `permitAll`은 **개발·연동 초기**용으로 이해한다.

---

## 8. application.yml 설정 항목

**이 단계에서 할 일**: datasource는 **기존 Oracle 설정을 그대로** 쓰고, 생체 정책만 추가한다.

```yaml
# 기존 spring.datasource.* 는 변경하지 않음 (B2 표준 Oracle 연결)

biometric:
  policy:
    max-retry-before-lockout: 3
    lockout-seconds: 30
    account-lock-threshold: 5
    fallback-password-enabled: true

# JPA 사용 시 (예시)
spring:
  jpa:
    hibernate:
      ddl-auto: none   # 🔴 운영에서는 validate 또는 none — DDL은 2절 스크립트로 관리
    properties:
      hibernate:
        dialect: org.hibernate.dialect.OracleDialect
```

---

## 9. 동작 확인 체크리스트

- [ ] Oracle에 `biometric_device`, `biometric_session`, `biometric_nonce` 생성 완료
- [ ] 테스트용 `biometric_device` 행 1건 이상 삽입 (ACTIVE, `public_key_b64` 채움)
- [ ] B2에 `biometric-auth-lib` 의존성 추가 후 빌드 성공
- [ ] `JpaDeviceStore` / `JpaSessionStore` / `JpaNonceStore` / `ConfigurableFailurePolicyStore` 빈 등록 확인
- [ ] `BiometricConfig` 의 `ChallengeService`, `EcdsaVerifier`, `FailurePolicyService` 빈 등록 확인
- [ ] `POST /biometric/challenge` → `session_id`, `server_challenge`, `expire_at` 반환
- [ ] 클라이언트가 올바른 ECDSA 서명으로 `POST /biometric/verify` → **200 + JWT**
- [ ] JWT payload에 **deviceId, userId** (또는 팀 표준 claim) 포함 여부 확인
- [ ] `POST /biometric/renew-key` → `RENEWED`, DB `public_key_b64`·`status=ACTIVE` 반영
- [ ] **기존 id/pw 로그인** → 기존과 동일하게 JWT 발급되는지 회귀 테스트 🔴
- [ ] (선택) 만료 세션·오래된 nonce 배치 삭제 스케줄 동작

---

## 부록: 파일 배치 요약

| 경로 (예시) | 설명 |
|-------------|------|
| `com.mycompany.b2.biometric.persistence.entity.*` | JPA 엔티티 |
| `com.mycompany.b2.biometric.persistence.repository.*` | Spring Data JPA |
| `com.mycompany.b2.biometric.persistence.Jpa*Store` | lib Store 구현 |
| `com.mycompany.b2.biometric.config.BiometricConfig` | `@Bean` 조립 |
| `com.mycompany.b2.biometric.config.BiometricPolicyProperties` | yml 바인딩 |
| `com.mycompany.b2.biometric.api.BiometricAuthController` | REST |
| `com.mycompany.b2.biometric.api.BiometricAuthService` | 유스케이스 |
| `com.mycompany.b2.security.JwtTokenService` | 기존 클래스 + **신규 메서드만** |

---

**문서 파일**: `biometric-auth-server/docs/B2_BIOMETRIC_INTEGRATION_GUIDE.md`
