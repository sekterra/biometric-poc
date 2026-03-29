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

    @Test
    void getUserId_alwaysReturnsUser01() throws Exception {
        mockMvc.perform(get("/api/device/user-id").param("device_id", "any-device-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("USER01"));
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
}
