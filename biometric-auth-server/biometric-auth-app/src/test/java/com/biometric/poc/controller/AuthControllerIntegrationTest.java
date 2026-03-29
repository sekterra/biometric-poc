package com.biometric.poc.controller;

import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DeviceStore deviceStore;

    private static final String ENROLLED_AT = "2024-01-01T00:00:00Z";

    private void registerDevice(String deviceId) throws Exception {
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER01",
                                                        "public_key",
                                                        "ZHVtbXk=",
                                                        "enrolled_at",
                                                        ENROLLED_AT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void registerDevice_success() throws Exception {
        String deviceId = "reg-ok-" + UUID.randomUUID();
        registerDevice(deviceId);
    }

    @Test
    void registerDevice_duplicate_returns409() throws Exception {
        String deviceId = "dup-" + UUID.randomUUID();
        registerDevice(deviceId);
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER01",
                                                        "public_key",
                                                        "ZHVtbXk=",
                                                        "enrolled_at",
                                                        ENROLLED_AT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_REGISTERED"));
    }

    @Test
    void challenge_deviceNotFound_returns404() throws Exception {
        mockMvc.perform(
                        post("/api/auth/challenge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        "unknown-device-xyz",
                                                        "user_id",
                                                        "USER01",
                                                        "client_nonce",
                                                        "aabbccddeeff00112233445566778899",
                                                        "timestamp",
                                                        System.currentTimeMillis()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void challenge_accountLocked_returns423() throws Exception {
        String deviceId = "locked-" + UUID.randomUUID();
        registerDevice(deviceId);
        deviceStore.updateStatus(deviceId, DeviceStatus.LOCKED);

        mockMvc.perform(
                        post("/api/auth/challenge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER01",
                                                        "client_nonce",
                                                        "0123456789abcdef0123456789abcdef",
                                                        "timestamp",
                                                        System.currentTimeMillis()))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
    }

    @Test
    void challenge_keyInvalidated_returns409() throws Exception {
        String deviceId = "inv-" + UUID.randomUUID();
        registerDevice(deviceId);
        deviceStore.invalidateKey(deviceId);

        mockMvc.perform(
                        post("/api/auth/challenge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER01",
                                                        "client_nonce",
                                                        "fedcba9876543210fedcba9876543210",
                                                        "timestamp",
                                                        System.currentTimeMillis()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KEY_INVALIDATED"));
    }

    @Test
    void token_sessionExpired_returns401() throws Exception {
        mockMvc.perform(
                        post("/api/auth/token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "session_id",
                                                        "nonexistent-session-id",
                                                        "device_id",
                                                        "dev",
                                                        "user_id",
                                                        "USER01",
                                                        "ec_signature",
                                                        "dGVzdA==",
                                                        "client_nonce",
                                                        "0123456789abcdef0123456789abcdef",
                                                        "timestamp",
                                                        System.currentTimeMillis()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESSION_EXPIRED"));
    }
}
