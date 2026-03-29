package com.biometric.poc.lib.model;

import com.biometric.poc.lib.auth.AuthConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailurePolicyConfig {

    @Builder.Default
    private int maxRetryBeforeLockout = 3;

    @Builder.Default
    private int lockoutSeconds = AuthConstants.DEFAULT_LOCKOUT_SECONDS;

    @Builder.Default
    private int accountLockThreshold = 5;

    @Builder.Default
    private boolean fallbackPasswordEnabled = true;
}
