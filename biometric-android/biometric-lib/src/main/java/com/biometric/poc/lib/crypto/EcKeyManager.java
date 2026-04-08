package com.biometric.poc.lib.crypto;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.ECGenParameterSpec;

public class EcKeyManager {

    private final String keyAlias;

    /**
     * @param keyAlias Android Keystore 키 별칭. 앱 패키지명 기반으로 전달할 것.
     *                 예) BuildConfig.APPLICATION_ID + ".biometric_ec_key"
     */
    public EcKeyManager(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    /**
     * Android Keystore에 EC 키쌍(secp256r1)을 생성합니다.
     *
     * <p>[갤럭시 탭 PoC 대응] {@code BIOMETRIC_WEAK}(안면인식)는 Keystore의 {@code
     * setUserAuthenticationRequired(true)}를 충족하지 못해 {@code UserNotAuthenticatedException}이
     * 날 수 있습니다. PoC에서는 해당 플래그를 쓰지 않고 {@code BiometricPrompt} 자체를 인증 게이트로
     * 둡니다.
     *
     * <p><b>보안 영향(요약)</b><br>
     * Keystore 하드웨어 보호·{@code setInvalidatedByBiometricEnrollment}·서버 ECDSA 검증·{@code
     * BiometricPrompt} 게이트는 유지됩니다. 다만 앱 프로세스에 물리적 접근이 있다면 생체 없이 서명 호출이
     * 이론상 가능해지므로, 실서비스에서는 아래 TODO 중 하나로 강화해야 합니다.
     *
     * <p>TODO: [실서비스] 아래 중 선택
     *
     * <ul>
     *   <li>방안 1. {@code BIOMETRIC_STRONG} 기기(지문 등) 전용 — {@code
     *       setUserAuthenticationRequired(true)}, {@code
     *       setUserAuthenticationValidityDurationSeconds(-1)} (또는 플랫폼에 맞는 파라미터)
     *   <li>방안 2. API 31+ {@code setUserAuthenticationParameters(10, AUTH_BIOMETRIC_STRONG |
     *       AUTH_DEVICE_CREDENTIAL)}
     *   <li>방안 3. 현장이 {@code BIOMETRIC_WEAK} 전용이면 서버 ECDSA 검증·정책으로 보완 (현 PoC 구조)
     * </ul>
     *
     * @throws GeneralSecurityException KeyGenParameterSpec 초기화·키 생성 실패 시
     */
    public void generateKeyPair()
            throws GeneralSecurityException, IOException, CertificateException, NoSuchAlgorithmException {
        KeyPairGenerator kpg =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");

        KeyGenParameterSpec.Builder specBuilder =
                new KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256);
        // setUserAuthenticationRequired(true) — 제거 (BIOMETRIC_WEAK PoC)
        // setUserAuthenticationValidityDurationSeconds(10) — 제거

        // API 24(N) 미만에서는 setInvalidatedByBiometricEnrollment 미지원
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            specBuilder.setInvalidatedByBiometricEnrollment(true);
        }

        KeyGenParameterSpec spec = specBuilder.build();

        kpg.initialize(spec);
        kpg.generateKeyPair();
    }

    public boolean isKeyGenerated()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks.containsAlias(keyAlias);
    }

    public String getPublicKeyBase64()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate certificate = ks.getCertificate(keyAlias);
        if (certificate == null) {
            throw new IllegalStateException("No certificate for alias: " + keyAlias);
        }
        byte[] encoded = certificate.getPublicKey().getEncoded();
        return Base64.encodeToString(encoded, Base64.NO_WRAP);
    }

    /**
     * 페이로드에 ECDSA 서명합니다. PoC에서는 Keystore 키에 사용자 인증이 묶여 있지 않으므로, 호출은 {@code
     * BiometricPrompt} 성공 이후에만 이루어져야 합니다(앱 레벨 게이트).
     *
     * @throws KeyNotFoundException Keystore에 해당 별칭의 개인키가 없을 때
     */
    public String signPayload(byte[] payload)
            throws GeneralSecurityException, IOException, KeyNotFoundException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(keyAlias, null);
        if (privateKey == null) {
            throw new KeyNotFoundException("로컬 키가 존재하지 않습니다. 재등록이 필요합니다.");
        }
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(payload);
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP);
    }

    public void deleteKeyPair()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(keyAlias);
    }
}
