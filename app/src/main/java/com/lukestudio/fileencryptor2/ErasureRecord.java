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

    final String reportId;

    final String fileName;
    final long sizeBytes;

    final long startedAtMillis;
    final long finishedAtMillis;

    final String method;
    final String status;
    final String errorMessage;

    final String manufacturer;
    final String deviceModel;
    final String androidVersion;
    final int androidApi;
    final String buildId;
    final String appVersion;

    final int cycles;
    final long bytesOverwritten;

    final String overwriteStatus;
    final String deleteStatus;
    final String operationType;

    final int filesFound;
    final int filesDeleted;
    final int filesFailed;

        /**
     * Redacts the base file name, keeping only the extension
     * (e.g. "photo.jpg" -> "****.jpg"). Names without a valid
     * extension are reduced to "****". Applied at construction
     * time so the full name is never stored in reports or logs.
     */
    private static String redactFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "***";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "****";
        }
        return "****" + fileName.substring(dot);
    }

    // Construtor antigo: mantém compatibilidade
    // com os registros e chamadas existentes.
    ErasureRecord(
            String fileName,
            long sizeBytes,
            long startedAtMillis,
            long finishedAtMillis,
            String method,
            String status,
            String errorMessage
    ) {

        this(
                java.util.UUID.randomUUID().toString(),
                fileName,
                sizeBytes,
                startedAtMillis,
                finishedAtMillis,
                method,
                status,
                errorMessage,
                "Not recorded",
                "Not recorded",
                "Not recorded",
                0,
                "Not recorded",
                "Not recorded",
                1,
                0,
                "Not recorded",
                "Not recorded"
        );
    }

    // Construtor completo.
    ErasureRecord(
            String reportId,
            String fileName,
            long sizeBytes,
            long startedAtMillis,
            long finishedAtMillis,
            String method,
            String status,
            String errorMessage,
            String manufacturer,
            String deviceModel,
            String androidVersion,
            int androidApi,
            String buildId,
            String appVersion,
            int cycles,
            long bytesOverwritten,
            String overwriteStatus,
            String deleteStatus
    ) {
    this(
            reportId,
            fileName,
            sizeBytes,
            startedAtMillis,
            finishedAtMillis,
            method,
            status,
            errorMessage,
            manufacturer,
            deviceModel,
            androidVersion,
            androidApi,
            buildId,
            appVersion,
            cycles,
            bytesOverwritten,
            overwriteStatus,
            deleteStatus,
            "FILE",
            0,
            0,
            0
    );
}

        ErasureRecord(
        String reportId,
        String fileName,
        long sizeBytes,
        long startedAtMillis,
        long finishedAtMillis,
        String method,
        String status,
        String errorMessage,
        String manufacturer,
        String deviceModel,
        String androidVersion,
        int androidApi,
        String buildId,
        String appVersion,
        int cycles,
        long bytesOverwritten,
        String overwriteStatus,
        String deleteStatus,
        String operationType,
        int filesFound,
        int filesDeleted,
        int filesFailed
) {

    this.reportId = reportId;

    // O nome nunca será armazenado.
    this.fileName = "FOLDER".equals(operationType)
            ? "Folder"
            : redactFileName(fileName);

    this.sizeBytes = sizeBytes;

    this.startedAtMillis = startedAtMillis;
    this.finishedAtMillis = finishedAtMillis;

    this.method = method;
    this.status = status;
    this.errorMessage = errorMessage;

    this.manufacturer = manufacturer;
    this.deviceModel = deviceModel;
    this.androidVersion = androidVersion;
    this.androidApi = androidApi;
    this.buildId = buildId;
    this.appVersion = appVersion;

    this.cycles = cycles;
    this.bytesOverwritten = bytesOverwritten;

    this.overwriteStatus = overwriteStatus;
    this.deleteStatus = deleteStatus;

    this.operationType = operationType;

    this.filesFound = filesFound;
    this.filesDeleted = filesDeleted;
    this.filesFailed = filesFailed;
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
            "Single overwrite with random data";

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
        String methodDescription =
                context.getString(R.string.single_random_overwrite);

        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(outFile)
                     )) {

            writer.write(context.getString(R.string.erasure_report_title));
            writer.newLine();

            writer.write(context.getString(
                    R.string.erasure_report_app,
                    formatDateTime(System.currentTimeMillis())
            ));
            writer.newLine();
            writer.newLine();

            writer.write(context.getString(
                    R.string.erasure_report_total,
                    records.size()
            ));
            writer.newLine();

            writer.write(context.getString(R.string.erasure_report_method, methodDescription));
            writer.newLine();

            writer.write(context.getString(R.string.erasure_report_divider));
            writer.newLine();

            writer.write(context.getString(R.string.erasure_report_divider));
            writer.newLine();

            SimpleDateFormat fmt =
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.US
                    );

            for (ErasureRecord r : records) {

                writer.write(context.getString(R.string.erasure_report_file, r.fileName));
                writer.newLine();

                writer.write(context.getString(R.string.erasure_report_size, r.sizeBytes));
                writer.newLine();

                writer.write(context.getString(
                        R.string.erasure_report_start,
                        fmt.format(new Date(r.startedAtMillis))
                ));
                writer.newLine();

                writer.write(context.getString(
                        R.string.erasure_report_end,
                        fmt.format(new Date(r.finishedAtMillis))
                ));
                writer.newLine();

                writer.write(context.getString(R.string.erasure_report_method, r.method));
                writer.newLine();

                writer.write(context.getString(R.string.erasure_report_status, r.status));
                writer.newLine();

                if (!r.errorMessage.isEmpty()) {
                    writer.write(context.getString(R.string.erasure_report_error, r.errorMessage));
                    writer.newLine();
                }

                writer.write(context.getString(R.string.erasure_report_divider));
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
        String methodDescription =
                context.getString(R.string.single_random_overwrite);

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
                        context.getString(R.string.erasure_report_title),
                        margin,
                        y,
                        titlePaint
                );

                y += lineHeight;

                canvas.drawText(
                        context.getString(
                                R.string.erasure_report_app,
                                fmt.format(new Date())
                        ),
                        margin,
                        y,
                        textPaint
                );

                y += lineHeight;

                canvas.drawText(
                        context.getString(
                                R.string.erasure_report_method,
                                methodDescription
                        ),
                        margin,
                        y,
                        textPaint
                );

                y += lineHeight;

                canvas.drawText(
                        context.getString(R.string.erasure_report_records, records.size())
                                + "  |  "
                                + context.getString(R.string.erasure_report_page, pageIndex),
                        margin,
                        y,
                        smallPaint
                );

                y += lineHeight;

                canvas.drawText(
                        context.getString(R.string.erasure_report_divider),
                        margin,
                        y,
                        smallPaint
                );

                y += lineHeight;
            }

            // Desenha o registro.
            canvas.drawText(
                    truncate(context.getString(R.string.erasure_report_file, r.fileName), 88),
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    context.getString(R.string.erasure_report_size, r.sizeBytes)
                            + "  |  "
                            + context.getString(R.string.erasure_report_status, r.status),
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    context.getString(
                            R.string.erasure_report_start,
                            fmt.format(new Date(r.startedAtMillis))
                    ) + "   " + context.getString(
                            R.string.erasure_report_end,
                            fmt.format(new Date(r.finishedAtMillis))
                    ),
                    margin,
                    y,
                    textPaint
            );
            y += lineHeight;

            canvas.drawText(
                    truncate(context.getString(R.string.erasure_report_method, r.method), 88),
                    margin,
                    y,
                    smallPaint
            );
            y += lineHeight;

            if (!r.errorMessage.isEmpty()) {

                canvas.drawText(
                        truncate(context.getString(R.string.erasure_report_error, r.errorMessage), 88),
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

    sb.append("{\"v\":3");

    sb.append(",\"reportId\":\"")
            .append(jsonEscape(r.reportId)).append('"');

    sb.append(",\"fileName\":\"")
            .append(jsonEscape(r.fileName)).append('"');

    sb.append(",\"size\":")
            .append(r.sizeBytes);

    sb.append(",\"startedAt\":")
            .append(r.startedAtMillis);

    sb.append(",\"finishedAt\":")
            .append(r.finishedAtMillis);

    sb.append(",\"method\":\"")
            .append(jsonEscape(r.method)).append('"');

    sb.append(",\"status\":\"")
            .append(jsonEscape(r.status)).append('"');

    sb.append(",\"error\":\"")
            .append(jsonEscape(r.errorMessage)).append('"');

    sb.append(",\"manufacturer\":\"")
            .append(jsonEscape(r.manufacturer)).append('"');

    sb.append(",\"deviceModel\":\"")
            .append(jsonEscape(r.deviceModel)).append('"');

    sb.append(",\"androidVersion\":\"")
            .append(jsonEscape(r.androidVersion)).append('"');

    sb.append(",\"androidApi\":")
            .append(r.androidApi);

    sb.append(",\"buildId\":\"")
            .append(jsonEscape(r.buildId)).append('"');

    sb.append(",\"appVersion\":\"")
            .append(jsonEscape(r.appVersion)).append('"');

    sb.append(",\"cycles\":")
            .append(r.cycles);

    sb.append(",\"bytesOverwritten\":")
            .append(r.bytesOverwritten);

    sb.append(",\"overwriteStatus\":\"")
            .append(jsonEscape(r.overwriteStatus)).append('"');

    sb.append(",\"deleteStatus\":\"")
            .append(jsonEscape(r.deleteStatus)).append('"');

    sb.append(",\"operationType\":\"")
            .append(jsonEscape(r.operationType)).append('"');

    sb.append(",\"filesFound\":")
            .append(r.filesFound);

    sb.append(",\"filesDeleted\":")
            .append(r.filesDeleted);

    sb.append(",\"filesFailed\":")
            .append(r.filesFailed);

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

        Long size =
                extractLong(line, "size");

        Long startedAt =
                extractLong(line, "startedAt");

        Long finishedAt =
                extractLong(line, "finishedAt");

        String status =
                extractString(line, "status");

        if (size == null
                || startedAt == null
                || finishedAt == null
                || status == null) {
            return null;
        }

        String method =
                extractString(line, "method");

        String error =
                extractString(line, "error");

        String reportId =
                extractString(line, "reportId");

        String manufacturer =
                extractString(line, "manufacturer");

        String deviceModel =
                extractString(line, "deviceModel");

        String androidVersion =
                extractString(line, "androidVersion");

        Long androidApi =
                extractLong(line, "androidApi");

        String buildId =
                extractString(line, "buildId");

        String appVersion =
                extractString(line, "appVersion");

        Long cycles =
                extractLong(line, "cycles");

        Long bytesOverwritten =
                extractLong(line, "bytesOverwritten");

        String overwriteStatus =
                extractString(line, "overwriteStatus");

        String deleteStatus =
                extractString(line, "deleteStatus");

        // Campos introduzidos na versão 3.

        String operationType =
                extractString(line, "operationType");

        Long filesFound =
                extractLong(line, "filesFound");

        Long filesDeleted =
                extractLong(line, "filesDeleted");

        Long filesFailed =
                extractLong(line, "filesFailed");

        // Registro antigo (v1).

        if (reportId == null) {

            return new ErasureRecord(
                    jsonUnescape(fileName),
                    size,
                    startedAt,
                    finishedAt,

                    method == null
                            ? METHOD_DESCRIPTION
                            : jsonUnescape(method),

                    jsonUnescape(status),

                    error == null
                            ? ""
                            : jsonUnescape(error)
            );
        }

        // Valores padrão para registros v2 antigos.

        String finalOperationType =
                operationType == null
                        ? "FILE"
                        : jsonUnescape(operationType);

        int finalFilesFound =
                filesFound == null
                        ? 0
                        : filesFound.intValue();

        int finalFilesDeleted =
                filesDeleted == null
                        ? 0
                        : filesDeleted.intValue();

        int finalFilesFailed =
                filesFailed == null
                        ? 0
                        : filesFailed.intValue();

        // Registro v2 ou v3.

        return new ErasureRecord(

                jsonUnescape(reportId),

                jsonUnescape(fileName),

                size,
                startedAt,
                finishedAt,

                method == null
                        ? METHOD_DESCRIPTION
                        : jsonUnescape(method),

                jsonUnescape(status),

                error == null
                        ? ""
                        : jsonUnescape(error),

                manufacturer == null
                        ? "Not recorded"
                        : jsonUnescape(manufacturer),

                deviceModel == null
                        ? "Not recorded"
                        : jsonUnescape(deviceModel),

                androidVersion == null
                        ? "Not recorded"
                        : jsonUnescape(androidVersion),

                androidApi == null
                        ? 0
                        : androidApi.intValue(),

                buildId == null
                        ? "Not recorded"
                        : jsonUnescape(buildId),

                appVersion == null
                        ? "Not recorded"
                        : jsonUnescape(appVersion),

                cycles == null
                        ? 1
                        : cycles.intValue(),

                bytesOverwritten == null
                        ? 0
                        : bytesOverwritten,

                overwriteStatus == null
                        ? "Not recorded"
                        : jsonUnescape(overwriteStatus),

                deleteStatus == null
                        ? "Not recorded"
                        : jsonUnescape(deleteStatus),

                finalOperationType,
                finalFilesFound,
                finalFilesDeleted,
                finalFilesFailed
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
        final int left = 40;
        final int right = 555;

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(20);
        titlePaint.setFakeBoldText(true);

        Paint sectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sectionPaint.setTextSize(12);
        sectionPaint.setFakeBoldText(true);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(10);

        Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallPaint.setTextSize(8);

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(1);

        SimpleDateFormat fmt = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
        );

        long durationMillis = Math.max(
                0,
                record.finishedAtMillis - record.startedAtMillis
        );

        long durationSeconds = durationMillis / 1000;

        String durationText = String.format(
                Locale.getDefault(),
                "%02d min %02d s",
                durationSeconds / 60,
                durationSeconds % 60
        );

        PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(
                        pageWidth,
                        pageHeight,
                        1
                ).create();

        PdfDocument.Page page =
                document.startPage(pageInfo);

        Canvas canvas = page.getCanvas();

        int y = 70;

        // -----------------------------------------
        // Cabeçalho e ícone.
        // -----------------------------------------

        Drawable icon = context.getDrawable(
                R.drawable.app_icon
        );

        if (icon != null) {

            iconBitmap = Bitmap.createBitmap(
                    64,
                    64,
                    Bitmap.Config.ARGB_8888
            );

            Canvas iconCanvas = new Canvas(iconBitmap);

            icon.setBounds(0, 0, 64, 64);
            icon.draw(iconCanvas);

            canvas.drawBitmap(
                    iconBitmap,
                    left,
                    35,
                    null
            );
        }

        canvas.drawText(
                context.getString(R.string.erasure_pdf_title),
                120,
                65,
                titlePaint
        );

        canvas.drawText(
                "S-File Encryptor",
                120,
                85,
                smallPaint
        );

        canvas.drawLine(left, 120, right, 120, linePaint);

        y = 155;

        // -----------------------------------------
        // DEVICE DETAILS.
        // -----------------------------------------

        canvas.drawText(
                context.getString(R.string.erasure_pdf_device_details),
                left,
                y,
                sectionPaint
        );

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_manufacturer, record.manufacturer),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_model, record.deviceModel),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_android, record.androidVersion),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_api, record.androidApi),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_build_id, truncate(record.buildId, 75)),
                left,
                y,
                textPaint
        );

        y += 25;

        canvas.drawLine(left, y, right, y, linePaint);

        // -----------------------------------------
        // DELETION DETAILS.
        // -----------------------------------------

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_deletion_details),
                left,
                y,
                sectionPaint
        );

        y += 25;

        
if ("FOLDER".equals(record.operationType)) {

    canvas.drawText(
            context.getString(R.string.erasure_pdf_folder_operation),
            left,
            y,
            textPaint
    );

    y += 18;

    canvas.drawText(
            context.getString(R.string.erasure_pdf_files_found, record.filesFound),
            left,
            y,
            textPaint
    );

    y += 18;

    canvas.drawText(
            context.getString(R.string.erasure_pdf_files_deleted, record.filesDeleted),
            left,
            y,
            textPaint
    );

    y += 18;

    canvas.drawText(
            context.getString(R.string.erasure_pdf_files_failed, record.filesFailed),
            left,
            y,
            textPaint
    );

    y += 18;

} else {

    canvas.drawText(
            context.getString(R.string.erasure_pdf_file, truncate(record.fileName, 75)),
            left,
            y,
            textPaint
    );

    y += 18;
}

        String sizeLabel = "FOLDER".equals(record.operationType)
        ? context.getString(R.string.erasure_pdf_folder_total_size)
        : context.getString(R.string.erasure_pdf_original_size);

canvas.drawText(
        sizeLabel + record.sizeBytes + " bytes",
        left,
        y,
        textPaint
);

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_method, truncate(record.method, 75)),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_cycles, record.cycles),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_bytes_overwritten, record.bytesOverwritten),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_start, fmt.format(new Date(record.startedAtMillis))),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_end, fmt.format(new Date(record.finishedAtMillis))),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_duration, durationText),
                left,
                y,
                textPaint
        );

        y += 25;

        canvas.drawLine(left, y, right, y, linePaint);

        // -----------------------------------------
        // OPERATION STATUS.
        // -----------------------------------------

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_operation_status),
                left,
                y,
                sectionPaint
        );

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_overwrite, record.overwriteStatus),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_deletion, record.deleteStatus),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_overall_result, record.status),
                left,
                y,
                textPaint
        );

        if (!record.errorMessage.isEmpty()) {

            y += 18;

            canvas.drawText(
                    context.getString(R.string.erasure_pdf_error, truncate(record.errorMessage, 75)),
                    left,
                    y,
                    textPaint
            );
        }

        y += 25;

        canvas.drawLine(left, y, right, y, linePaint);

        // -----------------------------------------
        // REPORT DETAILS.
        // -----------------------------------------

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_report_details),
                left,
                y,
                sectionPaint
        );

        y += 25;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_report_id, record.reportId),
                left,
                y,
                smallPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_report_date, fmt.format(new Date())),
                left,
                y,
                textPaint
        );

        y += 18;

        canvas.drawText(
                context.getString(R.string.erasure_pdf_app_version, record.appVersion),
                left,
                y,
                textPaint
        );

        // -----------------------------------------
        // Rodapé.
        // -----------------------------------------

        canvas.drawLine(
                left,
                780,
                right,
                780,
                linePaint
        );

        canvas.drawText(
                "S-File Encryptor",
                left,
                800,
                smallPaint
        );

        canvas.drawText(
                context.getString(R.string.erasure_pdf_individual_footer),
                left,
                815,
                smallPaint
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