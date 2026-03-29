package com.biometric.poc.util;

import java.util.Map;

/** 컨트롤러에서 반복되던 {@code Map.of("error", ...)} 패턴 정리. */
public final class ApiErrorBodies {

    private ApiErrorBodies() {}

    public static Map<String, String> error(String code) {
        return Map.of("error", code);
    }

    public static Map<String, String> error(String code, String detailKey, String detailValue) {
        return Map.of("error", code, detailKey, detailValue);
    }
}
