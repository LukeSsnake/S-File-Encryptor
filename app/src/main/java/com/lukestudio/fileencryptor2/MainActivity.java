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
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_INPUT);
    }

    private void pickOutput() {
        String inputName = name(inputUri);
        String outputName;

        if (decryptMode && inputName.toLowerCase().endsWith(".aes")) {
            outputName = inputName.substring(0, inputName.length() - 4);
        } else if (!decryptMode) {
            outputName = inputName.toLowerCase().endsWith(".aes") ? inputName : inputName + ".aes";
        } else {
            outputName = inputName;
        }

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, outputName);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, CREATE_OUTPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_INPUT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

            inputUri = data.getData();
            try {
                int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(inputUri, flags);
            } catch (Exception ignored) { }

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
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (flags != 0) {
                    getContentResolver().takePersistableUriPermission(outputUri, flags);
                }
            } catch (Exception ignored) { }

            final char[] pass = pendingPassword;
            pendingPassword = null;
            password.getText().clear();
            if (pass == null) return;

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
        File tempOutput = null;
        try {
            String prefix = decrypt ? "sfe_dec_" : "sfe_enc_";
            tempOutput = File.createTempFile(prefix, ".tmp", getCacheDir());

            long total = size(inputUri);
            File workFile = tempOutput;

            if (decrypt) {
                try (InputStream verify = getContentResolver().openInputStream(inputUri);
                     InputStream data = getContentResolver().openInputStream(inputUri);
                     OutputStream realOut = new FileOutputStream(workFile, false)) {
                    if (verify == null || data == null) throw new Exception("Não foi possível abrir o arquivo.");
                    CryptoEngine.decrypt(verify, data, realOut, pass, total, this::updateProgress);
                }
            } else {
                try (InputStream is = getContentResolver().openInputStream(inputUri);
                     OutputStream os = new FileOutputStream(workFile, false)) {
                    if (is == null) throw new Exception("Não foi possível abrir o arquivo.");
                    CryptoEngine.encrypt(is, os, pass, total, this::updateProgress);
                }
            }

            // The selected SAF destination is written only after encryption/decryption
            // has completed successfully. This prevents a wrong password or corrupt
            // encrypted file from leaving partial plaintext at the user's destination.
            copyToOutput(workFile, outputUri);

            deleteQuietly(tempOutput);
            runOnUiThread(() -> {
                setBusy(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                toast(decrypt ? R.string.decrypted : R.string.encrypted);
            });
        } catch (CryptoEngine.WrongPasswordException e) {
            deleteQuietly(tempOutput);
            deleteOutputQuietly();
            fail(R.string.wrong_password);
        } catch (CryptoEngine.CorruptFileException e) {
            deleteQuietly(tempOutput);
            deleteOutputQuietly();
            fail(R.string.corrupt_file);
        } catch (Exception e) {
            deleteQuietly(tempOutput);
            deleteOutputQuietly();
            fail(decrypt ? R.string.error_decrypt : R.string.error_encrypt);
        } finally {
            java.util.Arrays.fill(pass, '\0');
        }
    }

    private void copyToOutput(File source, Uri destination) throws Exception {
        if (destination == null) throw new Exception("Destino inválido.");

        long total = source.length();
        long done = 0;
        byte[] buffer = new byte[8192];

        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new Exception("Não foi possível abrir o destino.");

            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                if (total > 0) {
                    updateProgress((int) Math.min(100, done * 100 / total));
                }
            }
            out.flush();
        }
    }

    private long size(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private String name(Uri uri) {
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        try {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) return c.getString(i);
            }
        } finally {
            if (c != null) c.close();
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
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        if (outputUri == null) return;
        try {
            android.provider.DocumentsContract.deleteDocument(getContentResolver(), outputUri);
        } catch (Exception ignored) { }
    }

    private void deleteQuietly(File f) {
        if (f != null) {
            try { f.delete(); } catch (Exception ignored) { }
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        clearPendingPassword();
        executor.shutdownNow();
        super.onDestroy();
    }
}
