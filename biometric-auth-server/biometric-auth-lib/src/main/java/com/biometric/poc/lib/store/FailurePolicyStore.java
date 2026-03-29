package com.biometric.poc.lib.store;

import com.biometric.poc.lib.model.FailurePolicyConfig;

public interface FailurePolicyStore {

    FailurePolicyConfig getPolicy(String deviceId);
}
