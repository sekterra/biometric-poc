package com.skcc.biometric.lib.policy;

import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.network.AuthApiClient.FailurePolicyConfig;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로컬 실패·잠금 카운트. 서버 정책은 {@link #loadPolicyIfAbsent} 로 캐시({@link #POLICY_CACHE_TTL_MS} 주기).
 *
 * <p>TODO: [실서비스] 정책 강제 갱신(푸시)·오프라인 시 기본값 전략을 정의할 것.
 */
public class FailurePolicyManager {

    /**
     * 서버 정책 캐시 유효 시간 (5분).
     * TODO: [실서비스] 앱 운영 환경에 맞게 조정.
     */
    private static final long POLICY_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lockoutStartTime = new AtomicLong(0);
    private FailurePolicyConfig policy;
    /** 정책을 마지막으로 서버에서 조회한 시각 (epoch ms). */
    private long policyFetchedAt = 0L;

    /**
     * 서버에서 정책을 가져오되, 캐시가 유효하면 생략.
     * 캐시 유효 시간({@link #POLICY_CACHE_TTL_MS}) 초과 또는 {@link #invalidatePolicy} 호출 후엔 갱신.
     */
    public synchronized void loadPolicyIfAbsent(AuthApiClient client, String deviceId) throws IOException {
        long now = System.currentTimeMillis();
        if (policy != null && (now - policyFetchedAt) < POLICY_CACHE_TTL_MS) {
            return;
        }
        policy = client.getFailurePolicy(deviceId);
        policyFetchedAt = now;
    }

    /** 계정 잠금·정책 변경 후 다음 로그인에서 서버 정책을 즉시 갱신하기 위해 캐시 제거. */
    public synchronized void invalidatePolicy() {
        policy = null;
        policyFetchedAt = 0L;
    }

    public int recordFailure() {
        int count = failureCount.incrementAndGet();
        if (policy != null && count >= policy.maxRetryBeforeLockout) {
            lockoutStartTime.set(System.currentTimeMillis());
        }
        return count;
    }

    public boolean isLocallyLocked() {
        if (policy == null) {
            return false;
        }
        if (failureCount.get() < policy.maxRetryBeforeLockout) {
            return false;
        }
        if (policy.lockoutSeconds == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lockoutStartTime.get();
        return elapsed < (long) policy.lockoutSeconds * 1000L;
    }

    public int getLockRemainingSeconds() {
        if (!isLocallyLocked() || policy == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long remainingMs = lockoutStartTime.get() + (long) policy.lockoutSeconds * 1000L - now;
        return (int) Math.max(0, remainingMs / 1000L);
    }

    public boolean shouldRequestAccountLock() {
        if (policy == null) {
            return false;
        }
        return failureCount.get() >= policy.accountLockThreshold;
    }

    public boolean shouldFallbackToPassword() {
        if (policy == null) {
            return false;
        }
        return policy.fallbackPasswordEnabled && shouldRequestAccountLock();
    }

    public void reset() {
        failureCount.set(0);
        lockoutStartTime.set(0);
    }
}
