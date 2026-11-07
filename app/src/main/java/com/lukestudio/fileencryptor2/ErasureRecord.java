package com.lukestudio.fileencryptor2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

/**
 * Registro imutável de uma tentativa de exclusão segura.
 * Os campos são públicos e finais para manter o
 * estilo enxuto do projeto.
 */
class ErasureRecord {

    final String fileName;
    final long sizeBytes;
    final long startedAtMillis;
    final long finishedAtMillis;

    /** Descrição do método aplicado. */
    final String method;

    /** "SUCESSO" ou "FALHA". */
    final String status;

    /** Mensagem de erro, ou "" quando não houver. */
    final String errorMessage;

    ErasureRecord(
            String fileName,
            long sizeBytes,
            long startedAtMillis,
            long finishedAtMillis,
            String method,
            String status,
            String errorMessage
    ) {
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.startedAtMillis = startedAtMillis;
        this.finishedAtMillis = finishedAtMillis;
        this.method = method;
        this.status = status;
        this.errorMessage = errorMessage;
    }
}

/**
 * Registro e emissão de relatórios de exclusão
 * segura. O log é um arquivo JSON Lines no
 * armazenamento privado do app; a exportação
 * (texto ou PDF) é feita sob demanda.
 */
final class ErasureReport {

    private static final String LOG_FILE =
            "erasure_report.jsonl";

    private static final String METHOD_DESCRIPTION =
            "Sobrescrita única com dados aleatórios";

    private ErasureReport() {
    }

    /**
     * Anexa o registro ao log JSONL. O append por
     * linha garante que um crash no meio da escrita
     * não corrompa os registros anteriores.
     */
    static void append(
            Context context,
            ErasureRecord record
    ) throws IOException {

        File log = new File(
                context.getFilesDir(),
                LOG_FILE
        );

        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(log, true)
                     )) {

            writer.write(toJsonLine(record));
            writer.newLine();
        }
    }

    /**
     * Lê todos os registros do log. Linhas
     * incompletas ou inválidas (crash antigo) são
     * ignoradas silenciosamente.
     */
    static List<ErasureRecord> readAll(Context context)
            throws IOException {

        List<ErasureRecord> records =
                new ArrayList<ErasureRecord>();

        File log = new File(
                context.getFilesDir(),
                LOG_FILE
        );

        if (!log.isFile()) {
            return records;
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(log),
                                     "UTF-8"
                             )
                     )) {

            String line;

            while ((line = reader.readLine()) != null) {

                ErasureRecord record =
                        fromJsonLine(line);

                if (record != null) {
                    records.add(record);
                }
            }
        }

        return records;
    }

    /** Apaga todo o histórico de relatórios. */
    static void clear(Context context) {
        new File(context.getFilesDir(), LOG_FILE).delete();
    }

    /**
     * Exporta um relatório em texto simples para o
     * arquivo indicado (geralmente em cache, para
     * compartilhamento via FileProvider).
     */
    static void exportText(
            Context context,
            File outFile
    ) throws IOException {

        List<ErasureRecord> records =
                readAll(context);

        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(outFile)
                     )) {

            writer.write("RELATORIO DE EXCLUSAO SEGURA");
            writer.newLine();

            writer.write("S-File Encryptor - "
                    + formatDateTime(System.currentTimeMillis()));
            writer.newLine();
            writer.newLine();

            writer.write("Total de registros: "
                    + records.size());
            writer.newLine();

            writer.write("Metodo: " + METHOD_DESCRIPTION);
            writer.newLine();

            writer.write("Limitacao conhecida: sistemas de "
                    + "arquivos com journaling (ext4/f2fs) "
                    + "podem reter residuos fora do controle "
                    + "do aplicativo.");
            writer.newLine();

            writer.write("----------------------------------------");
            writer.newLine();

            SimpleDateFormat fmt =
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.US
                    );

            for (ErasureRecord r : records) {

                writer.write("Arquivo:   " + r.fileName);
                writer.newLine();

                writer.write("Tamanho:   "
                        + r.sizeBytes + " bytes");
                writer.newLine();

                writer.write("Inicio:    "
                        + fmt.format(new Date(r.startedAtMillis)));
                writer.newLine();

                writer.write("Termino:   "
                        + fmt.format(new Date(r.finishedAtMillis)));
                writer.newLine();

                writer.write("Metodo:    " + r.method);
                writer.newLine();

                writer.write("Status:    " + r.status);
                writer.newLine();

                if (!r.errorMessage.isEmpty()) {
                    writer.write("Erro:      " + r.errorMessage);
                    writer.newLine();
                }

                writer.write("----------------------------------------");
                writer.newLine();
            }
        }
    }

    /**
     * Exporta o relatório em PDF (A4), paginando os
     * registros. Usa o PdfDocument da própria
     * plataforma; nenhuma biblioteca adicional.
     */
    static void exportPdf(
            Context context,
            File outFile
    ) throws IOException {

        List<ErasureRecord> records =
                readAll(context);

        SimpleDateFormat fmt =
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                );

        PdfDocument document = new PdfDocument();

        Paint titlePaint = new Paint();
        titlePaint.setTextSize(14);
        titlePaint.setFakeBoldText(true);

        Paint textPaint = new Paint();
        textPaint.setTextSize(10);

        Paint smallPaint = new Paint();
        smallPaint.setTextSize(8);

        // A4: 595 x 842 pontos.
        final int pageWidth = 595;
        final int pageHeight = 842;

        // Margens.
        final int margin = 40;

        // Altura de cada linha desenhada.
        final int lineHeight = 13;

        int pageIndex = 0;

        PdfDocument.Page page = null;
        Canvas canvas = null;

        // Cursor vertical da página atual.
        int y = 0;

        for (ErasureRecord r : records) {

            int recordLines = r.errorMessage.isEmpty()
                    ? 5 : 6;

            int needed =
                    recordLines * lineHeight;

            // Quebra de página.
            if (page == null
                    || y + needed
                    > pageHeight - margin) {

                if (page != null) {
                    document.finishPage(page);
                }

                pageIndex++;

                PdfDocument.PageInfo info =
                        new PdfDocument.PageInfo.Builder(
                                pageWidth,
                                pageHeight,
                                pageIndex
                        ).create();

                page = document.startPage(info);
                canvas = page.getCanvas();

                y = margin;

                canvas.drawText(
                        "Relatorio de Exclusao Segura",
                        margin,
                        y,
                        titlePaint
                );

                y += lineHeight;

                canvas.drawText(
                        "S-File Encryptor - "
                                + fmt.format(new Date()),
                        margin,
                        y,
                        textPaint
                );

                y += lineHeight;

                canvas.drawText(
                        "Metodo: " + METHOD_DESCRIPTION,
                        margin,
                        y,
                        textPaint
                );

                y += lineHeight;

                canvas.drawText(
                        "Registros: " + records.size()
                                + "  |  Pagina " + pageIndex,
                        margin,
                        y,
                        smallPaint
                );

                y += lineHeight;

                canvas.drawText(
                        "Nota: journaling do sistema de "
                                + "arquivos pode reter residuos.",
                        margin,
                        y,
                        smallPaint
                );

                y += lineHeight;
            }

            // Desenha o registro.
            canvas.drawText(
                    truncate("Arquivo: " + r.fileName, 88),
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    "Tamanho: " + r.sizeBytes
                            + " bytes  |  Status: " + r.status,
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    "Inicio: "
                            + fmt.format(new Date(r.startedAtMillis))
                            + "   Termino: "
                            + fmt.format(new Date(r.finishedAtMillis)),
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    truncate("Metodo: " + r.method, 88),
                    margin,
                    y,
                    smallPaint
            );
            y += lineHeight;

            if (!r.errorMessage.isEmpty()) {

                canvas.drawText(
                        truncate("Erro: " + r.errorMessage, 88),
                        margin,
                        y,
                        smallPaint
                );
                y += lineHeight;
            }

            // Linha divisória entre registros.
            canvas.drawLine(
                    margin,
                    y,
                    pageWidth - margin,
                    y,
                    smallPaint
            );

            y += lineHeight;
        }

        if (page != null) {
            document.finishPage(page);
        }

        OutputStream out = null;

        try {
            out = new FileOutputStream(outFile);
            document.writeTo(out);

        } finally {

            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }

            document.close();
        }
    }

    // -------------------------------------------------
    // Serialização JSONL manual (sem dependências).
    // -------------------------------------------------

    private static String toJsonLine(ErasureRecord r) {

        StringBuilder sb = new StringBuilder();

        sb.append("{\"v\":1");
        sb.append(",\"fileName\":\"")
                .append(jsonEscape(r.fileName)).append('"');
        sb.append(",\"size\":").append(r.sizeBytes);
        sb.append(",\"startedAt\":").append(r.startedAtMillis);
        sb.append(",\"finishedAt\":").append(r.finishedAtMillis);
        sb.append(",\"method\":\"")
                .append(jsonEscape(r.method)).append('"');
        sb.append(",\"status\":\"")
                .append(jsonEscape(r.status)).append('"');
        sb.append(",\"error\":\"")
                .append(jsonEscape(r.errorMessage)).append('"');
        sb.append('}');

        return sb.toString();
    }

    /**
     * Parser tolerante: extrai apenas os campos
     * conhecidos. Retorna null para linhas inválidas.
     */
    private static ErasureRecord fromJsonLine(String line) {

        try {

            if (line == null
                    || !line.startsWith("{")
                    || !line.endsWith("}")) {
                return null;
            }

            String fileName =
                    extractString(line, "fileName");

            if (fileName == null) {
                return null;
            }

            Long size = extractLong(line, "size");
            Long startedAt = extractLong(line, "startedAt");
            Long finishedAt = extractLong(line, "finishedAt");

            String status =
                    extractString(line, "status");

            if (size == null || startedAt == null
                    || finishedAt == null
                    || status == null) {
                return null;
            }

            String method =
                    extractString(line, "method");

            String error =
                    extractString(line, "error");

            return new ErasureRecord(
                    jsonUnescape(fileName),
                    size,
                    startedAt,
                    finishedAt,
                    method == null
                            ? METHOD_DESCRIPTION
                            : jsonUnescape(method),
                    jsonUnescape(status),
                    error == null ? "" : jsonUnescape(error)
            );

        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extractString(
            String json,
            String key
    ) {

        String needle = "\"" + key + "\":\"";

        int start = json.indexOf(needle);

        if (start < 0) {
            return null;
        }

        start += needle.length();

        StringBuilder sb = new StringBuilder();

        for (int i = start; i < json.length(); i++) {

            char c = json.charAt(i);

            if (c == '\\') {

                if (i + 1 >= json.length()) {
                    return null;
                }

                sb.append(c);
                sb.append(json.charAt(i + 1));
                i++;
                continue;
            }

            if (c == '"') {
                return sb.toString();
            }

            sb.append(c);
        }

        return null;
    }

    private static Long extractLong(
            String json,
            String key
    ) {

        String needle = "\"" + key + "\":";

        int start = json.indexOf(needle);

        if (start < 0) {
            return null;
        }

        start += needle.length();

        int end = start;

        while (end < json.length()) {

            char c = json.charAt(end);

            if (c < '0' || c > '9') {
                break;
            }

            end++;
        }

        if (end == start) {
            return null;
        }

        return Long.parseLong(
                json.substring(start, end)
        );
    }

    private static String jsonEscape(String value) {

        if (value == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);

            } else if (c == '\n') {
                sb.append("\\n");

            } else if (c == '\r') {
                sb.append("\\r");

            } else if (c == '\t') {
                sb.append("\\t");

            } else if (c < 0x20) {
                sb.append(String.format(
                        "\\u%04x", (int) c
                ));

            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private static String jsonUnescape(String value) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '\\' && i + 1 < value.length()) {

                char next = value.charAt(i + 1);

                switch (next) {
                    case 'n':
                        sb.append('\n');
                        break;

                    case 'r':
                        sb.append('\r');
                        break;

                    case 't':
                        sb.append('\t');
                        break;

                    case 'u':
                        if (i + 5 < value.length()) {
                            sb.append((char) Integer.parseInt(
                                    value.substring(
                                            i + 2,
                                            i + 6
                                    ),
                                    16
                            ));
                            i += 4;
                        }
                        break;

                    default:
                        sb.append(next);
                }

                i++;
                continue;
            }

            sb.append(c);
        }

        return sb.toString();
    }

    private static String truncate(
            String value,
            int max
    ) {

        if (value.length() <= max) {
            return value;
        }

        return value.substring(0, max - 3) + "...";
    }

    private static String formatDateTime(long millis) {

        return new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
        ).format(new Date(millis));
    }

    static void exportPdf(
        Context context,
        OutputStream output,
        ErasureRecord record
) throws IOException {

    PdfDocument document = new PdfDocument();

    Bitmap iconBitmap = null;

    try {

        final int pageWidth = 595;
        final int pageHeight = 842;

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(20);
        titlePaint.setFakeBoldText(true);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(12);

        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setTextSize(9);

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(1);

        PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(
                        pageWidth,
                        pageHeight,
                        1
                ).create();

        PdfDocument.Page page =
                document.startPage(pageInfo);

        Canvas canvas = page.getCanvas();

        // Ícone original do aplicativo.
        Drawable icon = context.getDrawable(
                R.drawable.app_icon
        );

        if (icon != null) {

            iconBitmap = Bitmap.createBitmap(
                    72,
                    72,
                    Bitmap.Config.ARGB_8888
            );

            Canvas iconCanvas = new Canvas(iconBitmap);

            icon.setBounds(
                    0,
                    0,
                    72,
                    72
            );

            icon.draw(iconCanvas);

            canvas.drawBitmap(
                    iconBitmap,
                    40,
                    45,
                    null
            );
        }

        // Título.
        canvas.drawText(
                "Relatório de Exclusão",
                125,
                85,
                titlePaint
        );

        canvas.drawLine(
                40,
                135,
                555,
                135,
                linePaint
        );

        SimpleDateFormat fmt =
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                );

        int y = 190;
        final int lineHeight = 32;

        canvas.drawText(
                "Arquivo: " + truncate(record.fileName, 65),
                40,
                y,
                textPaint
        );

        y += lineHeight;

        canvas.drawText(
                "Tamanho: " + record.sizeBytes + " bytes",
                40,
                y,
                textPaint
        );

        y += lineHeight;

        canvas.drawText(
                "Início: "
                        + fmt.format(
                                new Date(record.startedAtMillis)
                        ),
                40,
                y,
                textPaint
        );

        y += lineHeight;

        canvas.drawText(
                "Término: "
                        + fmt.format(
                                new Date(record.finishedAtMillis)
                        ),
                40,
                y,
                textPaint
        );

        y += lineHeight;

        canvas.drawText(
                "Método: "
                        + truncate(record.method, 65),
                40,
                y,
                textPaint
        );

        y += lineHeight;

        canvas.drawText(
                "Status: " + record.status,
                40,
                y,
                textPaint
        );

        if (!record.errorMessage.isEmpty()) {

            y += lineHeight;

            canvas.drawText(
                    "Erro: "
                            + truncate(record.errorMessage, 65),
                    40,
                    y,
                    textPaint
            );
        }

        canvas.drawLine(
                40,
                760,
                555,
                760,
                linePaint
        );

        canvas.drawText(
                "S-File Encryptor",
                40,
                785,
                footerPaint
        );

        canvas.drawText(
                "Relatório de operação de exclusão",
                40,
                800,
                footerPaint
        );

        document.finishPage(page);

        document.writeTo(output);

    } finally {

        document.close();

        if (iconBitmap != null) {
            iconBitmap.recycle();
        }
    }
}

}
