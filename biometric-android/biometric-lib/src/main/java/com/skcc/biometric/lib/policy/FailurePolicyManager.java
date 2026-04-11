package com.skcc.biometric.lib.policy;

import com.skcc.biometric.lib.network.AuthApiClient;
import com.skcc.biometric.lib.network.AuthApiClient.FailurePolicyConfig;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FailurePolicyManager
 * 안면인식 실패 횟수·잠금 상태를 로컬에서 관리하고, 서버 정책을 캐시하는 클래스.
 *
 * <p>책임:
 * <ol>
 *   <li>서버 실패 정책 조회 및 5분 캐시 (loadPolicyIfAbsent)</li>
 *   <li>실패 횟수 누적 및 일시 잠금 조건 평가 (recordFailure, isLocallyLocked)</li>
 *   <li>계정 잠금 요청 조건 평가 (shouldRequestAccountLock)</li>
 *   <li>성공 시 카운터 초기화 (reset)</li>
 * </ol>
 *
 * <p>정책 항목 (FailurePolicyConfig):
 * <ul>
 *   <li>maxRetryBeforeLockout  — 이 횟수 이상 실패 시 일시 잠금 (CASE 4)</li>
 *   <li>lockoutSeconds         — 일시 잠금 지속 시간(초)</li>
 *   <li>accountLockThreshold  — 이 횟수 이상 실패 시 계정 잠금 요청 (CASE 9)</li>
 *   <li>fallbackPasswordEnabled — ID/PW 폴백 허용 여부</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>AtomicInteger/AtomicLong을 사용하므로 멀티스레드 환경에서 카운터 자체는 안전</li>
 *   <li>loadPolicyIfAbsent()는 synchronized — 동시 다중 서버 요청 방지</li>
 *   <li>policy null 상태(서버 정책 미로드)에서는 잠금·계정잠금 조건을 false로 처리</li>
 *   <li>TODO: [실서비스] 푸시 알림으로 정책 강제 갱신, 오프라인 시 기본값 전략 정의</li>
 * </ul>
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

    /**
     * 인증 실패를 기록하고 누적 실패 횟수를 반환합니다.
     * 실패 횟수가 maxRetryBeforeLockout 이상이면 잠금 시작 시각을 설정합니다.
     *
     * <p>호출 시점: BiometricPrompt.onAuthenticationFailed()
     *
     * @return 현재 누적 실패 횟수 (1부터 시작)
     */
    public int recordFailure() {
        int count = failureCount.incrementAndGet();
        if (policy != null && count >= policy.maxRetryBeforeLockout) {
            lockoutStartTime.set(System.currentTimeMillis());
        }
        return count;
    }

    /**
     * 현재 로컬 일시 잠금 상태인지 확인합니다 (CASE 4 판단 기준).
     *
     * @return true이면 잠금 중 (lockoutSeconds 경과 전), false이면 잠금 해제 또는 정책 미로드
     */
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

    /**
     * 잠금 해제까지 남은 시간을 초 단위로 반환합니다.
     *
     * @return 남은 초 (0이면 잠금 해제 또는 잠금 상태 아님)
     */
    public int getLockRemainingSeconds() {
        if (!isLocallyLocked() || policy == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long remainingMs = lockoutStartTime.get() + (long) policy.lockoutSeconds * 1000L - now;
        return (int) Math.max(0, remainingMs / 1000L);
    }

    /**
     * 서버에 계정 잠금을 요청해야 하는 상태인지 확인합니다 (CASE 9 판단 기준).
     * 누적 실패 횟수가 accountLockThreshold 이상이면 true를 반환합니다.
     *
     * @return true이면 서버 계정 잠금 API 호출 필요
     */
    public boolean shouldRequestAccountLock() {
        if (policy == null) {
            return false;
        }
        return failureCount.get() >= policy.accountLockThreshold;
    }

    /**
     * ID/PW 폴백 인증으로 전환해야 하는 상태인지 확인합니다.
     * fallbackPasswordEnabled 정책이 활성화되어 있고 계정 잠금 조건을 충족한 경우 true.
     *
     * @return true이면 ID/PW 폴백 UI 표시 필요
     */
    public boolean shouldFallbackToPassword() {
        if (policy == null) {
            return false;
        }
        return policy.fallbackPasswordEnabled && shouldRequestAccountLock();
    }

    /**
     * 실패 횟수와 잠금 시작 시각을 초기화합니다.
     * 인증 성공(CASE 1) 후 BiometricAuthManager에 의해 호출됩니다.
     */
    public void reset() {
        failureCount.set(0);
        lockoutStartTime.set(0);
    }
}
