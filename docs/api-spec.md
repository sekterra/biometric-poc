# API 명세 (ECDSA 방식)

Base URL: http://10.0.2.2:8080  (에뮬레이터 기준 / 실기기는 서버 IP로 교체)

## POST /api/device/register
Request:  { "device_id": "string", "user_id": "string",
            "public_key": "string (Base64, X.509)", "enrolled_at": "string (ISO8601)" }
Response 200: { "status": "REGISTERED" }  — 신규 등록
Response 200: { "status": "RE_REGISTERED" }  — 기존 기기가 KEY_INVALIDATED 인 경우 재등록(공개키·ACTIVE 복구)
Response 409: { "error": "ALREADY_REGISTERED" }  — ACTIVE 또는 LOCKED 인 기기

## PUT /api/device/update-key
Request:  { "device_id": "string", "status": "KEY_INVALIDATED" }
Response 200: { "status": "OK" }
Response 404: { "error": "DEVICE_NOT_FOUND" }

## [신규] PUT /api/device/renew-key
Request:  { "device_id": "string", "new_public_key": "string (Base64)" }
Response 200: { "status": "RENEWED" }
Response 404: { "error": "DEVICE_NOT_FOUND" }
Response 423: { "error": "ACCOUNT_LOCKED" }

## GET /api/device/user-id?device_id={id}
Response 200: { "user_id": "string", "status": "ACTIVE|LOCKED|KEY_INVALIDATED" }
Response 404: { "error": "DEVICE_NOT_FOUND" }

## PUT /api/device/unlock
Request:  { "device_id": "string" }
Response 200: { "status": "ACTIVE" }
Response 400: { "error": "NOT_LOCKED" }
Response 404: { "error": "DEVICE_NOT_FOUND" }

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
