package com.skcc.biometric.lib.network;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AuthApiClient
 * B2 인증 서버와 통신하는 동기 방식 HTTP API 클라이언트.
 *
 * <p>모든 메서드는 네트워크 I/O를 동반하므로 반드시 백그라운드 스레드에서 호출해야 합니다.
 * UI 스레드에서 호출 시 NetworkOnMainThreadException이 발생합니다.
 *
 * <p>제공 API:
 * <ul>
 *   <li>기기 등록    — POST /api/device/register</li>
 *   <li>기기 삭제    — DELETE /api/device/unregister</li>
 *   <li>공개키 갱신  — PUT /api/device/renew-key</li>
 *   <li>계정 잠금 해제 — PUT /api/device/unlock</li>
 *   <li>챌린지 요청  — POST /api/auth/challenge</li>
 *   <li>토큰 발급    — POST /api/auth/token</li>
 *   <li>계정 잠금    — POST /api/auth/account-lock</li>
 *   <li>실패 정책 조회 — GET /api/policy/failure-config</li>
 * </ul>
 *
 * <p>공통 예외 처리 규칙:
 * <ul>
 *   <li>HTTP 404 → DeviceNotFoundException</li>
 *   <li>HTTP 423 → AccountLockedException</li>
 *   <li>HTTP 409 → KeyInvalidatedException (챌린지/토큰 요청 시)</li>
 *   <li>HTTP 401 → TokenVerificationException (토큰 요청 시, errorCode 포함)</li>
 *   <li>그 외 비성공 → RuntimeException("HTTP " + code + ": " + body)</li>
 * </ul>
 *
 * <p>주의사항:
 * <ul>
 *   <li>SHARED_CLIENT(OkHttpClient)는 앱 전체에서 공유됨 — 연결 풀·DNS 캐시 재사용</li>
 *   <li>TODO: [실서비스] Certificate Pinning, 재시도 정책, 토큰 마스킹 인터셉터 적용</li>
 * </ul>
 */
public class AuthApiClient {

    private static final String TAG = "AuthApiClient";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * 앱 생명주기 동안 연결 풀·DNS 캐시를 재사용하기 위한 공유 클라이언트.
     *
     * <p>TODO: [실서비스] 네트워크 환경에 맞게 조정
     * 현장 환경(Wi-Fi 불안정)을 고려해 적절히 설정
     */
    private static final OkHttpClient SHARED_CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();

    private final String baseUrl;
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    /**
     * AuthApiClient를 초기화합니다.
     *
     * @param baseUrl B2 인증 서버 주소 (예: "https://auth.example.com"). 말미 슬래시는 자동 제거됩니다.
     */
    public AuthApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = SHARED_CLIENT;
    }

    private HttpUrl requireHttpUrl(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("Malformed URL: " + url);
        }
        return parsed;
    }

    private static void requireChallengeResponse(ChallengeResponse r) throws IOException {
        if (r == null) {
            throw new IOException("Empty challenge response body");
        }
        if (r.sessionId == null || r.sessionId.trim().isEmpty()) {
            throw new IOException("Missing session_id in challenge response");
        }
        if (r.serverChallenge == null || r.serverChallenge.trim().isEmpty()) {
            throw new IOException("Missing server_challenge in challenge response");
        }
    }

    private static void requireTokenResponse(TokenResponse r) throws IOException {
        if (r == null) {
            throw new IOException("Empty token response body");
        }
        if (r.accessToken == null || r.accessToken.trim().isEmpty()) {
            throw new IOException("Missing access_token in token response");
        }
        if (r.refreshToken == null || r.refreshToken.trim().isEmpty()) {
            throw new IOException("Missing refresh_token in token response");
        }
    }

    private static void requireFailurePolicy(FailurePolicyConfig r) throws IOException {
        if (r == null) {
            throw new IOException("Empty failure policy response body");
        }
    }

    /**
     * 서버에 등록된 단말의 사용자 ID·상태 조회.
     *
     * @throws DeviceNotFoundException HTTP 404 (DEVICE_NOT_FOUND)
     */
    public DeviceStatusResponse getUserId(String deviceId) throws IOException {
        HttpUrl url =
                requireHttpUrl(baseUrl + ApiPaths.DEVICE_USER_ID)
                        .newBuilder()
                        .addQueryParameter("device_id", deviceId)
                        .build();
        Request request = new Request.Builder().url(url).get().build();
        // TODO: [실서비스] URL 쿼리 파라미터에 device_id 포함 — 마스킹 처리 필요
        Log.d(TAG, "→ getUserId url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← getUserId code=" + code);
            String body = response.body() != null ? response.body().string() : "";
            if (code == 200) {
                DeviceStatusResponse parsed = gson.fromJson(body, DeviceStatusResponse.class);
                if (parsed == null
                        || parsed.userId == null
                        || parsed.userId.trim().isEmpty()) {
                    throw new IOException("Missing user_id in response");
                }
                if (parsed.status == null || parsed.status.trim().isEmpty()) {
                    throw new IOException("Missing status in response");
                }
                return parsed;
            }
            if (code == 404) {
                throw new DeviceNotFoundException();
            }
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "getUserId 실패", e);
            throw e;
        }
    }

    /**
     * 계정 잠금 해제(PoC: ID/PW 검증 없이 호출 — 실서비스는 MIS 경유).
     *
     * @throws DeviceNotFoundException HTTP 404
     * @throws RuntimeException          HTTP 400 시 {@code NOT_LOCKED} 메시지
     */
    public boolean unlockDevice(String deviceId) throws IOException {
        UnlockDeviceRequest req = new UnlockDeviceRequest();
        req.deviceId = deviceId;
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.DEVICE_UNLOCK);
        Request request =
                new Request.Builder()
                        .url(url)
                        .put(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ unlockDevice url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← unlockDevice code=" + code);
            String body = response.body() != null ? response.body().string() : "";
            if (code == 200) {
                return true;
            }
            if (code == 400) {
                throw new RuntimeException("NOT_LOCKED");
            }
            if (code == 404) {
                throw new DeviceNotFoundException();
            }
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "unlockDevice 실패", e);
            throw e;
        }
    }

    /**
     * 사용자 변경 시 기기 등록 정보 완전 삭제.
     *
     * @throws DeviceNotFoundException HTTP 404
     * @throws RuntimeException         HTTP 403 USER_MISMATCH
     */
    public boolean unregisterDevice(String deviceId, String userId) throws IOException {
        UnregisterRequest req = new UnregisterRequest(deviceId, userId);
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.DEVICE_UNREGISTER);
        Request request =
                new Request.Builder()
                        .url(url)
                        .delete(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        // TODO: [실서비스] device_id 마스킹 처리 필요
        Log.d(TAG, "→ unregisterDevice deviceId=" + deviceId);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← unregisterDevice code=" + code);
            String body = response.body() != null ? response.body().string() : "";
            if (code == 200) {
                return true;
            }
            if (code == 404) {
                throw new DeviceNotFoundException();
            }
            if (code == 403) {
                throw new RuntimeException("USER_MISMATCH");
            }
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "unregisterDevice 실패", e);
            throw e;
        }
    }

    /**
     * 기기를 서버에 등록합니다 (POST /api/device/register).
     *
     * @param deviceId       기기 고유 ID
     * @param userId         등록할 사용자 ID
     * @param publicKeyBase64 EC 공개키 (Base64 인코딩)
     * @param enrolledAt     등록 시각 (ISO 8601 UTC, 예: "2024-01-01T00:00:00Z")
     * @return true이면 등록 성공, false이면 이미 등록된 기기(HTTP 409)
     * @throws IOException 네트워크 오류 시
     */
    public boolean registerDevice(
            String deviceId, String userId, String publicKeyBase64, String enrolledAt)
            throws IOException {
        RegisterRequest req = new RegisterRequest();
        req.deviceId = deviceId;
        req.userId = userId;
        req.publicKey = publicKeyBase64;
        req.enrolledAt = enrolledAt;
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.DEVICE_REGISTER);
        Request request =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ registerDevice 요청 전송 완료");
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← registerDevice code=" + code);
            if (code == 409) {
                return false;
            }
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            StatusResponse parsed = gson.fromJson(body, StatusResponse.class);
            return parsed != null && "REGISTERED".equals(parsed.status);
        } catch (IOException e) {
            Log.e(TAG, "registerDevice 실패", e);
            throw e;
        }
    }

    /**
     * 기기 키 상태를 "KEY_INVALIDATED"로 갱신합니다 (PUT /api/device/update-key).
     * Android Keystore에서 키가 무효화된 경우 서버에 상태를 동기화할 때 사용합니다.
     *
     * @param deviceId 기기 ID
     * @return true이면 갱신 성공, false이면 기기 없음(HTTP 404)
     * @throws IOException 네트워크 오류 시
     */
    public boolean updateKeyStatus(String deviceId) throws IOException {
        UpdateKeyRequest req = new UpdateKeyRequest();
        req.deviceId = deviceId;
        req.status = "KEY_INVALIDATED";
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.DEVICE_UPDATE_KEY);
        Request request =
                new Request.Builder()
                        .url(url)
                        .put(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ updateKeyStatus 요청 전송 완료");
        Log.d(TAG, "→ updateKeyStatus url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← updateKeyStatus code=" + code);
            if (code == 404) {
                return false;
            }
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "updateKeyStatus 실패", e);
            throw e;
        }
    }

    /**
     * 등록된 기기의 공개키를 갱신하고 상태를 ACTIVE로 유지합니다.
     *
     * @throws DeviceNotFoundException HTTP 404
     * @throws AccountLockedException HTTP 423
     */
    public boolean renewKey(String deviceId, String newPublicKeyBase64) throws IOException {
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.DEVICE_RENEW_KEY);
        Log.d(TAG, "→ renewKey url=" + url);
        RenewKeyRequest req = new RenewKeyRequest(deviceId, newPublicKeyBase64);
        String json = gson.toJson(req);
        Request request =
                new Request.Builder()
                        .url(url)
                        .put(RequestBody.create(json, JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← renewKey code=" + code);
            if (code == 200) {
                return true;
            }
            if (code == 404) {
                throw new DeviceNotFoundException();
            }
            if (code == 423) {
                throw new AccountLockedException();
            }
            String body = response.body() != null ? response.body().string() : "";
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "renewKey 실패", e);
            throw e;
        }
    }

    /**
     * 서명 대상 챌린지를 서버에 요청합니다 (POST /api/auth/challenge).
     *
     * @param req 챌린지 요청 데이터 (deviceId, userId, clientNonce, timestamp)
     * @return 챌린지 응답 (sessionId, serverChallenge 포함)
     * @throws DeviceNotFoundException 기기 미등록 (HTTP 404)
     * @throws AccountLockedException 계정 잠금 (HTTP 423)
     * @throws KeyInvalidatedException 키 무효화 (HTTP 409)
     * @throws IOException 네트워크 오류 시
     */
    public ChallengeResponse getChallenge(ChallengeRequest req) throws IOException {
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.AUTH_CHALLENGE);
        Request request =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ getChallenge url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← getChallenge code=" + code);
            String body = response.body() != null ? response.body().string() : "";
            if (code == 200) {
                ChallengeResponse parsed = gson.fromJson(body, ChallengeResponse.class);
                requireChallengeResponse(parsed);
                return parsed;
            }
            if (code == 404) {
                throw new DeviceNotFoundException();
            }
            if (code == 423) {
                throw new AccountLockedException();
            }
            if (code == 409) {
                throw new KeyInvalidatedException();
            }
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "getChallenge 실패", e);
            throw e;
        }
    }

    /**
     * ECDSA 서명을 전송하고 액세스 토큰을 발급받습니다 (POST /api/auth/token).
     *
     * @param req 토큰 요청 데이터 (sessionId, deviceId, userId, ecSignature, clientNonce, timestamp)
     * @return 토큰 응답 (accessToken, refreshToken, expiresIn 포함)
     * @throws TokenVerificationException 서명 검증 실패 또는 세션 만료 (HTTP 401, errorCode 포함)
     * @throws IOException 네트워크 오류 시
     */
    public TokenResponse requestToken(TokenRequest req) throws IOException {
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.AUTH_TOKEN);
        Request request =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ requestToken 요청 전송 완료");
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← requestToken code=" + code);
            String body = response.body() != null ? response.body().string() : "";
            if (code == 200) {
                TokenResponse parsed = gson.fromJson(body, TokenResponse.class);
                requireTokenResponse(parsed);
                return parsed;
            }
            if (code == 401) {
                ApiErrorJson err = gson.fromJson(body, ApiErrorJson.class);
                String errorCode =
                        err != null && err.error != null && !err.error.trim().isEmpty()
                                ? err.error
                                : "UNKNOWN";
                throw new TokenVerificationException(errorCode);
            }
            throw new RuntimeException("HTTP " + code + ": " + body);
        } catch (IOException e) {
            Log.e(TAG, "requestToken 실패", e);
            throw e;
        }
    }

    /**
     * 서버에서 인증 실패 정책을 조회합니다 (GET /api/policy/failure-config).
     * FailurePolicyManager에 의해 5분 단위로 캐시됩니다.
     *
     * @param deviceId 기기 ID (정책이 기기별로 다를 수 있음)
     * @return 실패 정책 설정 (maxRetryBeforeLockout, lockoutSeconds, accountLockThreshold 등)
     * @throws IOException 네트워크 오류 시
     */
    public FailurePolicyConfig getFailurePolicy(String deviceId) throws IOException {
        HttpUrl url =
                requireHttpUrl(baseUrl + ApiPaths.POLICY_FAILURE_CONFIG)
                        .newBuilder()
                        .addQueryParameter("device_id", deviceId)
                        .build();
        Request request = new Request.Builder().url(url).get().build();
        Log.d(TAG, "→ getFailurePolicy url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← getFailurePolicy code=" + code);
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            FailurePolicyConfig parsed = gson.fromJson(body, FailurePolicyConfig.class);
            requireFailurePolicy(parsed);
            return parsed;
        } catch (IOException e) {
            Log.e(TAG, "getFailurePolicy 실패", e);
            throw e;
        }
    }

    /**
     * 인증 실패 횟수 초과 시 계정 잠금을 서버에 요청합니다 (POST /api/auth/account-lock).
     * FailurePolicyManager.shouldRequestAccountLock() 조건 충족 시 BiometricAuthManager에 의해 호출됩니다.
     *
     * @param deviceId 기기 ID
     * @param userId   잠금 대상 사용자 ID
     * @return true이면 잠금 성공
     * @throws IOException 네트워크 오류 시
     */
    public boolean lockAccount(String deviceId, String userId) throws IOException {
        AccountLockRequest req = new AccountLockRequest();
        req.deviceId = deviceId;
        req.userId = userId;
        HttpUrl url = requireHttpUrl(baseUrl + ApiPaths.AUTH_ACCOUNT_LOCK);
        Request request =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        Log.d(TAG, "→ lockAccount url=" + url);
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "← lockAccount code=" + code);
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            StatusResponse parsed = gson.fromJson(body, StatusResponse.class);
            return parsed != null && "LOCKED".equals(parsed.status);
        } catch (IOException e) {
            Log.e(TAG, "lockAccount 실패", e);
            throw e;
        }
    }

    private RuntimeException httpError(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        return new RuntimeException("HTTP " + response.code() + ": " + body);
    }

    private static class ApiErrorJson {
        @SerializedName("error")
        String error;
    }

    public static class RegisterRequest {
        @SerializedName("device_id")
        public String deviceId;

        @SerializedName("user_id")
        public String userId;

        @SerializedName("public_key")
        public String publicKey;

        @SerializedName("enrolled_at")
        public String enrolledAt;
    }

    public static class UpdateKeyRequest {
        @SerializedName("device_id")
        public String deviceId;

        public String status;
    }

    /** POST /api/auth/challenge 요청 본문 DTO. */
    public static class ChallengeRequest {
        @SerializedName("device_id")
        public String deviceId;      // 기기 고유 ID

        @SerializedName("user_id")
        public String userId;        // 인증 요청 사용자 ID

        @SerializedName("client_nonce")
        public String clientNonce;   // 재전송 공격 방지용 클라이언트 nonce (16바이트 랜덤 16진수)

        public long timestamp;       // 요청 시각 (epoch ms, 서버 TIMESTAMP_OUT_OF_RANGE 검증 기준)
    }

    /** POST /api/auth/challenge 응답 본문 DTO. */
    public static class ChallengeResponse {
        @SerializedName("session_id")
        public String sessionId;       // 서버 세션 ID (토큰 요청 시 재사용)

        @SerializedName("server_challenge")
        public String serverChallenge; // 서버 챌린지 값 (재전송 공격 방지용 서버 nonce)

        @SerializedName("expire_at")
        public long expireAt;          // 챌린지 만료 시각 (epoch ms)
    }

    /** POST /api/auth/token 요청 본문 DTO. */
    public static class TokenRequest {
        @SerializedName("session_id")
        public String sessionId;    // 챌린지 응답에서 받은 세션 ID

        @SerializedName("device_id")
        public String deviceId;     // 기기 ID

        @SerializedName("user_id")
        public String userId;       // 사용자 ID

        @SerializedName("ec_signature")
        public String ecSignature;  // ECDSA SHA-256 서명값 (Base64, 민감 정보 — 로그 출력 금지)

        @SerializedName("client_nonce")
        public String clientNonce;  // 챌린지 요청 시 사용한 클라이언트 nonce

        public long timestamp;      // 챌린지 요청 시각 (epoch ms)
    }

    /** POST /api/auth/token 응답 본문 DTO. */
    public static class TokenResponse {
        @SerializedName("access_token")
        public String accessToken;  // 발급된 액세스 토큰 (민감 정보 — 로그 출력 금지)

        @SerializedName("refresh_token")
        public String refreshToken; // 발급된 리프레시 토큰 (민감 정보 — 로그 출력 금지)

        @SerializedName("expires_in")
        public int expiresIn;       // 토큰 유효 시간(초)
    }

    /** GET /api/policy/failure-config 응답 본문 DTO. */
    public static class FailurePolicyConfig {
        @SerializedName("max_retry_before_lockout")
        public int maxRetryBeforeLockout;  // 일시 잠금 전 허용 실패 횟수

        @SerializedName("lockout_seconds")
        public int lockoutSeconds;         // 일시 잠금 시간(초)

        @SerializedName("account_lock_threshold")
        public int accountLockThreshold;   // 계정 잠금 요청 임계 실패 횟수

        @SerializedName("fallback_password_enabled")
        public boolean fallbackPasswordEnabled; // ID/PW 폴백 허용 여부
    }

    /** GET /api/device/user-id 응답 본문 DTO. */
    public static class DeviceStatusResponse {
        @SerializedName("user_id")
        public String userId; // 서버에 등록된 사용자 ID

        @SerializedName("status")
        public String status; // 기기 상태 (예: "ACTIVE", "LOCKED", "KEY_INVALIDATED")
    }

    private static class RenewKeyRequest {
        @SerializedName("device_id")
        String deviceId;

        @SerializedName("new_public_key")
        String newPublicKey;

        RenewKeyRequest(String deviceId, String newPublicKey) {
            this.deviceId = deviceId;
            this.newPublicKey = newPublicKey;
        }
    }

    private static class UnlockDeviceRequest {
        @SerializedName("device_id")
        String deviceId;
    }

    private static class UnregisterRequest {
        @SerializedName("device_id")
        String deviceId;

        @SerializedName("user_id")
        String userId;

        UnregisterRequest(String deviceId, String userId) {
            this.deviceId = deviceId;
            this.userId = userId;
        }
    }

    private static class StatusResponse {
        String status;
    }

    private static class AccountLockRequest {
        @SerializedName("device_id")
        String deviceId;

        @SerializedName("user_id")
        String userId;
    }
}
