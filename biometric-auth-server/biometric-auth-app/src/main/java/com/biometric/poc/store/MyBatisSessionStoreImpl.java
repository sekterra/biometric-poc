package com.biometric.poc.store;

import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.SessionStore;
import com.biometric.poc.mapper.SessionMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * ConcurrentHashMap 기반 {@link com.biometric.poc.lib.store.SessionStoreImpl}을 MyBatis로 교체한 구현체.
 */
@Component
public class MyBatisSessionStoreImpl implements SessionStore {

    private final SessionMapper sessionMapper;

    public MyBatisSessionStoreImpl(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    @Override
    public void save(SessionData sessionData) {
        sessionData.setCreatedAt(Instant.now());
        sessionMapper.insert(sessionData);
    }

    @Override
    public Optional<SessionData> findBySessionId(String sessionId) {
        SessionData result = sessionMapper.selectBySessionId(sessionId);
        if (result == null) {
            return Optional.empty();
        }
        if (result.getExpireAt() != null && result.getExpireAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (result.isUsed()) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    @Override
    public void markUsed(String sessionId) {
        sessionMapper.markUsed(sessionId);
    }
}
