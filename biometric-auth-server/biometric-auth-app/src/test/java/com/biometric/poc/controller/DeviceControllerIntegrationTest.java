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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DeviceStore deviceStore;

    private static final String ENROLLED_AT = "2024-01-01T00:00:00Z";
    private static final String ENROLLED_AT_2 = "2025-06-15T12:00:00Z";

    @Test
    void getUserId_existingDevice_returnsUserIdAndStatus() throws Exception {
        String deviceId = "user-id-ok-" + UUID.randomUUID();
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER99",
                                                        "public_key",
                                                        "ZHVtbXk=",
                                                        "enrolled_at",
                                                        ENROLLED_AT))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/device/user-id").param("device_id", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("USER99"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getUserId_unknownDevice_returns404() throws Exception {
        mockMvc.perform(get("/api/device/user-id").param("device_id", "unknown-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void register_keyInvalidatedDevice_allowsReRegister() throws Exception {
        String deviceId = "re-reg-" + UUID.randomUUID();
        String key1 = "a2V5MQ==";
        String key2 = "a2V5Mg==";

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
                                                        key1,
                                                        "enrolled_at",
                                                        ENROLLED_AT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        mockMvc.perform(
                        put("/api/device/update-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "status",
                                                        "KEY_INVALIDATED"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "user_id",
                                                        "USER02",
                                                        "public_key",
                                                        key2,
                                                        "enrolled_at",
                                                        ENROLLED_AT_2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RE_REGISTERED"));

        var opt = deviceStore.findByDeviceId(deviceId);
        assertTrue(opt.isPresent());
        assertEquals(DeviceStatus.ACTIVE, opt.get().getStatus());
        assertEquals(key2, opt.get().getPublicKeyBase64());
        assertEquals("USER02", opt.get().getUserId());
    }

    @Test
    void register_activeDevice_returns409() throws Exception {
        String deviceId = "active-dup-" + UUID.randomUUID();
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
                .andExpect(status().isOk());

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
                                                        "YWJjZA==",
                                                        "enrolled_at",
                                                        ENROLLED_AT_2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_REGISTERED"));
    }

    @Test
    void unlock_lockedDevice_returnsActive() throws Exception {
        String deviceId = "unlock-ok-" + UUID.randomUUID();
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
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/auth/account-lock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId, "user_id", "USER01"))))
                .andExpect(status().isOk());

        assertEquals(DeviceStatus.LOCKED, deviceStore.findByDeviceId(deviceId).get().getStatus());

        mockMvc.perform(
                        put("/api/device/unlock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertEquals(DeviceStatus.ACTIVE, deviceStore.findByDeviceId(deviceId).get().getStatus());
    }

    @Test
    void unlock_notLockedDevice_returns400() throws Exception {
        String deviceId = "unlock-not-" + UUID.randomUUID();
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
                .andExpect(status().isOk());

        mockMvc.perform(
                        put("/api/device/unlock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_LOCKED"));
    }

    @Test
    void unlock_unknownDevice_returns404() throws Exception {
        mockMvc.perform(
                        put("/api/device/unlock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", "no-device-" + UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void updateKey_deviceNotFound_returns404() throws Exception {
        mockMvc.perform(
                        put("/api/device/update-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        "no-such-device",
                                                        "status",
                                                        "KEY_INVALIDATED"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void updateKey_success() throws Exception {
        String deviceId = "upd-key-" + UUID.randomUUID();
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
                .andExpect(status().isOk());

        mockMvc.perform(
                        put("/api/device/update-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "status",
                                                        "KEY_INVALIDATED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        var opt = deviceStore.findByDeviceId(deviceId);
        assertTrue(opt.isPresent());
        assertEquals(DeviceStatus.KEY_INVALIDATED, opt.get().getStatus());
    }

    @Test
    void renewKey_success() throws Exception {
        String deviceId = "renew-ok-" + UUID.randomUUID();
        String key1 = "b2xkS2V5MTIz";
        String key2 = "bmV3S2V5NDU2Nzg=";

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
                                                        key1,
                                                        "enrolled_at",
                                                        ENROLLED_AT))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        put("/api/device/renew-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "new_public_key",
                                                        key2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RENEWED"));

        var opt = deviceStore.findByDeviceId(deviceId);
        assertTrue(opt.isPresent());
        assertEquals(DeviceStatus.ACTIVE, opt.get().getStatus());
        assertEquals(key2, opt.get().getPublicKeyBase64());
    }

    @Test
    void renewKey_deviceNotFound_returns404() throws Exception {
        mockMvc.perform(
                        put("/api/device/renew-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        "unknown-renew-" + UUID.randomUUID(),
                                                        "new_public_key",
                                                        "YQ=="))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void unregister_success() throws Exception {
        String deviceId = "unregister-ok-" + UUID.randomUUID();
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id", deviceId,
                                                        "user_id", "USER_UNR01",
                                                        "public_key", "ZHVtbXk=",
                                                        "enrolled_at", ENROLLED_AT))))
                .andExpect(status().isOk());

        assertTrue(deviceStore.existsByDeviceId(deviceId));

        mockMvc.perform(
                        delete("/api/device/unregister")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId,
                                                       "user_id", "USER_UNR01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREGISTERED"));

        assertFalse(deviceStore.existsByDeviceId(deviceId));
    }

    @Test
    void unregister_userMismatch_returns403() throws Exception {
        String deviceId = "unregister-mismatch-" + UUID.randomUUID();
        mockMvc.perform(
                        post("/api/device/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id", deviceId,
                                                        "user_id", "USER_OWNER",
                                                        "public_key", "ZHVtbXk=",
                                                        "enrolled_at", ENROLLED_AT))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        delete("/api/device/unregister")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId,
                                                       "user_id", "OTHER_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("USER_MISMATCH"));
    }

    @Test
    void unregister_deviceNotFound_returns404() throws Exception {
        mockMvc.perform(
                        delete("/api/device/unregister")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", "unknown-" + UUID.randomUUID(),
                                                       "user_id", "ANY_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void renewKey_lockedDevice_returns423() throws Exception {
        String deviceId = "renew-locked-" + UUID.randomUUID();
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
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/auth/account-lock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("device_id", deviceId, "user_id", "USER01"))))
                .andExpect(status().isOk());

        assertEquals(DeviceStatus.LOCKED, deviceStore.findByDeviceId(deviceId).get().getStatus());

        mockMvc.perform(
                        put("/api/device/renew-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "device_id",
                                                        deviceId,
                                                        "new_public_key",
                                                        "bmV3S2V5"))))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
    }
}
