package com.skcc.biometric.lib.network;

/**
 * 서버 토큰 엔드포인트에서 서명·세션·타임스탬프·nonce 검증 실패 시(HTTP 401, body {@code error}).
 */
public class TokenVerificationException extends RuntimeException {

    private final String errorCode;

    public TokenVerificationException(String errorCode) {
        super(errorCode != null ? errorCode : "UNKNOWN");
        this.errorCode = errorCode != null ? errorCode : "UNKNOWN";
    }

    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String getMessage() {
        return errorCode;
    }
}
