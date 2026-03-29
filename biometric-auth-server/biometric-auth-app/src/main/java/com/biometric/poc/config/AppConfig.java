package com.biometric.poc.config;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.policy.FailurePolicyService;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.FailurePolicyStore;
import com.biometric.poc.lib.store.FailurePolicyStoreImpl;
import com.biometric.poc.lib.store.NonceStore;
import com.biometric.poc.lib.store.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    // MyBatis 구현체(DeviceStore, SessionStore, NonceStore)는 @Component로 등록 — 여기서 @Bean 불필요

    /** DB 불필요 — in-memory 정책 저장 */
    @Bean
    public FailurePolicyStoreImpl failurePolicyStore() {
        return new FailurePolicyStoreImpl();
    }

    @Bean
    public ChallengeService challengeService(SessionStore sessionStore) {
        return new ChallengeService(sessionStore);
    }

    @Bean
    public EcdsaVerifier ecdsaVerifier(
            ChallengeService challengeService,
            NonceStore nonceStore,
            DeviceStore deviceStore) {
        return new EcdsaVerifier(challengeService, nonceStore, deviceStore);
    }

    @Bean
    public FailurePolicyService failurePolicyService(
            FailurePolicyStore failurePolicyStore, DeviceStore deviceStore) {
        return new FailurePolicyService(failurePolicyStore, deviceStore);
    }
}
