package com.biometric.poc.controller;

import com.biometric.poc.lib.model.DeviceInfo;
import com.biometric.poc.lib.model.DeviceStatus;
import com.biometric.poc.lib.store.DeviceStore;
import com.biometric.poc.util.ApiErrorBodies;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

// [실서비스 전환 시]
// 이 컨트롤러를 기존 MIS JWT 서버에 복사하고
// AppConfig의 @Bean 등록과 함께 추가하면 됨
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceStore deviceStore;

    public DeviceController(DeviceStore deviceStore) {
        this.deviceStore = deviceStore;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        if (deviceStore.existsByDeviceId(request.deviceId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorBodies.error("ALREADY_REGISTERED"));
        }

        Instant enrolled;
        try {
            enrolled = Instant.parse(request.enrolledAt());
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorBodies.error("INVALID_ENROLLED_AT"));
        }
        DeviceInfo deviceInfo =
                DeviceInfo.builder()
                        .deviceId(request.deviceId())
                        .userId(request.userId())
                        .publicKeyBase64(request.publicKey())
                        .enrolledAt(enrolled)
                        .updatedAt(enrolled)
                        .status(DeviceStatus.ACTIVE)
                        .build();

        try {
            deviceStore.save(deviceInfo);
        } catch (DataIntegrityViolationException e) {
            // exists + insert 사이 race 로 UNIQUE 위반 시
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorBodies.error("ALREADY_REGISTERED"));
        }
        return ResponseEntity.ok(Map.of("status", "REGISTERED"));
    }

    @PutMapping("/update-key")
    public ResponseEntity<Map<String, String>> updateKey(@Valid @RequestBody UpdateKeyRequest body) {
        if (deviceStore.findByDeviceId(body.deviceId()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }

        deviceStore.invalidateKey(body.deviceId());
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    @GetMapping("/user-id")
    public ResponseEntity<Map<String, String>> getUserId(@RequestParam("device_id") String deviceId) {
        // PoC: 실제 구현 시 DeviceStore.findByDeviceId()로 userId 조회
        return ResponseEntity.ok(Map.of("user_id", "USER01"));
    }

    // curl -X POST http://localhost:8080/api/device/register \
    //   -H "Content-Type: application/json" \
    //   -d '{"device_id":"dev001","user_id":"USER01","public_key":"BASE64==","enrolled_at":"2024-01-01T00:00:00Z"}'
    // curl http://localhost:8080/api/device/user-id?device_id=dev001
}

record RegisterRequest(
        @NotBlank(message = "device_id는 필수입니다.") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "user_id는 필수입니다.") @JsonProperty("user_id") String userId,
        @NotBlank(message = "public_key는 필수입니다.") @JsonProperty("public_key") String publicKey,
        @NotBlank(message = "enrolled_at는 필수입니다.") @JsonProperty("enrolled_at") String enrolledAt) {}

record UpdateKeyRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @JsonProperty("status") String status) {}
