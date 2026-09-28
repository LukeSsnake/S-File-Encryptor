package com.lukestudio.fileencryptor2;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
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
    private Uri inputUri;
    private boolean decryptMode;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_INPUT || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        inputUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    inputUri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        String name = name(inputUri);
        fileName.setText(name);
        decryptMode = name.toLowerCase().endsWith(".aes");
        action.setText(decryptMode ? R.string.decrypt : R.string.encrypt);
    }

    private void startWork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
            toast("Permita o acesso a todos os arquivos e toque novamente em ENCRYPT/DECRYPT.");
            return;
        }
        if (inputUri == null) {
            toast(R.string.select_file);
            return;
        }
        if (password.getText().length() == 0) {
            password.requestFocus();
            toast("Digite uma senha");
            return;
        }

        final String pass = password.getText().toString();
        password.getText().clear();
        final boolean decrypt = decryptMode;
        setBusy(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        executor.execute(() -> {
            File input = null;
            File output = null;
            File tempOutput = null;
            try {
                input = resolveFile(inputUri);
                if (input == null) throw new Exception("Não foi possível acessar o arquivo diretamente.");
                output = outputFile(input, decrypt);

                if (output.getCanonicalPath().equals(input.getCanonicalPath())) {
                    throw new Exception("O arquivo de saída é igual ao arquivo de entrada.");
                }

                // Never write directly over the final destination.  This is especially
                // important during decryption: a wrong password must not destroy an
                // existing file with the same name.  The final file is created only
                // after CryptoEngine finishes successfully.
                tempOutput = new File(output.getParentFile(), output.getName() + ".sfe_tmp");
                deleteQuietly(tempOutput);

                final File workFile = tempOutput;
                long total = input.length();
                if (decrypt) {
                    try (InputStream verify = new FileInputStream(input);
                         InputStream data = new FileInputStream(input);
                         OutputStream realOut = new FileOutputStream(workFile, false)) {
                        CryptoEngine.decrypt(verify, data, realOut, pass.toCharArray(), total, this::updateProgress);
                    }
                } else {
                    try (InputStream is = new FileInputStream(input);
                         OutputStream os = new FileOutputStream(workFile, false)) {
                        CryptoEngine.encrypt(is, os, pass.toCharArray(), total, this::updateProgress);
                    }
                }

                // Do not replace an existing destination unexpectedly.
                if (output.exists()) {
                    deleteQuietly(tempOutput);
                    throw new Exception("O arquivo de destino já existe.");
                }
                if (!tempOutput.renameTo(output)) {
                    deleteQuietly(tempOutput);
                    throw new Exception("Não foi possível finalizar o arquivo.");
                }

                runOnUiThread(() -> {
                    setBusy(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    toast(decrypt ? R.string.decrypted : R.string.encrypted);
                });
            } catch (CryptoEngine.WrongPasswordException e) {
                deleteQuietly(tempOutput);
                fail(R.string.wrong_password);
            } catch (CryptoEngine.CorruptFileException e) {
                deleteQuietly(tempOutput);
                fail(R.string.corrupt_file);
            } catch (Exception e) {
                deleteQuietly(tempOutput);
                fail(decrypt ? R.string.error_decrypt : R.string.error_encrypt);
            }
        });
    }

    private File outputFile(File input, boolean decrypt) {
        String name = input.getName();
        String outputName;
        if (decrypt && name.toLowerCase().endsWith(".aes")) {
            outputName = name.substring(0, name.length() - 4);
        } else if (!decrypt) {
            outputName = name.toLowerCase().endsWith(".aes") ? name : name + ".aes";
        } else {
            outputName = name;
        }
        return new File(input.getParentFile(), outputName);
    }

    private File resolveFile(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new File(uri.getPath());
        if (!"content".equalsIgnoreCase(uri.getScheme())) return null;

        String authority = uri.getAuthority();
        if (!"com.android.externalstorage.documents".equals(authority)) return null;
        if (!DocumentsContract.isDocumentUri(this, uri)) return null;

        String documentId;
        try {
            documentId = DocumentsContract.getDocumentId(uri);
        } catch (Exception e) {
            return null;
        }

        int colon = documentId.indexOf(':');
        if (colon <= 0) return null;
        String volume = documentId.substring(0, colon);
        String relative = documentId.substring(colon + 1);

        File root;
        if ("primary".equalsIgnoreCase(volume)) {
            root = Environment.getExternalStorageDirectory();
        } else if (Build.VERSION.SDK_INT >= 30) {
            root = findStorageVolume(volume);
        } else {
            root = null;
        }
        if (root == null) return null;
        return new File(root, relative);
    }

    private File findStorageVolume(String uuid) {
        try {
            for (android.os.storage.StorageVolume volume : getSystemService(android.os.storage.StorageManager.class).getStorageVolumes()) {
                String id = volume.getUuid();
                if (id != null && id.equalsIgnoreCase(uuid)) return volume.getDirectory();
            }
        } catch (Exception ignored) { }
        return null;
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
        executor.shutdownNow();
        super.onDestroy();
    }
}
