package com.biometric.poc.lib.ecdsa;

import com.biometric.poc.lib.auth.AuthConstants;
import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.NonceStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

@Slf4j
public class EcdsaVerifier {

    private final ChallengeService challengeService;
    private final NonceStore nonceStore;
    private final DeviceStore deviceStore;

    public EcdsaVerifier(
            ChallengeService challengeService,
            NonceStore nonceStore,
            DeviceStore deviceStore) {
        this.challengeService = challengeService;
        this.nonceStore = nonceStore;
        this.deviceStore = deviceStore;
    }

    /**
     * ECDSA 서명 검증 파이프라인.
     *
     * <p>① 세션 유효성 · ② 타임스탬프 허용 범위 · ③ nonce 재사용 여부 · ④ 서명·공개키 검증 · ⑤ 성공 시
     * nonce/세션 소비.
     */
    public VerificationResult verify(
            String sessionId,
            String deviceId,
            String userId,
            String ecSignatureBase64,
            String clientNonce,
            long timestamp) {

        SessionData session = resolveSessionOrExpired(sessionId);
        if (session == null) {
            return VerificationResult.SESSION_EXPIRED;
        }

        if (!isTimestampWithinTolerance(timestamp)) {
            return VerificationResult.TIMESTAMP_OUT_OF_RANGE;
        }

        if (nonceStore.isUsed(clientNonce)) {
            return VerificationResult.NONCE_REPLAY;
        }

        if (ecSignatureBase64 == null || ecSignatureBase64.isBlank()) {
            return VerificationResult.MISSING_SIGNATURE;
        }

        Optional<DeviceInfo> deviceOpt = deviceStore.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            return VerificationResult.INVALID_SIGNATURE;
        }

        String publicKeyBase64 = deviceOpt.get().getPublicKeyBase64();
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return VerificationResult.INVALID_SIGNATURE;
        }

        return verifySignatureAndConsume(
                sessionId,
                deviceId,
                clientNonce,
                timestamp,
                ecSignatureBase64,
                publicKeyBase64,
                session);
    }

    private SessionData resolveSessionOrExpired(String sessionId) {
        try {
            return challengeService.validateSession(sessionId);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private boolean isTimestampWithinTolerance(long timestamp) {
        return Math.abs(System.currentTimeMillis() - timestamp) <= AuthConstants.TIMESTAMP_TOLERANCE_MS;
    }

    private VerificationResult verifySignatureAndConsume(
            String sessionId,
            String deviceId,
            String clientNonce,
            long timestamp,
            String ecSignatureBase64,
            String publicKeyBase64,
            SessionData session) {

        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            String payload = buildPayload(session, clientNonce, deviceId, timestamp);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(pubKey);
            verifier.update(payloadBytes);
            boolean valid = verifier.verify(Base64.getDecoder().decode(ecSignatureBase64));

            if (!valid) {
                log.warn("ECDSA 서명 검증 실패 deviceId={}", deviceId);
                return VerificationResult.INVALID_SIGNATURE;
            }

            nonceStore.markUsed(clientNonce, deviceId);
            challengeService.markSessionUsed(sessionId);
            return VerificationResult.SUCCESS;
        } catch (Exception e) {
            log.warn("ECDSA 서명 검증 실패 deviceId={}", deviceId, e);
            return VerificationResult.INVALID_SIGNATURE;
        }
    }

    private static String buildPayload(
            SessionData session, String clientNonce, String deviceId, long timestamp) {
        return session.getServerChallengeHex()
                + ":"
                + clientNonce
                + ":"
                + deviceId
                + ":"
                + timestamp;
    }
}
