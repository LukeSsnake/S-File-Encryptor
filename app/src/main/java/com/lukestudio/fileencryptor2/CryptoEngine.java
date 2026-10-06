package com.lukestudio.fileencryptor2;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoEngine {

    private CryptoEngine() {}

    private static final SecureRandom RANDOM =
            new SecureRandom();

    /*
     * ============================================================
     * V1 - formato antigo
     * ============================================================
     *
     * salt       = 16 bytes
     * IV         = 16 bytes
     * ciphertext = AES-256-CBC
     * HMAC       = HMAC-SHA256
     *
     * KDF:
     * PBKDF2-HMAC-SHA256
     * 100.000 iterations
     * 64 bytes
     *
     * primeiros 32 bytes = AES key
     * últimos 32 bytes   = HMAC key
     */

    private static final int V1_SALT_SIZE = 16;
    private static final int V1_IV_SIZE = 16;
    private static final int V1_HMAC_SIZE = 32;
    private static final int V1_KEY_SIZE = 32;
    private static final int V1_DERIVED_SIZE = 64;
    private static final int V1_ITERATIONS = 100_000;

    /*
     * ============================================================
     * V2 - novo formato
     * ============================================================
     *
     * Header:
     *
     * 0..3    magic       "SFE2"
     * 4       version     2
     * 5       KDF         Argon2id
     * 6       cipher      AES-256-GCM
     * 7       reserved
     * 8..11   memory KiB
     * 12..15  iterations
     * 16..19  parallelism
     * 20..35  salt
     * 36..47  nonce
     *
     * Header total = 48 bytes
     *
     * Depois:
     *
     * ciphertext
     * GCM tag = 16 bytes
     */

    private static final byte[] V2_MAGIC =
            new byte[] {
                    'S', 'F', 'E', '2'
            };

    private static final int V2_VERSION = 2;

    private static final int V2_KDF_ARGON2ID = 1;

    private static final int V2_CIPHER_AES_256_GCM = 1;

    private static final int V2_HEADER_SIZE = 48;

    private static final int V2_SALT_SIZE = 16;

    private static final int V2_NONCE_SIZE = 12;

    private static final int V2_KEY_SIZE = 32;

    private static final int V2_TAG_SIZE = 16;

    /*
     * Parâmetros padrão do Argon2id.
     *
     * 64 MiB
     * 3 iterações
     * 1 lane
     */

    private static final int ARGON2_MEMORY_KIB =
            64 * 1024;

    private static final int ARGON2_ITERATIONS =
            3;

    private static final int ARGON2_PARALLELISM =
            1;

    /*
     * Limites defensivos para arquivos V2.
     */

    private static final int ARGON2_MIN_MEMORY_KIB =
            8 * 1024;

    private static final int ARGON2_MAX_MEMORY_KIB =
            256 * 1024;

    private static final int ARGON2_MIN_ITERATIONS =
            1;

    private static final int ARGON2_MAX_ITERATIONS =
            10;

    private static final int ARGON2_MIN_PARALLELISM =
            1;

    private static final int ARGON2_MAX_PARALLELISM =
            4;

    /*
     * Buffer usado durante operações de arquivo.
     */

    private static final int BUFFER_SIZE =
            64 * 1024;

    /*
     * ============================================================
     * Exceptions
     * ============================================================
     */

    public static class WrongPasswordException
            extends Exception {

        public WrongPasswordException() {
            super("Senha incorreta.");
        }
    }

    public static class CorruptFileException
            extends Exception {

        public CorruptFileException() {
            super("Arquivo inválido ou corrompido.");
        }

        public CorruptFileException(
                String message
        ) {
            super(message);
        }
    }

    /*
     * ============================================================
     * Progress
     * ============================================================
     */

    public interface Progress {
        void onProgress(long processedBytes);
    }

    /*
     * ============================================================
     * ENCRYPT
     * ============================================================
     *
     * Arquivos novos são sempre V2.
     */

    public static void encrypt(
            InputStream input,
            OutputStream output,
            char[] password,
            long totalBytes,
            Progress progress
    ) throws Exception {

        if (input == null ||
                output == null ||
                password == null) {

            throw new IllegalArgumentException();
        }

        if (totalBytes < 0) {
            totalBytes = 0;
        }

        byte[] passwordBytes = null;
        byte[] salt = new byte[V2_SALT_SIZE];
        byte[] nonce = new byte[V2_NONCE_SIZE];
        byte[] key = null;
        byte[] header = null;
        byte[] buffer = null;
        byte[] encryptedBuffer = null;

        try {

            passwordBytes =
                    new String(password)
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);

            key =
                    deriveArgon2id(
                            passwordBytes,
                            salt,
                            ARGON2_MEMORY_KIB,
                            ARGON2_ITERATIONS,
                            ARGON2_PARALLELISM
                    );

            header =
                    buildV2Header(
                            ARGON2_MEMORY_KIB,
                            ARGON2_ITERATIONS,
                            ARGON2_PARALLELISM,
                            salt,
                            nonce
                    );

            /*
             * O cabeçalho é autenticado como AAD.
             */

            output.write(header);

            /*
             * Bouncy Castle GCMBlockCipher é usado aqui em vez
             * do Cipher JCE/Conscrypt.
             *
             * Isso evita que o Conscrypt acumule centenas de MB
             * internamente durante Cipher.update().
             */

            GCMBlockCipher cipher =
                    new GCMBlockCipher(
                            new AESEngine()
                    );

            cipher.init(
                    true,
                    new AEADParameters(
                            new KeyParameter(key),
                            128,
                            nonce,
                            header
                    )
            );

            buffer =
                    new byte[BUFFER_SIZE];

            encryptedBuffer =
                    new byte[BUFFER_SIZE + 32];

            long processed = 0;

            int read;

            while ((read =
                    input.read(
                            buffer,
                            0,
                            buffer.length
                    )) != -1) {

                if (read == 0) {
                    continue;
                }

                int produced =
                        cipher.processBytes(
                                buffer,
                                0,
                                read,
                                encryptedBuffer,
                                0
                        );

                if (produced > 0) {

                    output.write(
                            encryptedBuffer,
                            0,
                            produced
                    );

                    Arrays.fill(
                            encryptedBuffer,
                            0,
                            produced,
                            (byte) 0
                    );
                }

                processed += read;

                if (progress != null) {
                    progress.onProgress(
                            processed
                    );
                }
            }

            try {

                int produced =
                        cipher.doFinal(
                                encryptedBuffer,
                                0
                        );

                if (produced > 0) {

                    output.write(
                            encryptedBuffer,
                            0,
                            produced
                    );

                    Arrays.fill(
                            encryptedBuffer,
                            0,
                            produced,
                            (byte) 0
                    );
                }

            } catch (InvalidCipherTextException e) {

                throw new GeneralSecurityException(
                        "Falha na criptografia GCM.",
                        e
                );
            }

            output.flush();

        } finally {

            if (buffer != null) {
                Arrays.fill(
                        buffer,
                        (byte) 0
                );
            }

            if (encryptedBuffer != null) {
                Arrays.fill(
                        encryptedBuffer,
                        (byte) 0
                );
            }

            if (passwordBytes != null) {
                Arrays.fill(
                        passwordBytes,
                        (byte) 0
                );
            }

            Arrays.fill(
                    salt,
                    (byte) 0
            );

            Arrays.fill(
                    nonce,
                    (byte) 0
            );

            if (key != null) {
                Arrays.fill(
                        key,
                        (byte) 0
                );
            }

            if (header != null) {
                Arrays.fill(
                        header,
                        (byte) 0
                );
            }
        }
    }

    /*
     * ============================================================
     * DECRYPT
     * ============================================================
     *
     * Detecta automaticamente:
     *
     * V2 -> Argon2id + AES-256-GCM
     * V1 -> PBKDF2 + AES-256-CBC + HMAC-SHA256
     */

    public static void decrypt(
            InputStream verifyInput,
            InputStream decryptInput,
            OutputStream output,
            char[] password,
            long totalBytes,
            Progress progress
    ) throws Exception {

        if (verifyInput == null ||
                decryptInput == null ||
                output == null ||
                password == null) {

            throw new IllegalArgumentException();
        }

        if (totalBytes < 1) {
            throw new CorruptFileException();
        }

        byte[] prefix =
                new byte[V2_MAGIC.length];

        try {

            readFully(
                    verifyInput,
                    prefix
            );

            boolean v2 =
                    Arrays.equals(
                            prefix,
                            V2_MAGIC
                    );

            if (v2) {

                decryptV2(
                        verifyInput,
                        decryptInput,
                        output,
                        password,
                        totalBytes,
                        progress,
                        prefix
                );

            } else {

                InputStream restoredVerifyInput =
                        new SequenceInputStream(
                                new ByteArrayInputStream(
                                        prefix
                                ),
                                verifyInput
                        );

                decryptV1(
                        restoredVerifyInput,
                        decryptInput,
                        output,
                        password,
                        totalBytes,
                        progress
                );
            }

        } catch (EOFException e) {

            throw new CorruptFileException();

        } finally {

            Arrays.fill(
                    prefix,
                    (byte) 0
            );
        }
    }

    /*
     * ============================================================
     * V2 DECRYPT
     * ============================================================
     *
     * A descriptografia é feita primeiro para um arquivo temporário.
     *
     * Isso é importante porque AES-GCM só confirma a autenticidade
     * do conteúdo quando o TAG é processado em doFinal().
     *
     * Portanto, nenhum plaintext é entregue ao arquivo final
     * antes da autenticação ser concluída.
     */

    private static void decryptV2(
            InputStream verifyInput,
            InputStream decryptInput,
            OutputStream output,
            char[] password,
            long totalBytes,
            Progress progress,
            byte[] alreadyReadMagic
    ) throws Exception {

        byte[] header =
                new byte[V2_HEADER_SIZE];

        byte[] passwordBytes = null;
        byte[] salt = null;
        byte[] nonce = null;
        byte[] key = null;
        byte[] buffer = null;
        byte[] plainBuffer = null;

        File tempFile = null;

        try {

            /*
             * Os primeiros 4 bytes (SFE2) já foram lidos.
             */

            System.arraycopy(
                    alreadyReadMagic,
                    0,
                    header,
                    0,
                    V2_MAGIC.length
            );

            readFully(
                    verifyInput,
                    header,
                    V2_MAGIC.length,
                    V2_HEADER_SIZE
                            - V2_MAGIC.length
            );

            /*
             * Validação do cabeçalho.
             */

            if (!Arrays.equals(
                    Arrays.copyOfRange(
                            header,
                            0,
                            4
                    ),
                    V2_MAGIC
            )) {

                throw new CorruptFileException();
            }

            int version =
                    header[4] & 0xFF;

            int kdf =
                    header[5] & 0xFF;

            int cipherId =
                    header[6] & 0xFF;

            /*
             * O byte 7 é reservado e deve permanecer zero.
             */

            if (header[7] != 0) {

                throw new CorruptFileException(
                        "Cabeçalho V2 inválido."
                );
            }

            if (version != V2_VERSION) {

                throw new CorruptFileException(
                        "Versão de arquivo não suportada."
                );
            }

            if (kdf != V2_KDF_ARGON2ID) {

                throw new CorruptFileException(
                        "KDF não suportado."
                );
            }

            if (cipherId !=
                    V2_CIPHER_AES_256_GCM) {

                throw new CorruptFileException(
                        "Cifra não suportada."
                );
            }

            int memoryKib =
                    readInt(
                            header,
                            8
                    );

            int iterations =
                    readInt(
                            header,
                            12
                    );

            int parallelism =
                    readInt(
                            header,
                            16
                    );

            validateArgon2Parameters(
                    memoryKib,
                    iterations,
                    parallelism
            );

            salt =
                    Arrays.copyOfRange(
                            header,
                            20,
                            36
                    );

            nonce =
                    Arrays.copyOfRange(
                            header,
                            36,
                            48
                    );

            passwordBytes =
                    new String(password)
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            key =
                    deriveArgon2id(
                            passwordBytes,
                            salt,
                            memoryKib,
                            iterations,
                            parallelism
                    );

            /*
             * Bouncy Castle GCMBlockCipher.
             *
             * O header continua sendo o AAD exatamente como no
             * formato V2 original.
             */

            GCMBlockCipher cipher =
                    new GCMBlockCipher(
                            new AESEngine()
                    );

            cipher.init(
                    false,
                    new AEADParameters(
                            new KeyParameter(key),
                            128,
                            nonce,
                            header
                    )
            );

            /*
             * decryptInput ainda está no início do arquivo.
             *
             * Consumimos o header e confirmamos que ele é igual
             * ao header obtido na primeira passagem.
             */

            byte[] decryptHeader =
                    new byte[V2_HEADER_SIZE];

            try {

                readFully(
                        decryptInput,
                        decryptHeader
                );

                if (!Arrays.equals(
                        header,
                        decryptHeader
                )) {

                    throw new CorruptFileException();
                }

            } finally {

                Arrays.fill(
                        decryptHeader,
                        (byte) 0
                );
            }

            /*
             * O tamanho do arquivo inclui o header.
             */

            long ciphertextAndTag =
                    totalBytes
                            - V2_HEADER_SIZE;

            if (ciphertextAndTag <
                    V2_TAG_SIZE) {

                throw new CorruptFileException();
            }

            long ciphertextLength =
                    ciphertextAndTag
                            - V2_TAG_SIZE;

            /*
             * Arquivo temporário.
             *
             * O plaintext só será copiado para o output depois
             * que o GCM confirmar o TAG.
             */

            tempFile =
                    File.createTempFile(
                            "sfe-v2-",
                            ".tmp"
                    );

            buffer =
                    new byte[BUFFER_SIZE];

            plainBuffer =
                    new byte[BUFFER_SIZE + 32];

            long remaining =
                    ciphertextLength;

            long processed =
                    V2_HEADER_SIZE;

            try (
                    FileOutputStream tempOutput =
                            new FileOutputStream(
                                    tempFile
                            )
            ) {

                while (remaining > 0) {

                    int wanted =
                            (int) Math.min(
                                    buffer.length,
                                    remaining
                            );

                    int read =
                            readSome(
                                    decryptInput,
                                    buffer,
                                    0,
                                    wanted
                            );

                    if (read < 0) {

                        throw new CorruptFileException();
                    }

                    if (read == 0) {
                        continue;
                    }

                    int produced =
                            cipher.processBytes(
                                    buffer,
                                    0,
                                    read,
                                    plainBuffer,
                                    0
                            );

                    if (produced > 0) {

                        tempOutput.write(
                                plainBuffer,
                                0,
                                produced
                        );

                        Arrays.fill(
                                plainBuffer,
                                0,
                                produced,
                                (byte) 0
                        );
                    }

                    remaining -= read;
                    processed += read;

                    if (progress != null) {
                        progress.onProgress(
                                processed
                        );
                    }
                }

                /*
                 * Lê exatamente o GCM TAG.
                 */

                byte[] tag =
                        new byte[V2_TAG_SIZE];

                try {

                    readFully(
                            decryptInput,
                            tag
                    );

                    /*
                     * Entrega o TAG ao GCM.
                     *
                     * O Bouncy Castle mantém a autenticação internamente
                     * e doFinal() valida o TAG.
                     */

                    int produced =
                            cipher.processBytes(
                                    tag,
                                    0,
                                    tag.length,
                                    plainBuffer,
                                    0
                            );

                    if (produced > 0) {

                        tempOutput.write(
                                plainBuffer,
                                0,
                                produced
                        );

                        Arrays.fill(
                                plainBuffer,
                                0,
                                produced,
                                (byte) 0
                        );
                    }

                    /*
                     * O TAG é validado aqui.
                     *
                     * Se a senha estiver errada ou o arquivo
                     * tiver sido alterado, doFinal() falhará.
                     */

                    try {

                        int finalProduced =
                                cipher.doFinal(
                                        plainBuffer,
                                        0
                                );

                        if (finalProduced > 0) {

                            tempOutput.write(
                                    plainBuffer,
                                    0,
                                    finalProduced
                            );

                            Arrays.fill(
                                    plainBuffer,
                                    0,
                                    finalProduced,
                                    (byte) 0
                            );
                        }

                    } catch (InvalidCipherTextException e) {

                        throw new WrongPasswordException();
                    }

                } finally {

                    Arrays.fill(
                            tag,
                            (byte) 0
                    );
                }

                tempOutput.flush();
            }

            /*
             * Garante que não existe conteúdo extra.
             */

            if (decryptInput.read() != -1) {

                throw new CorruptFileException();
            }

            /*
             * Só chegamos aqui se o GCM foi autenticado
             * com sucesso.
             *
             * Agora podemos entregar o plaintext ao output final.
             */

            try (
                    FileInputStream tempInput =
                            new FileInputStream(
                                    tempFile
                            )
            ) {

                long tempRemaining =
                        tempFile.length();

                while (tempRemaining > 0) {

                    int wanted =
                            (int) Math.min(
                                    buffer.length,
                                    tempRemaining
                            );

                    int read =
                            tempInput.read(
                                    buffer,
                                    0,
                                    wanted
                            );

                    if (read < 0) {
                        throw new CorruptFileException();
                    }

                    if (read == 0) {
                        continue;
                    }

                    output.write(
                            buffer,
                            0,
                            read
                    );

                    tempRemaining -= read;
                }
            }

            output.flush();

        } catch (EOFException e) {

            throw new CorruptFileException();

        } finally {

            if (tempFile != null &&
                    tempFile.exists()) {

                /*
                 *noinspection ResultOfMethodCallIgnored
                 */
                tempFile.delete();
            }

            if (buffer != null) {
                Arrays.fill(
                        buffer,
                        (byte) 0
                );
            }

            if (plainBuffer != null) {
                Arrays.fill(
                        plainBuffer,
                        (byte) 0
                );
            }

            if (passwordBytes != null) {
                Arrays.fill(
                        passwordBytes,
                        (byte) 0
                );
            }

            if (salt != null) {
                Arrays.fill(
                        salt,
                        (byte) 0
                );
            }

            if (nonce != null) {
                Arrays.fill(
                        nonce,
                        (byte) 0
                );
            }

            if (key != null) {
                Arrays.fill(
                        key,
                        (byte) 0
                );
            }

            Arrays.fill(
                    header,
                    (byte) 0
            );
        }
    }

    /*
     * ============================================================
     * V1 DECRYPT
     * ============================================================
     *
     * Mantém compatibilidade com os arquivos antigos.
     */

    private static void decryptV1(
            InputStream verifyInput,
            InputStream decryptInput,
            OutputStream output,
            char[] password,
            long totalBytes,
            Progress progress
    ) throws Exception {

        if (totalBytes <
                V1_SALT_SIZE
                        + V1_IV_SIZE
                        + V1_HMAC_SIZE
                        + 1) {

            throw new CorruptFileException();
        }

        byte[] salt =
                new byte[V1_SALT_SIZE];

        byte[] iv =
                new byte[V1_IV_SIZE];

        byte[] derived =
                null;

        byte[] aesKey =
                null;

        byte[] hmacKey =
                null;

        byte[] buffer =
                null;

        try {

            /*
             * Primeira passagem:
             * verifica o HMAC antes de aceitar os dados.
             */

            readFully(
                    verifyInput,
                    salt
            );

            readFully(
                    verifyInput,
                    iv
            );

            derived =
                    deriveV1(
                            password,
                            salt
                    );

            aesKey =
                    Arrays.copyOfRange(
                            derived,
                            0,
                            V1_KEY_SIZE
                    );

            hmacKey =
                    Arrays.copyOfRange(
                            derived,
                            V1_KEY_SIZE,
                            V1_DERIVED_SIZE
                    );

            Mac mac =
                    Mac.getInstance(
                            "HmacSHA256"
                    );

            mac.init(
                    new SecretKeySpec(
                            hmacKey,
                            "HmacSHA256"
                    )
            );

            mac.update(salt);
            mac.update(iv);

            long ciphertextLength =
                    totalBytes
                            - V1_SALT_SIZE
                            - V1_IV_SIZE
                            - V1_HMAC_SIZE;

            if (ciphertextLength <= 0 ||
                    (ciphertextLength % 16) != 0) {

                throw new CorruptFileException();
            }

            buffer =
                    new byte[BUFFER_SIZE];

            long remaining =
                    ciphertextLength;

            while (remaining > 0) {

                int wanted =
                        (int) Math.min(
                                buffer.length,
                                remaining
                        );

                int read =
                        readSome(
                                verifyInput,
                                buffer,
                                0,
                                wanted
                        );

                if (read < 0) {
                    throw new CorruptFileException();
                }

                if (read == 0) {
                    continue;
                }

                mac.update(
                        buffer,
                        0,
                        read
                );

                remaining -= read;
            }

            byte[] storedHmac =
                    new byte[V1_HMAC_SIZE];

            byte[] calculatedHmac =
                    null;

            try {

                readFully(
                        verifyInput,
                        storedHmac
                );

                calculatedHmac =
                        mac.doFinal();

                boolean valid =
                        MessageDigest.isEqual(
                                calculatedHmac,
                                storedHmac
                        );

                if (!valid) {
                    throw new WrongPasswordException();
                }

            } finally {

                Arrays.fill(
                        storedHmac,
                        (byte) 0
                );

                if (calculatedHmac != null) {
                    Arrays.fill(
                            calculatedHmac,
                            (byte) 0
                    );
                }
            }

            /*
             * Segunda passagem:
             * agora que o HMAC foi validado, fazemos a
             * descriptografia real.
             */

            readFully(
                    decryptInput,
                    salt
            );

            readFully(
                    decryptInput,
                    iv
            );

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/CBC/PKCS5Padding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(
                            aesKey,
                            "AES"
                    ),
                    new IvParameterSpec(
                            iv
                    )
            );

            remaining =
                    ciphertextLength;

            long processed =
                    V1_SALT_SIZE
                            + V1_IV_SIZE;

            buffer =
                    new byte[BUFFER_SIZE];

            while (remaining > 0) {

                int wanted =
                        (int) Math.min(
                                buffer.length,
                                remaining
                        );

                int read =
                        readSome(
                                decryptInput,
                                buffer,
                                0,
                                wanted
                        );

                if (read < 0) {
                    throw new CorruptFileException();
                }

                if (read == 0) {
                    continue;
                }

                byte[] plain =
                        cipher.update(
                                buffer,
                                0,
                                read
                        );

                if (plain != null &&
                        plain.length > 0) {

                    output.write(
                            plain
                    );

                    Arrays.fill(
                            plain,
                            (byte) 0
                    );
                }

                remaining -= read;
                processed += read;

                if (progress != null) {
                    progress.onProgress(
                            processed
                    );
                }
            }

            byte[] finalPlain =
                    cipher.doFinal();

            if (finalPlain != null &&
                    finalPlain.length > 0) {

                output.write(
                        finalPlain
                );

                Arrays.fill(
                        finalPlain,
                        (byte) 0
                );
            }

            output.flush();

        } catch (EOFException e) {

            throw new CorruptFileException();

        } finally {

            if (buffer != null) {
                Arrays.fill(
                        buffer,
                        (byte) 0
                );
            }

            Arrays.fill(
                    salt,
                    (byte) 0
            );

            Arrays.fill(
                    iv,
                    (byte) 0
            );

            if (derived != null) {
                Arrays.fill(
                        derived,
                        (byte) 0
                );
            }

            if (aesKey != null) {
                Arrays.fill(
                        aesKey,
                        (byte) 0
                );
            }

            if (hmacKey != null) {
                Arrays.fill(
                        hmacKey,
                        (byte) 0
                );
            }
        }
    }

    /*
     * ============================================================
     * decryptBytes()
     * ============================================================
     *
     * Mantém suporte para operações em memória.
     */

    public static byte[] decryptBytes(
            byte[] encrypted,
            char[] password
    ) throws Exception {

        if (encrypted == null ||
                password == null) {

            throw new IllegalArgumentException();
        }

        if (hasV2Magic(encrypted)) {

            return decryptBytesV2(
                    encrypted,
                    password
            );

        } else {

            return decryptBytesV1(
                    encrypted,
                    password
            );
        }
    }

    /*
     * ============================================================
     * V2 decryptBytes
     * ============================================================
     */

    private static byte[] decryptBytesV2(
            byte[] encrypted,
            char[] password
    ) throws Exception {

        if (encrypted.length <
                V2_HEADER_SIZE
                        + V2_TAG_SIZE) {

            throw new CorruptFileException();
        }

        byte[] header =
                Arrays.copyOfRange(
                        encrypted,
                        0,
                        V2_HEADER_SIZE
                );

        byte[] passwordBytes = null;
        byte[] salt = null;
        byte[] nonce = null;
        byte[] key = null;

        try {

            int version =
                    header[4] & 0xFF;

            int kdf =
                    header[5] & 0xFF;

            int cipherId =
                    header[6] & 0xFF;

            if (header[7] != 0) {

                throw new CorruptFileException(
                        "Cabeçalho V2 inválido."
                );
            }

            if (version != V2_VERSION ||
                    kdf != V2_KDF_ARGON2ID ||
                    cipherId !=
                            V2_CIPHER_AES_256_GCM) {

                throw new CorruptFileException();
            }

            int memoryKib =
                    readInt(
                            header,
                            8
                    );

            int iterations =
                    readInt(
                            header,
                            12
                    );

            int parallelism =
                    readInt(
                            header,
                            16
                    );

            validateArgon2Parameters(
                    memoryKib,
                    iterations,
                    parallelism
            );

            salt =
                    Arrays.copyOfRange(
                            header,
                            20,
                            36
                    );

            nonce =
                    Arrays.copyOfRange(
                            header,
                            36,
                            48
                    );

            passwordBytes =
                    new String(password)
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            key =
                    deriveArgon2id(
                            passwordBytes,
                            salt,
                            memoryKib,
                            iterations,
                            parallelism
                    );

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(
                            key,
                            "AES"
                    ),
                    new javax.crypto.spec.GCMParameterSpec(
                            128,
                            nonce
                    )
            );

            cipher.updateAAD(header);

            int dataLength =
                    encrypted.length
                            - V2_HEADER_SIZE;

            try {

                return cipher.doFinal(
                        encrypted,
                        V2_HEADER_SIZE,
                        dataLength
                );

            } catch (BadPaddingException e) {

                throw new WrongPasswordException();

            } catch (IllegalBlockSizeException e) {

                throw new CorruptFileException();
            }

        } finally {

            Arrays.fill(
                    header,
                    (byte) 0
            );

            if (passwordBytes != null) {
                Arrays.fill(
                        passwordBytes,
                        (byte) 0
                );
            }

            if (salt != null) {
                Arrays.fill(
                        salt,
                        (byte) 0
                );
            }

            if (nonce != null) {
                Arrays.fill(
                        nonce,
                        (byte) 0
                );
            }

            if (key != null) {
                Arrays.fill(
                        key,
                        (byte) 0
                );
            }
        }
    }

    /*
     * ============================================================
     * V1 decryptBytes
     * ============================================================
     */

    private static byte[] decryptBytesV1(
            byte[] encrypted,
            char[] password
    ) throws Exception {

        if (encrypted.length <
                V1_SALT_SIZE
                        + V1_IV_SIZE
                        + V1_HMAC_SIZE
                        + 16) {

            throw new CorruptFileException();
        }

        byte[] salt =
                Arrays.copyOfRange(
                        encrypted,
                        0,
                        V1_SALT_SIZE
                );

        byte[] iv =
                Arrays.copyOfRange(
                        encrypted,
                        V1_SALT_SIZE,
                        V1_SALT_SIZE
                                + V1_IV_SIZE
                );

        int ciphertextOffset =
                V1_SALT_SIZE
                        + V1_IV_SIZE;

        int ciphertextLength =
                encrypted.length
                        - ciphertextOffset
                        - V1_HMAC_SIZE;

        if (ciphertextLength <= 0 ||
                ciphertextLength % 16 != 0) {

            Arrays.fill(
                    salt,
                    (byte) 0
            );

            Arrays.fill(
                    iv,
                    (byte) 0
            );

            throw new CorruptFileException();
        }

        byte[] storedHmac =
                Arrays.copyOfRange(
                        encrypted,
                        encrypted.length
                                - V1_HMAC_SIZE,
                        encrypted.length
                );

        byte[] derived =
                null;

        byte[] aesKey =
                null;

        byte[] hmacKey =
                null;

        try {

            derived =
                    deriveV1(
                            password,
                            salt
                    );

            aesKey =
                    Arrays.copyOfRange(
                            derived,
                            0,
                            V1_KEY_SIZE
                    );

            hmacKey =
                    Arrays.copyOfRange(
                            derived,
                            V1_KEY_SIZE,
                            V1_DERIVED_SIZE
                    );

            Mac mac =
                    Mac.getInstance(
                            "HmacSHA256"
                    );

            mac.init(
                    new SecretKeySpec(
                            hmacKey,
                            "HmacSHA256"
                    )
            );

            mac.update(
                    salt
            );

            mac.update(
                    iv
            );

            mac.update(
                    encrypted,
                    ciphertextOffset,
                    ciphertextLength
            );

            byte[] calculatedHmac =
                    mac.doFinal();

            boolean valid =
                    MessageDigest.isEqual(
                            calculatedHmac,
                            storedHmac
                    );

            Arrays.fill(
                    calculatedHmac,
                    (byte) 0
            );

            if (!valid) {
                throw new WrongPasswordException();
            }

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/CBC/PKCS5Padding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(
                            aesKey,
                            "AES"
                    ),
                    new IvParameterSpec(
                            iv
                    )
            );

            try {

                return cipher.doFinal(
                        encrypted,
                        ciphertextOffset,
                        ciphertextLength
                );

            } catch (BadPaddingException e) {

                throw new CorruptFileException();

            } catch (IllegalBlockSizeException e) {

                throw new CorruptFileException();
            }

        } finally {

            Arrays.fill(
                    salt,
                    (byte) 0
            );

            Arrays.fill(
                    iv,
                    (byte) 0
            );

            Arrays.fill(
                    storedHmac,
                    (byte) 0
            );

            if (derived != null) {
                Arrays.fill(
                        derived,
                        (byte) 0
                );
            }

            if (aesKey != null) {
                Arrays.fill(
                        aesKey,
                        (byte) 0
                );
            }

            if (hmacKey != null) {
                Arrays.fill(
                        hmacKey,
                        (byte) 0
                );
            }
        }
    }

    /*
     * ============================================================
     * ARGON2ID
     * ============================================================
     */

    private static byte[] deriveArgon2id(
            byte[] password,
            byte[] salt,
            int memoryKib,
            int iterations,
            int parallelism
    ) {

        Argon2Parameters.Builder builder =
                new Argon2Parameters.Builder(
                        Argon2Parameters.ARGON2_id
                )
                        .withVersion(
                                Argon2Parameters
                                        .ARGON2_VERSION_13
                        )
                        .withIterations(
                                iterations
                        )
                        .withMemoryAsKB(
                                memoryKib
                        )
                        .withParallelism(
                                parallelism
                        )
                        .withSalt(
                                salt
                        );

        Argon2BytesGenerator generator =
                new Argon2BytesGenerator();

        generator.init(
                builder.build()
        );

        byte[] output =
                new byte[V2_KEY_SIZE];

        generator.generateBytes(
                password,
                output
        );

        return output;
    }

    /*
     * ============================================================
     * V1 PBKDF2
     * ============================================================
     */

    private static byte[] deriveV1(
            char[] password,
            byte[] salt
    ) throws GeneralSecurityException {

        javax.crypto.SecretKeyFactory factory =
                javax.crypto.SecretKeyFactory.getInstance(
                        "PBKDF2WithHmacSHA256"
                );

        javax.crypto.spec.PBEKeySpec spec =
                new javax.crypto.spec.PBEKeySpec(
                        password,
                        salt,
                        V1_ITERATIONS,
                        V1_DERIVED_SIZE * 8
                );

        try {

            return factory
                    .generateSecret(spec)
                    .getEncoded();

        } finally {

            spec.clearPassword();
        }
    }

    /*
     * ============================================================
     * V2 HEADER
     * ============================================================
     */

    private static byte[] buildV2Header(
            int memoryKib,
            int iterations,
            int parallelism,
            byte[] salt,
            byte[] nonce
    ) {

        byte[] header =
                new byte[V2_HEADER_SIZE];

        System.arraycopy(
                V2_MAGIC,
                0,
                header,
                0,
                V2_MAGIC.length
        );

        header[4] =
                (byte) V2_VERSION;

        header[5] =
                (byte) V2_KDF_ARGON2ID;

        header[6] =
                (byte) V2_CIPHER_AES_256_GCM;

        header[7] =
                0;

        writeInt(
                header,
                8,
                memoryKib
        );

        writeInt(
                header,
                12,
                iterations
        );

        writeInt(
                header,
                16,
                parallelism
        );

        System.arraycopy(
                salt,
                0,
                header,
                20,
                V2_SALT_SIZE
        );

        System.arraycopy(
                nonce,
                0,
                header,
                36,
                V2_NONCE_SIZE
        );

        return header;
    }

    /*
     * ============================================================
     * ARGON2 VALIDATION
     * ============================================================
     */

    private static void validateArgon2Parameters(
            int memoryKib,
            int iterations,
            int parallelism
    ) throws CorruptFileException {

        if (memoryKib <
                ARGON2_MIN_MEMORY_KIB ||
                memoryKib >
                        ARGON2_MAX_MEMORY_KIB) {

            throw new CorruptFileException(
                    "Parâmetro de memória inválido."
            );
        }

        if (iterations <
                ARGON2_MIN_ITERATIONS ||
                iterations >
                        ARGON2_MAX_ITERATIONS) {

            throw new CorruptFileException(
                    "Parâmetro de iterações inválido."
            );
        }

        if (parallelism <
                ARGON2_MIN_PARALLELISM ||
                parallelism >
                        ARGON2_MAX_PARALLELISM) {

            throw new CorruptFileException(
                    "Parâmetro de paralelismo inválido."
            );
        }
    }

    /*
     * ============================================================
     * MAGIC
     * ============================================================
     */

    private static boolean hasV2Magic(
            byte[] data
    ) {

        return data != null &&
                data.length >=
                        V2_MAGIC.length &&
                Arrays.equals(
                        Arrays.copyOfRange(
                                data,
                                0,
                                V2_MAGIC.length
                        ),
                        V2_MAGIC
                );
    }

    /*
     * ============================================================
     * IO HELPERS
     * ============================================================
     */

    private static void readFully(
            InputStream in,
            byte[] buffer
    ) throws IOException {

        readFully(
                in,
                buffer,
                0,
                buffer.length
        );
    }

    private static void readFully(
            InputStream in,
            byte[] buffer,
            int offset,
            int length
    ) throws IOException {

        int position =
                offset;

        int end =
                offset + length;

        while (position < end) {

            int n =
                    in.read(
                            buffer,
                            position,
                            end - position
                    );

            if (n < 0) {
                throw new EOFException();
            }

            if (n == 0) {
                continue;
            }

            position += n;
        }
    }

    private static int readSome(
            InputStream in,
            byte[] buffer,
            int offset,
            int length
    ) throws IOException {

        return in.read(
                buffer,
                offset,
                length
        );
    }

    /*
     * ============================================================
     * INTEGER HELPERS
     * ============================================================
     */

    private static void writeInt(
            byte[] buffer,
            int offset,
            int value
    ) {

        ByteBuffer.wrap(
                buffer,
                offset,
                4
        )
                .order(
                        ByteOrder.BIG_ENDIAN
                )
                .putInt(value);
    }

    private static int readInt(
            byte[] buffer,
            int offset
    ) {

        return ByteBuffer.wrap(
                buffer,
                offset,
                4
        )
                .order(
                        ByteOrder.BIG_ENDIAN
                )
                .getInt();
    }
}
