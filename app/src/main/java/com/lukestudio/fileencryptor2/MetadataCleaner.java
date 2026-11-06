package com.lukestudio.fileencryptor2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.zip.CRC32;
import java.io.RandomAccessFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import java.util.zip.ZipFile;

public final class MetadataCleaner {

    private static final int JPEG = 1;
    private static final int PNG = 2;
    private static final int WEBP = 3;
    private static final int GIF = 4;
    private static final int DOCX = 5;
    private static final int XLSX = 6;
    private static final int PPTX = 7;
    private static final int PDF = 8;

    private MetadataCleaner() {
    }

    public static void clean(
            File input,
            File output
    ) throws IOException {

        if (input == null || output == null
                || !input.isFile()
                || !input.canRead()
                || input.equals(output)
                || (output.exists() && !output.delete())) {
            throw new IOException("Arquivo inválido.");
        }

        int format = detectFormat(input);

        try {
            switch (format) {
                case JPEG:
                    cleanJpeg(input, output);
                    break;

                case PNG:
                    cleanPng(input, output);
                    break;

                case WEBP:
                    cleanWebp(input, output);
                    break;

                case GIF:
                    cleanGif(input, output);
                    break;

                case DOCX:
                    cleanOpenXml(input, output);
                    break;

                case XLSX:
                    cleanOpenXml(input, output);
                    break;

                case PPTX:
                    cleanOpenXml(input, output);
                    break;

                case PDF:
                    cleanPdf(input, output);
                    break;

                default:
                    throw new IOException(
                            "Formato não suportado."
                    );
            }

            if (!output.isFile() || output.length() == 0) {
                throw new IOException(
                        "Não foi possível validar o arquivo limpo."
                );
            }

        } catch (IOException | RuntimeException e) {
            if (output.exists()) {
                // Evita deixar um resultado incompleto.
                output.delete();
            }
            throw e;
        }
    }

    private static int detectFormat(File file)
            throws IOException {

        byte[] header = new byte[12];

        try (FileInputStream in = new FileInputStream(file)) {

            int count = in.read(header);

            if (count >= 3
                    && (header[0] & 0xFF) == 0xFF
                    && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF) {
                return JPEG;
            }

            if (count >= 8
                    && header[0] == (byte) 0x89
                    && header[1] == 'P'
                    && header[2] == 'N'
                    && header[3] == 'G'
                    && header[4] == 13
                    && header[5] == 10
                    && header[6] == 26
                    && header[7] == 10) {
                return PNG;
            }

            if (count >= 12
                    && header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P') {
                return WEBP;
            }

            if (count >= 6
                    && (matches(header, 0, "GIF87a")
                    || matches(header, 0, "GIF89a"))) {
                return GIF;
            }

            if (count >= 5
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F'
                    && header[4] == '-') {
                return PDF;
            }

        }

        int openXmlFormat = detectOpenXmlFormat(file);

        if (openXmlFormat != 0) {
            return openXmlFormat;
        }

        throw new IOException("Formato não reconhecido.");
    }

    private static void cleanJpeg(
            File input,
            File output
    ) throws IOException {

        try (
                PushbackInputStream in =
                        new PushbackInputStream(
                                new BufferedInputStream(
                                        new FileInputStream(input)
                                ),
                                2
                        );

                BufferedOutputStream out =
                        new BufferedOutputStream(
                                new FileOutputStream(output)
                        )
        ) {

            int first = in.read();
            int second = in.read();

            if (first != 0xFF || second != 0xD8) {
                throw new IOException("JPEG inválido.");
            }

            out.write(first);
            out.write(second);

            boolean foundEnd = false;

            while (!foundEnd) {

                int markerPrefix = in.read();

                if (markerPrefix == -1) {
                    throw new IOException("JPEG truncado.");
                }

                if (markerPrefix != 0xFF) {
                    throw new IOException("Estrutura JPEG inválida.");
                }

                int marker;

                do {
                    marker = in.read();

                    if (marker == -1) {
                        throw new IOException("JPEG truncado.");
                    }

                } while (marker == 0xFF);

                if (marker == 0x00) {
                    throw new IOException("Marcador JPEG inválido.");
                }

                if (marker == 0xD9) {
                    out.write(0xFF);
                    out.write(marker);
                    foundEnd = true;
                    continue;
                }

                if (marker == 0xD8
                        || (marker >= 0xD0 && marker <= 0xD7)
                        || marker == 0x01) {

                    out.write(0xFF);
                    out.write(marker);
                    continue;
                }

                int lengthHigh = in.read();
                int lengthLow = in.read();

                if (lengthHigh == -1 || lengthLow == -1) {
                    throw new IOException("JPEG truncado.");
                }

                int length =
                        (lengthHigh << 8) | lengthLow;

                if (length < 2) {
                    throw new IOException("Segmento JPEG inválido.");
                }

                boolean remove =
                        marker == 0xE1   // APP1: EXIF / XMP
                        || marker == 0xED // APP13: IPTC / Photoshop
                        || marker == 0xFE; // COM

                if (!remove) {

                    out.write(0xFF);
                    out.write(marker);
                    out.write(lengthHigh);
                    out.write(lengthLow);

                    copySegmentData(in, out, length - 2);

                } else if (marker == 0xE1) {

                    // Lê o payload para decidir se é EXIF.
                    // Mesmo descartado, pode conter a
                    // orientação que precisa ser preservada.
                    int payloadSize = length - 2;

                    byte[] payload = new byte[payloadSize];

                    readSegmentFully(in, payload);

                    int orientation =
                            extractJpegOrientation(payload);

                    if (orientation > 1 && orientation <= 8) {

                        // Regrava uma APP1 mínima contendo
                        // apenas o campo Orientation.
                        // O campo de comprimento do segmento
                        // inclui os próprios 2 bytes dele.
                        byte[] minimal =
                                buildMinimalExifSegment(
                                        orientation
                                );

                        int segmentLength =
                                minimal.length + 2;

                        out.write(0xFF);
                        out.write(0xE1);
                        out.write(
                                (segmentLength >>> 8) & 0xFF
                        );
                        out.write(segmentLength & 0xFF);
                        out.write(minimal);
                    }

                    // Qualquer outro conteúdo do APP1
                    // (EXIF completo, XMP, etc.) é descartado.

                } else {

                    // APP13 ou COM: apenas descarta o payload.
                    copySegmentData(in, null, length - 2);
                }

                if (marker == 0xDA) {
                    copyEntropyData(in, out);
                }
            }

        }
    }

    /**
     * Extrai o valor do campo Orientation (tag 0x0112)
     * de um payload APP1 EXIF. Retorna 0 se não
     * encontrar ou se a estrutura for inválida.
     */
    private static int extractJpegOrientation(
            byte[] payload
    ) {

        if (payload.length < 14) {
            return 0;
        }

        // O payload deve começar com "Exif\0\0".
        if (payload[0] != 'E' || payload[1] != 'x'
                || payload[2] != 'i' || payload[3] != 'f'
                || payload[4] != 0 || payload[5] != 0) {
            return 0;
        }

        int tiff = 6;

        boolean littleEndian;

        if (payload[tiff] == 'I'
                && payload[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (payload[tiff] == 'M'
                && payload[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return 0;
        }

        if (readU16(payload, tiff + 2, littleEndian)
                != 0x2A) {
            return 0;
        }

        long ifdOffset =
                readU32(payload, tiff + 4, littleEndian);

        if (ifdOffset <= 0
                || ifdOffset > payload.length - tiff) {
            return 0;
        }

        int ifd = tiff + (int) ifdOffset;

        if (ifd + 2 > payload.length) {
            return 0;
        }

        int entryCount =
                readU16(payload, ifd, littleEndian);

        if (ifd + 2 + entryCount * 12
                > payload.length) {
            return 0;
        }

        for (int i = 0; i < entryCount; i++) {

            int entry = ifd + 2 + i * 12;

            int tag =
                    readU16(payload, entry, littleEndian);

            if (tag != 0x0112) {
                continue;
            }

            int type =
                    readU16(payload, entry + 2, littleEndian);

            long count =
                    readU32(payload, entry + 4, littleEndian);

            if (type != 3 || count < 1) {
                return 0;
            }

            // Um SHORT cabe no próprio campo de valor.
            if (count == 1) {
                return readU16(
                        payload,
                        entry + 8,
                        littleEndian
                );
            }

            // Vários valores: o campo aponta para
            // o offset real dentro do TIFF.
            long valueOffset =
                    readU32(payload, entry + 8, littleEndian);

            if (valueOffset <= 0
                    || valueOffset > payload.length - tiff - 2) {
                return 0;
            }

            int valuePos =
                    tiff + (int) valueOffset;

            if (valuePos + 2 > payload.length) {
                return 0;
            }

            return readU16(
                    payload,
                    valuePos,
                    littleEndian
            );
        }

        return 0;
    }

    /**
     * Constrói um segmento APP1 EXIF mínimo
     * contendo somente o campo Orientation.
     */
    private static byte[] buildMinimalExifSegment(
            int orientation
    ) {

        // "Exif\0\0" + TIFF (II, 0x2A, IFD0 @ 8)
        // + IFD0 com 1 entrada (Orientation, SHORT)
        // + próximo IFD = 0.
        return new byte[] {
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I',
                0x2A, 0x00,
                0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,             // 1 entrada
                0x12, 0x01,             // tag 0x0112
                0x03, 0x00,             // tipo SHORT
                0x01, 0x00, 0x00, 0x00, // count 1
                (byte) orientation, 0x00,
                0x00, 0x00,             // valor + preenchimento
                0x00, 0x00, 0x00, 0x00  // próximo IFD: nenhum
        };
    }

    private static int readU16(
            byte[] data,
            int offset,
            boolean littleEndian
    ) {

        int b1 = data[offset] & 0xFF;
        int b2 = data[offset + 1] & 0xFF;

        return littleEndian
                ? (b2 << 8) | b1
                : (b1 << 8) | b2;
    }

    private static long readU32(
            byte[] data,
            int offset,
            boolean littleEndian
    ) {

        long b1 = data[offset] & 0xFFL;
        long b2 = data[offset + 1] & 0xFFL;
        long b3 = data[offset + 2] & 0xFFL;
        long b4 = data[offset + 3] & 0xFFL;

        return littleEndian
                ? (b4 << 24) | (b3 << 16) | (b2 << 8) | b1
                : (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static void readSegmentFully(
            PushbackInputStream in,
            byte[] buffer
    ) throws IOException {

        int offset = 0;

        while (offset < buffer.length) {

            int count = in.read(
                    buffer,
                    offset,
                    buffer.length - offset
            );

            if (count == -1) {
                throw new IOException("JPEG truncado.");
            }

            offset += count;
        }
    }

    private static void copySegmentData(
            PushbackInputStream in,
            BufferedOutputStream out,
            int length
    ) throws IOException {

        byte[] buffer = new byte[8192];

        while (length > 0) {

            int requested =
                    Math.min(buffer.length, length);

            int count = in.read(buffer, 0, requested);

            if (count == -1) {
                throw new IOException("JPEG truncado.");
            }

            if (out != null) {
                out.write(buffer, 0, count);
            }

            length -= count;
        }
    }

    private static void copyEntropyData(
        PushbackInputStream in,
        BufferedOutputStream out
) throws IOException {

    while (true) {

        int value = in.read();

        if (value == -1) {
            throw new IOException("JPEG truncado.");
        }

        if (value != 0xFF) {
            out.write(value);
            continue;
        }

        int next = in.read();

        if (next == -1) {
            throw new IOException("JPEG truncado.");
        }

        // Byte escapado ou marcador de reinício.
        if (next == 0x00
                || (next >= 0xD0 && next <= 0xD7)) {

            out.write(0xFF);
            out.write(next);
            continue;
        }

        // Um novo marcador será tratado pelo método principal.
        in.unread(next);
        in.unread(0xFF);
        return;
    }
}


private static void cleanPng(
        File input,
        File output
) throws IOException {

    byte[] signature = {
            (byte) 0x89, 'P', 'N', 'G',
            13, 10, 26, 10
    };

    try (
            BufferedInputStream in =
                    new BufferedInputStream(
                            new FileInputStream(input)
                    );

            BufferedOutputStream out =
                    new BufferedOutputStream(
                            new FileOutputStream(output)
                    )
    ) {

        byte[] receivedSignature = new byte[8];
        readFully(in, receivedSignature);

        for (int i = 0; i < 8; i++) {
            if (receivedSignature[i] != signature[i]) {
                throw new IOException("PNG inválido.");
            }
        }

        out.write(signature);

        boolean hasIHDR = false;
        boolean hasIDAT = false;
        boolean hasIEND = false;

        while (!hasIEND) {

            long length = readUInt32(in);

            if (length > 0x7FFFFFFFL) {
                throw new IOException(
                        "Chunk PNG muito grande."
                );
            }

            byte[] type = new byte[4];
            readFully(in, type);

            for (byte b : type) {
                int c = b & 0xFF;

                if (!((c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z'))) {
                    throw new IOException(
                            "Tipo de chunk PNG inválido."
                    );
                }
            }

            String chunk = new String(
                    type,
                    java.nio.charset.StandardCharsets.US_ASCII
            );

            if (!hasIHDR && !"IHDR".equals(chunk)) {
                throw new IOException(
                        "Cabeçalho PNG inválido."
                );
            }

            if ("IHDR".equals(chunk)) {

                if (hasIHDR || length != 13) {
                    throw new IOException(
                            "Chunk IHDR inválido."
                    );
                }

                hasIHDR = true;
            }

            if ("IDAT".equals(chunk)) {
                hasIDAT = true;
            }

            if ("IEND".equals(chunk)) {

                if (length != 0 || !hasIDAT) {
                    throw new IOException(
                            "Final PNG inválido."
                    );
                }

                hasIEND = true;
            }

            boolean remove =
                    "eXIf".equals(chunk)
                    || "tEXt".equals(chunk)
                    || "zTXt".equals(chunk)
                    || "iTXt".equals(chunk)
                    || "tIME".equals(chunk);

            CRC32 crc = new CRC32();
            crc.update(type);

            if (!remove) {
                writeUInt32(out, length);
                out.write(type);
            }

            byte[] buffer = new byte[64 * 1024];
            long remaining = length;

            while (remaining > 0) {

                int requested = (int) Math.min(
                        buffer.length,
                        remaining
                );

                int count = in.read(
                        buffer,
                        0,
                        requested
                );

                if (count == -1) {
                    throw new IOException(
                            "PNG truncado."
                    );
                }

                crc.update(buffer, 0, count);

                if (!remove) {
                    out.write(buffer, 0, count);
                }

                remaining -= count;
            }

            long storedCrc = readUInt32(in);

            if (crc.getValue() != storedCrc) {
                throw new IOException(
                        "CRC inválido no PNG."
                );
            }

            if (!remove) {
                writeUInt32(out, storedCrc);
            }
        }

        if (!hasIHDR || !hasIDAT || !hasIEND) {
            throw new IOException(
                    "Estrutura PNG incompleta."
            );
        }

        if (in.read() != -1) {
            throw new IOException(
                    "Dados extras após o fim do PNG."
            );
        }
    }
}

private static void readFully(
        BufferedInputStream in,
        byte[] buffer
) throws IOException {

    int offset = 0;

    while (offset < buffer.length) {

        int count = in.read(
                buffer,
                offset,
                buffer.length - offset
        );

        if (count == -1) {
            throw new IOException("Arquivo truncado.");
        }

        offset += count;
    }
}

private static long readUInt32(
        BufferedInputStream in
) throws IOException {

    int b1 = in.read();
    int b2 = in.read();
    int b3 = in.read();
    int b4 = in.read();

    if ((b1 | b2 | b3 | b4) < 0) {
        throw new IOException("PNG truncado.");
    }

    return ((long) b1 << 24)
            | ((long) b2 << 16)
            | ((long) b3 << 8)
            | (long) b4;
}

private static void writeUInt32(
        BufferedOutputStream out,
        long value
) throws IOException {

    out.write((int) (value >>> 24) & 0xFF);
    out.write((int) (value >>> 16) & 0xFF);
    out.write((int) (value >>> 8) & 0xFF);
    out.write((int) value & 0xFF);
}


private static void cleanWebp(
        File input,
        File output
) throws IOException {

    long inputSize = input.length();

    if (inputSize < 20) {
        throw new IOException("WebP inválido ou truncado.");
    }

    try (
            BufferedInputStream in =
                    new BufferedInputStream(
                            new FileInputStream(input)
                    );

            RandomAccessFile out =
                    new RandomAccessFile(output, "rw")
    ) {

        out.setLength(0);

        byte[] header = new byte[12];
        readFullyWebp(in, header);

        if (!matches(header, 0, "RIFF")
                || !matches(header, 8, "WEBP")) {
            throw new IOException("Cabeçalho WebP inválido.");
        }

        long riffSize = readLE32(header, 4);

        if (riffSize != inputSize - 8) {
            throw new IOException(
                    "Tamanho RIFF inválido."
            );
        }

        out.write(header);

        long remaining = riffSize - 4;

        boolean firstChunk = true;
        boolean hasVP8X = false;
        boolean hasImage = false;
        boolean removedMetadata = false;

        while (remaining > 0) {

            if (remaining < 8) {
                throw new IOException(
                        "Chunk WebP incompleto."
                );
            }

            byte[] chunkHeader = new byte[8];
            readFullyWebp(in, chunkHeader);

            remaining -= 8;

            String type = new String(
                    chunkHeader,
                    0,
                    4,
                    java.nio.charset.StandardCharsets.US_ASCII
            );

            long chunkSize = readLE32(chunkHeader, 4);

            long padding = chunkSize & 1L;

            if (chunkSize > remaining - padding) {
                throw new IOException(
                        "Tamanho de chunk WebP inválido."
                );
            }

            remaining -= chunkSize + padding;

            if (firstChunk && !"VP8X".equals(type)
                    && ("EXIF".equals(type)
                    || "XMP ".equals(type))) {
                throw new IOException(
                        "Estrutura WebP estendida inválida."
                );
            }

            firstChunk = false;

            if ("VP8 ".equals(type)
                    || "VP8L".equals(type)
                    || "ANMF".equals(type)) {
                hasImage = true;
            }

            boolean remove =
                    "EXIF".equals(type)
                    || "XMP ".equals(type);

            if (remove) {
                removedMetadata = true;

                skipFullyWebp(in, chunkSize);

                if (padding != 0) {
                    skipFullyWebp(in, 1);
                }

                continue;
            }

            out.write(chunkHeader);

            if ("VP8X".equals(type)) {

                if (hasVP8X || chunkSize != 10) {
                    throw new IOException(
                            "Cabeçalho VP8X inválido."
                    );
                }

                byte[] vp8x = new byte[10];
                readFullyWebp(in, vp8x);

                // Bit 3: EXIF
                // Bit 2: XMP
                vp8x[0] = (byte) (
                        vp8x[0] & ~(0x08 | 0x04)
                );

                out.write(vp8x);

                hasVP8X = true;

            } else {

                copyWebpData(
                        in,
                        out,
                        chunkSize
                );
            }

            if (padding != 0) {

                int pad = in.read();

                if (pad == -1) {
                    throw new IOException(
                            "WebP truncado."
                    );
                }

                out.write(pad);
            }
        }

        if (in.read() != -1) {
            throw new IOException(
                    "Dados extras após o fim do WebP."
            );
        }

        if (!hasImage) {
            throw new IOException(
                    "WebP não contém dados de imagem."
            );
        }

        if (removedMetadata && !hasVP8X) {
            throw new IOException(
                    "Não foi possível validar os metadados WebP."
            );
        }

        long finalSize = out.length() - 8;

        if (finalSize > 0xFFFFFFFFL) {
            throw new IOException(
                    "WebP resultante muito grande."
            );
        }

        // Atualiza o tamanho RIFF no cabeçalho.
        out.seek(4);
        writeLE32(out, finalSize);

        out.getFD().sync();
    }
}

private static void readFullyWebp(
        BufferedInputStream in,
        byte[] buffer
) throws IOException {

    int offset = 0;

    while (offset < buffer.length) {

        int count = in.read(
                buffer,
                offset,
                buffer.length - offset
        );

        if (count == -1) {
            throw new IOException("WebP truncado.");
        }

        offset += count;
    }
}

private static boolean matches(
        byte[] data,
        int offset,
        String expected
) {

    if (offset + expected.length() > data.length) {
        return false;
    }

    for (int i = 0; i < expected.length(); i++) {
        if (data[offset + i] != (byte) expected.charAt(i)) {
            return false;
        }
    }

    return true;
}

private static long readLE32(
        byte[] data,
        int offset
) {

    return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24);
}

private static void writeLE32(
        RandomAccessFile out,
        long value
) throws IOException {

    out.write((int) value & 0xFF);
    out.write((int) (value >>> 8) & 0xFF);
    out.write((int) (value >>> 16) & 0xFF);
    out.write((int) (value >>> 24) & 0xFF);
}

private static void copyWebpData(
        BufferedInputStream in,
        RandomAccessFile out,
        long length
) throws IOException {

    byte[] buffer = new byte[64 * 1024];

    while (length > 0) {

        int requested = (int) Math.min(
                buffer.length,
                length
        );

        int count = in.read(
                buffer,
                0,
                requested
        );

        if (count == -1) {
            throw new IOException("WebP truncado.");
        }

        out.write(buffer, 0, count);

        length -= count;
    }
}

private static void skipFullyWebp(
        BufferedInputStream in,
        long length
) throws IOException {

    byte[] buffer = new byte[64 * 1024];

    while (length > 0) {

        int requested = (int) Math.min(
                buffer.length,
                length
        );

        int count = in.read(
                buffer,
                0,
                requested
        );

        if (count == -1) {
            throw new IOException("WebP truncado.");
        }

        length -= count;
    }
}


private static void cleanGif(
        File input,
        File output
) throws IOException {

    try (
            BufferedInputStream in =
                    new BufferedInputStream(
                            new FileInputStream(input)
                    );

            BufferedOutputStream out =
                    new BufferedOutputStream(
                            new FileOutputStream(output)
                    )
    ) {

        byte[] header = new byte[6];
        readFullyGif(in, header);

        if (!matches(header, 0, "GIF87a")
                && !matches(header, 0, "GIF89a")) {
            throw new IOException("Cabeçalho GIF inválido.");
        }

        out.write(header);

        // Logical Screen Descriptor.
        byte[] screen = new byte[7];
        readFullyGif(in, screen);
        out.write(screen);

        int packed = screen[4] & 0xFF;

        // Tabela global de cores.
        if ((packed & 0x80) != 0) {

            int entries = 1 << ((packed & 0x07) + 1);
            int tableSize = entries * 3;

            copyGifData(in, out, tableSize);
        }

        boolean foundImage = false;
        boolean foundTrailer = false;

        while (!foundTrailer) {

            int introducer = in.read();

            if (introducer == -1) {
                throw new IOException("GIF truncado.");
            }

            switch (introducer) {

                // Image Descriptor.
                case 0x2C: {

                    byte[] descriptor = new byte[9];
                    readFullyGif(in, descriptor);

                    out.write(introducer);
                    out.write(descriptor);

                    int imagePacked =
                            descriptor[8] & 0xFF;

                    // Tabela local de cores.
                    if ((imagePacked & 0x80) != 0) {

                        int entries =
                                1 << ((imagePacked & 0x07) + 1);

                        copyGifData(
                                in,
                                out,
                                entries * 3
                        );
                    }

                    // LZW Minimum Code Size.
                    int codeSize = in.read();

                    if (codeSize < 2 || codeSize > 8) {
                        throw new IOException(
                                "Código LZW GIF inválido."
                        );
                    }

                    out.write(codeSize);

                    // Dados comprimidos da imagem.
                    copyGifSubBlocks(in, out);

                    foundImage = true;
                    break;
                }

                // Extension Introducer.
                case 0x21: {

                    int label = in.read();

                    if (label == -1) {
                        throw new IOException("GIF truncado.");
                    }

                    // Comentários: remover.
                    if (label == 0xFE) {
                        copyGifSubBlocks(in, null);
                        break;
                    }

                    // Application Extension.
                    if (label == 0xFF) {

                        int blockSize = in.read();

                        if (blockSize != 11) {
                            throw new IOException(
                                    "Extensão GIF inválida."
                            );
                        }

                        byte[] application = new byte[11];
                        readFullyGif(in, application);

                        boolean isXmp =
                                matches(
                                        application,
                                        0,
                                        "XMP DataXMP"
                                );

                        if (!isXmp) {
                            out.write(0x21);
                            out.write(label);
                            out.write(blockSize);
                            out.write(application);
                        }

                        copyGifSubBlocks(
                                in,
                                isXmp ? null : out
                        );

                        break;
                    }

                    // Graphic Control Extension.
                    // Plain Text Extension.
                    // Outras extensões.
                    out.write(0x21);
                    out.write(label);

                    if (label == 0xF9) {

                        copyGifGraphicControlExtension(in, out);

                        break;
                    }

                    if (label == 0x01) {

                        copyGifFixedExtension(
                                in,
                                out,
                                12
                        );

                        copyGifSubBlocks(in, out);
                        break;
                    }

                    // Extensões desconhecidas:
                    // preservar os sub-blocos.
                    copyGifSubBlocks(in, out);
                    break;
                }

                // Trailer: fim do GIF.
                case 0x3B:

                    out.write(0x3B);
                    foundTrailer = true;
                    break;

                default:
                    throw new IOException(
                            "Estrutura GIF inválida."
                    );
            }
        }

        if (!foundImage) {
            throw new IOException(
                    "GIF não contém quadros de imagem."
            );
        }

        if (in.read() != -1) {
            throw new IOException(
                    "Dados extras após o fim do GIF."
            );
        }

        out.flush();
    }
}

private static void copyGifFixedExtension(
        BufferedInputStream in,
        BufferedOutputStream out,
        int expectedSize
) throws IOException {

    int size = in.read();

    if (size != expectedSize) {
        throw new IOException(
                "Bloco de extensão GIF inválido."
        );
    }

    out.write(size);

    copyGifData(in, out, size);
}

private static void copyGifSubBlocks(
        BufferedInputStream in,
        BufferedOutputStream out
) throws IOException {

    while (true) {

        int size = in.read();

        if (size == -1) {
            throw new IOException("GIF truncado.");
        }

        if (out != null) {
            out.write(size);
        }

        if (size == 0) {
            break;
        }

        copyGifData(in, out, size);
    }
}

private static void copyGifData(
        BufferedInputStream in,
        BufferedOutputStream out,
        int length
) throws IOException {

    byte[] buffer = new byte[8192];

    while (length > 0) {

        int requested = Math.min(
                buffer.length,
                length
        );

        int count = in.read(
                buffer,
                0,
                requested
        );

        if (count == -1) {
            throw new IOException("GIF truncado.");
        }

        if (out != null) {
            out.write(buffer, 0, count);
        }

        length -= count;
    }
}

private static void readFullyGif(
        BufferedInputStream in,
        byte[] buffer
) throws IOException {

    int offset = 0;

    while (offset < buffer.length) {

        int count = in.read(
                buffer,
                offset,
                buffer.length - offset
        );

        if (count == -1) {
            throw new IOException("GIF truncado.");
        }

        offset += count;
    }
}


private static void copyGifGraphicControlExtension(
        BufferedInputStream in,
        BufferedOutputStream out
) throws IOException {

    int blockSize = in.read();

    if (blockSize != 4) {
        throw new IOException(
                "Graphic Control Extension inválida."
        );
    }

    out.write(blockSize);

    // Copia os quatro bytes de controle:
    // flags, duração e índice de transparência.
    copyGifData(in, out, 4);

    // O terminador obrigatório do bloco é 0x00.
    int terminator = in.read();

    if (terminator != 0) {
        throw new IOException(
                "Terminador GIF inválido."
        );
    }

    out.write(terminator);
}


private static void cleanPdf(
        File input,
        File output
) throws IOException {

    if (input.length() < 8) {
        throw new IOException("PDF inválido ou truncado.");
    }

    try (PDDocument document = PDDocument.load(
            input,
            MemoryUsageSetting.setupTempFileOnly()
    )) {

        if (document.isEncrypted()) {
            throw new IOException(
                    "PDF protegido por senha ou criptografia não suportado."
            );
        }

        if (document.getNumberOfPages() < 1) {
            throw new IOException(
                    "PDF não contém páginas."
            );
        }

        // Remove completamente o dicionário de informações.
        // Isso também elimina campos personalizados.
        document.setDocumentInformation(
                new PDDocumentInformation()
        );

        // Remove o fluxo de metadados XMP do catálogo.
        document.getDocumentCatalog().setMetadata(null);

        // Remove os identificadores internos do documento.
        document.getDocument().getTrailer().removeItem(
                com.tom_roush.pdfbox.cos.COSName.ID
        );

        // Salva uma nova versão do PDF.
        document.save(output);

    } catch (IOException e) {
        throw new IOException(
                "Não foi possível limpar os metadados do PDF: "
                        + e.getMessage(),
                e
        );
    } catch (RuntimeException e) {
        throw new IOException(
                "Erro ao processar a estrutura do PDF.",
                e
        );
    }
}

private static int detectOpenXmlFormat(File file)
        throws IOException {

    // Um arquivo que não é um ZIP válido não é
    // um pacote OpenXML; a mensagem de erro
    // adequada vem do chamador.
    try (ZipFile zip = new ZipFile(file)) {

        // Um pacote Office precisa ter este arquivo.
        if (zip.getEntry("[Content_Types].xml") == null) {
            return 0;
        }

        boolean docx =
                zip.getEntry("word/document.xml") != null;

        boolean xlsx =
                zip.getEntry("xl/workbook.xml") != null;

        boolean pptx =
                zip.getEntry("ppt/presentation.xml") != null;

        // Não aceita um pacote com estruturas conflitantes.
        int matches = (docx ? 1 : 0)
                + (xlsx ? 1 : 0)
                + (pptx ? 1 : 0);

        if (matches != 1) {
            return 0;
        }

        if (docx) {
            return DOCX;
        }

        if (xlsx) {
            return XLSX;
        }

        if (pptx) {
            return PPTX;
        }

        return 0;

    } catch (ZipException e) {
        return 0;
    }
}

private static void cleanOpenXml(
        File input,
        File output
) throws IOException {

    final int MAX_ENTRIES = 10000;

    final long MAX_TOTAL_SIZE =
            1024L * 1024L * 1024L; // 1 GiB

    final long MAX_ENTRY_SIZE =
            512L * 1024L * 1024L; // 512 MiB

    final long MAX_XML_SIZE =
            8L * 1024L * 1024L; // 8 MiB

    Set<String> metadataFiles = new HashSet<>();

    metadataFiles.add("docProps/core.xml");
    metadataFiles.add("docProps/app.xml");
    metadataFiles.add("docProps/custom.xml");

    Set<String> entryNames = new HashSet<>();

    long[] totalSize = {0L};

    boolean foundContentTypes = false;
    boolean foundRootRelationships = false;
    boolean foundMainDocument = false;

    try (
            ZipInputStream in =
                    new ZipInputStream(
                            new BufferedInputStream(
                                    new FileInputStream(input)
                            )
                    );

            ZipOutputStream out =
                    new ZipOutputStream(
                            new BufferedOutputStream(
                                    new FileOutputStream(output)
                            )
                    )
    ) {

        ZipEntry entry;

        int entryCount = 0;

        while ((entry = in.getNextEntry()) != null) {

            entryCount++;

            if (entryCount > MAX_ENTRIES) {
                throw new IOException(
                        "Documento contém entradas demais."
                );
            }

            String name = entry.getName();

            validateZipEntryName(name, entry.isDirectory());

            if (!entryNames.add(name)) {
                throw new IOException(
                        "Documento contém entradas duplicadas."
                );
            }

            // Remove os docProps de metadados e também
            // qualquer thumbnail embutido no pacote.
            boolean isThumbnail =
                    name.startsWith("docProps/thumbnail");

            if (metadataFiles.contains(name) || isThumbnail) {

                // Descarta o conteúdo, mas lê até o final
                // para permitir a verificação do CRC.
                copyZipEntryStreaming(
                        in,
                        null,
                        totalSize,
                        MAX_TOTAL_SIZE,
                        MAX_ENTRY_SIZE
                );

                in.closeEntry();
                continue;
            }

            boolean isContentTypes =
                    "[Content_Types].xml".equals(name);

            boolean isRootRelationships =
                    "_rels/.rels".equals(name);

            boolean isMainDocument =
                    "word/document.xml".equals(name)
                    || "xl/workbook.xml".equals(name)
                    || "ppt/presentation.xml".equals(name);

            if (isContentTypes) {
                foundContentTypes = true;
            }

            if (isRootRelationships) {
                foundRootRelationships = true;
            }

            if (isMainDocument) {
                foundMainDocument = true;
            }

            ZipEntry newEntry = new ZipEntry(name);

            // Não preserva timestamps potencialmente identificáveis.
            newEntry.setTime(0);

            out.putNextEntry(newEntry);

            if (isContentTypes || isRootRelationships) {

                // Somente os dois XMLs que precisam ser
                // modificados são carregados em memória.
                byte[] data = readZipEntryLimited(
                        in,
                        totalSize,
                        MAX_TOTAL_SIZE,
                        MAX_XML_SIZE
                );

                if (isContentTypes) {
                    data = cleanContentTypes(data);
                } else {
                    data = cleanRootRelationships(data);
                }

                out.write(data);

            } else {

                // Todas as outras entradas são processadas
                // diretamente, sem criar grandes byte arrays.
                copyZipEntryStreaming(
                        in,
                        out,
                        totalSize,
                        MAX_TOTAL_SIZE,
                        MAX_ENTRY_SIZE
                );
            }

            out.closeEntry();
            in.closeEntry();
        }

        if (!foundContentTypes
                || !foundRootRelationships
                || !foundMainDocument) {

            throw new IOException(
                    "Estrutura do documento Office incompleta."
            );
        }

        out.finish();
    }
}

private static void validateZipEntryName(
        String name,
        boolean directory
) throws IOException {

    if (name == null
            || name.isEmpty()
            || name.length() > 4096
            || name.startsWith("/")
            || name.startsWith("\\")
            || name.contains("\\")
            || name.contains(":")
            || name.indexOf('\0') >= 0) {

        throw new IOException(
                "Caminho inválido no documento."
        );
    }

    String[] parts = name.split("/", -1);

    for (int i = 0; i < parts.length; i++) {

        String part = parts[i];

        if (".".equals(part)
                || "..".equals(part)) {

            throw new IOException(
                    "Caminho inseguro no documento."
            );
        }

        // Somente o último componente pode ser vazio,
        // e apenas quando representa um diretório.
        if (part.isEmpty()
                && !(directory && i == parts.length - 1)) {

            throw new IOException(
                    "Nome de entrada ZIP inválido."
            );
        }
    }

    if (directory && !name.endsWith("/")) {
        throw new IOException(
                "Diretório ZIP inválido."
        );
    }
}

private static void copyZipEntryStreaming(
        ZipInputStream in,
        ZipOutputStream out,
        long[] totalSize,
        long maxTotalSize,
        long maxEntrySize
) throws IOException {

    byte[] buffer = new byte[64 * 1024];

    long entrySize = 0;

    int count;

    while ((count = in.read(buffer)) != -1) {

        if (count > maxEntrySize - entrySize) {
            throw new IOException(
                    "Entrada ZIP excede o limite permitido."
            );
        }

        if (count > maxTotalSize - totalSize[0]) {
            throw new IOException(
                    "Documento excede o limite total permitido."
            );
        }

        entrySize += count;
        totalSize[0] += count;

        if (out != null) {
            out.write(buffer, 0, count);
        }
    }
}

private static byte[] readZipEntryLimited(
        ZipInputStream in,
        long[] totalSize,
        long maxTotalSize,
        long maxEntrySize
) throws IOException {

    ByteArrayOutputStream buffer =
            new ByteArrayOutputStream();

    byte[] data = new byte[8192];

    long entrySize = 0;

    int count;

    while ((count = in.read(data)) != -1) {

        if (count > maxEntrySize - entrySize) {
            throw new IOException(
                    "Arquivo XML excede o limite permitido."
            );
        }

        if (count > maxTotalSize - totalSize[0]) {
            throw new IOException(
                    "Documento excede o limite total permitido."
            );
        }

        entrySize += count;
        totalSize[0] += count;

        buffer.write(data, 0, count);
    }

    return buffer.toByteArray();
}

private static byte[] cleanContentTypes(
        byte[] input
) throws IOException {

    try {
        Document document = parseXml(input);

        Node root = document.getDocumentElement();

        removeNodesWithPartNamePrefix(
                root,
                "/docProps/core.xml"
        );

        removeNodesWithPartNamePrefix(
                root,
                "/docProps/app.xml"
        );

        removeNodesWithPartNamePrefix(
                root,
                "/docProps/custom.xml"
        );

        removeNodesWithPartNamePrefix(
                root,
                "/docProps/thumbnail"
        );

        return writeXml(document);

    } catch (Exception e) {

        throw new IOException(
                "Content_Types.xml inválido: "
                        + e.getClass().getSimpleName()
                        + " - "
                        + e.getMessage(),
                e
        );
    }
}

private static byte[] cleanRootRelationships(
        byte[] input
) throws IOException {

    try {

        Document document =
                parseXml(input);

        Node root =
                document.getDocumentElement();

        Node child = root.getFirstChild();

        while (child != null) {

            Node next = child.getNextSibling();

            if (child instanceof Element) {

                Element element =
                        (Element) child;

                String target =
                        element.getAttribute(
                                "Target"
                        );

                if ("docProps/core.xml".equals(target)
                        || "docProps/app.xml".equals(target)
                        || "docProps/custom.xml".equals(target)
                        || (target != null
                        && target.contains(
                        "docProps/thumbnail"))) {

                    root.removeChild(child);
                }
            }

            child = next;
        }

        return writeXml(document);

    } catch (Exception e) {

        throw new IOException(
                "_rels/.rels inválido.",
                e
        );
    }
}

private static void removeNodesWithPartNamePrefix(
        Node root,
        String partNamePrefix
) {

    Node child = root.getFirstChild();

    while (child != null) {

        Node next = child.getNextSibling();

        if (child instanceof Element) {

            Element element =
                    (Element) child;

            String value =
                    element.getAttribute(
                            "PartName"
                    );

            if (value != null
                    && value.startsWith(partNamePrefix)) {
                root.removeChild(child);
            }
        }

        child = next;
    }
}

private static Document parseXml(
        byte[] data
) throws Exception {

    DocumentBuilderFactory factory =
            DocumentBuilderFactory.newInstance();

    // As features de segurança abaixo são opcionais:
    // alguns parsers Android podem não suportá-las.
    // Por isso, cada uma é aplicada em try/catch
    // próprio, degradando com segurança em vez de
    // abortar. A garantia universal contra entidades
    // externas fica no EntityResolver registrado no
    // DocumentBuilder, que é suportado por qualquer
    // parser DOM.
    try {
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
    } catch (Exception ignored) {
    }

    try {
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
    } catch (Exception ignored) {
    }

    try {
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
    } catch (Exception ignored) {
    }

    try {
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false
        );
    } catch (Exception ignored) {
    }

    try {
        factory.setXIncludeAware(false);
    } catch (UnsupportedOperationException ignored) {
        // Recurso não suportado pelo parser Android.
    }

    factory.setExpandEntityReferences(false);

    DocumentBuilder builder =
            factory.newDocumentBuilder();

    // Garantia universal: qualquer tentativa de
    // resolver uma entidade externa é rejeitada
    // explicitamente com falha, em vez de receber
    // conteúdo vazio. Este resolver só é invocado
    // em documentos com DOCTYPE ou entidades
    // externas — documentos Office comuns nunca
    // o acionam, então não há risco de
    // incompatibilidade. Classe anônima em vez de
    // lambda, para não depender de desugaring.
    builder.setEntityResolver(
            new EntityResolver() {

                @Override
                public InputSource resolveEntity(
                        String publicId,
                        String systemId
                ) throws SAXException {

                    throw new SAXException(
                            "Entidade externa não permitida."
                    );
                }
            }
    );

    Document document =
            builder.parse(
                    new ByteArrayInputStream(data)
            );

    document.getDocumentElement().normalize();

    return document;
}

private static byte[] writeXml(
        Document document
) throws Exception {

    TransformerFactory factory =
            TransformerFactory.newInstance();

    Transformer transformer =
            factory.newTransformer();

    transformer.setOutputProperty(
            OutputKeys.ENCODING,
            "UTF-8"
    );

    transformer.setOutputProperty(
            OutputKeys.OMIT_XML_DECLARATION,
            "no"
    );

    ByteArrayOutputStream output =
            new ByteArrayOutputStream();

    transformer.transform(
            new DOMSource(document),
            new StreamResult(output)
    );

    return output.toByteArray();
}

}
