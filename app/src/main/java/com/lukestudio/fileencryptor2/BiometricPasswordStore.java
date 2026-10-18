package com.lukestudio.fileencryptor2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.WeakHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda a senha de uso biométrico cifrada com uma chave não exportável do Android Keystore.
 * A senha em texto claro nunca é gravada em SharedPreferences.
 */
public final class BiometricPasswordStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "SFileEncryptorBiometricKey";
    private static final String PREFS = "biometric_password";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_IV = "iv";
    private static final String KEY_CIPHERTEXT = "ciphertext";
    private static final int GCM_TAG_BITS = 128;

    // Só aceita objetos Cipher criados por esta classe, e apenas uma vez.
    private static final WeakHashMap<Cipher, Boolean> ENCRYPTION_CIPHERS = new WeakHashMap<>();
    private static final WeakHashMap<Cipher, Boolean> DECRYPTION_CIPHERS = new WeakHashMap<>();

    private BiometricPasswordStore() { }

    private static void registerCipher(Cipher cipher, boolean encryption) {
        synchronized (ENCRYPTION_CIPHERS) {
            (encryption ? ENCRYPTION_CIPHERS : DECRYPTION_CIPHERS).put(cipher, Boolean.TRUE);
        }
    }

    private static boolean consumeCipher(Cipher cipher, boolean encryption) {
        synchronized (ENCRYPTION_CIPHERS) {
            return (encryption ? ENCRYPTION_CIPHERS : DECRYPTION_CIPHERS).remove(cipher) != null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = loadKeyStore();
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw new IllegalStateException("Chave biométrica não encontrada.");
        }
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new IllegalStateException("Chave biométrica inválida.");
        }
        return (SecretKey) key;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
            throw new IllegalStateException("Chave biométrica inválida.");
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            // Autenticação necessária a cada uso da chave.
            builder.setUserAuthenticationValidityDurationSeconds(-1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    public static Cipher createEncryptionCipher(Context context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        } catch (KeyPermanentlyInvalidatedException invalidated) {
            // Este método deve ser chamado apenas no fluxo explícito de cadastro/alteração.
            // A chave antiga já não pode ser usada; remova primeiro os dados cifrados
            // antigos para não deixar a interface indicar que a biometria continua ativa.
            SharedPreferences p = prefs(context);
            boolean cleared = p.edit()
                    .remove(KEY_ENABLED)
                    .remove(KEY_IV)
                    .remove(KEY_CIPHERTEXT)
                    .commit();
            if (!cleared) {
                throw new IllegalStateException(
                        "Não foi possível limpar a configuração biométrica inválida.",
                        invalidated
                );
            }

            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                throw new IllegalStateException("Não foi possível remover a chave biométrica inválida.");
            }

            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        }
        registerCipher(cipher, true);
        return cipher;
    }

    public static Cipher createDecryptionCipher(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String ivText = p.getString(KEY_IV, null);
        String encryptedText = p.getString(KEY_CIPHERTEXT, null);
        if (!p.getBoolean(KEY_ENABLED, false)
                || ivText == null
                || encryptedText == null
                || encryptedText.length() < 24
                || encryptedText.length() > 1_048_576) {
            throw new IllegalStateException("Senha biométrica não configurada ou inválida.");
        }
        byte[] iv = null;
        try {
            iv = Base64.decode(ivText, Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalStateException("IV biométrico inválido.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            } catch (KeyPermanentlyInvalidatedException invalidated) {
                boolean cleared = p.edit()
                        .remove(KEY_ENABLED)
                        .remove(KEY_IV)
                        .remove(KEY_CIPHERTEXT)
                        .commit();
                if (!cleared) {
                    throw new IllegalStateException(
                            "Não foi possível limpar a configuração biométrica inválida.",
                            invalidated);
                }
                KeyStore keyStore = loadKeyStore();
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    throw new IllegalStateException(
                            "Não foi possível remover a chave biométrica inválida.", invalidated);
                }
                throw new IllegalStateException(
                        "A chave biométrica foi invalidada. Configure a biometria novamente.",
                        invalidated);
            }
            registerCipher(cipher, false);
            return cipher;
        } finally {
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    public static void saveEncryptedPassword(Context context, Cipher authenticatedCipher,
                                             char[] password) throws Exception {
        if (authenticatedCipher == null || password == null || password.length == 0
                || password.length > 262_144
                || !"AES/GCM/NoPadding".equals(authenticatedCipher.getAlgorithm())
                || authenticatedCipher.getIV() == null
                || authenticatedCipher.getIV().length != 12
                || !consumeCipher(authenticatedCipher, true)) {
            throw new IllegalArgumentException("Cipher de cadastro biométrico inválido.");
        }
        byte[] plain = null;
        byte[] encrypted = null;
        byte[] iv = null;
        try {
            plain = encodePassword(password);
            encrypted = authenticatedCipher.doFinal(plain);
            iv = authenticatedCipher.getIV();
            if (iv == null || iv.length != 12) {
                throw new IllegalStateException("IV biométrico inválido.");
            }
            SharedPreferences.Editor editor = prefs(context).edit()
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putBoolean(KEY_ENABLED, true);
            if (!editor.commit()) {
                throw new IllegalStateException("Não foi possível salvar a configuração biométrica.");
            }
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    public static char[] decryptPassword(Context context, Cipher authenticatedCipher) throws Exception {
        if (authenticatedCipher == null
                || !"AES/GCM/NoPadding".equals(authenticatedCipher.getAlgorithm())
                || !consumeCipher(authenticatedCipher, false)) {
            throw new IllegalArgumentException("Cipher biométrico inválido.");
        }
        SharedPreferences p = prefs(context);
        String encryptedText = p.getString(KEY_CIPHERTEXT, null);
        if (!p.getBoolean(KEY_ENABLED, false)
                || encryptedText == null
                || encryptedText.length() < 24
                || encryptedText.length() > 1_048_576) {
            throw new IllegalStateException("Senha biométrica não encontrada ou inválida.");
        }
        byte[] encrypted = null;
        byte[] plain = null;
        try {
            encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);
            plain = authenticatedCipher.doFinal(encrypted);
            return decodePassword(plain);
        } finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ENABLED, false)) return false;
        String ivText = p.getString(KEY_IV, null);
        String encryptedText = p.getString(KEY_CIPHERTEXT, null);
        if (ivText == null || encryptedText == null || encryptedText.length() < 24
                || encryptedText.length() > 1_048_576) return false;
        byte[] iv = null;
        byte[] ciphertext = null;
        try {
            iv = Base64.decode(ivText, Base64.NO_WRAP);
            ciphertext = Base64.decode(encryptedText, Base64.NO_WRAP);
            return iv.length == 12 && ciphertext.length >= (GCM_TAG_BITS / 8)
                    && loadKeyStore().containsAlias(KEY_ALIAS);
        } catch (Exception e) {
            return false;
        } finally {
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
    }

    /** Desativa os dados e informa se a chave foi removida com sucesso. */
    public static boolean disable(Context context) {
        // Remove apenas as chaves deste recurso; não apaga possíveis preferências futuras.
        boolean prefsCleared = prefs(context).edit()
                .remove(KEY_ENABLED)
                .remove(KEY_IV)
                .remove(KEY_CIPHERTEXT)
                .commit();

        // Se a remoção persistente falhar, preserve a chave: apagar a chave
        // agora poderia deixar ciphertext órfão nas preferências e tornar o
        // estado inconsistente sem necessidade.
        if (!prefsCleared) {
            return false;
        }

        boolean keyDeleted = true;
        try {
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
                keyDeleted = !keyStore.containsAlias(KEY_ALIAS);
            }
        } catch (Exception e) {
            keyDeleted = false;
        }
        return keyDeleted;
    }

    private static byte[] encodePassword(char[] password) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        return bytes;
    }

    private static char[] decodePassword(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        char[] chars = new char[decoded.remaining()];
        decoded.get(chars);
        if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        return chars;
    }
}
