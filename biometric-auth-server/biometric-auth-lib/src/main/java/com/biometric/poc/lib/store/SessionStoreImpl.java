package com.biometric.poc.lib.store;

// PoC: ConcurrentHashMap 기반 in-memory 구현체
// 실서비스 전환 시 MyBatisSessionStoreImpl로 교체할 것
// 인터페이스는 변경 없이 구현체만 교체

import com.biometric.poc.lib.model.SessionData;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionStoreImpl implements SessionStore {

    private final ConcurrentHashMap<String, SessionData> map = new ConcurrentHashMap<>();

    @Override
    public void save(SessionData sessionData) {
        map.put(sessionData.getSessionId(), sessionData);
    }

    @Override
    public Optional<SessionData> findBySessionId(String sessionId) {
        SessionData data = map.get(sessionId);
        if (data == null) {
            return Optional.empty();
        }
        if (data.isUsed()) {
            return Optional.empty();
        }
        if (data.getExpireAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(data);
    }

    @Override
    public void markUsed(String sessionId) {
        SessionData data = map.get(sessionId);
        if (data != null) {
            data.setUsed(true);
        }
    }
}
