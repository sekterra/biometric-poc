package com.biometric.poc.lib.network;

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

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 앱 생명주기 동안 연결 풀·DNS 캐시를 재사용하기 위한 공유 클라이언트. */
    private static final OkHttpClient SHARED_CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
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
        if (r.sessionId == null || r.sessionId.isBlank()) {
            throw new IOException("Missing session_id in challenge response");
        }
        if (r.serverChallenge == null || r.serverChallenge.isBlank()) {
            throw new IOException("Missing server_challenge in challenge response");
        }
    }

    private static void requireTokenResponse(TokenResponse r) throws IOException {
        if (r == null) {
            throw new IOException("Empty token response body");
        }
        if (r.accessToken == null || r.accessToken.isBlank()) {
            throw new IOException("Missing access_token in token response");
        }
        if (r.refreshToken == null || r.refreshToken.isBlank()) {
            throw new IOException("Missing refresh_token in token response");
        }
    }

    private static void requireFailurePolicy(FailurePolicyConfig r) throws IOException {
        if (r == null) {
            throw new IOException("Empty failure policy response body");
        }
    }

    public String getUserId(String deviceId) throws IOException {
        HttpUrl url =
                requireHttpUrl(baseUrl + "/api/device/user-id")
                        .newBuilder()
                        .addQueryParameter("device_id", deviceId)
                        .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            UserIdResponse parsed = gson.fromJson(body, UserIdResponse.class);
            return parsed != null ? parsed.userId : null;
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
        Request request =
                new Request.Builder()
                        .url(requireHttpUrl(baseUrl + "/api/device/register"))
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 409) {
                return false;
            }
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            StatusResponse parsed = gson.fromJson(body, StatusResponse.class);
            return parsed != null && "REGISTERED".equals(parsed.status);
        }
    }

    public boolean updateKeyStatus(String deviceId) throws IOException {
        UpdateKeyRequest req = new UpdateKeyRequest();
        req.deviceId = deviceId;
        req.status = "KEY_INVALIDATED";
        Request request =
                new Request.Builder()
                        .url(requireHttpUrl(baseUrl + "/api/device/update-key"))
                        .put(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404) {
                return false;
            }
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            return true;
        }
    }

    public ChallengeResponse getChallenge(ChallengeRequest req) throws IOException {
        Request request =
                new Request.Builder()
                        .url(requireHttpUrl(baseUrl + "/api/auth/challenge"))
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            ChallengeResponse parsed = gson.fromJson(body, ChallengeResponse.class);
            requireChallengeResponse(parsed);
            return parsed;
        }
    }

    public TokenResponse requestToken(TokenRequest req) throws IOException {
        Request request =
                new Request.Builder()
                        .url(requireHttpUrl(baseUrl + "/api/auth/token"))
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            TokenResponse parsed = gson.fromJson(body, TokenResponse.class);
            requireTokenResponse(parsed);
            return parsed;
        }
    }

    public FailurePolicyConfig getFailurePolicy(String deviceId) throws IOException {
        HttpUrl url =
                requireHttpUrl(baseUrl + "/api/policy/failure-config")
                        .newBuilder()
                        .addQueryParameter("device_id", deviceId)
                        .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            FailurePolicyConfig parsed = gson.fromJson(body, FailurePolicyConfig.class);
            requireFailurePolicy(parsed);
            return parsed;
        }
    }

    public boolean lockAccount(String deviceId, String userId) throws IOException {
        AccountLockRequest req = new AccountLockRequest();
        req.deviceId = deviceId;
        req.userId = userId;
        Request request =
                new Request.Builder()
                        .url(requireHttpUrl(baseUrl + "/api/auth/account-lock"))
                        .post(RequestBody.create(gson.toJson(req), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw httpError(response);
            }
            String body = response.body() != null ? response.body().string() : "";
            StatusResponse parsed = gson.fromJson(body, StatusResponse.class);
            return parsed != null && "LOCKED".equals(parsed.status);
        }
    }

    private RuntimeException httpError(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        return new RuntimeException("HTTP " + response.code() + ": " + body);
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

    private static class UserIdResponse {
        @SerializedName("user_id")
        String userId;
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
