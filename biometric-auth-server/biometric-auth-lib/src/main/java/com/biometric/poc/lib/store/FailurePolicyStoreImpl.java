package com.biometric.poc.lib.store;

// PoC: ConcurrentHashMap 기반 in-memory 구현체
// 실서비스 전환 시 MyBatisFailurePolicyStoreImpl로 교체할 것
// 인터페이스는 변경 없이 구현체만 교체
// 실서비스: DB 기반 device별 정책 조회로 교체

import com.biometric.poc.lib.model.FailurePolicyConfig;

public class FailurePolicyStoreImpl implements FailurePolicyStore {

    private static final FailurePolicyConfig DEFAULT = FailurePolicyConfig.builder().build();

    @Override
    public FailurePolicyConfig getPolicy(String deviceId) {
        return DEFAULT;
    }
}
