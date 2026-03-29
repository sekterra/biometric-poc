package com.biometric.poc.store;

import com.biometric.poc.lib.auth.AuthConstants;
import com.biometric.poc.lib.store.NonceStore;
import com.biometric.poc.mapper.NonceMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * ConcurrentHashMap 기반 {@link com.biometric.poc.lib.store.NonceStoreImpl}을 MyBatis로 교체한 구현체.
 * 실서비스 전환 시 Redis 구현체로 교체 가능.
 */
@Component
public class MyBatisNonceStoreImpl implements NonceStore {

    private final NonceMapper nonceMapper;

    public MyBatisNonceStoreImpl(NonceMapper nonceMapper) {
        this.nonceMapper = nonceMapper;
    }

    @Override
    public boolean isUsed(String nonce) {
        return nonceMapper.countByNonce(nonce) > 0;
    }

    @Override
    public void markUsed(String nonce, String deviceId) {
        Instant now = Instant.now();
        Instant expireAt = now.plus(AuthConstants.NONCE_TTL_MINUTES, ChronoUnit.MINUTES);
        nonceMapper.insert(nonce, deviceId, now, expireAt);
        nonceMapper.deleteExpired(now);
    }
}
