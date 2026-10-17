
package com.lukestudio.fileencryptor2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class BiometricPasswordStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "SFileEncryptorBiometricKey";
    private static final String PREFS = "biometric_password";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_IV = "iv";
    private static final String PREF_CIPHERTEXT = "ciphertext";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private BiometricPasswordStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences p = prefs(context);

        return p.getBoolean(PREF_ENABLED, false)
                && p.contains(PREF_IV)
                && p.contains(PREF_CIPHERTEXT);
    }

    public static Cipher createEncryptionCipher(Context context)
            throws Exception {

        // Na configuração da criptografia, pode criar uma chave.
        ensureKey();

        SecretKey key = getExistingKey();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        return cipher;
    }

    public static Cipher createDecryptionCipher(Context context)
            throws Exception {

        SharedPreferences p = prefs(context);

        String encodedIv = p.getString(PREF_IV, null);
        String encodedCiphertext =
                p.getString(PREF_CIPHERTEXT, null);

        if (encodedIv == null || encodedCiphertext == null) {
            throw new IllegalStateException(
                    "Biometria não configurada."
            );
        }

        // IMPORTANTE: não criar uma nova chave durante a descriptografia.
        // É necessária a mesma chave que criptografou a senha original.
        SecretKey key = getExistingKey();

        byte[] iv = null;

        try {
            iv = Base64.decode(encodedIv, Base64.NO_WRAP);

            if (iv.length != GCM_IV_LENGTH) {
                throw new IllegalStateException(
                        "IV biométrico inválido."
                );
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );

            return cipher;

        } finally {
            if (iv != null) {
                Arrays.fill(iv, (byte) 0);
            }
        }
    }

    public static void saveEncryptedPassword(
            Context context,
            Cipher cipher,
            char[] password
    ) throws Exception {

        byte[] plain = new String(password)
                .getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = null;

        try {
            encrypted = cipher.doFinal(plain);

            byte[] iv = cipher.getIV();

            if (iv == null || iv.length != GCM_IV_LENGTH) {
                throw new IllegalStateException(
                        "IV de criptografia inválido."
                );
            }

            boolean saved = prefs(context).edit()
                    .putString(
                            PREF_IV,
                            Base64.encodeToString(iv, Base64.NO_WRAP)
                    )
                    .putString(
                            PREF_CIPHERTEXT,
                            Base64.encodeToString(
                                    encrypted,
                                    Base64.NO_WRAP
                            )
                    )
                    .putBoolean(PREF_ENABLED, true)
                    .commit();

            if (!saved) {
                throw new IllegalStateException(
                        "Não foi possível salvar a configuração biométrica."
                );
            }

        } finally {
            Arrays.fill(plain, (byte) 0);

            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    public static char[] decryptPassword(
            Context context,
            Cipher cipher
    ) throws Exception {

        String encoded = prefs(context).getString(
                PREF_CIPHERTEXT,
                null
        );

        if (encoded == null) {
            throw new IllegalStateException(
                    "Biometria não configurada."
            );
        }

        byte[] encrypted = Base64.decode(
                encoded,
                Base64.NO_WRAP
        );

        byte[] plain = null;

        try {
            // AES-GCM verifica a integridade antes de retornar o texto.
            plain = cipher.doFinal(encrypted);

            return new String(
                    plain,
                    StandardCharsets.UTF_8
            ).toCharArray();

        } finally {
            Arrays.fill(encrypted, (byte) 0);

            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    public static void disable(Context context) {
        // Mantém o comportamento de desativação explícita pelo usuário.
        prefs(context).edit().clear().commit();

        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);

            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
            }

        } catch (Exception ignored) {
            // A desativação não deve interromper a interface.
        }
    }

    private static void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);

        if (ks.containsAlias(KEY_ALIAS)) {
            return;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );

        KeyGenParameterSpec.Builder builder =
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT
                                | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        .setUserAuthenticationRequired(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
            );
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(0);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true);
        }

        generator.init(builder.build());
        generator.generateKey();
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);

        if (!ks.containsAlias(KEY_ALIAS)) {
            throw new IllegalStateException(
                    "A chave biométrica original não foi encontrada. "
                            + "É necessário configurar a biometria novamente."
            );
        }

        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);

        if (key == null) {
            throw new IllegalStateException(
                    "Não foi possível recuperar a chave biométrica."
            );
        }

        return key;
    }
}
