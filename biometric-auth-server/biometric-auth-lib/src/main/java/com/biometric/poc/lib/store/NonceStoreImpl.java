package com.biometric.poc.lib.store;

import com.biometric.poc.lib.auth.AuthConstants;

// PoC: ConcurrentHashMap 기반 in-memory 구현체
// 실서비스 전환 시 MyBatisNonceStoreImpl로 교체할 것
// 인터페이스는 변경 없이 구현체만 교체
// 실서비스: MyBatisNonceStoreImpl 또는 Redis 구현체로 교체

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class NonceStoreImpl implements NonceStore {

    private static final long EVICT_AFTER_SECONDS = AuthConstants.NONCE_TTL_MINUTES * 60L;

    private final ConcurrentHashMap<String, Instant> map = new ConcurrentHashMap<>();

    @Override
    public boolean isUsed(String nonce) {
        return map.containsKey(nonce);
    }

    @Override
    public void markUsed(String nonce, String deviceId) {
        map.put(nonce, Instant.now());
        evictOlderThanFiveMinutes();
    }

    private void evictOlderThanFiveMinutes() {
        Instant cutoff = Instant.now().minusSeconds(EVICT_AFTER_SECONDS);
        map.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
