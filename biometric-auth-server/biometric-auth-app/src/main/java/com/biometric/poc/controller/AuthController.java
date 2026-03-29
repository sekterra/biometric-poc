package com.biometric.poc.controller;

import com.biometric.poc.lib.challenge.ChallengeService;
import com.biometric.poc.lib.ecdsa.EcdsaVerifier;
import com.biometric.poc.lib.ecdsa.VerificationResult;
import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.JwtTokenPair;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.policy.FailurePolicyService;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.service.JwtIssuerService;
import com.biometric.poc.util.ApiErrorBodies;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ChallengeService challengeService;
    private final EcdsaVerifier ecdsaVerifier;
    private final FailurePolicyService failurePolicyService;
    private final JwtIssuerService jwtIssuerService;
    private final DeviceStore deviceStore;

    public AuthController(
            ChallengeService challengeService,
            EcdsaVerifier ecdsaVerifier,
            FailurePolicyService failurePolicyService,
            JwtIssuerService jwtIssuerService,
            DeviceStore deviceStore) {
        this.challengeService = challengeService;
        this.ecdsaVerifier = ecdsaVerifier;
        this.failurePolicyService = failurePolicyService;
        this.jwtIssuerService = jwtIssuerService;
        this.deviceStore = deviceStore;
    }

    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@Valid @RequestBody ChallengeRequest body) {
        var deviceOpt = deviceStore.findByDeviceId(body.deviceId());
        if (deviceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }

        DeviceInfo device = deviceOpt.get();
        if (device.getStatus() == DeviceStatus.LOCKED) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(ApiErrorBodies.error("ACCOUNT_LOCKED"));
        }
        if (device.getStatus() == DeviceStatus.KEY_INVALIDATED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorBodies.error("KEY_INVALIDATED"));
        }

        SessionData session =
                challengeService.createSession(
                        body.deviceId(), body.userId(), body.clientNonce(), body.timestamp());

        return ResponseEntity.ok(
                Map.of(
                        "session_id",
                        session.getSessionId(),
                        "server_challenge",
                        session.getServerChallengeHex(),
                        "expire_at",
                        session.getExpireAt().toEpochMilli()));
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@Valid @RequestBody TokenRequest body) {
        VerificationResult result =
                ecdsaVerifier.verify(
                        body.sessionId(),
                        body.deviceId(),
                        body.userId(),
                        body.ecSignature(),
                        body.clientNonce(),
                        body.timestamp());

        if (result != VerificationResult.SUCCESS) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorBodies.error(result.name()));
        }

        JwtTokenPair pair = jwtIssuerService.issueTokenPair(body.userId());
        return ResponseEntity.ok(
                Map.of(
                        "access_token",
                        pair.getAccessToken(),
                        "refresh_token",
                        pair.getRefreshToken(),
                        "expires_in",
                        pair.getExpiresIn()));
    }

    @PostMapping("/account-lock")
    public ResponseEntity<Map<String, String>> accountLock(@Valid @RequestBody AccountLockRequest body) {
        failurePolicyService.lockAccount(body.deviceId());
        return ResponseEntity.ok(Map.of("status", "LOCKED"));
    }

    // curl -X POST http://localhost:8080/api/auth/challenge \
    //   -H "Content-Type: application/json" \
    //   -d '{"device_id":"dev001","user_id":"USER01","client_nonce":"abc123","timestamp":현재시각ms}'
}

record ChallengeRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "INVALID_USER_ID") @JsonProperty("user_id") String userId,
        @NotBlank(message = "INVALID_CLIENT_NONCE") @JsonProperty("client_nonce") String clientNonce,
        @Min(value = 0, message = "INVALID_TIMESTAMP") long timestamp) {}

record TokenRequest(
        @NotBlank(message = "INVALID_SESSION_ID") @JsonProperty("session_id") String sessionId,
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "INVALID_USER_ID") @JsonProperty("user_id") String userId,
        @NotBlank(message = "INVALID_EC_SIGNATURE") @JsonProperty("ec_signature") String ecSignature,
        @NotBlank(message = "INVALID_CLIENT_NONCE") @JsonProperty("client_nonce") String clientNonce,
        @Min(value = 0, message = "INVALID_TIMESTAMP") long timestamp) {}

record AccountLockRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "INVALID_USER_ID") @JsonProperty("user_id") String userId) {}
