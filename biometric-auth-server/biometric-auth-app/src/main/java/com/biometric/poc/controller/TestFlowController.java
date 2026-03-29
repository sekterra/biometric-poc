package com.biometric.poc.controller;

// [PoC 전용] H2 데이터 확인용 임시 엔드포인트
// spring.profiles.active=test-flow 일 때만 활성화
// 실서비스 전환 전 반드시 제거할 것

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.ecdsa.VerificationResult;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.JwtTokenPair;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.NonceStore;
import com.biometric.poc.service.JwtIssuerService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/test")
@Profile("test-flow")
public class TestFlowController {

    private final DeviceStore deviceStore;
    private final ChallengeService challengeService;
    private final EcdsaVerifier ecdsaVerifier;
    private final JwtIssuerService jwtIssuerService;
    @SuppressWarnings("unused")
    private final NonceStore nonceStore;

    public TestFlowController(
            DeviceStore deviceStore,
            ChallengeService challengeService,
            EcdsaVerifier ecdsaVerifier,
            JwtIssuerService jwtIssuerService,
            NonceStore nonceStore) {
        this.deviceStore = deviceStore;
        this.challengeService = challengeService;
        this.ecdsaVerifier = ecdsaVerifier;
        this.jwtIssuerService = jwtIssuerService;
        this.nonceStore = nonceStore;
    }

    @PostMapping("/full-flow")
    public ResponseEntity<?> fullFlow(@Valid @RequestBody TestFullFlowRequest body) throws Exception {
        String deviceId = body.deviceId();
        String userId = body.userId();

        KeyPair keyPair = generateEcKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        ResponseEntity<?> deviceStep = ensureDeviceRegistered(deviceId, userId, publicKeyBase64);
        if (deviceStep != null) {
            return deviceStep;
        }

        String clientNonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis();

        SessionData session =
                challengeService.createSession(deviceId, userId, clientNonce, timestamp);
        String ecSignature = signChallengePayload(keyPair, session, clientNonce, deviceId, timestamp);

        VerificationResult vr =
                ecdsaVerifier.verify(
                        session.getSessionId(),
                        deviceId,
                        userId,
                        ecSignature,
                        clientNonce,
                        timestamp);

        if (vr != VerificationResult.SUCCESS) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("verification", vr.name(), "session_id", session.getSessionId()));
        }

        return ResponseEntity.ok(buildSuccessBody(deviceId, userId, publicKeyBase64, session, clientNonce));
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /** 신규 등록 또는 ACTIVE 기기의 공개키 갱신. 비ACTIVE면 409. */
    private ResponseEntity<?> ensureDeviceRegistered(
            String deviceId, String userId, String publicKeyBase64) {
        if (!deviceStore.existsByDeviceId(deviceId)) {
            DeviceInfo device =
                    DeviceInfo.builder()
                            .deviceId(deviceId)
                            .userId(userId)
                            .publicKeyBase64(publicKeyBase64)
                            .enrolledAt(Instant.now())
                            .status(DeviceStatus.ACTIVE)
                            .build();
            deviceStore.save(device);
            return null;
        }
        DeviceInfo existing = deviceStore.findByDeviceId(deviceId).orElseThrow();
        if (existing.getStatus() != DeviceStatus.ACTIVE) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "DEVICE_NOT_ACTIVE", "status", existing.getStatus().name()));
        }
        deviceStore.updatePublicKey(deviceId, publicKeyBase64);
        return null;
    }

    private static String signChallengePayload(
            KeyPair keyPair,
            SessionData session,
            String clientNonce,
            String deviceId,
            long timestamp)
            throws Exception {
        String payload =
                session.getServerChallengeHex()
                        + ":"
                        + clientNonce
                        + ":"
                        + deviceId
                        + ":"
                        + timestamp;
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private Map<String, Object> buildSuccessBody(
            String deviceId,
            String userId,
            String publicKeyBase64,
            SessionData session,
            String clientNonce) {
        JwtTokenPair pair = jwtIssuerService.issueTokenPair(userId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("device_id", deviceId);
        res.put("user_id", userId);
        res.put("public_key_preview", preview(publicKeyBase64, 20));
        res.put("session_id", session.getSessionId());
        res.put("nonce", clientNonce);
        res.put("verification", "SUCCESS");
        res.put("access_token_preview", preview(pair.getAccessToken(), 20));
        return res;
    }

    private static String preview(String value, int maxLen) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    public record TestFullFlowRequest(
            @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
            @NotBlank(message = "INVALID_USER_ID") @JsonProperty("user_id") String userId) {}
}
