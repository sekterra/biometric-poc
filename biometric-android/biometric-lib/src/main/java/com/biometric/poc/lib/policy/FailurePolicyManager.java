package com.biometric.poc.lib.policy;

import com.biometric.poc.lib.network.AuthApiClient;
import com.biometric.poc.lib.network.AuthApiClient.FailurePolicyConfig;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로컬 실패·잠금 카운트. 서버 정책은 {@link #loadPolicyIfAbsent} 로 캐시(최초 1회 조회).
 *
 * <p>TODO: [실서비스] 정책 TTL·강제 갱신(푸시)·오프라인 시 기본값 전략을 정의할 것.
 */
public class FailurePolicyManager {

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lockoutStartTime = new AtomicLong(0);
    private FailurePolicyConfig policy;

    /** 서버에서 정책을 가져오되, 이미 캐시되어 있으면 생략. 계정 잠금 등으로 무효화 시 {@link #invalidatePolicy}. */
    public synchronized void loadPolicyIfAbsent(AuthApiClient client, String deviceId) throws IOException {
        if (policy != null) {
            return;
        }
        policy = client.getFailurePolicy(deviceId);
    }

    /** 계정 잠금·정책 변경 후 다음 로그인에서 서버 정책을 다시 받기 위해 캐시 제거. */
    public synchronized void invalidatePolicy() {
        policy = null;
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
