package com.biometric.poc.schedule;

import com.biometric.poc.mapper.NonceMapper;
import com.biometric.poc.mapper.SessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 만료 세션·nonce 정리. 단위 테스트에서 스케줄 중복 실행을 피하기 위해 {@code test} 프로파일에서는 비활성화.
 *
 * <p>TODO: [실서비스] fixedDelay / initialDelay 를 트래픽·DB 부하에 맞게 조정.
 */
@Slf4j
@Component
@Profile("!test")
public class SessionCleanupScheduler {

    private final SessionMapper sessionMapper;
    private final NonceMapper nonceMapper;

    public SessionCleanupScheduler(SessionMapper sessionMapper, NonceMapper nonceMapper) {
        this.sessionMapper = sessionMapper;
        this.nonceMapper = nonceMapper;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        int deleted = sessionMapper.deleteExpired(now);
        log.info("만료 세션 삭제: {}건", deleted);
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredNonces() {
        Instant now = Instant.now();
        int deleted = nonceMapper.deleteExpired(now);
        log.info("만료 Nonce 삭제: {}건", deleted);
    }
}
