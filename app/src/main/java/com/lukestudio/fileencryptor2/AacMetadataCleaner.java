
package com.lukestudio.fileencryptor2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public final class AacMetadataCleaner {

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final int MAX_ID3_SIZE =
            256 * 1024 * 1024;

    private AacMetadataCleaner() {
    }

    /**
     * Remove tags ID3v2 iniciais e ID3v1 finais
     * de um arquivo AAC ADTS.
     *
     * Os quadros AAC são copiados sem recodificação.
     */
    public static boolean clean(
            File input,
            File output
    ) throws IOException {

        if (input == null
                || !input.isFile()
                || !input.canRead()) {

            throw new IOException(
                    "Arquivo AAC inválido ou inacessível."
            );
        }

        if (output == null) {
            throw new IOException(
                    "Arquivo de saída inválido."
            );
        }

        if (input.getCanonicalFile().equals(
                output.getCanonicalFile()
        )) {
            throw new IOException(
                    "Entrada e saída não podem ser o mesmo arquivo."
            );
        }

        long inputLength = input.length();

        if (inputLength < 7) {
            throw new IOException(
                    "Arquivo AAC muito pequeno."
            );
        }

        File parent =
                output.getAbsoluteFile().getParentFile();

        if (parent == null) {
            throw new IOException(
                    "Diretório de saída inválido."
            );
        }

        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException(
                    "Não foi possível criar o diretório."
            );
        }

        if (!parent.isDirectory() || !parent.canWrite()) {
            throw new IOException(
                    "Diretório de saída sem permissão de gravação."
            );
        }

        File temp = File.createTempFile(
                ".sfe-aac-",
                ".tmp",
                parent
        );

        boolean success = false;

        try {

            long start = findAudioStart(input);
            long end = findAudioEnd(input, start);

            if (start >= end) {
                throw new IOException(
                        "Nenhum conteúdo AAC válido encontrado."
                );
            }

            validateAdts(input, start, end);

            copyAudio(
                    input,
                    temp,
                    start,
                    end - start
            );

            if (temp.length() != end - start) {
                throw new IOException(
                        "Falha ao copiar os dados AAC."
                );
            }

            validateAdts(
                    temp,
                    0,
                    temp.length()
            );

            replaceOutput(temp, output);

            success = true;

            return true;

        } finally {

            if (!success && temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Identifica uma tag ID3v2 no início.
     */
    private static long findAudioStart(
            File file
    ) throws IOException {

        try (InputStream in =
                     new BufferedInputStream(
                             new FileInputStream(file)
                     )) {

            byte[] header = new byte[10];

            int read = readFully(in, header);

            if (read < 3) {
                throw new IOException(
                        "Arquivo AAC inválido."
                );
            }

            if (header[0] != 'I'
                    || header[1] != 'D'
                    || header[2] != '3') {

                return 0;
            }

            if (read < 10) {
                throw new IOException(
                        "Cabeçalho ID3v2 incompleto."
                );
            }

            int majorVersion = header[3] & 0xFF;

            if (majorVersion < 2 || majorVersion > 4) {
                throw new IOException(
                        "Versão ID3v2 não suportada."
                );
            }

            int size = readSynchsafeInt(header, 6);

            if (size < 0 || size > MAX_ID3_SIZE) {
                throw new IOException(
                        "Tamanho ID3v2 inválido."
                );
            }

            long totalSize = 10L + size;           

            if (totalSize >= file.length()) {
                throw new IOException(
                        "Tag ID3v2 excede o arquivo."
                );
            }

            return totalSize;
        }
    }

    /**
     * Identifica uma possível tag ID3v1 no final.
     */
    private static long findAudioEnd(
            File file,
            long start
    ) throws IOException {

        long length = file.length();

        if (length - start < 128) {
            return length;
        }

        try (InputStream in =
                     new BufferedInputStream(
                             new FileInputStream(file)
                     )) {

            long position = length - 128;

            long skipped = 0;

            while (skipped < position) {

                long n = in.skip(position - skipped);

                if (n <= 0) {
                    if (in.read() < 0) {
                        throw new IOException(
                                "Falha ao acessar final do AAC."
                        );
                    }

                    n = 1;
                }

                skipped += n;
            }

            byte[] signature = new byte[3];

            if (readFully(in, signature) == 3
                    && signature[0] == 'T'
                    && signature[1] == 'A'
                    && signature[2] == 'G') {

                return length - 128;
            }
        }

        return length;
    }

    /**
     * Valida os quadros ADTS e seus comprimentos.
     */
    private static void validateAdts(
            File file,
            long start,
            long end
    ) throws IOException {

        if (start < 0
                || end <= start
                || end > file.length()) {

            throw new IOException(
                    "Limites AAC inválidos."
            );
        }

        long position = start;
        int frameCount = 0;

        try (InputStream in =
                     new BufferedInputStream(
                             new FileInputStream(file)
                     )) {

            skipFully(in, start);

            while (position < end) {

                long remaining = end - position;

                if (remaining < 7) {
                    throw new IOException(
                            "Quadro ADTS incompleto."
                    );
                }

                byte[] header = new byte[7];

                if (readFully(in, header) != 7) {
                    throw new IOException(
                            "Cabeçalho ADTS incompleto."
                    );
                }

                int b0 = header[0] & 0xFF;
                int b1 = header[1] & 0xFF;

                if (b0 != 0xFF || (b1 & 0xF6) != 0xF0) {
                    throw new IOException(
                            "Sincronização ADTS inválida."
                    );
                }

                int protectionAbsent = b1 & 1;

                int headerSize =
                        protectionAbsent == 1 ? 7 : 9;

                int frameLength =
                        ((header[3] & 0x03) << 11)
                                | ((header[4] & 0xFF) << 3)
                                | ((header[5] & 0xE0) >> 5);

                if (frameLength < headerSize
                        || frameLength > remaining) {

                    throw new IOException(
                            "Comprimento de quadro ADTS inválido."
                    );
                }

                long extra = frameLength - 7;

                skipFully(in, extra);

                position += frameLength;

                frameCount++;

                if (frameCount > 10_000_000) {
                    throw new IOException(
                            "Quantidade excessiva de quadros AAC."
                    );
                }
            }
        }

        if (position != end || frameCount == 0) {
            throw new IOException(
                    "Fluxo AAC inválido."
            );
        }
    }

    private static void copyAudio(
            File input,
            File output,
            long start,
            long length
    ) throws IOException {

        try (
                InputStream in =
                        new BufferedInputStream(
                                new FileInputStream(input)
                        );

                OutputStream out =
                        new BufferedOutputStream(
                                new FileOutputStream(output)
                        )
        ) {

            skipFully(in, start);

            byte[] buffer = new byte[BUFFER_SIZE];

            long remaining = length;

            while (remaining > 0) {

                int count = in.read(
                        buffer,
                        0,
                        (int) Math.min(
                                buffer.length,
                                remaining
                        )
                );

                if (count < 0) {
                    throw new IOException(
                            "Fim inesperado do arquivo AAC."
                    );
                }

                if (count == 0) {
                    continue;
                }

                out.write(buffer, 0, count);

                remaining -= count;
            }

            out.flush();
        }
    }

    private static int readSynchsafeInt(
            byte[] data,
            int offset
    ) throws IOException {

        int result = 0;

        for (int i = 0; i < 4; i++) {

            int value = data[offset + i] & 0xFF;

            if ((value & 0x80) != 0) {
                throw new IOException(
                        "Tamanho ID3v2 inválido."
                );
            }

            result = (result << 7) | value;
        }

        return result;
    }

    private static int readFully(
            InputStream in,
            byte[] buffer
    ) throws IOException {

        int offset = 0;

        while (offset < buffer.length) {

            int count = in.read(
                    buffer,
                    offset,
                    buffer.length - offset
            );

            if (count < 0) {
                break;
            }

            if (count == 0) {
                continue;
            }

            offset += count;
        }

        return offset;
    }

    private static void skipFully(
            InputStream in,
            long amount
    ) throws IOException {

        long remaining = amount;

        while (remaining > 0) {

            long skipped = in.skip(remaining);

            if (skipped <= 0) {

                if (in.read() < 0) {
                    throw new IOException(
                            "Fim inesperado do arquivo."
                    );
                }

                skipped = 1;
            }

            remaining -= skipped;
        }
    }

    private static void replaceOutput(
            File temp,
            File output
    ) throws IOException {

        File backup = new File(
                output.getAbsolutePath() + ".sfe-backup"
        );

        if (backup.exists() && !backup.delete()) {
            throw new IOException(
                    "Não foi possível remover backup anterior."
            );
        }

        boolean hadOutput = output.exists();

        if (hadOutput && !output.renameTo(backup)) {
            throw new IOException(
                    "Não foi possível preservar a saída original."
            );
        }

        if (!temp.renameTo(output)) {

            if (hadOutput && backup.exists()) {
                backup.renameTo(output);
            }

            throw new IOException(
                    "Não foi possível criar o arquivo AAC de saída."
            );
        }

        if (backup.exists()) {
            backup.delete();
        }
    }
}
