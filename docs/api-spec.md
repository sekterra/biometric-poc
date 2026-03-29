# API 명세 (ECDSA 방식)

Base URL: http://10.0.2.2:8080  (에뮬레이터 기준 / 실기기는 서버 IP로 교체)

## POST /api/device/register
Request:  { "device_id": "string", "user_id": "string",
            "public_key": "string (Base64, X.509)", "enrolled_at": "string (ISO8601)" }
Response 200: { "status": "REGISTERED" }
Response 409: { "error": "ALREADY_REGISTERED" }

## PUT /api/device/update-key
Request:  { "device_id": "string", "status": "KEY_INVALIDATED" }
Response 200: { "status": "OK" }
Response 404: { "error": "DEVICE_NOT_FOUND" }

## GET /api/device/user-id?device_id={id}
Response 200: { "user_id": "USER01" }   ← PoC 시나리오, 항상 USER01 반환

## POST /api/auth/challenge
Request:  { "device_id": "string", "user_id": "string",
            "client_nonce": "string (hex32)", "timestamp": "long (unix ms)" }
Response 200: { "session_id": "string", "server_challenge": "string (hex64)",
                "expire_at": "long (unix ms)" }
Response 404: { "error": "DEVICE_NOT_FOUND" }
Response 409: { "error": "KEY_INVALIDATED" }
Response 423: { "error": "ACCOUNT_LOCKED" }

## POST /api/auth/token
Request:  { "session_id": "string", "device_id": "string", "user_id": "string",
            "ec_signature": "string (Base64, DER)", "client_nonce": "string (hex32)",
            "timestamp": "long (unix ms)" }
Response 200: { "access_token": "string", "refresh_token": "string", "expires_in": 1800 }
Response 401: { "error": "SESSION_EXPIRED" | "TIMESTAMP_OUT_OF_RANGE" |
                          "NONCE_REPLAY" | "INVALID_SIGNATURE" }

## GET /api/policy/failure-config?device_id={id}
Response 200: { "max_retry_before_lockout": 3, "lockout_seconds": 30,
                "account_lock_threshold": 5, "fallback_password_enabled": true }

## POST /api/auth/account-lock
Request:  { "device_id": "string", "user_id": "string" }
Response 200: { "status": "LOCKED" }
