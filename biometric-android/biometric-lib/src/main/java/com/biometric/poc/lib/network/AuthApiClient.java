package com.biometric.poc.lib.network;

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
 * 동기 API 클라이언트 — 호출은 백그라운드 스레드에서 수행할 것.
 *
 * <p>TODO: [실서비스] OkHttp에 Certificate Pinning, 재시도 정책, 인터셉터(로깅 시 토큰 마스킹) 적용.
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

    public static class ChallengeRequest {
        @SerializedName("device_id")
        public String deviceId;

        @SerializedName("user_id")
        public String userId;

        @SerializedName("client_nonce")
        public String clientNonce;

        public long timestamp;
    }

    public static class ChallengeResponse {
        @SerializedName("session_id")
        public String sessionId;

        @SerializedName("server_challenge")
        public String serverChallenge;

        @SerializedName("expire_at")
        public long expireAt;
    }

    public static class TokenRequest {
        @SerializedName("session_id")
        public String sessionId;

        @SerializedName("device_id")
        public String deviceId;

        @SerializedName("user_id")
        public String userId;

        @SerializedName("ec_signature")
        public String ecSignature;

        @SerializedName("client_nonce")
        public String clientNonce;

        public long timestamp;
    }

    public static class TokenResponse {
        @SerializedName("access_token")
        public String accessToken;

        @SerializedName("refresh_token")
        public String refreshToken;

        @SerializedName("expires_in")
        public int expiresIn;
    }

    public static class FailurePolicyConfig {
        @SerializedName("max_retry_before_lockout")
        public int maxRetryBeforeLockout;

        @SerializedName("lockout_seconds")
        public int lockoutSeconds;

        @SerializedName("account_lock_threshold")
        public int accountLockThreshold;

        @SerializedName("fallback_password_enabled")
        public boolean fallbackPasswordEnabled;
    }

    public static class DeviceStatusResponse {
        @SerializedName("user_id")
        public String userId;

        @SerializedName("status")
        public String status;
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
