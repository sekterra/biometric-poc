package com.biometric.poc.lib.ecdsa;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.NonceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcdsaVerifierTest {

    @Mock ChallengeService challengeService;
    @Mock NonceStore nonceStore;
    @Mock DeviceStore deviceStore;

    private EcdsaVerifier verifier;
    private KeyPair testKeyPair;
    private String testPublicKeyBase64;
    private KeyPair otherKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        testKeyPair = kpg.generateKeyPair();
        testPublicKeyBase64 =
                Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());

        KeyPairGenerator kpg2 = KeyPairGenerator.getInstance("EC");
        kpg2.initialize(new ECGenParameterSpec("secp256r1"));
        otherKeyPair = kpg2.generateKeyPair();

        verifier = new EcdsaVerifier(challengeService, nonceStore, deviceStore);
    }

    private void stubDeviceStore(String deviceId) {
        DeviceInfo info =
                DeviceInfo.builder()
                        .deviceId(deviceId)
                        .userId("USER01")
                        .publicKeyBase64(testPublicKeyBase64)
                        .enrolledAt(Instant.now())
                        .updatedAt(Instant.now())
                        .status(DeviceStatus.ACTIVE)
                        .build();
        when(deviceStore.findByDeviceId(deviceId)).thenReturn(Optional.of(info));
    }

    private SessionData session(
            String sessionId,
            String deviceId,
            String userId,
            String serverChallengeHex,
            String clientNonce,
            long timestamp) {
        Instant created = Instant.now();
        return SessionData.builder()
                .sessionId(sessionId)
                .deviceId(deviceId)
                .userId(userId)
                .serverChallengeHex(serverChallengeHex)
                .clientNonce(clientNonce)
                .timestamp(timestamp)
                .expireAt(created.plusSeconds(60))
                .used(false)
                .createdAt(created)
                .build();
    }

    private String signPayload(String payload, KeyPair keyPair) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    @Test
    void verify_validRequest_returnsSuccess() throws Exception {
        String sessionId = "sess1";
        String deviceId = "dev1";
        String userId = "USER01";
        String serverChallengeHex = "a".repeat(64);
        String clientNonce = "b".repeat(32);
        long timestamp = System.currentTimeMillis();

        SessionData sd =
                session(sessionId, deviceId, userId, serverChallengeHex, clientNonce, timestamp);
        when(challengeService.validateSession(sessionId)).thenReturn(sd);

        when(nonceStore.isUsed(clientNonce)).thenReturn(false);
        stubDeviceStore(deviceId);

        String payload =
                serverChallengeHex + ":" + clientNonce + ":" + deviceId + ":" + timestamp;
        String ecSig = signPayload(payload, testKeyPair);

        VerificationResult result =
                verifier.verify(sessionId, deviceId, userId, ecSig, clientNonce, timestamp);

        assertEquals(VerificationResult.SUCCESS, result);
        verify(nonceStore, times(1)).markUsed(clientNonce, deviceId);
        verify(challengeService, times(1)).markSessionUsed(sessionId);
    }

    @Test
    void verify_expiredSession_returnsSessionExpired() {
        when(challengeService.validateSession("missing"))
                .thenThrow(new IllegalStateException("SESSION_EXPIRED"));

        VerificationResult result =
                verifier.verify(
                        "missing",
                        "dev",
                        "u",
                        "sig",
                        "nonce",
                        System.currentTimeMillis());

        assertEquals(VerificationResult.SESSION_EXPIRED, result);
    }

    @Test
    void verify_oldTimestamp_returnsTimestampOutOfRange() {
        String sessionId = "s2";
        long oldTs = System.currentTimeMillis() - 31_000;
        SessionData sd =
                session(sessionId, "d1", "u1", "c".repeat(64), "n".repeat(32), oldTs);
        when(challengeService.validateSession(sessionId)).thenReturn(sd);

        VerificationResult result =
                verifier.verify(sessionId, "d1", "u1", "sig", "n".repeat(32), oldTs);

        assertEquals(VerificationResult.TIMESTAMP_OUT_OF_RANGE, result);
    }

    @Test
    void verify_reusedNonce_returnsNonceReplay() {
        String sessionId = "s3";
        long ts = System.currentTimeMillis();
        SessionData sd =
                session(sessionId, "d1", "u1", "c".repeat(64), "nonceX", ts);
        when(challengeService.validateSession(sessionId)).thenReturn(sd);
        when(nonceStore.isUsed("nonceX")).thenReturn(true);

        VerificationResult result =
                verifier.verify(sessionId, "d1", "u1", "sig", "nonceX", ts);

        assertEquals(VerificationResult.NONCE_REPLAY, result);
    }

    @Test
    void verify_invalidSignature_returnsInvalidSignature() throws Exception {
        String sessionId = "s4";
        String deviceId = "dev4";
        String userId = "USER01";
        String serverChallengeHex = "d".repeat(64);
        String clientNonce = "e".repeat(32);
        long timestamp = System.currentTimeMillis();

        SessionData sd =
                session(sessionId, deviceId, userId, serverChallengeHex, clientNonce, timestamp);
        when(challengeService.validateSession(sessionId)).thenReturn(sd);
        when(nonceStore.isUsed(clientNonce)).thenReturn(false);
        stubDeviceStore(deviceId);

        String payload =
                serverChallengeHex + ":" + clientNonce + ":" + deviceId + ":" + timestamp;
        String wrongSig = signPayload(payload, otherKeyPair);

        VerificationResult result =
                verifier.verify(sessionId, deviceId, userId, wrongSig, clientNonce, timestamp);

        assertEquals(VerificationResult.INVALID_SIGNATURE, result);
    }

    @Test
    void verify_nullSignature_returnsMissingSignature() {
        String sessionId = "s5";
        long ts = System.currentTimeMillis();
        SessionData sd =
                session(sessionId, "d1", "u1", "c".repeat(64), "nonce5", ts);
        when(challengeService.validateSession(sessionId)).thenReturn(sd);
        when(nonceStore.isUsed("nonce5")).thenReturn(false);

        VerificationResult result =
                verifier.verify(sessionId, "d1", "u1", null, "nonce5", ts);

        assertEquals(VerificationResult.MISSING_SIGNATURE, result);
    }
}
