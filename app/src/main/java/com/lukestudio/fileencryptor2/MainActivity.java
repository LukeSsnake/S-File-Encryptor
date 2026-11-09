package com.lukestudio.fileencryptor2;

import java.nio.CharBuffer;
import java.util.zip.ZipFile;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.widget.CheckBox;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import androidx.documentfile.provider.DocumentFile;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import javax.crypto.Cipher;

public class MainActivity extends FragmentActivity {

    private static final int PICK_FILE = 10;
    private static final int PICK_FOLDER = 11;
    private static final int CREATE_OUTPUT = 12;
    private static final int PICK_OUTPUT_FOLDER = 13;

    private static final int MODE_FILE = 0;
    private static final int MODE_FOLDER = 1;

    private static final int CREATE_METADATA_OUTPUT = 14;
    private File metadataTempFile;
    private String metadataOutputName;
    private boolean metadataCleaningInProgress = false;

    /*
     * Tamanho mínimo exigido para novas senhas.
     *
     * Essa regra é aplicada somente ao criar ou alterar
     * senhas. A descriptografia continua aceitando senhas
     * menores para preservar compatibilidade com arquivos
     * antigos.
     */
    private static final int MIN_PASSWORD_LENGTH = 12;
    // Limite de cópia de entrada para impedir consumo ilimitado de cache/disco.
    private static final long MAX_INPUT_BYTES = 8L * 1024L * 1024L * 1024L;

    private Uri inputUri;
    private Uri outputUri;

    private boolean outputDocumentCreatedByApp = false;
    private boolean decryptMode;
    private boolean inputIsFolder;
    private int inputMode = MODE_FILE;

    private char[] pendingPassword;

    private TextView fileName;
    private TextView progressText;
    private EditText password;
    private ProgressBar progress;
    private Button action;
    private Button fileButton;
    private Button folderButton;
    private Button helpButton;
    private Button infoButton;
    private Button infoCrypto;
    private Button metaButton;
    private Button deleteButton;
    private Switch biometricSwitch;
    private TextView biometricSettings;

    private boolean restoringBiometricSwitch;

    private long zipTotalBytes;
    private long zipProcessedBytes;

    private long zipActualBytes;
    private int zipEntryCount;

    private long unzipTotalBytes;
    private long unzipProcessedBytes;

    private static final int CREATE_ERASURE_PDF = 15;
    private ErasureRecord pendingErasureRecord;

    private static final long MAX_ZIP_ENTRY_SIZE =
        1L * 1024 * 1024 * 1024; // 1 GiB por arquivo

        private static final long MAX_ZIP_TOTAL_SIZE =
        2L * 1024 * 1024 * 1024; // 2 GiB no total

        private static final int MAX_ZIP_ENTRIES = 10_000;
        private static final int MAX_ZIP_DEPTH = 20;
        private static final int MAX_ZIP_PATH_LENGTH = 1_024;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
    );

        PDFBoxResourceLoader.init(getApplicationContext());

        getOnBackPressedDispatcher().addCallback(
                this,
                new androidx.activity.OnBackPressedCallback(true) {

                @Override
                public void handleOnBackPressed() {

                showExitDialog();
            }
        }
);

        cleanTemporaryFiles();

        getWindow().setNavigationBarColor(
                android.graphics.Color.rgb(38, 50, 56)
        );

        if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q) {

            getWindow().setNavigationBarContrastEnforced(false);
        }

        getWindow().getDecorView().setSystemUiVisibility(0);

        setContentView(R.layout.main);

        fileName = findViewById(R.id.fileName);
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progressText);
        password = findViewById(R.id.password);
        action = findViewById(R.id.actionButton);

        fileButton = findViewById(R.id.fileButton);
        folderButton = findViewById(R.id.folderButton);
        helpButton = findViewById(R.id.helpButton);
        infoButton = findViewById(R.id.infoButton);
        infoCrypto = findViewById(R.id.infoCrypto);
        metaButton = findViewById(R.id.metaButton);
        deleteButton = findViewById(R.id.deleteButton);

        biometricSwitch = findViewById(R.id.biometricSwitch);
        biometricSettings = findViewById(R.id.biometricSettings);
        
        boolean biometricEnabled =
                BiometricPasswordStore.isEnabled(this);

        biometricSwitch.setChecked(biometricEnabled);
        biometricSettings.setEnabled(biometricEnabled);

        password.setVisibility(
                biometricEnabled
                        ? View.GONE
                        : View.VISIBLE
        );

        handleIncomingIntent(getIntent());

        fileButton.setOnClickListener(v -> {
                deleteButton.setVisibility(View.VISIBLE);
                pickFile();
        });
        folderButton.setOnClickListener(v -> {
                deleteButton.setVisibility(View.VISIBLE);
                pickFolder();
        });
        helpButton.setOnClickListener(v -> showHelp());
        infoButton.setOnClickListener(v -> showSupport());
        infoCrypto.setOnClickListener(v -> showCrypto());
        metaButton.setOnClickListener(v -> {
                if (inputUri == null || inputIsFolder) {
                        Toast.makeText(
                this,
                "Selecione um arquivo válido.",
                Toast.LENGTH_SHORT
        ).show();
                } else {
                        showMeta();
                }
        });

        action.setOnClickListener(v -> startWork());

        deleteButton.setOnClickListener(v -> {
                if (inputUri == null) {
                        Toast.makeText(
                this,
                "Selecione um arquivo válido.",
                Toast.LENGTH_SHORT
        ).show();
                } else {
                        showSecureDeleteConfirmation();
                }
        });

        biometricSwitch.setOnCheckedChangeListener(
                (button, checked) -> {

                    if (restoringBiometricSwitch) {
                        return;
                    }

                    if (checked) {

                        password.setVisibility(View.GONE);
                        enableBiometric();

                    } else {

                        restoringBiometricSwitch = true;
                        biometricSwitch.setChecked(true);
                        restoringBiometricSwitch = false;

                        disableBiometric();
                    }
                }
        );

        biometricSettings.setOnClickListener(
                v -> changeBiometricPassword()
        );

        if (b == null) {
                checkSupportLaunchCounter();
        }

    }

    private void cleanTemporaryFiles() {
    File cacheDir = getCacheDir();
    File[] files = cacheDir.listFiles();

    if (files == null) {
        return;
    }

    // Considera antigos os temporários com mais de 2 horas.
    long maxAge = 2L * 60 * 60 * 1000;
    long now = System.currentTimeMillis();

    for (File file : files) {
        if (!file.isFile()) {
            continue;
        }

        String name = file.getName();

        boolean isTemporary =
                name.startsWith("sfe-v1-") ||
                name.startsWith("sfe-v2-") ||
                name.startsWith("sfe_input_") ||
                name.startsWith("sfe_zip_") ||
                name.startsWith("sfe_dec_") ||
                name.startsWith("sfe_enc_") ||
                name.startsWith("sfe_meta_in_") ||
                name.startsWith("sfe_meta_out_");

        if (!isTemporary) {
            continue;
        }

        // Não exclui arquivos recentes.
        long age = now - file.lastModified();

        if (age < maxAge) {
            continue;
        }

        if (file.delete()) {
            android.util.Log.d(
                    "SFileEncryptor",
                    "Arquivo temporário antigo removido."
            );
        } else {
            android.util.Log.w(
                    "SFileEncryptor",
                    "Falha ao remover arquivo temporário antigo."
            );
        }
    }
}

    private char[] copyPassword(EditText field) {
    android.text.Editable editable = field.getText();

    char[] copy = new char[editable.length()];

    editable.getChars(
            0,
            editable.length(),
            copy,
            0
    );

    return copy;
}

        private void showErasureReportDialog(ErasureRecord record) {

    SimpleDateFormat fmt = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
    );

    String reportText =
            "ID do relatório: " + record.reportId

                    + "\n\nArquivo: " + record.fileName

                    + "\n\nTamanho: "
                    + record.sizeBytes + " bytes"

                    + "\n\nInício: "
                    + fmt.format(
                            new Date(record.startedAtMillis)
                    )

                    + "\n\nTérmino: "
                    + fmt.format(
                            new Date(record.finishedAtMillis)
                    )

                    + "\n\nMétodo: "
                    + record.method

                    + "\n\nCiclos: "
                    + record.cycles

                    + "\n\nBytes sobrescritos: "
                    + record.bytesOverwritten

                    + "\n\nSobrescrita: "
                    + record.overwriteStatus

                    + "\n\nExclusão: "
                    + record.deleteStatus

                    + "\n\nResultado geral: "
                    + record.status

                    + "\n\nFabricante: "
                    + record.manufacturer

                    + "\n\nModelo: "
                    + record.deviceModel

                    + "\n\nAndroid: "
                    + record.androidVersion

                    + "\n\nAPI: "
                    + record.androidApi

                    + "\n\nBuild ID: "
                    + record.buildId

                    + "\n\nVersão do aplicativo: "
                    + record.appVersion;

    if (!record.errorMessage.isEmpty()) {

        reportText +=
                "\n\nErro: "
                        + record.errorMessage;
    }

    new AlertDialog.Builder(this)
            .setTitle("🛡️ Relatório de Exclusão")
            .setMessage("\n" + reportText + "\n")
            .setNeutralButton(
                    "Salvar PDF",
                    (dialog, which) ->
                            requestErasurePdf(record)
            )
            .setPositiveButton("Fechar", null)
            .setCancelable(false)
            .show();
}

        private void executeSecureOverwrite() {

    if (inputUri == null || inputIsFolder) {
        Toast.makeText(
                this,
                "Selecione um arquivo válido.",
                Toast.LENGTH_SHORT
        ).show();
        return;
    }

    setBusy(true);

progress.setProgress(0);

progressText.setText(
        "Preparando exclusão..."
);

    Uri targetUri = inputUri;

    executor.execute(() -> {

        long startedAt = System.currentTimeMillis();

        String originalName = "Arquivo";
        long originalSize = -1;

        // Obtém nome e tamanho antes da sobrescrita.
        try (Cursor cursor = getContentResolver().query(
                targetUri,
                new String[]{
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE
                },
                null,
                null,
                null
        )) {

            if (cursor != null && cursor.moveToFirst()) {

                int nameIndex = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                int sizeIndex = cursor.getColumnIndex(
                        OpenableColumns.SIZE
                );

                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);

                    if (name != null && !name.isEmpty()) {
                        originalName = name;
                    }
                }

                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    originalSize = cursor.getLong(sizeIndex);
                }
            }

        } catch (Exception ignored) {
            // A falha na leitura dos metadados
            // não deve impedir a exclusão.
        }

        SecureDeleteManager.Result overwriteResult =
        SecureDeleteManager.overwriteFile(
                getApplicationContext(),
                targetUri,
                (bytesWritten, totalBytes) ->
                        updateSecureDeleteProgress(
                                bytesWritten,
                                totalBytes
                        )
        );

        SecureDeleteManager.Result finalResult;

        if (overwriteResult.status == SecureDeleteManager.Status.SUCCESS) {

            finalResult = SecureDeleteManager.deleteFile(
                    getApplicationContext(),
                    targetUri
            );

        } else {

            finalResult = overwriteResult;
        }

        long finishedAt = System.currentTimeMillis();

        // -----------------------------------------
        // Resultado geral.
        // -----------------------------------------

        String recordStatus;
        String errorMessage;

        if (overwriteResult.status
                != SecureDeleteManager.Status.SUCCESS) {

            recordStatus = "OVERWRITE_FAILED";
            errorMessage = overwriteResult.message;

        } else if (finalResult.status
                != SecureDeleteManager.Status.SUCCESS) {

            recordStatus = "DELETE_FAILED";
            errorMessage = finalResult.message;

        } else {

            recordStatus = "SUCCESS";
            errorMessage = "";
        }

        // -----------------------------------------
        // Status individuais.
        // -----------------------------------------

        String overwriteStatus;

        if (overwriteResult.status
                == SecureDeleteManager.Status.SUCCESS) {

            overwriteStatus = "SUCCESS";

        } else {

            overwriteStatus = "FAILED";
        }

        String deleteStatus;

        if (overwriteResult.status
                != SecureDeleteManager.Status.SUCCESS) {

            deleteStatus = "NOT_EXECUTED";

        } else if (finalResult.status
                == SecureDeleteManager.Status.SUCCESS) {

            deleteStatus = "SUCCESS";

        } else {

            deleteStatus = "FAILED";
        }

        // -----------------------------------------
        // Dados do dispositivo.
        // -----------------------------------------

        String manufacturer =
                android.os.Build.MANUFACTURER;

        String deviceModel =
                android.os.Build.MODEL;

        String androidVersion =
                android.os.Build.VERSION.RELEASE;

        int androidApi =
                android.os.Build.VERSION.SDK_INT;

        String buildId =
                android.os.Build.ID;

        String appVersion;

        try {

            appVersion =
                    getPackageManager()
                            .getPackageInfo(
                                    getPackageName(),
                                    0
                            )
                            .versionName;

            if (appVersion == null
                    || appVersion.isEmpty()) {

                appVersion = "Não registrada";
            }

        } catch (Exception e) {

            appVersion = "Não registrada";
        }

        // -----------------------------------------
        // Tamanho registrado.
        // -----------------------------------------

        long recordedSize = originalSize >= 0
                ? originalSize
                : overwriteResult.bytesOverwritten;

        // -----------------------------------------
        // Cria o registro completo.
        // -----------------------------------------

        ErasureRecord record = new ErasureRecord(

                java.util.UUID.randomUUID().toString(),

                originalName,
                recordedSize,

                startedAt,
                finishedAt,

                "Sobrescrita única com dados aleatórios",

                recordStatus,
                errorMessage,

                manufacturer,
                deviceModel,
                androidVersion,
                androidApi,
                buildId,
                appVersion,

                1,

                overwriteResult.bytesOverwritten,

                overwriteStatus,
                deleteStatus
        );

        // Salva o registro sem deixar uma falha
        // no relatório interromper a operação.
        boolean reportSaved = true;

        try {

            ErasureReport.append(
                    getApplicationContext(),
                    record
            );

        } catch (IOException e) {

            reportSaved = false;
        }

        final boolean finalReportSaved = reportSaved;

        runOnUiThread(() -> {

            setBusy(false);

            String title;
            String message;

            if (overwriteResult.status
                    != SecureDeleteManager.Status.SUCCESS) {

                title = "Sobrescrita não concluída";

                message = overwriteResult.message
                        + "\n\nO arquivo não foi excluído.";

            } else if (finalResult.status
                    == SecureDeleteManager.Status.SUCCESS) {

                title = "Operação concluída";

                message = overwriteResult.message
                        + "\n\n"
                        + finalResult.message
                        + "\n\nBytes sobrescritos: "
                        + overwriteResult.bytesOverwritten;

                if (targetUri.equals(inputUri)) {

                    inputUri = null;

                    fileName.setText(
                            "Nenhum arquivo selecionado"
                    );
                    
                }

            } else {

                title = "Arquivo não excluído";

                message =
                        "A sobrescrita foi concluída, "
                        + "mas a exclusão falhou.\n\n"
                        + finalResult.message
                        + "\n\nBytes sobrescritos: "
                        + overwriteResult.bytesOverwritten;
            }

            if (!finalReportSaved) {

                message +=
                        "\n\nAviso: não foi possível "
                        + "salvar o registro do relatório.";
            }

            AlertDialog.Builder builder =
                    new AlertDialog.Builder(
                            MainActivity.this
                    )
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton(
                                    "OK",
                                    null
                            )
                            .setCancelable(false);

            // O botão aparece quando a operação
            // foi concluída com sucesso.
            if (recordStatus.equals("SUCCESS")) {

                builder.setNegativeButton(
                        "Ver Relatório",
                        (dialog, which) ->
                                showErasureReportDialog(record)
                );
            }

            builder.show();
        });
    });
}

        private void executeSecureFolderOverwrite() {

    if (inputUri == null || !inputIsFolder) {
        Toast.makeText(
                this,
                "Selecione uma pasta válida.",
                Toast.LENGTH_SHORT
        ).show();
        return;
    }

    setBusy(true);

progress.setProgress(0);
progressText.setText("Preparando exclusão...");

    Uri targetUri = inputUri;

    executor.execute(() -> {

        long startedAt = System.currentTimeMillis();

        DocumentFile root =
                DocumentFile.fromTreeUri(
                        getApplicationContext(),
                        targetUri
                );                

        if (root == null || !root.isDirectory()) {

            runOnUiThread(() -> {

                setBusy(false);

                new AlertDialog.Builder(
                        MainActivity.this
                )
                        .setTitle("Pasta inválida")
                        .setMessage(
                                "Não foi possível acessar a pasta selecionada."
                        )
                        .setPositiveButton("OK", null)
                        .show();
            });

            return;
        }

        final int expectedTotalFiles =
        countFilesInFolder(root);

        final int[] processedFiles = {0};

        int[] totalFiles = {0};
        int[] successfulFiles = {0};
        int[] failedFiles = {0};
        long[] bytesOverwritten = {0};

        List<DocumentFile> folders =
                new ArrayList<>();

        processFolderForSecureDeletion(
        root,
        totalFiles,
        successfulFiles,
        failedFiles,
        bytesOverwritten,
        folders,
        expectedTotalFiles,
        processedFiles
);

        /*
         * Remove as pastas somente depois de
         * processar todos os arquivos.
         *
         * As pastas mais internas são removidas
         * primeiro.
         */
        if (failedFiles[0] == 0) {

    // Adiciona a pasta principal ao final.
    folders.add(root);

    // Remove primeiro as subpastas e depois a raiz.
    for (int i = folders.size() - 1; i >= 0; i--) {

        DocumentFile folder = folders.get(i);

        try {

            if (folder.exists()
                    && folder.isDirectory()) {

                folder.delete();
            }

        } catch (Exception ignored) {
            // A falha na remoção da pasta
            // não interrompe o restante da operação.
        }
    }
}

        long finishedAt =
                System.currentTimeMillis();

        // -----------------------------------------
// Valores finais dos contadores.
// -----------------------------------------

final int finalTotalFiles = totalFiles[0];

final int finalSuccessfulFiles =
        successfulFiles[0];

final int finalFailedFiles =
        failedFiles[0];

final long finalBytesOverwritten =
        bytesOverwritten[0];
        
        final int filesFound = totalFiles[0];
        final int filesSuccessful = successfulFiles[0];
        final int filesFailed = failedFiles[0];
        final long totalBytes = bytesOverwritten[0];

        // -----------------------------------------
// Resultado geral.
 // -----------------------------------------

String recordStatus;
String errorMessage;

if (finalFailedFiles == 0) {

    recordStatus = "SUCCESS";
    errorMessage = "";

} else {

    recordStatus = "PARTIAL_FAILURE";

    errorMessage =
            "Uma ou mais operações de sobrescrita "
            + "ou exclusão não foram concluídas.";
}

// -----------------------------------------
// Status individuais.
// -----------------------------------------

String overwriteStatus =
        finalFailedFiles == 0
                ? "SUCCESS"
                : "NOT_SEPARATELY_TRACKED";

String deleteStatus =
        finalFailedFiles == 0
                ? "SUCCESS"
                : "NOT_SEPARATELY_TRACKED";

// -----------------------------------------
// Dados do dispositivo.
// -----------------------------------------

String manufacturer =
        android.os.Build.MANUFACTURER;

String deviceModel =
        android.os.Build.MODEL;

String androidVersion =
        android.os.Build.VERSION.RELEASE;

int androidApi =
        android.os.Build.VERSION.SDK_INT;

String buildId =
        android.os.Build.ID;

String appVersion;

try {

    appVersion =
            getPackageManager()
                    .getPackageInfo(
                            getPackageName(),
                            0
                    )
                    .versionName;

    if (appVersion == null
            || appVersion.isEmpty()) {

        appVersion = "Não registrada";
    }

} catch (Exception e) {

    appVersion = "Não registrada";
}

// -----------------------------------------
// Cria o registro completo.
// -----------------------------------------

ErasureRecord record = new ErasureRecord(

        java.util.UUID.randomUUID().toString(),

        "****",
        finalBytesOverwritten,

        startedAt,
        finishedAt,

        "Sobrescrita única com dados aleatórios",

        recordStatus,
        errorMessage,

        manufacturer,
        deviceModel,
        androidVersion,
        androidApi,
        buildId,
        appVersion,

        1,

        finalBytesOverwritten,

        overwriteStatus,
        deleteStatus,

        "FOLDER",
        finalTotalFiles,
        finalSuccessfulFiles,
        finalFailedFiles
);

// -----------------------------------------
// Salva o relatório.
// -----------------------------------------

boolean reportSaved = true;

try {

    ErasureReport.append(
            getApplicationContext(),
            record
    );

} catch (IOException e) {

    reportSaved = false;
}

final boolean finalReportSaved = reportSaved;

        runOnUiThread(() -> {
            
            setBusy(false);
            
            String title;
            String message;

            if (filesFailed == 0) {

                title = "Operação concluída";

                message =
                        "Pasta processada com sucesso."
                        + "\n\nArquivos encontrados: "
                        + filesFound
                        + "\nArquivos excluídos: "
                        + filesSuccessful
                        + "\nBytes sobrescritos: "
                        + totalBytes;

                if (targetUri.equals(inputUri)) {

                    inputUri = null;
                    inputIsFolder = false;

                    fileName.setText(
                            "Nenhum arquivo selecionado"
                    );
                    
                }

            } else {

                title = "Operação parcialmente concluída";

                message =
                        "A pasta foi processada, "
                        + "mas alguns arquivos não puderam "
                        + "ser excluídos."
                        + "\n\nArquivos encontrados: "
                        + filesFound
                        + "\nArquivos excluídos: "
                        + filesSuccessful
                        + "\nArquivos com falha: "
                        + filesFailed
                        + "\nBytes sobrescritos: "
                        + totalBytes;
            }

            AlertDialog.Builder builder =
            new AlertDialog.Builder(
                    MainActivity.this
            )
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(
                            "OK",
                            null
                    )                    
                    .setCancelable(false);
                    // O botão aparece quando a operação
            // foi concluída com sucesso.
            if (recordStatus.equals("SUCCESS")) {

                builder.setNegativeButton(
                        "Ver Relatório",
                        (dialog, which) ->
                                showErasureReportDialog(record)
                );
            }
                builder.show();
        });
    });
}

        private int countFilesInFolder(DocumentFile folder) {

    if (folder == null
            || !folder.exists()
            || !folder.isDirectory()) {
        return 0;
    }

    int count = 0;

    DocumentFile[] children = folder.listFiles();

    if (children == null) {
        return 0;
    }

    for (DocumentFile child : children) {

        if (child == null) {
            continue;
        }

        if (child.isDirectory()) {

            count += countFilesInFolder(child);

        } else if (child.isFile()) {

            count++;
        }
    }

    return count;
}

        private void processFolderForSecureDeletion(
        DocumentFile folder,
        int[] totalFiles,
        int[] successfulFiles,
        int[] failedFiles,
        long[] bytesOverwritten,
        List<DocumentFile> folders,
        int expectedTotalFiles,
        int[] processedFiles
) {

    if (folder == null
            || !folder.exists()
            || !folder.isDirectory()) {
        return;
    }

    DocumentFile[] children = folder.listFiles();

    if (children == null) {
        return;
    }

    for (DocumentFile child : children) {

        if (child == null) {
            continue;
        }

        if (child.isDirectory()) {

            processFolderForSecureDeletion(
                    child,
                    totalFiles,
                    successfulFiles,
                    failedFiles,
                    bytesOverwritten,
                    folders,
                    expectedTotalFiles,
                    processedFiles
            );

            folders.add(child);

            continue;
        }

        if (!child.isFile()) {
            continue;
        }

       totalFiles[0]++;

try {

    Uri fileUri = child.getUri();

    SecureDeleteManager.Result overwriteResult =
            SecureDeleteManager.overwriteFile(
                    getApplicationContext(),
                    fileUri
            );

    if (overwriteResult.status
            != SecureDeleteManager.Status.SUCCESS) {

        failedFiles[0]++;

        continue;
    }

    bytesOverwritten[0] +=
            overwriteResult.bytesOverwritten;

    SecureDeleteManager.Result deleteResult =
            SecureDeleteManager.deleteFile(
                    getApplicationContext(),
                    fileUri
            );

    if (deleteResult.status
            == SecureDeleteManager.Status.SUCCESS
            || !child.exists()) {

        successfulFiles[0]++;

    } else {

        failedFiles[0]++;
    }

} catch (Exception e) {

    failedFiles[0]++;

} finally {

    processedFiles[0]++;

    final int percent = expectedTotalFiles > 0
            ? (int) (
                    processedFiles[0] * 100L
                    / expectedTotalFiles
            )
            : 100;

    final int currentFile = processedFiles[0];

    runOnUiThread(() -> {

        progress.setProgress(
                Math.min(100, percent)
        );

        progressText.setText(
                "Excluindo arquivos: "
                        + currentFile
                        + " de "
                        + expectedTotalFiles
        );
    });
}
    }
}

        private void showSecureDeleteConfirmation() {

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    int padding = (int) (20 * getResources().getDisplayMetrics().density);

    layout.setPadding(padding, padding, padding, 0);

    TextView message = new TextView(this);
    message.setText(
            "Esta operação irá sobrescrever os dados do arquivo " +
            "antes de excluí-lo.\n\n" +
            "É uma proteção a mais do que simplesmente excluir de modo convencional.\n" +
            "Ainda podem restar resíduos.\n\n"
    );

    message.setTextSize(16);

    CheckBox checkBox = new CheckBox(this);
    checkBox.setText("Sim, desejo continuar com a exclusão.");

    layout.addView(message);
    layout.addView(checkBox);

    AlertDialog dialoguee = new AlertDialog.Builder(this)
            .setTitle(R.string.del_title)
            .setView(layout)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton(R.string.deletee, (d, which) -> {
                if (inputIsFolder) {

                        executeSecureFolderOverwrite();

                } else {

                        executeSecureOverwrite();
                }
            })
            .create();

    dialoguee.setOnShowListener(d -> {
        dialoguee.getButton(AlertDialog.BUTTON_POSITIVE)
                .setEnabled(false);

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            dialoguee.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(isChecked);
        });
    });

    dialoguee.show();
}

    private void showHelp() {

        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_message)
                .setPositiveButton(
                        android.R.string.ok,
                        null
                )
                .show();
    }

    private void showMeta() {

        AlertDialog met =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.meta_title
                        )
                        .setMessage(
                                getString(
                                        R.string.meta_message                                  
                                )
                        )
                        .setNegativeButton(
                                R.string.meta_cancel,
                                null
                                )
                        .setPositiveButton(
                        "REMOVER METADADOS",
                        (d, which) -> startMetadataCleaning()
                        )
                        .create();

        met.show();

    }

    private void showCrypto() {

        AlertDialog cryp =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.crypto_title
                        )
                        .setMessage(
                                getString(
                                        R.string.crypto_message                                  
                                )
                        )
                        .setNeutralButton(
                                "CÓDIGO FONTE",
                                (d, which) -> {

                                    try {

                                        Intent in =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://github.com/LukeSsnake/S-File-Encryptor"
                                                        )
                                                );

                                        startActivity(in);

                                    } catch (Exception g) {}
                                }
                        )
                        .setPositiveButton(
                        R.string.okay,
                        null
                        )
                        .create();

        cryp.show();

    }

    private void showSupport() {

        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.support_title
                        )
                        .setMessage(
                                getString(
                                        R.string.support_message,
                                        getString(
                                                R.string.pix_email
                                        )
                                )
                        )
                        .setNeutralButton(
                                "⭐AVALIAR",
                                (d, which) -> {

                                    try {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "market://details?id=com.lukestudio.fileencryptor2"
                                                        )
                                                );

                                        startActivity(intent);

                                    } catch (Exception e) {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2"
                                                        )
                                                );

                                        startActivity(intent);
                                    }
                                }
                        )
                        .setPositiveButton(
                                R.string.copy_pix,
                                (d, which) -> {

                                    ClipboardManager clipboard =
                                            (ClipboardManager)
                                                    getSystemService(
                                                            CLIPBOARD_SERVICE
                                                    );

                                    if (clipboard != null) {

                                        clipboard.setPrimaryClip(
                                                ClipData.newPlainText(
                                                        "PIX",
                                                        getString(
                                                                R.string.pix_email
                                                        )
                                                )
                                        );

                                        Toast.makeText(
                                                MainActivity.this,
                                                R.string.pix_copied,
                                                Toast.LENGTH_SHORT
                                        ).show();

                                        showBankAppsDialog();
                                    }
                                }
                        )
                        .setNegativeButton("☕DOAR",
                                (d, which) -> {

                                    try {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://buymeacoffee.com/lukess"
                                                        )
                                                );

                                        startActivity(intent);

                                    } catch (Exception e) {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://buymeacoffee.com/lukess"
                                                        )
                                                );

                                        startActivity(intent);
                                    }
                                }
                        )
                        .create();

        dialog.show();
    }

    private void showBankAppsDialog() {

    String[] bankPackages = {
            "com.google.android.apps.walletnfcrel",
            "com.nu.production",
            "br.com.bb.android",
            "com.itau",
            "com.bradesco",
            "com.santander.app",
            "br.gov.caixa.tem",
            "br.com.gabba.Caixa",
            "br.com.intermedium",
            "com.c6bank.app",
            "com.btgpactual.banking",
            "br.com.sicoobnet",
            "br.com.sicredi.app",
            "br.com.banrisul",
            "br.com.bancopan",
            "br.com.neon",
            "com.original.bank",
            "br.com.uol.ps.myaccount",
            "com.mercadopago.wallet",
            "com.picpay"
    };

    android.content.pm.PackageManager pm =
            getPackageManager();

    java.util.List<BankApp> installedApps =
            new java.util.ArrayList<>();

    for (String packageName : bankPackages) {

        try {

            Intent launchIntent =
                    pm.getLaunchIntentForPackage(packageName);

            if (launchIntent == null) {
                continue;
            }

            android.content.pm.ApplicationInfo appInfo =
                    pm.getApplicationInfo(packageName, 0);

            String appName =
                    pm.getApplicationLabel(appInfo).toString();

            android.graphics.drawable.Drawable appIcon =
                    pm.getApplicationIcon(appInfo);

            installedApps.add(
                    new BankApp(
                            appName,
                            packageName,
                            appIcon,
                            launchIntent
                    )
            );

        } catch (Exception ignored) {
            // Aplicativo não instalado ou indisponível.
        }
    }

    if (installedApps.isEmpty()) {

        Toast.makeText(
                this,
                "Nenhum aplicativo bancário compatível foi encontrado.",
                Toast.LENGTH_LONG
        ).show();

        return;
    }

    java.util.Collections.sort(
            installedApps,
            (a, b) -> a.name.compareToIgnoreCase(b.name)
    );

    android.widget.ArrayAdapter<BankApp> adapter =
            new android.widget.ArrayAdapter<BankApp>(
                    this,
                    android.R.layout.select_dialog_item,
                    installedApps
            ) {

                @Override
                public android.view.View getView(
                        int position,
                        android.view.View convertView,
                        android.view.ViewGroup parent
                ) {

                    BankApp app = getItem(position);

                    android.widget.LinearLayout row =
                            new android.widget.LinearLayout(
                                    MainActivity.this
                            );

                    row.setOrientation(
                            android.widget.LinearLayout.HORIZONTAL
                    );

                    row.setGravity(
                            android.view.Gravity.CENTER_VERTICAL
                    );

                    int padding =
                            (int) (12 * getResources()
                                    .getDisplayMetrics().density);

                    row.setPadding(
                            padding,
                            padding,
                            padding,
                            padding
                    );

                    android.widget.ImageView icon =
                            new android.widget.ImageView(
                                    MainActivity.this
                            );

                    int iconSize =
                            (int) (40 * getResources()
                                    .getDisplayMetrics().density);

                    android.widget.LinearLayout.LayoutParams iconParams =
                            new android.widget.LinearLayout.LayoutParams(
                                    iconSize,
                                    iconSize
                            );

                    icon.setImageDrawable(app.icon);

                    row.addView(icon, iconParams);

                    android.widget.TextView name =
                            new android.widget.TextView(
                                    MainActivity.this
                            );

                    name.setText(app.name);
                    name.setTextSize(16);
                    name.setPadding(
                            padding,
                            0,
                            0,
                            0
                    );

                    row.addView(
                            name,
                            new android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1
                            )
                    );

                    return row;
                }
            };

    new AlertDialog.Builder(this)
            .setTitle("PIX Copiado!\n" +
                "Escolha seu app bancário"
        )
            .setAdapter(
                    adapter,
                    (dialog, which) -> {

                        BankApp selected =
                                installedApps.get(which);

                        try {

                            startActivity(selected.launchIntent);

                        } catch (Exception e) {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Não foi possível abrir o aplicativo.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
            )
            .setNegativeButton("Fechar", null)
            .setCancelable(false)
            .show();
}

        private void checkSupportLaunchCounter() {

    android.content.SharedPreferences prefs =
            getSharedPreferences(
                    "SFileEncryptorPrefs",
                    MODE_PRIVATE
            );

    int launchCount =
            prefs.getInt("launch_count", 0);

    launchCount++;

    if (launchCount >= 10) {

        prefs.edit()
                .putInt("launch_count", 0)
                .apply();

        showSupport();

    } else {

        prefs.edit()
                .putInt("launch_count", launchCount)
                .apply();
    }
}

    private void enableBiometric() {

        if (!canUseBiometric()) {
            return;
        }

        showRegisterPasswordDialog();
    }

    private boolean canUseBiometric() {

        BiometricManager manager =
                BiometricManager.from(this);

        int result =
                manager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                );

        if (result ==
                BiometricManager.BIOMETRIC_SUCCESS) {

            return true;
        }

        restoringBiometricSwitch = true;
        biometricSwitch.setChecked(false);
        restoringBiometricSwitch = false;

        password.setVisibility(View.VISIBLE);

        if (result ==
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {

            toast(R.string.biometric_not_enrolled);

        } else {

            toast(R.string.biometric_unavailable);
        }

        return false;
    }

    private void showRegisterPasswordDialog() {

        final EditText field = new EditText(this);

        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setHint(R.string.password_hint);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(50, 0, 50, 0);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(
                field,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.register_default_password
                        )
                        .setMessage(
                                R.string.register_default_password_message
                        )
                        .setView(box)
                        .setNegativeButton(
                                android.R.string.cancel,
                                (d, w) -> {

                                    restoringBiometricSwitch = true;
                                    biometricSwitch.setChecked(false);
                                    restoringBiometricSwitch = false;

                                    password.setVisibility(
                                            View.VISIBLE
                                    );
                                }
                        )
                        .setPositiveButton(
                                R.string.save,
                                null
                        )
                        .setCancelable(false)
                        .create();

        dialog.setOnShowListener(
                d -> dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {

                    if (field.getText().length() == 0) {

                        field.requestFocus();

                        toast(
                                R.string.enter_password
                        );

                        return;
                    }

                    if (field.getText().length() <
                            MIN_PASSWORD_LENGTH) {

                        field.requestFocus();

                        toast(
                                R.string.short_password
                        );

                        return;
                    }

                    char[] newPassword = copyPassword(field);

                    dialog.dismiss();

                    authenticateForEncryption(
                            newPassword
                    );
                })
        );

        dialog.setOnCancelListener(
                d -> {

                    restoringBiometricSwitch = true;
                    biometricSwitch.setChecked(false);
                    restoringBiometricSwitch = false;

                    password.setVisibility(View.VISIBLE);
                }
        );

        dialog.show();
    }

    private void authenticateForEncryption(
            char[] newPassword
    ) {

        final Cipher cipher;

        try {

            cipher =
                    BiometricPasswordStore
                            .createEncryptionCipher(this);

        } catch (Exception e) {

            Arrays.fill(
                    newPassword,
                    '\0'
            );

            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;

            password.setVisibility(View.VISIBLE);

            toast(
                    R.string.biometric_setup_error
            );

            return;
        }

        BiometricPrompt prompt =
                new BiometricPrompt(
                        this,
                        ContextCompat.getMainExecutor(this),
                        new BiometricPrompt.AuthenticationCallback() {

                            @Override
public void onAuthenticationSucceeded(
        BiometricPrompt.AuthenticationResult result
) {
    super.onAuthenticationSucceeded(result);

    try {
        // Obtém o Cipher validado pela autenticação biométrica.
        if (result.getCryptoObject() == null
                || result.getCryptoObject().getCipher() == null) {
            throw new IllegalStateException(
                    "Cipher biométrico indisponível."
            );
        }

        Cipher authenticatedCipher =
                result.getCryptoObject().getCipher();

        // Salva a senha usando o Cipher autenticado.
        BiometricPasswordStore.saveEncryptedPassword(
                MainActivity.this,
                authenticatedCipher,
                newPassword
        );

        // Atualiza o estado do controle biométrico.
        restoringBiometricSwitch = true;
        biometricSwitch.setChecked(true);
        restoringBiometricSwitch = false;

        biometricSettings.setEnabled(true);

        // Oculta o campo de senha.
        password.setVisibility(View.GONE);

        // Informa que a biometria foi ativada.
        toast(R.string.biometric_enabled);

    } catch (Exception e) {

        // Restaura o estado visual em caso de erro.
        restoringBiometricSwitch = true;
        biometricSwitch.setChecked(false);
        restoringBiometricSwitch = false;

        biometricSettings.setEnabled(false);

        password.setVisibility(View.VISIBLE);

        toast(R.string.biometric_setup_error);

    } finally {
        // Limpa a senha temporária da memória.
        Arrays.fill(newPassword, '\0');
    }
}

                            @Override
                            public void onAuthenticationError(
                                    int errorCode,
                                    CharSequence errString
                            ) {

                                Arrays.fill(
                                        newPassword,
                                        '\0'
                                );

                                restoringBiometricSwitch = true;
                                biometricSwitch.setChecked(false);
                                restoringBiometricSwitch = false;

                                password.setVisibility(
                                        View.VISIBLE
                                );
                            }
                        }
                );

        try {
            prompt.authenticate(
                    new BiometricPrompt.PromptInfo.Builder()
                            .setTitle(getString(R.string.biometric_title))
                            .setSubtitle(getString(R.string.biometric_register_subtitle))
                            .setNegativeButtonText(getString(android.R.string.cancel))
                            .build(),
                    new BiometricPrompt.CryptoObject(cipher)
            );
        } catch (RuntimeException e) {
            Arrays.fill(newPassword, '\0');
            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(BiometricPasswordStore.isEnabled(this));
            restoringBiometricSwitch = false;
            biometricSettings.setEnabled(BiometricPasswordStore.isEnabled(this));
            password.setVisibility(View.VISIBLE);
            toast(R.string.biometric_setup_error);
        }
    }

    private void disableBiometric() {

        new AlertDialog.Builder(this)
                .setTitle(
                        R.string.disable_biometric_title
                )
                .setMessage(
                        R.string.disable_biometric_message
                )
                .setNegativeButton(
                        android.R.string.cancel,
                        null
                )
                .setPositiveButton(
                        R.string.confirm,
                        (dialog, which) -> {

                            boolean disabled = BiometricPasswordStore.disable(this);
                            boolean stillEnabled = BiometricPasswordStore.isEnabled(this);

                            restoringBiometricSwitch = true;
                            biometricSwitch.setChecked(stillEnabled);
                            restoringBiometricSwitch = false;

                            password.setVisibility(
                                    stillEnabled ? View.GONE : View.VISIBLE
                            );
                            biometricSettings.setEnabled(stillEnabled);

                            toast(disabled
                                    ? R.string.biometric_disabled
                                    : R.string.biometric_setup_error);
                        }
                )
                .show();
    }

    private void changeBiometricPassword() {

        if (!BiometricPasswordStore.isEnabled(this)) {

            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;

            password.setVisibility(View.VISIBLE);

            return;
        }

        final Cipher cipher;

        try {

            cipher =
                    BiometricPasswordStore
                            .createDecryptionCipher(this);

        } catch (Exception e) {

            boolean stillEnabled = BiometricPasswordStore.isEnabled(this);
            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(stillEnabled);
            restoringBiometricSwitch = false;

            biometricSettings.setEnabled(stillEnabled);
            password.setVisibility(View.VISIBLE);

            toast(R.string.biometric_setup_error);

            return;
        }

        authenticateForDecryption(
                cipher,
                true,
                false
        );
    }

    
private void authenticateForDecryption(
        Cipher cipher,
        boolean showPassword,
        boolean allowManualFallback
) {

    BiometricPrompt prompt =
            new BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    new BiometricPrompt.AuthenticationCallback() {

                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result
                        ) {
                            super.onAuthenticationSucceeded(result);

                            char[] stored = null;

                            try {
                                // Obtém o Cipher validado pela biometria.
                                if (result.getCryptoObject() == null
                                        || result.getCryptoObject().getCipher() == null) {
                                    throw new IllegalStateException(
                                            "Cipher biométrico indisponível."
                                    );
                                }

                                Cipher authenticatedCipher =
                                        result.getCryptoObject().getCipher();

                                // Descriptografa a senha salva.
                                stored =
                                        BiometricPasswordStore.decryptPassword(
                                                MainActivity.this,
                                                authenticatedCipher
                                        );

                                if (showPassword) {

                                    showStoredPasswordDialog(stored);

                                } else {

                                    useBiometricPassword(stored);
                                    stored = null;
                                }

                            } catch (Exception e) {

                                // Não apaga a chave nem a senha salva
                                // por causa de um erro genérico.
                                password.setVisibility(View.VISIBLE);

                                toast(
                                        R.string.biometric_setup_error
                                );

                            } finally {

                                if (stored != null) {
                                    Arrays.fill(stored, '\0');
                                }
                            }
                        }

                        @Override
                        public void onAuthenticationError(
                                int errorCode,
                                CharSequence errString
                        ) {
                            super.onAuthenticationError(
                                    errorCode,
                                    errString
                            );

                            if (allowManualFallback) {
                                showManualPasswordFallback();
                            }
                        }
                    }
            );

    try {
        prompt.authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(R.string.biometric_title))
                        .setSubtitle(getString(R.string.biometric_unlock_subtitle))
                        .setNegativeButtonText(
                                allowManualFallback
                                        ? getString(R.string.use_manual_password)
                                        : getString(android.R.string.cancel))
                        .build(),
                new BiometricPrompt.CryptoObject(cipher)
        );
    } catch (RuntimeException e) {
        if (allowManualFallback) {
            showManualPasswordFallback();
        } else {
            toast(R.string.biometric_setup_error);
        }
    }
}

    private void useBiometricPassword(char[] stored) {

        if (inputUri == null) {

            Arrays.fill(
                    stored,
                    '\0'
            );

            toast(R.string.select_file);

            return;
        }

        clearPendingPassword();

        pendingPassword = stored;

        pickOutput();
    }

    private void showStoredPasswordDialog(char[] stored) {

        final EditText field = new EditText(this);

        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setText(CharBuffer.wrap(stored));
        field.setSelection(field.length());

        LinearLayout box = new LinearLayout(this);
        box.setPadding(50, 0, 50, 0);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(
                field,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(R.string.default_password)
                        .setMessage(
                                R.string.change_default_password_message
                        )
                        .setView(box)
                        .setNegativeButton(
                                android.R.string.cancel,
                                null
                        )
                        .setPositiveButton(
                                R.string.save,
                                null
                        )
                        .setCancelable(false)
                        .create();

        dialog.setOnShowListener(
                d -> dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {

                    if (field.getText().length() == 0) {

                        field.requestFocus();

                        toast(
                                R.string.enter_password
                        );

                        return;
                    }

                    if (field.getText().length() <
                            MIN_PASSWORD_LENGTH) {

                        field.requestFocus();

                        toast(
                                R.string.short_password
                        );

                        return;
                    }

                    char[] replacement = copyPassword(field);

                    dialog.dismiss();

                    Arrays.fill(
                            stored,
                            '\0'
                    );

                    authenticateForReplacement(
                            replacement
                    );
                })
        );

        dialog.setOnDismissListener(
                d -> Arrays.fill(
                        stored,
                        '\0'
                )
        );

        dialog.show();
    }

    
private void authenticateForReplacement(
        char[] replacement
) {

    final Cipher cipher;

    try {

        cipher =
                BiometricPasswordStore
                        .createEncryptionCipher(this);

    } catch (Exception e) {

        Arrays.fill(replacement, '\0');

        toast(
                R.string.biometric_setup_error
        );

        return;
    }

    BiometricPrompt prompt =
            new BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    new BiometricPrompt.AuthenticationCallback() {

                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result
                        ) {
                            super.onAuthenticationSucceeded(result);

                            try {

                                // Obtém o Cipher validado pela biometria.
                                if (result.getCryptoObject() == null
                                        || result.getCryptoObject().getCipher() == null) {
                                    throw new IllegalStateException(
                                            "Cipher biométrico indisponível."
                                    );
                                }

                                Cipher authenticatedCipher =
                                        result.getCryptoObject().getCipher();

                                // Salva a nova senha com o Cipher autenticado.
                                BiometricPasswordStore
                                        .saveEncryptedPassword(
                                                MainActivity.this,
                                                authenticatedCipher,
                                                replacement
                                        );

                                toast(
                                        R.string.password_changed
                                );

                            } catch (Exception e) {

                                // Não desativa nem apaga a configuração
                                // biométrica por um erro genérico.
                                toast(
                                        R.string.biometric_setup_error
                                );

                            } finally {

                                Arrays.fill(
                                        replacement,
                                        '\0'
                                );
                            }
                        }

                        @Override
                        public void onAuthenticationError(
                                int errorCode,
                                CharSequence errString
                        ) {
                            super.onAuthenticationError(
                                    errorCode,
                                    errString
                            );

                            Arrays.fill(
                                    replacement,
                                    '\0'
                            );
                        }
                    }
            );

    try {
        prompt.authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(R.string.biometric_title))
                        .setSubtitle(getString(R.string.biometric_change_subtitle))
                        .setNegativeButtonText(getString(android.R.string.cancel))
                        .build(),
                new BiometricPrompt.CryptoObject(cipher)
        );
    } catch (RuntimeException e) {
        Arrays.fill(replacement, '\0');
        toast(R.string.biometric_setup_error);
    }
}

    /* =========================================================
       SELEÇÃO DE ARQUIVO / PASTA
       ========================================================= */

    private void pickFile() {

        if (metadataCleaningInProgress) {
                return;
        }

        Intent i =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        i.setType("*/*");

        i.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION |
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
);

        startActivityForResult(
                i,
                PICK_FILE
        );
       
    }

    private void pickFolder() {

        if (metadataCleaningInProgress) {
                return;
        }

        Intent i =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT_TREE
                );

        i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        startActivityForResult(
                i,
                PICK_FOLDER
        );
    }

    private void pickOutput() {

        boolean encryptedFolder =
                decryptMode &&
                isFolderEncryptedName(
                        name(inputUri)
                );

        if (inputIsFolder) {

            if (decryptMode) {
                pickOutputFolder();
            } else {
                pickOutputFile();
            }

            return;
        }

        if (encryptedFolder) {

            pickOutputFolder();

            return;
        }

        pickOutputFile();
    }

    private void pickOutputFile() {

        String inputName =
                name(inputUri);

        String outputName;

        if (decryptMode) {

            if (isFolderEncryptedName(inputName)) {

                outputName =
                        inputName.substring(
                                0,
                                inputName.length()
                                        - "_pasta.aes".length()
                        );

            } else if (
                    inputName
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".aes")
            ) {

                outputName =
                        inputName.substring(
                                0,
                                inputName.length() - 4
                        );

            } else {

                outputName = inputName;
            }

        } else {

            if (inputIsFolder) {

                outputName =
                        inputName + "_pasta.aes";

            } else if (
                    inputName
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".aes")
            ) {

                outputName = inputName;

            } else {

                outputName =
                        inputName + ".aes";
            }
        }

        Intent i =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );

        i.setType(
                "application/octet-stream"
        );

        i.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        i.putExtra(
                Intent.EXTRA_TITLE,
                outputName
        );

        i.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivityForResult(
                i,
                CREATE_OUTPUT
        );
    }

    private void pickOutputFolder() {

        Intent i =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT_TREE
                );

        i.putExtra(
                "android.content.extra.SHOW_ADVANCED",
                true
        );

        i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        startActivityForResult(
                i,
                PICK_OUTPUT_FOLDER
        );
    }

    private void requestErasurePdf(ErasureRecord record) {

    pendingErasureRecord = record;

    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

    intent.addCategory(Intent.CATEGORY_OPENABLE);

    intent.setType("application/pdf");

    intent.putExtra(
            Intent.EXTRA_TITLE,
            "Relatorio_Exclusao-" + record.reportId + ".pdf"
    );

    startActivityForResult(
            intent,
            CREATE_ERASURE_PDF
    );
}

    /* =========================================================
       RESULTADOS DOS SELETORES
       ========================================================= */

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == CREATE_ERASURE_PDF) {

    if (resultCode != RESULT_OK
            || data == null
            || data.getData() == null) {

        pendingErasureRecord = null;
        return;
    }

    Uri destination = data.getData();

    persistWritePermission(destination, data);

    ErasureRecord record = pendingErasureRecord;
    pendingErasureRecord = null;

    if (record == null) {
        Toast.makeText(
                this,
                "Registro do relatório indisponível.",
                Toast.LENGTH_LONG
        ).show();
        return;
    }

    executor.execute(() -> {

        try (OutputStream output =
                     getContentResolver().openOutputStream(
                             destination,
                             "w"
                     )) {

            if (output == null) {
                throw new IOException(
                        "Não foi possível abrir o destino."
                );
            }

            ErasureReport.exportPdf(
                    getApplicationContext(),
                    output,
                    record
            );

            runOnUiThread(() ->
                    Toast.makeText(
                            MainActivity.this,
                            "Relatório PDF salvo com sucesso.",
                            Toast.LENGTH_LONG
                    ).show()
            );

        } catch (Exception e) {

            runOnUiThread(() ->
                    Toast.makeText(
                            MainActivity.this,
                            "Falha ao salvar o PDF: "
                                    + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show()
            );
        }
    });

    return;
}

        if (requestCode == CREATE_METADATA_OUTPUT) {

    if (resultCode != RESULT_OK
            || data == null
            || data.getData() == null) {

        deleteQuietly(metadataTempFile);

        metadataTempFile = null;
        metadataOutputName = null;
        metadataCleaningInProgress = false;

        setBusy(false);

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        return;
    }

    Uri destination = data.getData();

    persistWritePermission(destination, data);

    saveMetadataOutput(destination);

    return;
}

        if (
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null
        ) {

            if (
                    requestCode == CREATE_OUTPUT ||
                    requestCode == PICK_OUTPUT_FOLDER
            ) {
                clearPendingPassword();
            }

            return;
        }

        if (requestCode == PICK_FILE) {

            inputUri = data.getData();

            persistReadPermission(
                    inputUri,
                    data
            );

            inputMode = MODE_FILE;
            inputIsFolder = false;           

            String selectedName =
                    name(inputUri);

            fileName.setText(
                    selectedName
            );

            decryptMode = selectedName
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".aes");
        
            action.setText(
                    decryptMode
                            ? R.string.decrypt
                            : R.string.encrypt
            );

            return;
        }

        if (requestCode == PICK_FOLDER) {
            
            inputUri = data.getData();

            persistTreePermission(
                    inputUri,
                    data
            );

            inputMode = MODE_FOLDER;
            inputIsFolder = true;

            String selectedName =
                    treeName(inputUri);

            fileName.setText(
                    selectedName
            );

            decryptMode = false;

            action.setText(
                    R.string.encrypt
            );

            return;
        }

        if (requestCode == CREATE_OUTPUT) {

            outputUri = data.getData();
            outputDocumentCreatedByApp = true;

            persistWritePermission(
                    outputUri,
                    data
            );

            startPendingWork();

            return;
        }

        if (requestCode == PICK_OUTPUT_FOLDER) {

            outputUri = data.getData();
            outputDocumentCreatedByApp = false;

            persistTreePermission(
                    outputUri,
                    data
            );

            startPendingWork();
        }
    }

    private void persistReadPermission(
        Uri uri,
        Intent data
) {

    try {

        int flags =
                data.getFlags()
                        & (
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );

        if (flags != 0) {

            getContentResolver()
                    .takePersistableUriPermission(
                            uri,
                            flags
                    );
        }

    } catch (Exception ignored) {
    }
}

    private void persistWritePermission(
            Uri uri,
            Intent data
    ) {

        try {

            int flags =
                    data.getFlags()
                            & (
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );

            if (flags != 0) {

                getContentResolver()
                        .takePersistableUriPermission(
                                uri,
                                flags
                        );
            }

        } catch (Exception ignored) {
        }
    }

    private void persistTreePermission(
            Uri uri,
            Intent data
    ) {

        try {

            int flags =
                    data.getFlags()
                            & (
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );

            if (flags != 0) {

                getContentResolver()
                        .takePersistableUriPermission(
                                uri,
                                flags
                        );
            }

        } catch (Exception ignored) {
        }
    }

    
private void startMetadataCleaning() {

    if (inputUri == null || inputIsFolder || decryptMode) {
        fail("Selecione um arquivo válido para remover os metadados.");
        return;
    }

    if (metadataCleaningInProgress) {
        return;
    }

    metadataCleaningInProgress = true;

    final Uri sourceUri = inputUri;
    final String originalName = name(sourceUri);

    // Identifica vídeos pelo formato da extensão.
    String lowerName = originalName == null
            ? ""
            : originalName.toLowerCase(java.util.Locale.ROOT);

    final boolean isVideo =
            lowerName.endsWith(".mp4")
            || lowerName.endsWith(".m4v")
            || lowerName.endsWith(".3gp");

    setBusy(true);
    progressText.setText("Preparando...");

    getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    );

    executor.execute(() -> {

        File inputTemp = null;
        File cleanedTemp = null;

        try {

            inputTemp = File.createTempFile(
                    "sfe_meta_in_",
                    ".tmp",
                    getCacheDir()
            );

            cleanedTemp = File.createTempFile(
                    "sfe_meta_out_",
                    ".tmp",
                    getCacheDir()
            );

            // Copia o original para o cache privado.
            copyUriToFile(
                    sourceUri,
                    inputTemp
            );

            // Encaminha para o limpador correto.
            if (isVideo) {

                VideoMetadataCleaner.clean(
                        MainActivity.this,
                        inputTemp,
                        cleanedTemp
                );

            } else {

                MetadataCleaner.clean(
                        inputTemp,
                        cleanedTemp
                );
            }

            if (!cleanedTemp.isFile()
                    || cleanedTemp.length() <= 0) {

                throw new IOException(
                        "Não foi possível validar o arquivo limpo."
                );
            }

            final File result = cleanedTemp;
            cleanedTemp = null;

            runOnUiThread(() -> {

                metadataTempFile = result;

                metadataOutputName =
                        "no-mtd_" + originalName;

                setBusy(false);

                getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );

                pickMetadataOutput();

            });

        } catch (Exception e) {

            runOnUiThread(() -> {

                setBusy(false);

                getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );

                deleteQuietly(metadataTempFile);

                metadataTempFile = null;
                metadataOutputName = null;
                metadataCleaningInProgress = false;

                String errorMessage = e.getMessage();

                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.getClass().getSimpleName();
                }

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Não foi possível limpar")
                        .setMessage(
                                "Não foi possível remover os metadados deste arquivo.\n\n"
                                + errorMessage
                        )
                        .setPositiveButton(
                                android.R.string.ok,
                                null
                        )
                        .setCancelable(false)
                        .show();
            });

        } finally {

            deleteQuietly(inputTemp);
            deleteQuietly(cleanedTemp);
        }
    });
}

private void pickMetadataOutput() {

    try {

        Intent intent = new Intent(
                Intent.ACTION_CREATE_DOCUMENT
        );

        intent.setType("application/octet-stream");

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.putExtra(
                Intent.EXTRA_TITLE,
                metadataOutputName
        );

        intent.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivityForResult(
                intent,
                CREATE_METADATA_OUTPUT
        );

    } catch (Exception e) {

        deleteQuietly(metadataTempFile);

        metadataTempFile = null;
        metadataOutputName = null;
        metadataCleaningInProgress = false;

        setBusy(false);

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        fail(
                "Não foi possível abrir o seletor de destino."
        );
    }
}

private void saveMetadataOutput(Uri destination) {

    final File source = metadataTempFile;

    if (destination == null
            || source == null
            || !source.isFile()) {

        deleteQuietly(source);

        metadataTempFile = null;
        metadataOutputName = null;
        metadataCleaningInProgress = false;

        fail("Arquivo temporário ou destino indisponível.");
        return;
    }

    setBusy(true);

    getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    );

    executor.execute(() -> {

        boolean saved = false;
        boolean partialDeleted = true;

        try {

            copyToOutput(
                    source,
                    destination,
                    false
            );

            saved = true;

        } catch (Exception e) {

            try {

                partialDeleted =
                        DocumentsContract.deleteDocument(
                                getContentResolver(),
                                destination
                        );

            } catch (Exception ignored) {

                partialDeleted = false;
            }

        } finally {

            deleteQuietly(source);

            metadataTempFile = null;
            metadataOutputName = null;
        }

        final boolean success = saved;
        final boolean outputCleaned = partialDeleted;

        runOnUiThread(() -> {

            metadataCleaningInProgress = false;

            setBusy(false);

            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );

            if (success) {

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("METADADOS REMOVIDOS!")
                        .setMessage(
                                "Arquivo salvo com sucesso."
                        )
                        .setPositiveButton(
                                android.R.string.ok,
                                null
                        )
                        .setCancelable(false)
                        .show();

            } else {

                String message =
                        "Não foi possível salvar o arquivo limpo.";

                if (!outputCleaned) {
                    message += "\n\nNão foi possível excluir o arquivo incompleto. Verifique o destino e remova-o manualmente.";
                }

                fail(message);
            }
        });
    });
}

    private void startPendingWork() {

        final char[] pass =
                pendingPassword;

        pendingPassword = null;

        password.getText().clear();

        if (pass == null) {
            return;
        }

        final boolean decrypt =
                decryptMode;

        final boolean folder =
                inputIsFolder ||
                (
                        decrypt &&
                        isFolderEncryptedName(
                                name(inputUri)
                        )
                );

        setBusy(true);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        executor.execute(
                () -> doWork(
                        pass,
                        decrypt,
                        folder
                )
        );
    }

    /* =========================================================
       INÍCIO DA OPERAÇÃO
       ========================================================= */

    private void startWork() {

        if (metadataCleaningInProgress) {
           return;
        }

        if (inputUri == null) {

            toast(
                    R.string.select_file
            );

            return;
        }

        if (
                BiometricPasswordStore
                        .isEnabled(this)
        ) {

            unlockWithBiometric();

            return;
        }

        int passwordLength =
                password.getText().length();

        if (passwordLength == 0) {

            password.requestFocus();

            toast(
                    R.string.enter_password
            );

            return;
        }

        /*
         * A senha mínima de 12 caracteres é exigida
         * somente para novas criptografias.
         *
         * Na descriptografia, senhas antigas menores
         * continuam sendo aceitas.
         */
        if (!decryptMode &&
                passwordLength < MIN_PASSWORD_LENGTH) {

            password.requestFocus();

            toast(
                    R.string.short_password
            );

            return;
        }

        pendingPassword = copyPassword(password);

        pickOutput();
    }

    private void unlockWithBiometric() {

        final Cipher cipher;

        
try {

    cipher =
            BiometricPasswordStore
                    .createDecryptionCipher(this);

} catch (Exception e) {

    // Mantém a configuração biométrica salva.
    password.setVisibility(View.VISIBLE);

    toast(
            R.string.biometric_setup_error
    );

    showManualPasswordFallback();

    return;
}

        authenticateForDecryption(
                cipher,
                false,
                true
        );
    }

    private void showManualPasswordFallback() {

        if (inputUri == null) {

            toast(
                    R.string.select_file
            );

            return;
        }

        final EditText field =
                new EditText(this);

        field.setSingleLine(true);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
        );
        field.setHint(
                R.string.password_hint
        );

        LinearLayout box =
                new LinearLayout(this);

        box.setPadding(
                50,
                0,
                50,
                0
        );

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        box.addView(
                field,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.manual_password
                        )
                        .setView(box)
                        .setNegativeButton(
                                android.R.string.cancel,
                                null
                        )
                        .setPositiveButton(
                                R.string.continue_button,
                                null
                        )
                        .create();

        dialog.setOnShowListener(
                d -> dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {

                    // Mantém a mesma política de senha usada pelo fluxo normal:
                    // novas criptografias exigem MIN_PASSWORD_LENGTH; descriptografia
                    // aceita senhas antigas mais curtas para preservar compatibilidade.
                    if (!decryptMode
                            && field.getText().length() < MIN_PASSWORD_LENGTH) {

                        field.requestFocus();
                        toast(R.string.short_password);
                        return;
                    }

                    clearPendingPassword();

                    pendingPassword = copyPassword(field);

                    dialog.dismiss();

                    pickOutput();
                })
        );

        dialog.show();
    }

    /* =========================================================
       PROCESSAMENTO
       ========================================================= */

    private void doWork(
            char[] pass,
            boolean decrypt,
            boolean folder
    ) {

        File tempInput = null;
        File tempZip = null;
        File tempOutput = null;

        try {

            tempInput =
                    File.createTempFile(
                            "sfe_input_",
                            ".tmp",
                            getCacheDir()
                    );

            if (folder && !decrypt) {

                tempZip =
                        File.createTempFile(
                                "sfe_zip_",
                                ".zip",
                                getCacheDir()
                        );

                zipTotalBytes = 0;
zipProcessedBytes = 0;
zipActualBytes = 0;
zipEntryCount = 0;

calculateZipTotal(
        inputUri,
        treeDocumentId(inputUri),
        ""
);

// A contagem do pré-cálculo não deve ser somada
// novamente à contagem durante a compactação.
zipEntryCount = 0;

updateZipProgress(0);

zipTree(
        inputUri,
        tempZip
);
                updateZipProgress(100);

                copyFile(
                        tempZip,
                        tempInput
                );

            } else {

                copyUriToFile(
                        inputUri,
                        tempInput
                );
            }

            long total =
                    tempInput.length();

            if (total < 1) {

                throw new Exception(
                        "Arquivo vazio."
                );
            }

            tempOutput =
                    File.createTempFile(
                            decrypt
                                    ? "sfe_dec_"
                                    : "sfe_enc_",
                            ".tmp",
                            getCacheDir()
                    );

            if (decrypt) {

                updateCryptoProgress(
                        0,
                        total,
                        true
                );

                try (
                        InputStream verify =
                                new FileInputStream(
                                        tempInput
                                );

                        InputStream data =
                                new FileInputStream(
                                        tempInput
                                );

                        OutputStream realOut =
                                new FileOutputStream(
                                        tempOutput,
                                        false
                                )
                ) {

                    CryptoEngine.decrypt(
                            verify,
                            data,
                            realOut,
                            pass,
                            total,
                            value -> updateCryptoProgress(
                                    value,
                                    total,
                                    true
                            ),
                            getCacheDir()
                    );
                }

            } else {

                updateCryptoProgress(
                        0,
                        total,
                        false
                );

                try (
                        InputStream is =
                                new FileInputStream(
                                        tempInput
                                );

                        OutputStream os =
                                new FileOutputStream(
                                        tempOutput,
                                        false
                                )
                ) {

                    CryptoEngine.encrypt(
                            is,
                            os,
                            pass,
                            total,
                            value -> updateCryptoProgress(
                                    value,
                                    total,
                                    false
                            )
                    );
                }
            }

            if (decrypt && folder) {

                unzipTotalBytes =
                        calculateUnzipTotal(
                                tempOutput
                        );

                unzipProcessedBytes = 0;

                updateUnzipProgress(0);

                unzipToTree(
                        tempOutput,
                        outputUri
                );

                updateUnzipProgress(100);

            } else {

                copyToOutput(
                        tempOutput,
                        outputUri,
                        decrypt
                );
            }

            deleteQuietly(tempInput);
            deleteQuietly(tempZip);
            deleteQuietly(tempOutput);

            outputDocumentCreatedByApp = false;

            runOnUiThread(() -> {

                setBusy(false);

                getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );

                new AlertDialog.Builder(MainActivity.this)
        .setTitle(
                decrypt
                        ? "🔓 DESCRIPTOGRAFADO!"
                        : "🔒 CRIPTOGRAFADO!"
        )
        .setMessage(
                decrypt
                        ? R.string.decrypted
                        : R.string.encrypted
        )
        .setCancelable(false)
        .setPositiveButton(
                android.R.string.ok,
                (dialog, which) -> dialog.dismiss()
        )
        .show();
            });

        } catch (
                CryptoEngine.WrongPasswordException e
        ) {

            cleanup(
                    tempInput,
                    tempZip,
                    tempOutput
            );
            
            boolean outputCleaned = deleteOutputQuietly();

            fail(
                    outputCleaned
                            ? getString(R.string.wrong_password)
                            : getString(R.string.wrong_password)
                                    + "\n\nNão foi possível remover o arquivo de saída incompleto. Verifique e exclua-o manualmente no local escolhido."
            );

        } catch (
                CryptoEngine.CorruptFileException e
        ) {

            cleanup(
                    tempInput,
                    tempZip,
                    tempOutput
            );
            
            boolean outputCleaned = deleteOutputQuietly();

            fail(
                    outputCleaned
                            ? getString(R.string.corrupt_file)
                            : getString(R.string.corrupt_file)
                                    + "\n\nNão foi possível remover o arquivo de saída incompleto. Verifique e exclua-o manualmente no local escolhido."
            );

        
} catch (Exception e) {

    cleanup(
            tempInput,
            tempZip,
            tempOutput
    );

    boolean outputCleaned = deleteOutputQuietly();

    // Não exibe mensagens de exceção/caminhos internos na interface.
    String errorMessage = "Não foi possível concluir a operação. Verifique a senha, o arquivo e o espaço disponível.";
    if (!outputCleaned) {
        errorMessage += "\n\nNão foi possível remover o arquivo de saída incompleto. Verifique e exclua-o manualmente no local escolhido.";
    }
    fail(errorMessage);

} finally {
            // Limpeza incondicional: também ocorre se uma etapa lançar exceção.
            deleteQuietly(tempInput);
            deleteQuietly(tempZip);
            deleteQuietly(tempOutput);
            Arrays.fill(pass, '\0');
        }
    }

    /*
     * Recebe bytes processados e calcula o percentual.
     *
     * O valor permanece como long para suportar arquivos grandes.
     */
    private void updateCryptoProgress(
            long value,
            long total,
            boolean decrypting
    ) {

        runOnUiThread(() -> {

            int percent;

            if (total <= 0) {

                percent = 0;

            } else {

                percent =
                        (int) Math.min(
                                100L,
                                (value * 100L) / total
                        );
            }

            progress.setProgress(
                    percent
            );
                    
            progressText.setText(
                    getString(
                            decrypting
                                    ? R.string.decrypting_progress
                                    : R.string.encrypting_progress,
                            percent
                    )
            );
        
        });
    }

    private void updateSecureDeleteProgress(
        long bytesWritten,
        long totalBytes
) {

    runOnUiThread(() -> {

        int percent = 0;

        if (totalBytes > 0) {

            percent = (int) Math.min(
                    100.0,
                    ((double) bytesWritten / totalBytes) * 100.0
            );
        }

        progress.setProgress(percent);

        progressText.setText(
                "Sobrescrevendo arquivo: " + percent + "%"
        );
    });
}

    private void updateZipProgress(
            int value
    ) {

        runOnUiThread(() -> {

            progress.setProgress(
                    value
            );

            progressText.setText(
                    getString(
                            R.string.compressing_progress,
                            value
                    )
            );
        });
    }

    private void updateUnzipProgress(
            int value
    ) {

        runOnUiThread(() -> {

            progress.setProgress(
                    value
            );

            progressText.setText(
                    getString(
                            R.string.decompressing_progress,
                            value
                    )
            );
        });
    }

    private void cleanup(
            File a,
            File b,
            File c
    ) {

        deleteQuietly(a);
        deleteQuietly(b);
        deleteQuietly(c);
    }

    /* =========================================================
       CÁLCULO DO TAMANHO DO ZIP
       ========================================================= */

    private void calculateZipTotal(
        Uri treeUri,
        String parentDocumentId,
        String relativePath
) throws Exception {

    Uri childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentDocumentId
            );

    String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };

    try (Cursor c = getContentResolver().query(
            childrenUri,
            projection,
            null,
            null,
            null
    )) {

        if (c == null) {
            throw new Exception(
                    "Não foi possível ler a pasta."
            );
        }

        while (c.moveToNext()) {

    String id = c.getString(0);
    String childName = c.getString(1);
    String mime = c.getString(2);

    Uri childUri =
            DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    id
            );

    // Ignora o arquivo de saída antes de contar a entrada.
    if (isOutputUri(childUri)) {
        continue;
    }

    zipEntryCount++;

    if (zipEntryCount > MAX_ZIP_ENTRIES) {
        throw new Exception(
                "A pasta excede o limite de "
                        + MAX_ZIP_ENTRIES
                        + " entradas."
        );
    }

            if (childName == null
                    || childName.isEmpty()
                    || childName.contains("/")
                    || childName.contains("\\")) {
                throw new Exception(
                        "A pasta contém um nome de arquivo inválido."
                );
            }

            String path = relativePath.isEmpty()
                    ? childName
                    : relativePath + "/" + childName;

            boolean directory = isDirectoryMime(mime);

            validateZipEntryPath(path, directory);            

            if (directory) {

                calculateZipTotal(
                        treeUri,
                        id,
                        path
                );

            } else {

                long size = 0;

                if (!c.isNull(3)) {
                    size = c.getLong(3);
                }

                // Tamanho desconhecido é validado durante
                // a leitura real do arquivo.
                if (size < 0) {
                    size = 0;
                }

                if (size > MAX_ZIP_ENTRY_SIZE) {
                    throw new Exception(
                            "O arquivo "
                                    + childName
                                    + " excede o limite de 1 GiB."
                    );
                }

                if (size > MAX_ZIP_TOTAL_SIZE - zipTotalBytes) {
                    throw new Exception(
                            "A pasta excede o limite total de 2 GiB."
                    );
                }

                zipTotalBytes += size;
            }
        }
    }
}

    private boolean isDirectoryMime(
            String mime
    ) {

        return DocumentsContract.Document.MIME_TYPE_DIR
                .equals(mime);
    }

    /* =========================================================
       ZIP DA PASTA
       ========================================================= */

    private void zipTree(
            Uri treeUri,
            File zipFile
    ) throws Exception {

        try (
                OutputStream fos =
                        new FileOutputStream(
                                zipFile,
                                false
                        );

                ZipOutputStream zos =
                        new ZipOutputStream(fos)
        ) {

            zipChildren(
                    treeUri,
                    treeDocumentId(treeUri),
                    "",
                    zos
            );

            zos.finish();
        }
    }

    private void zipChildren(
            Uri treeUri,
            String parentDocumentId,
            String relativePath,
            ZipOutputStream zos
    ) throws Exception {

        Uri childrenUri =
                DocumentsContract
                        .buildChildDocumentsUriUsingTree(
                                treeUri,
                                parentDocumentId
                        );

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (
                Cursor c =
                        getContentResolver()
                                .query(
                                        childrenUri,
                                        projection,
                                        null,
                                        null,
                                        null
                                )
        ) {

            if (c == null) {

                throw new Exception(
                        "Não foi possível ler a pasta."
                );
            }

            while (c.moveToNext()) {

                String id = c.getString(0);
String childName = c.getString(1);
String mime = c.getString(2);

if (childName == null
        || childName.isEmpty()
        || childName.contains("/")
        || childName.contains("\\")) {

    throw new Exception(
            "A pasta contém um nome de arquivo inválido."
    );
}

String path = relativePath.isEmpty()
        ? childName
        : relativePath + "/" + childName;

Uri childUri =
        DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                id
        );

if (isOutputUri(childUri)) {
    continue;
}

boolean directory = isDirectoryMime(mime);

validateZipEntryPath(path, directory);

zipEntryCount++;

if (zipEntryCount > MAX_ZIP_ENTRIES) {
    throw new Exception(
            "A pasta excede o limite de "
                    + MAX_ZIP_ENTRIES
                    + " entradas."
    );
}

                if (isDirectoryMime(mime)) {

                    ZipEntry dirEntry =
                            new ZipEntry(
                                    path + "/"
                            );

                    zos.putNextEntry(
                            dirEntry
                    );

                    zos.closeEntry();

                    zipChildren(
                            treeUri,
                            id,
                            path,
                            zos
                    );

                } else {

                    ZipEntry entry =
                            new ZipEntry(path);

                    zos.putNextEntry(entry);
                    long entryBytes = 0;

                    try (
                            InputStream in =
                                    getContentResolver()
                                            .openInputStream(
                                                    childUri
                                            )
                    ) {

                        if (in == null) {

                            throw new Exception(
                                    "Não foi possível abrir "
                                            + childName
                            );
                        }

                        byte[] buffer =
                                new byte[8192];

                        int n;

                        while ((n = in.read(buffer)) != -1) {

    if (n > MAX_ZIP_ENTRY_SIZE - entryBytes) {
        throw new Exception(
                "O arquivo "
                        + childName
                        + " excede o limite de 1 GiB."
        );
    }

    if (n > MAX_ZIP_TOTAL_SIZE - zipActualBytes) {
        throw new Exception(
                "A compactação excede o limite total de 2 GiB."
        );
    }

    zos.write(buffer, 0, n);

    entryBytes += n;
    zipActualBytes += n;
    zipProcessedBytes += n;

    updateZipProgressFromBytes();
}
                    }

                    zos.closeEntry();
                }
            }
        }
    }

    private void updateZipProgressFromBytes() {

        int value;

        if (zipTotalBytes <= 0) {

            value = 100;

        } else {

            value =
                    (int) Math.min(
                            100,
                            (zipProcessedBytes * 100)
                                    / zipTotalBytes
                    );
        }

        updateZipProgress(value);
    }

    /*
     * Verifica se uma URI corresponde exatamente ao arquivo
     * de saída que está sendo criado.
     */
    private boolean isOutputUri(Uri uri) {

        if (uri == null || outputUri == null) {
            return false;
        }

        try {

            String uriAuthority =
                    uri.getAuthority();

            String outputAuthority =
                    outputUri.getAuthority();

            if (uriAuthority == null ||
                    outputAuthority == null ||
                    !uriAuthority.equals(
                            outputAuthority
                    )) {

                return false;
            }

            String childId =
                    DocumentsContract
                            .getDocumentId(uri);

            String outputId =
                    DocumentsContract
                            .getDocumentId(outputUri);

            return childId.equals(outputId);

        } catch (Exception e) {

            return uri.equals(outputUri);
        }
    }

    /* =========================================================
       CÁLCULO DA DESCOMPACTAÇÃO
       ========================================================= */

    

private long calculateUnzipTotal(
        File zipFile
) throws Exception {

    long total = 0;
    int entryCount = 0;

    try (ZipFile zip = new ZipFile(zipFile)) {

        java.util.Enumeration<? extends ZipEntry> entries =
                zip.entries();

        while (entries.hasMoreElements()) {

            ZipEntry entry = entries.nextElement();
            entryCount++;

            if (entryCount > MAX_ZIP_ENTRIES) {
                throw new Exception(
                        "O ZIP excede o limite de "
                                + MAX_ZIP_ENTRIES
                                + " entradas."
                );
            }

            String entryName = entry.getName();

            // Rejeita barras invertidas no nome original.
            if (entryName == null
                    || entryName.isEmpty()
                    || entryName.indexOf('\\') >= 0
                    || !isSafeZipPath(entryName)) {

                throw new Exception(
                        "O ZIP contém um caminho inválido ou não seguro."
                );
            }

            String path = entryName;

            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            if (path.isEmpty()
                    || path.length() > MAX_ZIP_PATH_LENGTH) {

                throw new Exception(
                        "O ZIP contém um caminho vazio ou muito longo."
                );
            }

            int slashCount = 0;

            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    slashCount++;
                }
            }

            int depth = slashCount + 1;

            if (depth > MAX_ZIP_DEPTH) {
                throw new Exception(
                        "O ZIP excede o limite de "
                                + MAX_ZIP_DEPTH
                                + " níveis."
                );
            }

            if (entry.isDirectory()) {
                continue;
            }

            long size = entry.getSize();

            // O tamanho real também deve ser limitado
            // durante a extração.
            if (size < 0) {
                continue;
            }

            if (size > MAX_ZIP_ENTRY_SIZE) {
                throw new Exception(
                        "O arquivo "
                                + path
                                + " excede o limite de 1 GiB."
                );
            }

            // Evita overflow durante a soma.
            if (size > MAX_ZIP_TOTAL_SIZE - total) {
                throw new Exception(
                        "O ZIP excede o limite total de 2 GiB."
                );
            }

            total += size;
        }
    }

    return total;
}


    /* =========================================================
       DESCOMPACTAÇÃO DA PASTA
       ========================================================= */

    
private void unzipToTree(
        File zipFile,
        Uri destinationTree
) throws Exception {

    String originalName = name(inputUri);
    String suffix = "_pasta.aes";

    if (originalName == null || !originalName.endsWith(suffix)) {
        throw new Exception("Nome do arquivo inválido.");
    }

    String folderName = originalName.substring(
            0,
            originalName.length() - suffix.length()
    );

    if (folderName.isEmpty()) {
        throw new Exception("Nome da pasta inválido.");
    }

    // Registra exclusivamente os documentos criados nesta extração.
    java.util.List<Uri> createdUris = new java.util.ArrayList<>();
    java.util.Map<String, Uri> directories = new java.util.HashMap<>();

    // Entradas explícitas do ZIP: impede nomes repetidos.
    java.util.Set<String> seenEntries = new java.util.HashSet<>();

    // true = diretório; false = arquivo.
    // Também registra diretórios implícitos.
    java.util.Map<String, Boolean> entryTypes =
            new java.util.HashMap<>();

    Uri outputFolder = createDirectory(destinationTree, folderName);

    if (outputFolder == null) {
        throw new Exception(
                "Não foi possível criar a pasta de destino."
        );
    }

    createdUris.add(outputFolder);
    directories.put("", outputFolder);

    long totalExtracted = 0;
    int entryCount = 0;

    try (
            InputStream fis = new FileInputStream(zipFile);
            ZipInputStream zis = new ZipInputStream(fis)
    ) {

        ZipEntry entry;
        byte[] buffer = new byte[8192];

        while ((entry = zis.getNextEntry()) != null) {

            entryCount++;

            if (entryCount > MAX_ZIP_ENTRIES) {
                throw new Exception(
                        "O ZIP excede o limite de "
                                + MAX_ZIP_ENTRIES + " entradas."
                );
            }

            
String entryName = entry.getName();

// Rejeita barras invertidas antes de qualquer normalização.
if (entryName == null
        || entryName.isEmpty()
        || entryName.indexOf('\\') >= 0
        || !isSafeZipPath(entryName)) {

    throw new Exception(
            "O ZIP contém um caminho inválido ou não seguro."
    );
}

            boolean isDirectory = entry.isDirectory();

            // Normaliza o caminho removendo barras finais.
            String path = entryName;

            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            if (path.isEmpty()) {
                throw new Exception(
                        "O ZIP contém um caminho vazio."
                );
            }

            // Limite de comprimento.
            if (path.length() > MAX_ZIP_PATH_LENGTH) {
                throw new Exception(
                        "O ZIP contém um caminho muito longo."
                );
            }

            // Limite de profundidade.
            int slashCount = 0;

            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    slashCount++;
                }
            }

            int depth = slashCount + 1;

            if (depth > MAX_ZIP_DEPTH) {
                throw new Exception(
                        "O ZIP excede o limite de "
                                + MAX_ZIP_DEPTH
                                + " níveis de pastas."
                );
            }

            // Impede entradas explícitas repetidas.
            if (!seenEntries.add(path)) {
                throw new Exception(
                        "O ZIP contém caminhos duplicados."
                );
            }

            /*
             * Registra os diretórios ancestrais.
             * Um caminho que já seja arquivo não pode ser diretório.
             */
            int slash = path.indexOf('/');

            while (slash >= 0) {

                String parentPath = path.substring(0, slash);
                Boolean parentType = entryTypes.get(parentPath);

                if (Boolean.FALSE.equals(parentType)) {
                    throw new Exception(
                            "O ZIP contém conflito entre arquivo e pasta."
                    );
                }

                entryTypes.put(parentPath, true);

                slash = path.indexOf('/', slash + 1);
            }

            // Verifica conflito entre o caminho atual e entradas anteriores.
            Boolean existingType = entryTypes.get(path);

            if (isDirectory) {

                if (Boolean.FALSE.equals(existingType)) {
                    throw new Exception(
                            "O ZIP contém conflito entre arquivo e pasta."
                    );
                }

                // Pode já existir como diretório implícito.
                entryTypes.put(path, true);

            } else {

                if (existingType != null) {
                    throw new Exception(
                            "O ZIP contém conflito entre arquivo e pasta."
                    );
                }

                entryTypes.put(path, false);
            }

            if (isDirectory) {

                createZipDirectories(
                        path,
                        outputFolder,
                        directories,
                        createdUris
                );

            } else {

                int lastSlash = path.lastIndexOf('/');

                String parentPath = lastSlash >= 0
                        ? path.substring(0, lastSlash)
                        : "";

                Uri parent = parentPath.isEmpty()
                        ? outputFolder
                        : createZipDirectories(
                                parentPath,
                                outputFolder,
                                directories,
                                createdUris
                        );

                String fileName = lastSlash >= 0
                        ? path.substring(lastSlash + 1)
                        : path;

                if (fileName.isEmpty()) {
                    throw new Exception(
                            "Nome de arquivo inválido."
                    );
                }

                Uri fileUri = createFile(parent, fileName);

                if (fileUri == null) {
                    throw new Exception(
                            "Não foi possível criar " + fileName
                    );
                }

                // Registra antes de começar a gravar.
                createdUris.add(fileUri);

                long entryExtracted = 0;

                try (
                        OutputStream out =
                                getContentResolver().openOutputStream(fileUri)
                ) {

                    if (out == null) {
                        throw new Exception(
                                "Não foi possível escrever " + fileName
                        );
                    }

                    int n;

                    while ((n = zis.read(buffer)) != -1) {

                        // Protege contra limites excedidos e overflow.
                        if (n > MAX_ZIP_ENTRY_SIZE - entryExtracted) {
                            throw new Exception(
                                    "O arquivo " + fileName
                                            + " excede o limite de 1 GiB."
                            );
                        }

                        if (n > MAX_ZIP_TOTAL_SIZE - totalExtracted) {
                            throw new Exception(
                                    "A extração excede o limite total de 2 GiB."
                            );
                        }

                        out.write(buffer, 0, n);

                        entryExtracted += n;
                        totalExtracted += n;

                        unzipProcessedBytes += n;
                        updateUnzipProgressFromBytes();
                    }

                    out.flush();
                }
            }

            zis.closeEntry();
        }

    } catch (Exception e) {

        // Exclui apenas os documentos criados nesta operação.
        // A ordem inversa remove arquivos antes das pastas.
        for (int i = createdUris.size() - 1; i >= 0; i--) {
            try {
                DocumentsContract.deleteDocument(
                        getContentResolver(),
                        createdUris.get(i)
                );
            } catch (Exception ignored) {
                // Alguns provedores podem impedir a exclusão.
            }
        }

        throw e;
    }
}

    private void updateUnzipProgressFromBytes() {

        int value;

        if (unzipTotalBytes <= 0) {

            value = 100;

        } else {

            value =
                    (int) Math.min(
                            100,
                            (unzipProcessedBytes * 100)
                                    / unzipTotalBytes
                    );
        }

        updateUnzipProgress(value);
    }

    private void validateZipEntryPath(
        String path,
        boolean directory
) throws Exception {

    if (path == null
            || path.isEmpty()
            || path.length() > MAX_ZIP_PATH_LENGTH
            || !isSafeZipPath(path)) {

        throw new Exception(
                "O caminho excede os limites permitidos "
                        + "ou é inválido."
        );
    }

    String normalized = path.replace('\\', '/');
    String[] segments = normalized.split("/");

    for (String segment : segments) {
        if (segment.isEmpty()
                || ".".equals(segment)
                || "..".equals(segment)) {

            throw new Exception(
                    "A pasta contém um caminho inválido."
            );
        }
    }

    int depth = segments.length;

    if (depth > MAX_ZIP_DEPTH) {
        throw new Exception(
                "A pasta excede o limite de "
                        + MAX_ZIP_DEPTH
                        + " níveis."
        );
    }
}

    
private boolean isSafeZipPath(String path) {

    if (path == null || path.isEmpty()) {
        return false;
    }

    // Rejeita caminhos absolutos e barras invertidas.
    if (path.startsWith("/")
            || path.indexOf('\\') >= 0) {
        return false;
    }

    // Rejeita caminhos de unidade do Windows, como C:arquivo
    // e C:/arquivo.
    if (path.matches("^[a-zA-Z]:.*")) {
        return false;
    }

    // Rejeita caracteres de controle, incluindo NUL.
    for (int i = 0; i < path.length(); i++) {
        char ch = path.charAt(i);

        if (ch < 0x20 || ch == 0x7F) {
            return false;
        }
    }

    // Permite uma única barra final para diretórios.
    String normalized = path;

    if (normalized.endsWith("/")) {
        normalized = normalized.substring(
                0,
                normalized.length() - 1
        );
    }

    if (normalized.isEmpty()) {
        return false;
    }

    // Verifica todos os segmentos individualmente.
    String[] segments = normalized.split("/", -1);

    for (String segment : segments) {

        if (segment.isEmpty()
                || ".".equals(segment)
                || "..".equals(segment)) {
            return false;
        }

        // Rejeita nomes ambíguos ou incompatíveis
        // com sistemas de arquivos Windows.
        if (segment.indexOf(':') >= 0
                || segment.endsWith(".")
                || segment.endsWith(" ")) {
            return false;
        }
    }

    return true;
}

    /* =========================================================
       DOCUMENT PROVIDER
       ========================================================= */

    private String treeDocumentId(
            Uri treeUri
    ) {

        return DocumentsContract
                .getTreeDocumentId(
                        treeUri
                );
    }

    private Uri createDirectory(
            Uri parentTree,
            String name
    ) {

        try {

            String parentId =
                    DocumentsContract
                            .getTreeDocumentId(
                                    parentTree
                            );

            Uri parentUri =
                    DocumentsContract
                            .buildDocumentUriUsingTree(
                                    parentTree,
                                    parentId
                            );

            return DocumentsContract
                    .createDocument(
                            getContentResolver(),
                            parentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            name
                    );

        } catch (Exception e) {

            return null;
        }
    }

    private Uri createDirectory(
            Uri parentFolder,
            String name,
            boolean unused
    ) {

        return createDirectory(
                parentFolder,
                name
        );
    }

    private Uri createDirectories(
            Uri root,
            String path
    ) {

        if (path == null ||
                path.isEmpty()) {
            return root;
        }

        String[] parts =
                path.split("/");

        Uri current = root;

        for (String part : parts) {

            if (part.isEmpty()) {
                continue;
            }

            Uri existing =
                    findChild(
                            current,
                            part
                    );

            if (existing != null) {

                current = existing;

            } else {

                Uri created =
                        createChildDirectory(
                                current,
                                part
                        );

                if (created == null) {
                    return null;
                }

                current = created;
            }
        }

        return current;
    }

    private Uri createChildDirectory(
            Uri parent,
            String name
    ) {

        try {

            String parentId =
                    DocumentsContract
                            .getDocumentId(
                                    parent
                            );

            Uri parentUri =
                    DocumentsContract
                            .buildDocumentUriUsingTree(
                                    parent,
                                    parentId
                            );

            return DocumentsContract
                    .createDocument(
                            getContentResolver(),
                            parent,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            name
                    );

        } catch (Exception e) {

            return null;
        }
    }

    private Uri createFile(
            Uri parent,
            String name
    ) {

        try {

            return DocumentsContract
                    .createDocument(
                            getContentResolver(),
                            parent,
                            "application/octet-stream",
                            name
                    );

        } catch (Exception e) {

            return null;
        }
    }

    private Uri findChild(
            Uri parent,
            String name
    ) {

        try {

            String parentId =
                    DocumentsContract
                            .getDocumentId(
                                    parent
                            );

            Uri children =
                    DocumentsContract
                            .buildChildDocumentsUriUsingTree(
                                    parent,
                                    parentId
                            );

            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };

            try (
                    Cursor c =
                            getContentResolver()
                                    .query(
                                            children,
                                            projection,
                                            null,
                                            null,
                                            null
                                    )
            ) {

                if (c == null) {
                    return null;
                }

                while (c.moveToNext()) {

                    String id =
                            c.getString(0);

                    String childName =
                            c.getString(1);

                    if (name.equals(childName)) {

                        return DocumentsContract
                                .buildDocumentUriUsingTree(
                                        parent,
                                        id
                                );
                    }
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    /* =========================================================
       ARQUIVOS
       ========================================================= */

    private void copyUriToFile(
            Uri source,
            File destination
    ) throws Exception {

        try (
                InputStream in =
                        getContentResolver()
                                .openInputStream(source);

                OutputStream out =
                        new FileOutputStream(
                                destination,
                                false
                        )
        ) {

            if (in == null) {

                throw new Exception(
                        "Não foi possível abrir o arquivo."
                );
            }

            byte[] buffer = new byte[8192];
            long copied = 0;
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    if (copied > MAX_INPUT_BYTES - n) {
                        throw new IOException("O arquivo excede o limite permitido.");
                    }
                    out.write(buffer, 0, n);
                    copied += n;
                    Arrays.fill(buffer, 0, n, (byte) 0);
                }
                out.flush();
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    
private void copyFile(
        File source,
        File destination
) throws Exception {

    byte[] buffer = new byte[8192];

    try {
        try (
                InputStream in = new FileInputStream(source);
                OutputStream out = new FileOutputStream(
                        destination,
                        false
                )
        ) {
            int n;

            while ((n = in.read(buffer)) != -1) {
                if (n == 0) {
                    continue;
                }

                out.write(buffer, 0, n);
            }

            out.flush();
        }
    } finally {
        Arrays.fill(buffer, (byte) 0);
    }
}


    

private void copyToOutput(
        File source,
        Uri destination,
        boolean decrypting
) throws Exception {

    if (source == null || destination == null) {
        throw new Exception("Destino inválido.");
    }

    long total = source.length();
    long done = 0;

    byte[] buffer = new byte[8192];

    try {
        try (
                InputStream in = new FileInputStream(source);
                OutputStream out = getContentResolver()
                        .openOutputStream(destination)
        ) {
            if (out == null) {
                throw new Exception(
                        "Não foi possível abrir o destino."
                );
            }

            int n;

            while ((n = in.read(buffer)) != -1) {
                if (n == 0) {
                    continue;
                }

                out.write(buffer, 0, n);
                done += n;

                if (total > 0) {

    if (metadataCleaningInProgress) {

        updateProgress(
                (int) Math.min(
                        100,
                        (done * 100L) / total
                )
        );

    } else {

        updateCryptoProgress(
                done,
                total,
                decrypting
        );
    }
}
            }

            out.flush();
        }
    } finally {
        Arrays.fill(buffer, (byte) 0);
    }
}



    /* =========================================================
       NOMES
       ========================================================= */

    private String name(Uri uri) {

        if (uri == null) {
            return "arquivo";
        }

        if (DocumentsContract.isTreeUri(uri)) {
            return treeName(uri);
        }

        Cursor c = null;

        try {

            c =
                    getContentResolver()
                            .query(
                                    uri,
                                    new String[]{
                                            OpenableColumns.DISPLAY_NAME
                                    },
                                    null,
                                    null,
                                    null
                            );

            if (c != null &&
                    c.moveToFirst()) {

                int i =
                        c.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );

                if (i >= 0 &&
                        !c.isNull(i)) {

                    return c.getString(i);
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (c != null) {
                c.close();
            }
        }

        return "arquivo";
    }

    private String treeName(Uri treeUri) {

        Cursor c = null;

        try {

            String documentId =
                    DocumentsContract
                            .getTreeDocumentId(
                                    treeUri
                            );

            Uri documentUri =
                    DocumentsContract
                            .buildDocumentUriUsingTree(
                                    treeUri,
                                    documentId
                            );

            c =
                    getContentResolver()
                            .query(
                                    documentUri,
                                    new String[]{
                                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                    },
                                    null,
                                    null,
                                    null
                            );

            if (c != null &&
                    c.moveToFirst()) {

                String result =
                        c.getString(0);

                if (result != null &&
                        !result.isEmpty()) {

                    return result;
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (c != null) {
                c.close();
            }
        }

        return "pasta";
    }

    private boolean isFolderEncryptedName(
            String value
    ) {

        return value != null &&
                value.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(
                                "_pasta.aes"
                        );
    }

    /* =========================================================
       UI / LIMPEZA
       ========================================================= */

    private void updateProgress(
            int value
    ) {

        /*
         * Este método recebe um percentual diretamente.
         * Mantemos compatibilidade com eventuais chamadas antigas.
         */
        runOnUiThread(() -> {

            progress.setProgress(
                    Math.max(
                            0,
                            Math.min(
                                    100,
                                    value
                            )
                    )
            );

            if (metadataCleaningInProgress) {

                progressText.setText("Limpando...");

            } else {

            progressText.setText(
                 getString(
                            decryptMode
                                    ? R.string.decrypting_progress
                                    : R.string.encrypting_progress,
                            Math.max(
                                    0,
                                    Math.min(
                                            100,
                                            value
                                    )
                            )
                    )
            );
        }
        });
    }

    private void setBusy(
            boolean busy
    ) {

        runOnUiThread(() -> {

            action.setEnabled(!busy);
            fileButton.setEnabled(!busy);
            folderButton.setEnabled(!busy);
            helpButton.setEnabled(!busy);
            infoButton.setEnabled(!busy);
            infoCrypto.setEnabled(!busy);
            metaButton.setEnabled(!busy);
            deleteButton.setEnabled(!busy);
            
            biometricSwitch.setEnabled(!busy);

            biometricSettings.setEnabled(
                    !busy &&
                    BiometricPasswordStore
                            .isEnabled(this)
            );

            progress.setVisibility(
                    busy
                            ? View.VISIBLE
                            : View.GONE
            );

            progressText.setVisibility(
                    busy
                            ? View.VISIBLE
                            : View.GONE
            );
        });
    }

    
private void fail(int res) {
    fail(getString(res));
}

private void fail(String message) {
    runOnUiThread(() -> {
        setBusy(false);

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        String errorMessage = message;

        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            errorMessage = "Ocorreu um erro inesperado.";
        }

        new AlertDialog.Builder(MainActivity.this)
                .setTitle("‼️ ERRO!")
                .setMessage(errorMessage)
                .setCancelable(false)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> dialog.dismiss()
                )
                .show();
    });
}

    private void clearPendingPassword() {

        if (pendingPassword != null) {

            Arrays.fill(
                    pendingPassword,
                    '\0'
            );

            pendingPassword = null;
        }

        password.getText().clear();
    }

    /**
     * Tenta remover somente o documento que esta operação criou.
     * Retorna false se não for possível confirmar a exclusão, para que a
     * interface avise o usuário sobre um possível arquivo parcial residual.
     */
    private boolean deleteOutputQuietly() {

        if (!outputDocumentCreatedByApp) {
            return true;
        }

        Uri target = outputUri;
        if (target == null || DocumentsContract.isTreeUri(target)) {
            return false;
        }

        try {
            boolean deleted = DocumentsContract.deleteDocument(
                    getContentResolver(),
                    target
            );
            if (deleted) {
                outputDocumentCreatedByApp = false;
            }
            return deleted;
        } catch (Exception ignored) {
            // Mantém a flag para não declarar a limpeza como concluída.
            return false;
        }
    }

    private void deleteQuietly(
            File f
    ) {

        if (f != null) {

            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private void toast(
            int res
    ) {

        Toast.makeText(
                this,
                res,
                Toast.LENGTH_LONG
        ).show();
    }
        
private Uri createZipDirectories(
        String relativePath,
        Uri rootFolder,
        java.util.Map<String, Uri> directories,
        java.util.List<Uri> createdUris
) throws Exception {

    Uri current = rootFolder;
    StringBuilder currentPath = new StringBuilder();

    for (String segment : relativePath.split("/")) {

        if (segment.isEmpty()
                || ".".equals(segment)
                || "..".equals(segment)) {
            throw new Exception("Caminho de pasta inválido.");
        }

        if (currentPath.length() > 0) {
            currentPath.append('/');
        }

        currentPath.append(segment);

        String path = currentPath.toString();
        Uri existing = directories.get(path);

        if (existing != null) {
            current = existing;
            continue;
        }

        Uri created = DocumentsContract.createDocument(
                getContentResolver(),
                current,
                DocumentsContract.Document.MIME_TYPE_DIR,
                segment
        );

        if (created == null) {
            throw new Exception(
                    "Não foi possível criar a pasta " + segment
            );
        }

        createdUris.add(created);
        directories.put(path, created);
        current = created;
    }

    return current;
}

        private String getFileName(Uri uri) {
    Cursor cursor = getContentResolver().query(
            uri,
            new String[]{OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
    );

    if (cursor != null) {
        try {
            if (cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } finally {
            cursor.close();
        }
    }

    String path = uri.getLastPathSegment();

    return path != null ? path : "Arquivo selecionado";
}

        private void handleIncomingIntent(Intent intent) {

    if (intent == null) {
        return;
    }

    String intentAction = intent.getAction();

    Uri uri = null;

    // Recebimento pelo menu Compartilhar
    if (Intent.ACTION_SEND.equals(intentAction)) {

        if (android.os.Build.VERSION.SDK_INT >= 33) {

            uri = intent.getParcelableExtra(
                    Intent.EXTRA_STREAM,
                    Uri.class
            );

        } else {

            uri = intent.getParcelableExtra(
                    Intent.EXTRA_STREAM
            );
        }

        // Compatibilidade com aplicativos que enviam ClipData
        if (uri == null
                && intent.getClipData() != null
                && intent.getClipData().getItemCount() > 0) {

            uri = intent.getClipData()
                    .getItemAt(0)
                    .getUri();
        }

    }

    // Abertura direta pelo gerenciador de arquivos
    else if (Intent.ACTION_VIEW.equals(intentAction)) {

        uri = intent.getData();

    } else {
        return;
    }

    if (uri == null) {
        return;
    }

    // Registrar arquivo recebido
    inputUri = uri;

    inputMode = MODE_FILE;
    inputIsFolder = false;

    deleteButton.setVisibility(View.GONE);

    // Obter nome do arquivo
    String selectedName = name(inputUri);

    fileName.setText(selectedName);

    // Identificar se é um arquivo criptografado
    decryptMode = selectedName
            .toLowerCase(java.util.Locale.ROOT)
            .endsWith(".aes");

    // Atualizar botão de metadados
    metaButton.setVisibility(
            decryptMode ? View.GONE : View.VISIBLE
    );

    // Atualizar operação principal
    action.setText(
            decryptMode
                    ? R.string.decrypt
                    : R.string.encrypt
    );
}

        @Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);

    setIntent(intent);

    handleIncomingIntent(intent);
}

        private static class BankApp {

    final String name;
    final String packageName;
    final android.graphics.drawable.Drawable icon;
    final Intent launchIntent;

    BankApp(
            String name,
            String packageName,
            android.graphics.drawable.Drawable icon,
            Intent launchIntent
    ) {

        this.name = name;
        this.packageName = packageName;
        this.icon = icon;
        this.launchIntent = launchIntent;
    }

    @Override
    public String toString() {
        return name;
    }
}

private void showExitDialog() {

    new AlertDialog.Builder(this)
            .setTitle("SAIR?")            
            .setPositiveButton(
                    "SIM",
                    (dialog, which) -> finish()
            )
            .setNegativeButton(
                    "NÃO",
                    (dialog, which) -> dialog.dismiss()
            )
            .setNeutralButton(
              "⭐AVALIAR",
                                (d, which) -> {

                                    try {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "market://details?id=com.lukestudio.fileencryptor2"
                                                        )
                                                );

                                        startActivity(intent);

                                    } catch (Exception e) {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2"
                                                        )
                                                );

                                        startActivity(intent);
                                    }
                                }
                        )            
            .setCancelable(true)
            .show();
}

    @Override
    protected void onDestroy() {

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        clearPendingPassword();

        executor.shutdownNow();

        super.onDestroy();
    }
}
