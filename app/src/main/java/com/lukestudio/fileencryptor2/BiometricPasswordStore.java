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

/**
 * Stores the user's default password encrypted by an Android Keystore key.
 * The Keystore key requires biometric authentication for every use.
 */
public final class BiometricPasswordStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "SFileEncryptorBiometricKey";
    private static final String PREFS = "biometric_password";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_IV = "iv";
    private static final String PREF_CIPHERTEXT = "ciphertext";

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

    public static Cipher createEncryptionCipher(Context context) throws Exception {
        ensureKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        return cipher;
    }

    public static Cipher createDecryptionCipher(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String encodedIv = p.getString(PREF_IV, null);
        if (encodedIv == null) {
            throw new IllegalStateException("Biometria não configurada.");
        }

        ensureKey();
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getKey(),
                    new GCMParameterSpec(128, iv)
            );
            return cipher;
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void saveEncryptedPassword(
            Context context,
            Cipher cipher,
            char[] password
    ) throws Exception {
        byte[] plain = new String(password).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            encrypted = cipher.doFinal(plain);
            prefs(context).edit()
                    .putBoolean(PREF_ENABLED, true)
                    .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply();
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static char[] decryptPassword(Context context, Cipher cipher) throws Exception {
        String encoded = prefs(context).getString(PREF_CIPHERTEXT, null);
        if (encoded == null) {
            throw new IllegalStateException("Biometria não configurada.");
        }

        byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] plain = null;
        try {
            plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public static void disable(Context context) {
        prefs(context).edit().clear().apply();

        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
        }
    }

    private static void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );

        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
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

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }
}
