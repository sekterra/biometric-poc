package com.biometric.poc.lib.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.biometric.BiometricPrompt;

import com.biometric.poc.lib.BiometricLibConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.ECGenParameterSpec;

public class EcKeyManager {

    public static final String KEY_ALIAS = "biometric_poc_ec_key";

    /**
     * Android Keystore에 EC 키쌍(secp256r1)을 생성합니다.
     *
     * <p><b>[deprecated 처리 이유]</b><br>
     * {@code setUserAuthenticationValidityDurationSeconds(int)}는 API 30(Android 11)에서
     * deprecated되었습니다. PoC 범위에서는 {@code @SuppressWarnings("deprecation")}으로
     * 경고를 억제하고 있습니다.
     *
     * <p><b>[실서비스 전환 시 교체 방법]</b><br>
     * minSdk를 31 이상으로 올리는 경우:
     * <pre>
     * builder.setUserAuthenticationParameters(
     *     10,
     *     KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL
     * );
     * </pre>
     *
     * minSdk 28을 유지하는 경우 (API 분기 처리):
     * <pre>
     * if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
     *     builder.setUserAuthenticationParameters(
     *         10,
     *         KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL
     *     );
     * } else {
     *     builder.setUserAuthenticationValidityDurationSeconds(10);
     * }
     * </pre>
     *
     * <p><b>[컴파일 오류 비고]</b><br>
     * {@code UserAuthenticationParameters} 심볼이 인식되지 않는 경우
     * SDK 플랫폼 jar 캐시 문제일 수 있습니다.
     * Android Studio에서 직접 빌드하거나 SDK를 재설치한 후 재확인을 권장합니다.
     *
     * @throws InvalidAlgorithmParameterException KeyGenParameterSpec 초기화 실패 시
     * @throws KeyStoreException                  AndroidKeyStore 접근 실패 시
     */
    @SuppressWarnings("deprecation")
    public void generateKeyPair()
            throws GeneralSecurityException, IOException, CertificateException, NoSuchAlgorithmException {
        KeyPairGenerator kpg =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");

        KeyGenParameterSpec spec =
                new KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationValidityDurationSeconds(
                                BiometricLibConstants.KEY_AUTH_VALIDITY_SECONDS)
                        .setInvalidatedByBiometricEnrollment(true)
                        .build();

        kpg.initialize(spec);
        kpg.generateKeyPair();
    }

    public boolean isKeyGenerated()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks.containsAlias(KEY_ALIAS);
    }

    public String getPublicKeyBase64()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate certificate = ks.getCertificate(KEY_ALIAS);
        if (certificate == null) {
            throw new IllegalStateException("No certificate for alias: " + KEY_ALIAS);
        }
        byte[] encoded = certificate.getPublicKey().getEncoded();
        return Base64.encodeToString(encoded, Base64.NO_WRAP);
    }

    public BiometricPrompt.CryptoObject buildCryptoObject()
            throws GeneralSecurityException, IOException, CertificateException, NoSuchAlgorithmException,
                    UnrecoverableKeyException, KeyStoreException, KeyPermanentlyInvalidatedException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        PrivateKey privateKey = (PrivateKey) ks.getKey(KEY_ALIAS, null);
        if (privateKey == null) {
            throw new IllegalStateException("No private key for alias: " + KEY_ALIAS);
        }
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        return new BiometricPrompt.CryptoObject(sig);
    }

    public String sign(BiometricPrompt.CryptoObject cryptoObject, byte[] payload)
            throws GeneralSecurityException {
        Signature sig = cryptoObject.getSignature();
        sig.update(payload);
        byte[] signature = sig.sign();
        return Base64.encodeToString(signature, Base64.NO_WRAP);
    }

    public void deleteKeyPair()
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(KEY_ALIAS);
    }
}
