
package com.lukestudio.fileencryptor2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;

public final class VideoMetadataCleaner {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ATOMS = 100000;

    private static final String TEST_MDTA_KEY =
        "mdta:com.android.model";

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final long MAX_OUTPUT_SIZE =
        16L * 1024L * 1024L * 1024L;

    private static final Set<String> METADATA_ATOMS =
        new HashSet<>(Arrays.asList(
                "©xyz",
                "©day",
                "©nam",
                "©ART",
                "©alb",
                "©cmt",
                "©des",
                "©too",
                "©enc",
                "©dir",
                "©grp",
                "©lyr",
                "©wrt"
        ));

    private static final Set<String> THREE_GP_METADATA_ATOMS =
        new HashSet<>(Arrays.asList(
                "titl", // Título
                "dscp", // Descrição
                "cprt", // Direitos autorais
                "perf", // Artista ou intérprete
                "auth", // Autor
                "gnre", // Gênero
                "rtng", // Classificação
                "clsf", // Classificação de conteúdo
                "kywd", // Palavras-chave
                "albm", // Álbum
                "yrrc", // Ano de gravação
                "coll", // Coleção
                "urat"  // Avaliação do usuário
        ));

    private static final Set<String> CONTAINERS =
            new HashSet<>(Arrays.asList(
                    "moov",
                    "trak",
                    "mdia",
                    "minf",
                    "stbl",
                    "edts",
                    "dinf",
                    "mvex",
                    "moof",
                    "traf",
                    "mfra",
                    "udta",
                    "meta",
                    "ilst",
                    "sinf",
                    "schi",
                    "wave",
                    "tref",
                    "meco",
                    "mere"
            ));

    private VideoMetadataCleaner() {
    }

    public enum VideoFormat {
        MP4,
        M4V,
        THREE_GP
    }

    public static final class AtomInfo {

        public final String type;
        public final String path;
        public final long offset;
        public final long size;
        public final long headerSize;

        private AtomInfo(
                String type,
                String path,
                long offset,
                long size,
                long headerSize
        ) {
            this.type = type;
            this.path = path;
            this.offset = offset;
            this.size = size;
            this.headerSize = headerSize;
        }

        @Override
        public String toString() {
            return path
                    + " | offset=" + offset
                    + " | size=" + size;
        }
    }

    /**
     * Identifica MP4, M4V ou 3GP.
     *
     * A extensão não é utilizada como única prova.
     * O arquivo também precisa apresentar uma estrutura
     * básica compatível com ISO Base Media File Format.
     */
    public static VideoFormat detectFormat(File file)
            throws IOException {

        validateFile(file);

        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "r")) {

            long length = raf.length();

            if (length < 12) {
                throw new IOException("Arquivo de vídeo inválido.");
            }

            long size = readUnsignedInt(raf);
            String type = readFourCC(raf);

            if (!"ftyp".equals(type)) {
                throw new IOException(
                        "Contêiner de vídeo não reconhecido."
                );
            }

            long headerSize = 8;

            if (size == 1) {
                size = raf.readLong();
                headerSize = 16;

                if (size < 0) {
                    throw new IOException(
                            "Tamanho de arquivo não suportado."
                    );
                }
            }

            if (size == 0) {
                size = length;
            }

            if (size < headerSize + 8 || size > length) {
                throw new IOException(
                        "Cabeçalho de vídeo inválido."
                );
            }

            long payloadSize = size - headerSize;

            if (payloadSize > 1024 * 1024) {
                throw new IOException(
                        "Cabeçalho ftyp excessivamente grande."
                );
            }

            byte[] payload = new byte[(int) payloadSize];
            raf.readFully(payload);

            if (payload.length < 8) {
                throw new IOException(
                        "Identificação do contêiner incompleta."
                );
            }

            String majorBrand = new String(
                    payload,
                    0,
                    4,
                    StandardCharsets.ISO_8859_1
            );

            if (majorBrand.startsWith("3g")) {
                return VideoFormat.THREE_GP;
            }

            if ("M4V ".equals(majorBrand)
                    || "M4VH".equals(majorBrand)
                    || "M4VP".equals(majorBrand)) {
                return VideoFormat.M4V;
            }

            if ("isom".equals(majorBrand)
                    || "iso2".equals(majorBrand)
                    || "iso3".equals(majorBrand)
                    || "iso4".equals(majorBrand)
                    || "iso5".equals(majorBrand)
                    || "iso6".equals(majorBrand)
                    || "mp41".equals(majorBrand)
                    || "mp42".equals(majorBrand)
                    || "avc1".equals(majorBrand)
                    || "dash".equals(majorBrand)
                    || "M4V ".equals(majorBrand)) {
                return VideoFormat.MP4;
            }

            throw new IOException(
                    "Formato não suportado: " + majorBrand
            );
        }
    }

    /**
     * Analisa os átomos sem modificar o arquivo.
     */
    public static List<AtomInfo> inspect(File file)
            throws IOException {

        validateFile(file);

        List<AtomInfo> result = new ArrayList<>();

        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "r")) {

            parseAtoms(
                    raf,
                    0,
                    raf.length(),
                    0,
                    "",
                    result
            );
        }

        if (result.isEmpty()) {
            throw new IOException(
                    "Nenhum átomo válido encontrado."
            );
        }

        return Collections.unmodifiableList(result);
    }

    private static List<AtomInfo> inspect(
        RandomAccessFile raf
) throws IOException {

    List<AtomInfo> result = new ArrayList<>();

    parseAtoms(
            raf,
            0,
            raf.length(),
            0,
            "",
            result
    );

    return Collections.unmodifiableList(result);
}

    public static final class MetadataInfo {

    public final String type;
    public final String path;
    public final long offset;
    public final long size;

    private MetadataInfo(
            String type,
            String path,
            long offset,
            long size
    ) {
        this.type = type;
        this.path = path;
        this.offset = offset;
        this.size = size;
    }

    @Override
    public String toString() {
        return type
                + " | " + path
                + " | offset=" + offset
                + " | size=" + size;
    }
}

private static final class FreeformInfo {

    final String namespace;
    final String name;
    final int dataType;
    final long dataSize;

    FreeformInfo(
            String namespace,
            String name,
            int dataType,
            long dataSize
    ) {
        this.namespace = namespace;
        this.name = name;
        this.dataType = dataType;
        this.dataSize = dataSize;
    }
}

private static final byte[] XMP_UUID = new byte[] {
        (byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB,
        (byte) 0x97, (byte) 0xA9, 0x42, (byte) 0xE8,
        (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94,
        (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC
};

/**
 * Reconhece o UUID utilizado para identificar XMP
 * em arquivos ISO Base Media.
 */
private static boolean isXmpUuid(
        RandomAccessFile raf,
        AtomInfo atom
) throws IOException {

    if (!"uuid".equals(atom.type)
            || atom.headerSize < 24
            || atom.size < atom.headerSize) {
        return false;
    }

    long uuidOffset =
            atom.offset + atom.headerSize - 16;

    raf.seek(uuidOffset);

    byte[] actual = new byte[16];
    raf.readFully(actual);

    return Arrays.equals(actual, XMP_UUID);
}

public static List<MetadataInfo> findMetadata(File file)
        throws IOException {

    List<AtomInfo> atoms = inspect(file);
    List<MetadataInfo> metadata = new ArrayList<>();

    Map<String, List<String>> keyTables = new HashMap<>();

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {

        // Primeira passagem: leitura das tabelas keys.
        for (AtomInfo atom : atoms) {

            if (!"keys".equals(atom.type)) {
                continue;
            }

            String parent = getParentPath(atom.path);

            if (!"meta".equals(getLastPathComponent(parent))) {
                continue;
            }

            List<String> keys = readKeyTable(raf, atom);

            keyTables.put(parent, keys);
        }

        // Segunda passagem: identificação dos metadados.
        for (AtomInfo atom : atoms) {

            inspectHeaderTimestamps(
                raf,
                atom,
                metadata
            );

            // XMP identificado pelo UUID específico.
            if (isXmpUuid(raf, atom)) {

                addMetadata(
                        metadata,
                        "XMP UUID",
                        atom
                );

                continue;
            }

            // Informações de localização 3GPP.
            if ("loci".equals(atom.type)
                    && isDirectChildOf(atom, "udta")) {

                addMetadata(
                        metadata,
                        "loci",
                        atom
                );

                continue;
            }

            // Metadados definidos pelo padrão 3GPP.
if (THREE_GP_METADATA_ATOMS.contains(atom.type)
        && isDirectChildOf(atom, "udta")) {

    addMetadata(
            metadata,
            "3GPP:" + atom.type,
            atom
    );

    continue;
}
            // Os itens tradicionais devem ser filhos diretos de ilst.
            if (!isDirectIlstItem(atom)) {
                continue;
            }

            // Metadados tradicionais.
            if (METADATA_ATOMS.contains(atom.type)) {

                addMetadata(
                        metadata,
                        atom.type,
                        atom
                );

                continue;
            }

            // Metadados personalizados.
            if ("----".equals(atom.type)) {

    FreeformInfo info = readFreeformInfo(
            raf,
            atoms,
            atom
    );

    if (info == null) {
        continue;
    }

    String metadataName =
            "freeform:"
                    + info.namespace
                    + ":"
                    + info.name;

    if (isPersonalMetadataKey(metadataName)) {

        addMetadata(
                metadata,
                metadataName,
                atom
        );
    }

    continue;
}

            /*
             * Itens mdta:
             * o identificador de quatro bytes representa
             * o índice da chave dentro da tabela keys.
             */
            String ilstPath = getParentPath(atom.path);
            String metaPath = getParentPath(ilstPath);

            List<String> keys = keyTables.get(metaPath);

            if (keys == null) {
                continue;
            }

            long index = getNumericFourCC(atom.type);

            if (index < 1 || index > keys.size()) {
                continue;
            }

            String keyName = keys.get((int) index - 1);

            if (isPersonalMetadataKey(keyName)) {

                addMetadata(
                        metadata,
                        keyName,
                        atom
                );
            }
        }
    }

    return Collections.unmodifiableList(metadata);
}

private static void addMetadata(
        List<MetadataInfo> metadata,
        String type,
        AtomInfo atom
) {

    metadata.add(new MetadataInfo(
            type,
            atom.path,
            atom.offset,
            atom.size
    ));
}

private static void inspectHeaderTimestamps(
        RandomAccessFile raf,
        AtomInfo atom,
        List<MetadataInfo> metadata
) throws IOException {

    String expectedParent;

    switch (atom.type) {
        case "mvhd":
            expectedParent = "moov";
            break;

        case "tkhd":
            expectedParent = "trak";
            break;

        case "mdhd":
            expectedParent = "mdia";
            break;

        default:
            return;
    }

    String parent = getParentPath(atom.path);

    if (!expectedParent.equals(getLastPathComponent(parent))) {
        return;
    }

    long payloadStart = atom.offset + atom.headerSize;
    long atomEnd = atom.offset + atom.size;

    if (payloadStart < atom.offset
            || atomEnd < payloadStart
            || atomEnd > raf.length()
            || atomEnd - payloadStart < 12) {

        throw new IOException(
                "Cabeçalho temporal inválido: " + atom.type
        );
    }

    raf.seek(payloadStart);

    int version = raf.readUnsignedByte();
    int flags = (raf.readUnsignedByte() << 16)
            | (raf.readUnsignedByte() << 8)
            | raf.readUnsignedByte();

    if (version != 0 && version != 1) {
    throw new IOException(
            "Versão temporal não suportada: "
                    + atom.type
    );
}

    int timestampSize = version == 0 ? 4 : 8;

    long requiredSize = version == 0 ? 12 : 20;

    if (atomEnd - payloadStart < requiredSize) {
        throw new IOException(
                "Campos temporais incompletos: " + atom.type
        );
    }

    long creationOffset = payloadStart + 4;
    long modificationOffset =
            creationOffset + timestampSize;

    metadata.add(new MetadataInfo(
            atom.type + ".creation_time",
            atom.path,
            creationOffset,
            timestampSize
    ));

    metadata.add(new MetadataInfo(
            atom.type + ".modification_time",
            atom.path,
            modificationOffset,
            timestampSize
    ));
}

private static String getParentPath(String path) {

    int separator = path.lastIndexOf('/');

    if (separator < 0) {
        return "";
    }

    return path.substring(0, separator);
}

private static String getLastPathComponent(String path) {

    int separator = path.lastIndexOf('/');

    return separator < 0
            ? path
            : path.substring(separator + 1);
}

private static boolean isDirectIlstItem(AtomInfo atom) {

    String parent = getParentPath(atom.path);

    return "ilst".equals(getLastPathComponent(parent));
}

private static boolean isTestMdtaItem(
        RandomAccessFile raf,
        AtomInfo atom,
        List<AtomInfo> atoms
) throws IOException {

    if (!isDirectIlstItem(atom)) {
        return false;
    }

    String metaPath = getParentPath(
            getParentPath(atom.path)
    );

    long index = getNumericFourCC(atom.type);

    if (index <= 0) {
        return false;
    }

    for (AtomInfo candidate : atoms) {

        if (!"keys".equals(candidate.type)) {
            continue;
        }

        if (!metaPath.equals(
                getParentPath(candidate.path))) {
            continue;
        }

        List<String> keys = readKeyTable(
                raf,
                candidate
        );

        if (index > keys.size()) {
            return false;
        }

        String key = keys.get((int) index - 1);

        return isPersonalMetadataKey(key);
    }

    return false;
}

private static boolean isDirectChildOf(
        AtomInfo atom,
        String parentType
) {

    String parent = getParentPath(atom.path);

    return parentType.equals(getLastPathComponent(parent));
}

private static long getNumericFourCC(String type) {

    byte[] bytes = type.getBytes(
            StandardCharsets.ISO_8859_1
    );

    if (bytes.length != 4) {
        return -1;
    }

    return ((bytes[0] & 0xFFL) << 24)
            | ((bytes[1] & 0xFFL) << 16)
            | ((bytes[2] & 0xFFL) << 8)
            | (bytes[3] & 0xFFL);
}

private static List<String> readKeyTable(
        RandomAccessFile raf,
        AtomInfo atom
) throws IOException {

    List<String> keys = new ArrayList<>();

    long payloadStart = atom.offset + atom.headerSize;
    long atomEnd = atom.offset + atom.size;

    if (payloadStart < atom.offset
            || atomEnd < payloadStart
            || atomEnd > raf.length()
            || atomEnd - payloadStart < 8) {

        throw new IOException("Tabela keys inválida.");
    }

    raf.seek(payloadStart);

    // FullBox: version e flags.
    int versionAndFlags = raf.readInt();

    if (versionAndFlags != 0) {
        throw new IOException(
                "Versão ou flags da tabela keys não suportadas."
        );
    }

    long entryCount = readUnsignedInt(raf);

    if (entryCount > 10000) {
        throw new IOException(
                "Quantidade excessiva de chaves."
        );
    }

    long position = payloadStart + 8;

    for (long i = 0; i < entryCount; i++) {

        if (position > atomEnd
                || atomEnd - position < 8) {

            throw new IOException(
                    "Entrada keys truncada."
            );
        }

        raf.seek(position);

        long entrySize = readUnsignedInt(raf);
        String namespace = readFourCC(raf);

        if (entrySize < 8
                || entrySize > atomEnd - position) {

            throw new IOException(
                    "Tamanho de chave inválido."
            );
        }

        long keyLength = entrySize - 8;

        if (keyLength > 1024) {
            throw new IOException(
                    "Nome de chave muito grande."
            );
        }

        byte[] keyBytes = new byte[(int) keyLength];

        raf.readFully(keyBytes);

        String keyName = namespace + ":"
                + new String(
                        keyBytes,
                        StandardCharsets.UTF_8
                );

        keys.add(keyName);

        position += entrySize;
    }

    if (position != atomEnd) {
        throw new IOException(
                "Tabela keys inconsistente."
        );
    }

    return keys;
}

/**
 * Lê uma string FullBox utilizada pelos átomos
 * mean e name de uma entrada freeform.
 *
 * Estrutura:
 * Version + Flags: 4 bytes
 * String UTF-8: restante do conteúdo
 */
private static String readFreeformString(
        RandomAccessFile raf,
        AtomInfo atom
) throws IOException {

    long payloadStart = atom.offset + atom.headerSize;
    long atomEnd = atom.offset + atom.size;

    if (payloadStart < atom.offset
            || atomEnd < payloadStart
            || atomEnd > raf.length()
            || atomEnd - payloadStart < 4) {

        throw new IOException(
                "Átomo freeform inválido: " + atom.type
        );
    }

    long length = atomEnd - payloadStart - 4;

    if (length > 4096) {
        throw new IOException(
                "Nome de metadado excessivamente grande."
        );
    }

    raf.seek(payloadStart);

    int versionAndFlags = raf.readInt();

    if (versionAndFlags != 0) {
        throw new IOException(
                "Versão ou flags freeform não suportadas."
        );
    }

    byte[] bytes = new byte[(int) length];

    raf.readFully(bytes);

    return new String(
            bytes,
            StandardCharsets.UTF_8
    ).replace("\u0000", "");
}

/**
 * Lê o cabeçalho do átomo data.
 *
 * Os primeiros quatro bytes contêm o tipo de dado
 * e os quatro seguintes representam o locale.
 *
 * O conteúdo real não é carregado na memória.
 */
private static int validateFreeformData(
        RandomAccessFile raf,
        AtomInfo atom
) throws IOException {

    long payloadStart = atom.offset + atom.headerSize;
    long atomEnd = atom.offset + atom.size;

    if (payloadStart < atom.offset
            || atomEnd < payloadStart
            || atomEnd > raf.length()
            || atomEnd - payloadStart < 8) {

        throw new IOException(
                "Átomo data inválido."
        );
    }

    raf.seek(payloadStart);

    int dataType = raf.readInt();

    // Locale: quatro bytes.
    raf.readInt();

    return dataType;
}

/**
 * Encontra um filho direto de um átomo,
 * respeitando os limites físicos do contêiner.
 */
private static AtomInfo findDirectChild(
        List<AtomInfo> atoms,
        AtomInfo parent,
        String childType
) {

    String expectedPath = parent.path + "/" + childType;

    long parentEnd = parent.offset + parent.size;

    for (AtomInfo child : atoms) {

        if (!expectedPath.equals(child.path)) {
            continue;
        }

        long childEnd = child.offset + child.size;

        if (child.offset >= parent.offset + parent.headerSize
                && childEnd <= parentEnd) {

            return child;
        }
    }

    return null;
}

/**
 * Analisa uma entrada personalizada ----.
 *
 * Não lê o conteúdo real do metadado.
 */
private static FreeformInfo readFreeformInfo(
        RandomAccessFile raf,
        List<AtomInfo> atoms,
        AtomInfo atom
) throws IOException {

    if (!"----".equals(atom.type)) {
        return null;
    }

    AtomInfo mean = findDirectChild(
            atoms,
            atom,
            "mean"
    );

    AtomInfo name = findDirectChild(
            atoms,
            atom,
            "name"
    );

    AtomInfo data = findDirectChild(
            atoms,
            atom,
            "data"
    );

    if (mean == null || name == null || data == null) {
        return null;
    }

    String namespace = readFreeformString(
            raf,
            mean
    );

    String fieldName = readFreeformString(
            raf,
            name
    );

    int dataType = validateFreeformData(
            raf,
            data
    );

    long dataSize = data.size - data.headerSize - 8;

    return new FreeformInfo(
            namespace,
            fieldName,
            dataType,
            dataSize
    );
}

private static boolean isPersonalMetadataKey(String key) {

    String normalized = key.toLowerCase(
            java.util.Locale.ROOT
    );

    return normalized.contains("location")
            || normalized.contains("gps")
            || normalized.contains("latitude")
            || normalized.contains("longitude")
            || normalized.contains("altitude")
            || normalized.contains("iso6709")
            || normalized.contains("creationdate")
            || normalized.contains("creation_time")
            || normalized.contains("modification_time")
            || normalized.contains("date")
            || normalized.contains("make")
            || normalized.contains("marketname")
            || normalized.contains("manufacturer")
            || normalized.contains("model")
            || normalized.contains("camera")
            || normalized.contains("device")
            || normalized.contains("serial")
            || normalized.contains("software")
            || normalized.contains("encoder")
            || normalized.contains("title")
            || normalized.contains("description")
            || normalized.contains("comment")
            || normalized.contains("author")
            || normalized.contains("artist")
            || normalized.contains("copyright")
            || normalized.contains("xmp")
            || normalized.contains("direction.facing")
            || normalized.contains("direction.motion");
}


private static boolean hasValidAtomHeaderAt(
        RandomAccessFile raf,
        long position,
        long end
) throws IOException {

    long savedPosition = raf.getFilePointer();

    try {

        if (position < 0 || end - position < 8) {
            return false;
        }

        raf.seek(position);

        long size32 = readUnsignedInt(raf);
        String type = readFourCC(raf);

        // Os identificadores de átomos devem ser imprimíveis.
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);

            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }

        long headerSize = 8;
        long atomSize;

        if (size32 == 1) {

            if (end - position < 16) {
                return false;
            }

            atomSize = raf.readLong();
            headerSize = 16;

            if (atomSize < 0) {
                return false;
            }

        } else if (size32 == 0) {

            atomSize = end - position;

        } else {

            atomSize = size32;
        }

        if ("uuid".equals(type)) {
            headerSize += 16;
        }

        return atomSize >= headerSize
                && atomSize <= end - position;

    } finally {

        raf.seek(savedPosition);
    }
}

    private static void parseAtoms(
            RandomAccessFile raf,
            long start,
            long end,
            int depth,
            String parentPath,
            List<AtomInfo> result
    ) throws IOException {

        if (depth > MAX_DEPTH) {
            throw new IOException(
                    "Profundidade excessiva de contêineres."
            );
        }

        if (start < 0
                || end < start
                || end > raf.length()) {
            throw new IOException(
                    "Limites inválidos de contêiner."
            );
        }

        long position = start;

        while (position < end) {

            long remaining = end - position;

            if (remaining < 8) {
                throw new IOException(
                        "Cabeçalho de átomo incompleto."
                );
            }

            raf.seek(position);

            long size32 = readUnsignedInt(raf);
            String type = readFourCC(raf);

            long headerSize = 8;
            long atomSize;

            if (size32 == 1) {

                if (remaining < 16) {
                    throw new IOException(
                            "Cabeçalho estendido incompleto."
                    );
                }

                atomSize = raf.readLong();
                headerSize = 16;

                if (atomSize < 0) {
                    throw new IOException(
                            "Tamanho de átomo não suportado."
                    );
                }

            } else if (size32 == 0) {

                atomSize = remaining;

            } else {

                atomSize = size32;
            }

            if ("uuid".equals(type)) {
                headerSize += 16;
            }

            if (atomSize < headerSize
        || atomSize > remaining) {

    throw new IOException(
        "Tipo: " + type
                + "\nPosição: " + position
                + "\nTamanho: " + atomSize
                + "\nRestante: " + remaining
                + "\nContêiner: " + parentPath
                + "\nCabeçalho: " + headerSize
);
}

            long atomEnd = position + atomSize;

            if (atomEnd < position || atomEnd > end) {
                throw new IOException(
                        "Limite de átomo inválido."
                );
            }

            String path = parentPath.isEmpty()
                    ? type
                    : parentPath + "/" + type;

            if (result.size() >= MAX_ATOMS) {
                throw new IOException(
                        "Quantidade excessiva de átomos."
                );
            }

            result.add(new AtomInfo(
                    type,
                    path,
                    position,
                    atomSize,
                    headerSize
            ));

            boolean isIlstItem =
        "ilst".equals(getLastPathComponent(parentPath))
                && !"free".equals(type);

if (CONTAINERS.contains(type) || isIlstItem) {

    long childStart = position + headerSize;

    /*
     * meta é um FullBox.
     * Seus primeiros quatro bytes são version e flags.
     */
    if ("meta".equals(type)) {
        if (!hasValidAtomHeaderAt(
            raf,
            childStart,
            atomEnd
    )) {
        childStart += 4;
    }
    }

    if (childStart > atomEnd) {
        throw new IOException(
                "Cabeçalho interno inválido: " + type
        );
    }

    if (childStart < atomEnd) {

        parseAtoms(
                raf,
                childStart,
                atomEnd,
                depth + 1,
                path,
                result
        );
    }
}

            position = atomEnd;
        }
    }

    /**
 * Remove metadados pessoais de um arquivo MP4/M4V/3GP,
 * preservando os streams de áudio/vídeo e demais estruturas.
 *
 * A saída é escrita em um arquivo temporário.
 *
 */
public static boolean clean(
        Context context,
        File input,
        File output
) throws IOException {

    if (input == null || !input.isFile()) {
        throw new IOException(
                "Arquivo de entrada inválido."
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

    if (input.length() > MAX_OUTPUT_SIZE) {
        throw new IOException(
                "Arquivo excede o limite de tamanho suportado."
        );
    }

    VideoFormat format = detectFormat(input);

    File parent = output.getAbsoluteFile().getParentFile();

    if (parent == null) {
        throw new IOException(
                "Diretório de saída inválido."
        );
    }

    if (!parent.exists() && !parent.mkdirs()) {
        throw new IOException(
                "Não foi possível criar o diretório de saída."
        );
    }

    if (!parent.isDirectory() || !parent.canWrite()) {
        throw new IOException(
                "Diretório de saída não permite gravação."
        );
    }

    File temp = File.createTempFile(
            ".sfe-video-",
            ".tmp",
            parent
    );

    boolean success = false;

    try (
            RandomAccessFile raf =
                    new RandomAccessFile(input, "r")
    ) {

        List<AtomInfo> atoms = inspect(raf);

        List<AtomInfo> removable =
                new ArrayList<>();
        List<AtomInfo> removableMdta =
                new ArrayList<>();

        List<MetadataInfo> timestamps =
                new ArrayList<>();

        for (AtomInfo atom : atoms) {

            if (isTestMdtaItem(
        raf,
        atom,
        atoms
)) {

    removableMdta.add(atom);

} else if (shouldRemoveAtom(
        context,
        raf,
        atom,
        format,
        atoms
)) {

    removable.add(atom);
}

            inspectHeaderTimestamps(
                    raf,
                    atom,
                    timestamps
            );
        }

        // Primeiro copiamos o arquivo integralmente.
        try (
                BufferedOutputStream out =
                        new BufferedOutputStream(
                                new FileOutputStream(temp),
                                COPY_BUFFER_SIZE
                        )
        ) {

            copyRegion(
                    raf,
                    out,
                    0,
                    raf.length()
            );

            out.flush();
        }

        if (temp.length() != input.length()) {
            throw new IOException(
                    "Falha ao copiar o arquivo original."
            );
        }

        // Depois alteramos somente a cópia temporária.
        try (
                RandomAccessFile cleaned =
                        new RandomAccessFile(temp, "rw")
        ) {

            // Substitui os metadados tradicionais por free.
for (AtomInfo atom : removable) {

    replaceAtomWithFree(
            cleaned,
            atom
    );
}

            // Limpa o conteúdo do item mdta de teste.
for (AtomInfo atom : removableMdta) {

    clearMdtaAtom(
            cleaned,
            atom,
            atoms
    );
}

            // Zera os timestamps de criação e modificação.
            for (MetadataInfo timestamp : timestamps) {

    clearTimestamp(
            cleaned,
            timestamp
    );
}
        }

        if (temp.length() != input.length()) {
            throw new IOException(
                    "O tamanho do arquivo foi alterado inesperadamente."
            );
        }

            verifyCleanedFile(
                context,
                temp,
                format,
                removable,
                removableMdta,
                timestamps,
                input.length()
            );

        verifyMediaDataUnchanged(
        input,
        temp,
        atoms
);

        replaceOutput(
                temp,
                output
        );

        success = true;
        return true;

    } finally {

        if (!success && temp.exists()) {
            temp.delete();
        }
    }
}

    private static void replaceAtomWithFree(
        RandomAccessFile raf,
        AtomInfo atom
) throws IOException {

    if (atom.size < 8) {
        throw new IOException(
                "Átomo pequeno demais para substituição."
        );
    }

    long atomEnd = atom.offset + atom.size;

    if (atom.offset < 0
            || atomEnd < atom.offset
            || atomEnd > raf.length()) {

        throw new IOException(
                "Limites inválidos do átomo."
        );
    }

    raf.seek(atom.offset);

    long newHeaderSize;

    if (atom.size <= 0xFFFFFFFFL) {

        raf.writeInt((int) atom.size);
        raf.writeBytes("free");

        newHeaderSize = 8;

    } else {

        raf.writeInt(1);
        raf.writeBytes("free");
        raf.writeLong(atom.size);

        newHeaderSize = 16;
    }

    // Limpa completamente o conteúdo antigo.
    byte[] zeros = new byte[COPY_BUFFER_SIZE];

    long remaining = atom.size - newHeaderSize;

    while (remaining > 0) {

        int count = (int) Math.min(
                zeros.length,
                remaining
        );

        raf.write(
                zeros,
                0,
                count
        );

        remaining -= count;
    }
}

private static void clearMdtaAtom(
        RandomAccessFile raf,
        AtomInfo atom,
        List<AtomInfo> atoms
) throws IOException {

    if (!isDirectIlstItem(atom)) {
        throw new IOException(
                "Item mdta inválido."
        );
    }

    long atomEnd = atom.offset + atom.size;

    if (atom.offset < 0
            || atom.size < atom.headerSize
            || atomEnd < atom.offset
            || atomEnd > raf.length()) {

        throw new IOException(
                "Limites inválidos do item mdta."
        );
    }

    String dataPath = atom.path + "/data";

    List<AtomInfo> dataAtoms = new ArrayList<>();

    for (AtomInfo candidate : atoms) {

        if (!dataPath.equals(candidate.path)) {
            continue;
        }

        long dataEnd =
                candidate.offset + candidate.size;

        if (candidate.offset < atom.offset + atom.headerSize
                || dataEnd < candidate.offset
                || dataEnd > atomEnd
                || candidate.size < candidate.headerSize + 8) {

            throw new IOException(
                    "Átomo data inválido."
            );
        }

        dataAtoms.add(candidate);
    }

    if (dataAtoms.isEmpty()) {
        throw new IOException(
                "Item mdta sem átomo data."
        );
    }

    byte[] zeros = new byte[COPY_BUFFER_SIZE];

    for (AtomInfo data : dataAtoms) {

        long contentStart =
                data.offset + data.headerSize + 8;

        long remaining =
                data.offset + data.size - contentStart;

        raf.seek(contentStart);

        while (remaining > 0) {

            int count = (int) Math.min(
                    zeros.length,
                    remaining
            );

            raf.write(zeros, 0, count);

            remaining -= count;
        }
    }
}

private static void verifyMdtaAtomCleared(
        RandomAccessFile raf,
        AtomInfo atom,
        List<AtomInfo> atoms
) throws IOException {

    String dataPath = atom.path + "/data";

    boolean foundData = false;

    byte[] buffer = new byte[COPY_BUFFER_SIZE];

    long atomEnd = atom.offset + atom.size;

    for (AtomInfo candidate : atoms) {

        if (!dataPath.equals(candidate.path)) {
            continue;
        }

        foundData = true;

        long dataEnd =
                candidate.offset + candidate.size;

        if (candidate.offset < atom.offset + atom.headerSize
                || dataEnd < candidate.offset
                || dataEnd > atomEnd
                || candidate.size < candidate.headerSize + 8) {

            throw new IOException(
                    "Átomo data inválido durante verificação."
            );
        }

        long position =
                candidate.offset + candidate.headerSize + 8;

        long remaining = dataEnd - position;

        raf.seek(position);

        while (remaining > 0) {

            int count = (int) Math.min(
                    buffer.length,
                    remaining
            );

            raf.readFully(buffer, 0, count);

            for (int i = 0; i < count; i++) {

                if (buffer[i] != 0) {
                    throw new IOException(
                            "Falha ao limpar valor mdta: "
                                    + atom.type
                    );
                }
            }

            remaining -= count;
        }
    }

    if (!foundData) {
        throw new IOException(
                "Item mdta sem átomo data durante verificação."
        );
    }
}
    
private static void clearTimestamp(
        RandomAccessFile raf,
        MetadataInfo timestamp
) throws IOException {

    long end;

    try {
        end = Math.addExact(
                timestamp.offset,
                timestamp.size
        );
    } catch (ArithmeticException e) {
        throw new IOException(
                "Overflow no timestamp: " + timestamp.path,
                e
        );
    }

    if (timestamp.size != 4 && timestamp.size != 8) {
        throw new IOException(
                "Tamanho inválido: " + timestamp.path
                        + " | size=" + timestamp.size
        );
    }

    if (timestamp.offset < 0
            || end > raf.length()) {

        throw new IOException(
                "Posição inválida: " + timestamp.path
                        + " | offset=" + timestamp.offset
                        + " | size=" + timestamp.size
                        + " | arquivo=" + raf.length()
        );
    }

    raf.seek(timestamp.offset);

    for (int i = 0; i < timestamp.size; i++) {
        raf.writeByte(0);
    }
}

    private static void verifyMediaDataUnchanged(
        File original,
        File cleaned,
        List<AtomInfo> originalAtoms
) throws IOException {

    byte[] originalBuffer = new byte[COPY_BUFFER_SIZE];
    byte[] cleanedBuffer = new byte[COPY_BUFFER_SIZE];

    boolean foundMediaData = false;

    try (
            RandomAccessFile source =
                    new RandomAccessFile(original, "r");

            RandomAccessFile target =
                    new RandomAccessFile(cleaned, "r")
    ) {

        for (AtomInfo atom : originalAtoms) {

            if (!"mdat".equals(atom.type)) {
                continue;
            }

            foundMediaData = true;

            if (atom.offset < 0
                    || atom.size < atom.headerSize
                    || atom.offset + atom.size > source.length()
                    || atom.offset + atom.size > target.length()) {

                throw new IOException(
                        "Bloco de mídia inválido."
                );
            }

            source.seek(atom.offset);
            target.seek(atom.offset);

            long remaining = atom.size;

            while (remaining > 0) {

                int count = (int) Math.min(
                        originalBuffer.length,
                        remaining
                );

                source.readFully(
                        originalBuffer,
                        0,
                        count
                );

                target.readFully(
                        cleanedBuffer,
                        0,
                        count
                );

                for (int i = 0; i < count; i++) {

                    if (originalBuffer[i] != cleanedBuffer[i]) {

                        throw new IOException(
                                "Os dados de áudio ou vídeo foram alterados."
                        );
                    }
                }

                remaining -= count;
            }
        }
    }

    if (!foundMediaData) {
        throw new IOException(
                "Nenhum bloco de mídia encontrado."
        );
    }
}

    private static void verifyCleanedFile(
        Context context,
        File file,
        VideoFormat format,
        List<AtomInfo> removable,
        List<AtomInfo> removableMdta,
        List<MetadataInfo> timestamps,
        long originalLength
) throws IOException {

    if (!file.isFile() || file.length() != originalLength) {
        throw new IOException(
                "O tamanho do arquivo foi alterado."
        );
    }

    try (RandomAccessFile raf =
                 new RandomAccessFile(file, "r")) {

        // Confirma que cada metadado foi substituído por free.
        for (AtomInfo atom : removable) {

            raf.seek(atom.offset);

            long size32 = readUnsignedInt(raf);
            String type = readFourCC(raf);

            if (!"free".equals(type)) {
                throw new IOException(
                        "Falha ao remover metadado: " + atom.type
                );
            }

            long actualSize;

            if (size32 == 1) {
                actualSize = raf.readLong();
            } else {
                actualSize = size32;
            }

            if (actualSize != atom.size) {
                throw new IOException(
                        "Tamanho de caixa alterado inesperadamente."
                );
            }
        }

        // Confirma que os valores mdta foram zerados.
for (AtomInfo atom : removableMdta) {

    verifyMdtaAtomCleared(
            raf,
            atom,
            inspect(raf)
    );
}
        
        // Confirma que todos os timestamps foram zerados.
for (MetadataInfo timestamp : timestamps) {

    raf.seek(timestamp.offset);

    for (int i = 0; i < timestamp.size; i++) {

        if (raf.readUnsignedByte() != 0) {
            throw new IOException(
                    "Falha ao limpar timestamp: "
                            + timestamp.type
            );
        }
    }
}

        // Reanalisa o arquivo depois da limpeza.
List<AtomInfo> cleanedAtoms = inspect(raf);

// Procura metadados pessoais que ainda deveriam ter sido removidos.
for (AtomInfo atom : cleanedAtoms) {

    if (shouldRemoveAtom(
        context,
        raf,
        atom,
        format,
        cleanedAtoms
) && !isTestMdtaItem(
        raf,
        atom,
        cleanedAtoms
    )) {

        throw new IOException(
                "Ainda existem metadados que deveriam ter sido removidos: "
                        + atom.type
        );
    }
    }
    }
}

    private static long readUnsignedInt(RandomAccessFile raf)
            throws IOException {

        return raf.readInt() & 0xFFFFFFFFL;
    }

    private static void copyRegion(
        RandomAccessFile raf,
        BufferedOutputStream out,
        long offset,
        long length
) throws IOException {

    if (offset < 0 || length < 0) {
        throw new IOException(
                "Região inválida."
        );
    }

    long fileLength = raf.length();

    if (offset > fileLength
            || length > fileLength - offset) {

        throw new IOException(
                "Região fora dos limites do arquivo."
        );
    }

    raf.seek(offset);

    byte[] buffer = new byte[COPY_BUFFER_SIZE];

    long remaining = length;

    while (remaining > 0) {

        int wanted = (int) Math.min(
                buffer.length,
                remaining
        );

        int read = raf.read(
                buffer,
                0,
                wanted
        );

        if (read < 0) {
            throw new IOException(
                    "Fim inesperado do arquivo."
            );
        }

        if (read == 0) {
            continue;
        }

        out.write(buffer, 0, read);

        remaining -= read;
    }
}

    private static void replaceOutput(
        File temp,
        File output
) throws IOException {

    if (!temp.isFile()) {
        throw new IOException(
                "Arquivo temporário inexistente."
        );
    }

    if (temp.length() == 0) {
        throw new IOException(
                "Arquivo temporário vazio."
        );
    }

    File backup = new File(
            output.getAbsolutePath() + ".sfe-backup"
    );

    if (backup.exists()) {

        if (!backup.delete()) {
            throw new IOException(
                    "Não foi possível remover backup anterior."
            );
        }
    }

    boolean hadOutput = output.exists();

    if (hadOutput) {

        if (!output.renameTo(backup)) {

            throw new IOException(
                    "Não foi possível preservar o arquivo de saída original."
            );
        }
    }

    boolean moved = temp.renameTo(output);

    if (!moved) {

        if (hadOutput && backup.exists()) {
            backup.renameTo(output);
        }

        throw new IOException(
                "Não foi possível criar o arquivo de saída."
        );
    }

    if (backup.exists()) {

        if (!backup.delete()) {

            /*
             * A saída nova já está válida.
             * Não destruímos a saída apenas porque
             * a limpeza do backup falhou.
             */
        }
    }
}

    private static String readFourCC(RandomAccessFile raf)
            throws IOException {

        byte[] bytes = new byte[4];
        raf.readFully(bytes);

        return new String(
                bytes,
                StandardCharsets.ISO_8859_1
        );
    }

    private static void validateFile(File file)
            throws IOException {

        if (file == null
                || !file.isFile()
                || !file.canRead()) {
            throw new IOException(
                    "Arquivo de vídeo inválido ou inacessível."
            );
        }

        if (file.length() < 8) {
            throw new IOException(
                    "Arquivo de vídeo muito pequeno."
            );
        }
    }

    
    private static void saveDiagnosticReport(
        Context context,
        String report
) throws IOException {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw new IOException(
                "O salvamento automático exige Android 10 ou superior."
        );
    }

    ContentValues values = new ContentValues();

    String fileName = "diagnostico_mdta_"
            + System.currentTimeMillis()
            + ".txt";

    values.put(
            MediaStore.Downloads.DISPLAY_NAME,
            fileName
    );

    values.put(
            MediaStore.Downloads.MIME_TYPE,
            "text/plain"
    );

    values.put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS
    );

    Uri uri = context.getContentResolver().insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
    );

    if (uri == null) {
        throw new IOException(
                "Não foi possível criar o relatório TXT."
        );
    }

    try (OutputStream output =
                 context.getContentResolver().openOutputStream(uri)) {

        if (output == null) {
            throw new IOException(
                    "Não foi possível abrir o relatório TXT."
            );
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(output, "UTF-8"))) {

            writer.write(report);
        }
    }
}


private static boolean shouldRemoveAtom(
        Context context,
        RandomAccessFile raf,
        AtomInfo atom,
        VideoFormat format,
        List<AtomInfo> atoms
) throws IOException {

    String type = atom.type;

    // TESTE 1: metadados tradicionais de ilst.
    if (METADATA_ATOMS.contains(type)
            && isDirectIlstItem(atom)) {
        return true;
    }

    // TESTE 2: metadados 3GPP.
    if (format == VideoFormat.THREE_GP
            && THREE_GP_METADATA_ATOMS.contains(type)
            && isDirectChildOf(atom, "udta")) {
        return true;
    }

    // TESTE 3: localização 3GPP.
    if ("loci".equals(type)
            && isDirectChildOf(atom, "udta")) {
        return true;
    }

    // TESTE 4: XMP UUID.
    if ("uuid".equals(type)
            && isXmpUuid(raf, atom)) {
        return true;
    }

    // TESTE 5: QuickTime freeform.
    if ("----".equals(type)
            && isDirectIlstItem(atom)) {

        FreeformInfo info = readFreeformInfo(
                raf,
                atoms,
                atom
        );

        return info != null
                && isPersonalMetadataKey(
                        "freeform:"
                                + info.namespace
                                + ":"
                                + info.name
                );
    }

    // TESTE: remover somente uma chave mdta específica.
if (isDirectIlstItem(atom)) {

    String metaPath = getParentPath(
            getParentPath(atom.path)
    );

    long index = getNumericFourCC(type);

    if (index > 0) {

        for (AtomInfo candidate : atoms) {

            if (!"keys".equals(candidate.type)) {
                continue;
            }

            if (!metaPath.equals(
                    getParentPath(candidate.path)
            )) {
                continue;
            }

            List<String> keys = readKeyTable(
                    raf,
                    candidate
            );

            if (index > keys.size()) {
                continue;
            }

            String key = keys.get(
                    (int) index - 1
            );

            return isPersonalMetadataKey(key);
        }
    }
}

    return false;
}

}
