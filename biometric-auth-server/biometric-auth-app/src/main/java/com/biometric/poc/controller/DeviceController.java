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
import org.springframework.validation.annotation.Validated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.Optional;

// [실서비스 전환 시]
// 이 컨트롤러를 기존 MIS JWT 서버에 복사하고
// AppConfig의 @Bean 등록과 함께 추가하면 됨
@Validated
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private static final Logger log = LoggerFactory.getLogger(DeviceController.class);

    private final DeviceStore deviceStore;

    public DeviceController(DeviceStore deviceStore) {
        this.deviceStore = deviceStore;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        Instant enrolled;
        try {
            enrolled = Instant.parse(request.enrolledAt());
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorBodies.error("INVALID_ENROLLED_AT"));
        }

        var existingOpt = deviceStore.findByDeviceId(request.deviceId());
        if (existingOpt.isPresent()) {
            DeviceInfo existing = existingOpt.get();
            if (existing.getStatus() == DeviceStatus.ACTIVE
                    || existing.getStatus() == DeviceStatus.LOCKED) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiErrorBodies.error("ALREADY_REGISTERED"));
            }
            if (existing.getStatus() == DeviceStatus.KEY_INVALIDATED) {
                Instant now = Instant.now();
                DeviceInfo reRegistered =
                        DeviceInfo.builder()
                                .deviceId(request.deviceId())
                                .userId(request.userId())
                                .publicKeyBase64(request.publicKey())
                                .enrolledAt(enrolled)
                                .updatedAt(now)
                                .status(DeviceStatus.ACTIVE)
                                .build();
                deviceStore.reRegister(reRegistered);
                return ResponseEntity.ok(Map.of("status", "RE_REGISTERED"));
            }
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

    // TODO: [실서비스] 공개키 갱신 시 기존 세션 전체 무효화 처리 추가
    @PutMapping("/renew-key")
    public ResponseEntity<Map<String, String>> renewKey(@Valid @RequestBody RenewKeyRequest request) {
        Optional<DeviceInfo> deviceOpt = deviceStore.findByDeviceId(request.deviceId());
        if (deviceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }
        DeviceInfo device = deviceOpt.get();
        if (device.getStatus() == DeviceStatus.LOCKED) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(ApiErrorBodies.error("ACCOUNT_LOCKED"));
        }
        deviceStore.renewKey(request.deviceId(), request.newPublicKey(), Instant.now());
        return ResponseEntity.ok(Map.of("status", "RENEWED"));
    }

    @GetMapping("/user-id")
    public ResponseEntity<?> getUserId(
            @RequestParam("device_id") @NotBlank(message = "INVALID_DEVICE_ID") String deviceId) {
        var opt = deviceStore.findByDeviceId(deviceId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }
        DeviceInfo d = opt.get();
        return ResponseEntity.ok(new DeviceUserIdResponse(d.getUserId(), d.getStatus().name()));
    }

    // TODO: [실서비스] 이 API는 앱이 직접 호출하지 않음
    //        MIS 인증 서버가 ID/PW 검증 완료 후 호출하는 구조로 변경 필요
    //        앱 직접 호출 시 ID/PW 검증 없이 unlock 가능하므로 보안 취약
    @PutMapping("/unlock")
    public ResponseEntity<Map<String, String>> unlock(@Valid @RequestBody UnlockRequest body) {
        var opt = deviceStore.findByDeviceId(body.deviceId());
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }
        if (opt.get().getStatus() != DeviceStatus.LOCKED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorBodies.error("NOT_LOCKED"));
        }
        deviceStore.updateStatus(body.deviceId(), DeviceStatus.ACTIVE);
        return ResponseEntity.ok(Map.of("status", "ACTIVE"));
    }

    // 사용자 변경 시 기기 등록 정보 완전 삭제
    // TODO: [실서비스] device_id + user_id 일치 검증 강화, 관리자 인증 토큰 검증 추가
    @DeleteMapping("/unregister")
    public ResponseEntity<?> unregister(@Valid @RequestBody UnregisterRequest request) {
        Optional<DeviceInfo> deviceOpt = deviceStore.findByDeviceId(request.deviceId());
        if (deviceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorBodies.error("DEVICE_NOT_FOUND"));
        }
        DeviceInfo device = deviceOpt.get();
        if (!device.getUserId().equals(request.userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorBodies.error("USER_MISMATCH"));
        }
        deviceStore.delete(request.deviceId());
        log.info("기기 등록 삭제 완료 deviceId={} userId={}", request.deviceId(), request.userId());
        return ResponseEntity.ok(Map.of("status", "UNREGISTERED"));
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

record RenewKeyRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "INVALID_PUBLIC_KEY") @JsonProperty("new_public_key") String newPublicKey) {}

record UnlockRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId) {}

record UnregisterRequest(
        @NotBlank(message = "INVALID_DEVICE_ID") @JsonProperty("device_id") String deviceId,
        @NotBlank(message = "INVALID_USER_ID")   @JsonProperty("user_id")   String userId) {}

record DeviceUserIdResponse(
        @JsonProperty("user_id") String userId, @JsonProperty("status") String status) {}
