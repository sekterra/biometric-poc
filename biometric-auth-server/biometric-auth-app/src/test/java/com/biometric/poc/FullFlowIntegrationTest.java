package com.biometric.poc;

import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.lib.store.NonceStore;
import com.biometric.poc.lib.store.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FullFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired DeviceStore deviceStore;
    @Autowired SessionStore sessionStore;
    @Autowired NonceStore nonceStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    static KeyPair testKeyPair;
    static String publicKeyBase64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        testKeyPair = kpg.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());
    }

    @Test
    void fullFlow_register_challenge_token_success() throws Exception {

        String deviceId = "testDevice001";
        String userId = "USER01";
        String clientNonce = "aabbccddeeff00112233445566778899";
        long timestamp = System.currentTimeMillis();

        String registerBody =
                """
                {
                  "device_id":   "%s",
                  "user_id":     "%s",
                  "public_key":  "%s",
                  "enrolled_at": "2026-01-01T00:00:00Z"
                }
                """
                        .formatted(deviceId, userId, publicKeyBase64);

        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        var device = deviceStore.findByDeviceId(deviceId).orElseThrow();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(device.getPublicKeyBase64()).isEqualTo(publicKeyBase64);

        String challengeBody =
                """
                {
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(deviceId, userId, clientNonce, timestamp);

        String challengeResponse =
                mockMvc.perform(
                                post("/api/auth/challenge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challengeBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.session_id").exists())
                        .andExpect(jsonPath("$.server_challenge").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionId =
                objectMapper.readTree(challengeResponse).get("session_id").asText();
        String serverChallenge =
                objectMapper.readTree(challengeResponse).get("server_challenge").asText();

        SessionData session = sessionStore.findBySessionId(sessionId).orElseThrow();
        assertThat(session.isUsed()).isFalse();
        assertThat(session.getClientNonce()).isEqualTo(clientNonce);

        String payload = serverChallenge + ":" + clientNonce + ":" + deviceId + ":" + timestamp;

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        String ecSignature = Base64.getEncoder().encodeToString(sig.sign());

        String tokenBody =
                """
                {
                  "session_id":   "%s",
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "ec_signature": "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(
                                sessionId, deviceId, userId, ecSignature, clientNonce, timestamp);

        String tokenResponse =
                mockMvc.perform(
                                post("/api/auth/token")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(tokenBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.access_token").exists())
                        .andExpect(jsonPath("$.refresh_token").exists())
                        .andExpect(jsonPath("$.expires_in").value(1800))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String accessToken =
                objectMapper.readTree(tokenResponse).get("access_token").asText();
        assertThat(accessToken.split("\\.")).hasSize(3);

        assertThat(sessionStore.findBySessionId(sessionId)).isEmpty();

        assertThat(nonceStore.isUsed(clientNonce)).isTrue();
    }

    /**
     * 동일 client_nonce로 두 번째 challenge를 만든 뒤 토큰을 요청하면,
     * 세션은 유효하지만 nonce가 이미 소비되어 NONCE_REPLAY가 된다.
     * (동일 tokenBody를 두 번 보내면 첫 번째 성공 후 세션이 소비되어 SESSION_EXPIRED가 된다.)
     */
    @Test
    void fullFlow_token_replay_nonce_rejected() throws Exception {

        String deviceId = "testDevice002";
        String userId = "USER01";
        String clientNonce = "replay00112233445566778899aabbcc";

        String registerBody =
                """
                {
                  "device_id":   "%s",
                  "user_id":     "%s",
                  "public_key":  "%s",
                  "enrolled_at": "2026-01-01T00:00:00Z"
                }
                """
                        .formatted(deviceId, userId, publicKeyBase64);
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody))
                .andExpect(status().isOk());

        long ts1 = System.currentTimeMillis();
        String challengeBody1 =
                """
                {
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(deviceId, userId, clientNonce, ts1);
        String challengeResponse1 =
                mockMvc.perform(
                                post("/api/auth/challenge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challengeBody1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionId1 =
                objectMapper.readTree(challengeResponse1).get("session_id").asText();
        String serverChallenge1 =
                objectMapper.readTree(challengeResponse1).get("server_challenge").asText();

        String payload1 =
                serverChallenge1 + ":" + clientNonce + ":" + deviceId + ":" + ts1;
        Signature sig1 = Signature.getInstance("SHA256withECDSA");
        sig1.initSign(testKeyPair.getPrivate());
        sig1.update(payload1.getBytes(StandardCharsets.UTF_8));
        String ecSignature1 = Base64.getEncoder().encodeToString(sig1.sign());

        String tokenBody1 =
                """
                {
                  "session_id":   "%s",
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "ec_signature": "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(
                                sessionId1, deviceId, userId, ecSignature1, clientNonce, ts1);

        mockMvc.perform(
                        post("/api/auth/token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tokenBody1))
                .andExpect(status().isOk());

        long ts2 = System.currentTimeMillis();
        String challengeBody2 =
                """
                {
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(deviceId, userId, clientNonce, ts2);
        String challengeResponse2 =
                mockMvc.perform(
                                post("/api/auth/challenge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challengeBody2))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionId2 =
                objectMapper.readTree(challengeResponse2).get("session_id").asText();
        String serverChallenge2 =
                objectMapper.readTree(challengeResponse2).get("server_challenge").asText();

        String payload2 =
                serverChallenge2 + ":" + clientNonce + ":" + deviceId + ":" + ts2;
        Signature sig2 = Signature.getInstance("SHA256withECDSA");
        sig2.initSign(testKeyPair.getPrivate());
        sig2.update(payload2.getBytes(StandardCharsets.UTF_8));
        String ecSignature2 = Base64.getEncoder().encodeToString(sig2.sign());

        String tokenBody2 =
                """
                {
                  "session_id":   "%s",
                  "device_id":    "%s",
                  "user_id":      "%s",
                  "ec_signature": "%s",
                  "client_nonce": "%s",
                  "timestamp":    %d
                }
                """
                        .formatted(
                                sessionId2, deviceId, userId, ecSignature2, clientNonce, ts2);

        mockMvc.perform(
                        post("/api/auth/token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tokenBody2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NONCE_REPLAY"));
    }
}
