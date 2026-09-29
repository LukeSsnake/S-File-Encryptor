package com.lukestudio.fileencryptor2;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
private static final int PICK_INPUT = 10;
private static final int CREATE_OUTPUT = 11;

private Uri inputUri;
private Uri outputUri;
private boolean decryptMode;
private char[] pendingPassword;

private TextView fileName, progressText;
private EditText password;
private ProgressBar progress;
private Button action;
private final ExecutorService executor = Executors.newSingleThreadExecutor();

@Override
protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(R.layout.main);

    fileName = findViewById(R.id.fileName);
    password = findViewById(R.id.password);
    progress = findViewById(R.id.progress);
    progressText = findViewById(R.id.progressText);
    action = findViewById(R.id.actionButton);

    findViewById(R.id.openButton).setOnClickListener(v -> pickInput());
    action.setOnClickListener(v -> startWork());
}

private void pickInput() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    );
    startActivityForResult(i, PICK_INPUT);
}

private void pickOutput() {
    String inputName = name(inputUri);
    String outputName;

    if (decryptMode && inputName.toLowerCase().endsWith(".aes")) {
        outputName = inputName.substring(0, inputName.length() - 4);
    } else if (!decryptMode) {
        outputName = inputName.toLowerCase().endsWith(".aes")
                ? inputName
                : inputName + ".aes";
    } else {
        outputName = inputName;
    }

    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType("application/octet-stream");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_TITLE, outputName);
    i.addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_READ_URI_PERMISSION
    );
    startActivityForResult(i, CREATE_OUTPUT);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == PICK_INPUT) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        inputUri = data.getData();

        try {
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
    getContentResolver().takePersistableUriPermission(
            inputUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
    );
}
        } catch (Exception ignored) {
        }

        String selectedName = name(inputUri);
        fileName.setText(selectedName);
        decryptMode = selectedName.toLowerCase().endsWith(".aes");
        action.setText(decryptMode ? R.string.decrypt : R.string.encrypt);
        return;
    }

    if (requestCode == CREATE_OUTPUT) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            clearPendingPassword();
            return;
        }

        outputUri = data.getData();

        try {
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if (flags != 0) {
                getContentResolver().takePersistableUriPermission(outputUri, flags);
            }
        } catch (Exception ignored) {
        }

        final char[] pass = pendingPassword;
        pendingPassword = null;
        password.getText().clear();

        if (pass == null) {
            return;
        }

        final boolean decrypt = decryptMode;

        setBusy(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        executor.execute(() -> doWork(pass, decrypt));
    }
}

private void startWork() {
    if (inputUri == null) {
        toast(R.string.select_file);
        return;
    }

    if (password.getText().length() == 0) {
        password.requestFocus();
        toast("Digite uma senha");
        return;
    }

    pendingPassword = password.getText().toString().toCharArray();
    pickOutput();
}

private void doWork(char[] pass, boolean decrypt) {
    File tempInput = null;
    File tempOutput = null;

    try {
        /*
         * SAF providers are allowed to report an unknown file size (-1).
         * Copy the selected document to the app's private cache first so
         * CryptoEngine can use the exact file length.
         */
        tempInput = File.createTempFile(
                "sfe_input_",
                ".tmp",
                getCacheDir()
        );

        copyUriToFile(inputUri, tempInput);

        long total = tempInput.length();

        if (total < 1) {
            throw new Exception("Arquivo vazio.");
        }

        String prefix = decrypt ? "sfe_dec_" : "sfe_enc_";

        tempOutput = File.createTempFile(
                prefix,
                ".tmp",
                getCacheDir()
        );

        if (decrypt) {
            try (InputStream verify = new FileInputStream(tempInput);
                 InputStream data = new FileInputStream(tempInput);
                 OutputStream realOut = new FileOutputStream(tempOutput, false)) {

                CryptoEngine.decrypt(
                        verify,
                        data,
                        realOut,
                        pass,
                        total,
                        this::updateProgress
                );
            }
        } else {
            try (InputStream is = new FileInputStream(tempInput);
                 OutputStream os = new FileOutputStream(tempOutput, false)) {

                CryptoEngine.encrypt(
                        is,
                        os,
                        pass,
                        total,
                        this::updateProgress
                );
            }
        }

        /*
         * The user-selected SAF destination is written only after
         * encryption/decryption has completed successfully.
         *
         * This prevents a wrong password, corrupt encrypted file, or
         * interrupted crypto operation from leaving partial plaintext
         * at the user's destination.
         */
        copyToOutput(tempOutput, outputUri);

        deleteQuietly(tempInput);
        deleteQuietly(tempOutput);

        runOnUiThread(() -> {
            setBusy(false);
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
            toast(decrypt ? R.string.decrypted : R.string.encrypted);
        });

    } catch (CryptoEngine.WrongPasswordException e) {
        deleteQuietly(tempInput);
        deleteQuietly(tempOutput);
        deleteOutputQuietly();
        fail(R.string.wrong_password);

    } catch (CryptoEngine.CorruptFileException e) {
        deleteQuietly(tempInput);
        deleteQuietly(tempOutput);
        deleteOutputQuietly();
        fail(R.string.corrupt_file);

    } catch (Exception e) {
        deleteQuietly(tempInput);
        deleteQuietly(tempOutput);
        deleteOutputQuietly();
        fail(decrypt ? R.string.error_decrypt : R.string.error_encrypt);

    } finally {
        java.util.Arrays.fill(pass, '\0');
    }
}

private void copyUriToFile(Uri source, File destination) throws Exception {
    if (source == null) {
        throw new Exception("Arquivo de entrada inválido.");
    }

    try (InputStream in = getContentResolver().openInputStream(source);
         OutputStream out = new FileOutputStream(destination, false)) {

        if (in == null) {
            throw new Exception("Não foi possível abrir o arquivo.");
        }

        if (out == null) {
            throw new Exception("Não foi possível criar o arquivo temporário.");
        }

        byte[] buffer = new byte[8192];
        int n;

        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }

        out.flush();
    }
}

private void copyToOutput(File source, Uri destination) throws Exception {
    if (source == null || destination == null) {
        throw new Exception("Destino inválido.");
    }

    long total = source.length();
    long done = 0;
    byte[] buffer = new byte[8192];

    /*
     * Do not use the "w" mode overload here because it was added in API 26.
     * The no-argument overload is compatible with minSdk 23.
     */
    try (InputStream in = new FileInputStream(source);
         OutputStream out = getContentResolver().openOutputStream(destination)) {

        if (out == null) {
            throw new Exception("Não foi possível abrir o destino.");
        }

        int n;

        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            done += n;

            if (total > 0) {
                updateProgress(
                        (int) Math.min(100, done * 100 / total)
                );
            }
        }

        out.flush();
    }
}

private String name(Uri uri) {
    Cursor c = null;

    try {
        c = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        );

        if (c != null && c.moveToFirst()) {
            int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);

            if (i >= 0 && !c.isNull(i)) {
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

private void updateProgress(int value) {
    runOnUiThread(() -> {
        progress.setProgress(value);
        progressText.setText(value + "%");
    });
}

private void setBusy(boolean busy) {
    runOnUiThread(() -> {
        action.setEnabled(!busy);
        findViewById(R.id.openButton).setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progressText.setVisibility(busy ? View.VISIBLE : View.GONE);
    });
}

private void fail(int res) {
    runOnUiThread(() -> {
        setBusy(false);
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        toast(res);
    });
}

private void clearPendingPassword() {
    if (pendingPassword != null) {
        java.util.Arrays.fill(pendingPassword, '\0');
        pendingPassword = null;
    }

    password.getText().clear();
}

private void deleteOutputQuietly() {
    if (outputUri == null) {
        return;
    }

    try {
        android.provider.DocumentsContract.deleteDocument(
                getContentResolver(),
                outputUri
        );
    } catch (Exception ignored) {
    }
}

private void deleteQuietly(File f) {
    if (f != null) {
        try {
            f.delete();
        } catch (Exception ignored) {
        }
    }
}

private void toast(int res) {
    Toast.makeText(this, res, Toast.LENGTH_LONG).show();
}

private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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