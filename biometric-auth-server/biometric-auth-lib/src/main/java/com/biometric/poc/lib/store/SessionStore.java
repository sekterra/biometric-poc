package com.biometric.poc.lib.store;

import com.biometric.poc.lib.model.SessionData;

import java.util.Optional;

public interface SessionStore {

    void save(SessionData sessionData);

    /**
     * 만료(expireAt &lt; now) 또는 used=true 이면 Optional.empty() 반환
     */
    Optional<SessionData> findBySessionId(String sessionId);

    void markUsed(String sessionId);
}
