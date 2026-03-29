package com.biometric.poc.controller;

import com.biometric.poc.lib.model.FailurePolicyConfig;
import com.biometric.poc.lib.policy.FailurePolicyService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// [실서비스 전환 시] 이 엔드포인트는 인증·인가(예: 내부망·API 키·JWT)로 보호할 것.
@RestController
@RequestMapping("/api/policy")
@Validated
public class PolicyController {

    private final FailurePolicyService failurePolicyService;

    public PolicyController(FailurePolicyService failurePolicyService) {
        this.failurePolicyService = failurePolicyService;
    }

    @GetMapping("/failure-config")
    public ResponseEntity<Map<String, Object>> failureConfig(
            @RequestParam("device_id") @NotBlank(message = "INVALID_DEVICE_ID") String deviceId) {
        FailurePolicyConfig policy = failurePolicyService.getPolicy(deviceId);
        return ResponseEntity.ok(
                Map.of(
                        "max_retry_before_lockout",
                        policy.getMaxRetryBeforeLockout(),
                        "lockout_seconds",
                        policy.getLockoutSeconds(),
                        "account_lock_threshold",
                        policy.getAccountLockThreshold(),
                        "fallback_password_enabled",
                        policy.isFallbackPasswordEnabled()));
    }

    // curl http://localhost:8080/api/policy/failure-config?device_id=dev001
}
