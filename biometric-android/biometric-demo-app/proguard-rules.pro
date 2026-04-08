# biometric-lib 네트워크 모델 보존 (Gson 직렬화)
-keep class com.biometric.poc.lib.network.** { *; }

# ErrorCode enum 보존
-keep enum com.biometric.poc.lib.ErrorCode { *; }

# Keystore 관련 클래스 보존
-keep class com.biometric.poc.lib.crypto.** { *; }

# 콜백 인터페이스 보존
-keep interface com.biometric.poc.lib.auth.** { *; }
