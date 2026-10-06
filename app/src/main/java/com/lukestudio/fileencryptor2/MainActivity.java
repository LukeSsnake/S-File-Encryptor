package com.lukestudio.fileencryptor2;

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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;

public class MainActivity extends FragmentActivity {

    private static final int PICK_FILE = 10;
    private static final int PICK_FOLDER = 11;
    private static final int CREATE_OUTPUT = 12;
    private static final int PICK_OUTPUT_FOLDER = 13;

    private static final int MODE_FILE = 0;
    private static final int MODE_FOLDER = 1;

    private Uri inputUri;
    private Uri outputUri;

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
    private Switch biometricSwitch;
    private TextView biometricSettings;

    private boolean restoringBiometricSwitch;

    private long zipTotalBytes;
    private long zipProcessedBytes;

    private long unzipTotalBytes;
    private long unzipProcessedBytes;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

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

        fileButton.setOnClickListener(v -> pickFile());
        folderButton.setOnClickListener(v -> pickFolder());
        helpButton.setOnClickListener(v -> showHelp());
        infoButton.setOnClickListener(v -> showSupport());

        action.setOnClickListener(v -> startWork());

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
                    .setNegativeButton(
                            "AVALIAR ⭐",
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
                                }
                            }
                    )
                    .create();

    dialog.show();
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
                        .create();

        dialog.setOnShowListener(
                d -> dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {

                    if (field.getText().length() == 0) {
                        field.requestFocus();
                        toast(R.string.enter_password);
                        return;
                    }

                    char[] newPassword =
                            field.getText()
                                    .toString()
                                    .toCharArray();

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

                                try {

                                    BiometricPasswordStore
                                            .saveEncryptedPassword(
                                                    MainActivity.this,
                                                    cipher,
                                                    newPassword
                                            );

                                    restoringBiometricSwitch = true;
                                    biometricSwitch.setChecked(true);
                                    restoringBiometricSwitch = false;

                                    biometricSettings
                                            .setEnabled(true);

                                    password.setVisibility(
                                            View.GONE
                                    );

                                    toast(
                                            R.string.biometric_enabled
                                    );

                                } catch (Exception e) {

                                    restoringBiometricSwitch = true;
                                    biometricSwitch.setChecked(false);
                                    restoringBiometricSwitch = false;

                                    password.setVisibility(
                                            View.VISIBLE
                                    );

                                    toast(
                                            R.string.biometric_setup_error
                                    );

                                } finally {

                                    Arrays.fill(
                                            newPassword,
                                            '\0'
                                    );
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

        prompt.authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(
                                getString(
                                        R.string.biometric_title
                                )
                        )
                        .setSubtitle(
                                getString(
                                        R.string.biometric_register_subtitle
                                )
                        )
                        .setNegativeButtonText(
                                getString(
                                        android.R.string.cancel
                                )
                        )
                        .build(),
                new BiometricPrompt.CryptoObject(cipher)
        );
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

                            restoringBiometricSwitch = true;
                            biometricSwitch.setChecked(false);
                            restoringBiometricSwitch = false;

                            password.setVisibility(
                                    View.VISIBLE
                            );

                            biometricSettings.setEnabled(false);

                            BiometricPasswordStore.disable(
                                    this
                            );

                            toast(
                                    R.string.biometric_disabled
                            );
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

            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;

            password.setVisibility(View.VISIBLE);

            BiometricPasswordStore.disable(this);

            toast(
                    R.string.biometric_setup_error
            );

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

                                char[] stored = null;

                                try {

                                    stored =
                                            BiometricPasswordStore
                                                    .decryptPassword(
                                                            MainActivity.this,
                                                            cipher
                                                    );

                                    if (showPassword) {

                                        showStoredPasswordDialog(
                                                stored
                                        );

                                    } else {

                                        useBiometricPassword(
                                                stored
                                        );

                                        stored = null;
                                    }

                                } catch (Exception e) {

                                    restoringBiometricSwitch = true;
                                    biometricSwitch.setChecked(false);
                                    restoringBiometricSwitch = false;

                                    biometricSettings
                                            .setEnabled(false);

                                    password.setVisibility(
                                            View.VISIBLE
                                    );

                                    BiometricPasswordStore
                                            .disable(
                                                    MainActivity.this
                                            );

                                    toast(
                                            R.string.biometric_setup_error
                                    );

                                } finally {

                                    if (stored != null) {

                                        Arrays.fill(
                                                stored,
                                                '\0'
                                        );
                                    }
                                }
                            }

                            @Override
                            public void onAuthenticationError(
                                    int errorCode,
                                    CharSequence errString
                            ) {

                                if (allowManualFallback) {
                                    showManualPasswordFallback();
                                }
                            }
                        }
                );

        prompt.authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(
                                getString(
                                        R.string.biometric_title
                                )
                        )
                        .setSubtitle(
                                getString(
                                        R.string.biometric_unlock_subtitle
                                )
                        )
                        .setNegativeButtonText(
                                allowManualFallback
                                        ? getString(
                                                R.string.use_manual_password
                                        )
                                        : getString(
                                                android.R.string.cancel
                                        )
                        )
                        .build(),
                new BiometricPrompt.CryptoObject(cipher)
        );
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
        field.setText(new String(stored));
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
                        .create();

        dialog.setOnShowListener(
                d -> dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {

                    if (field.getText().length() == 0) {
                        field.requestFocus();
                        toast(R.string.enter_password);
                        return;
                    }

                    char[] replacement =
                            field.getText()
                                    .toString()
                                    .toCharArray();

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

            Arrays.fill(
                    replacement,
                    '\0'
            );

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

                                try {

                                    BiometricPasswordStore
                                            .saveEncryptedPassword(
                                                    MainActivity.this,
                                                    cipher,
                                                    replacement
                                            );

                                    toast(
                                            R.string.password_changed
                                    );

                                } catch (Exception e) {

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

                                Arrays.fill(
                                        replacement,
                                        '\0'
                                );
                            }
                        }
                );

        prompt.authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(
                                getString(
                                        R.string.biometric_title
                                )
                        )
                        .setSubtitle(
                                getString(
                                        R.string.biometric_change_subtitle
                                )
                        )
                        .setNegativeButtonText(
                                getString(
                                        android.R.string.cancel
                                )
                        )
                        .build(),
                new BiometricPrompt.CryptoObject(cipher)
        );
    }

    /* =========================================================
       SELEÇÃO DE ARQUIVO / PASTA
       ========================================================= */

    private void pickFile() {

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
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        startActivityForResult(
                i,
                PICK_FILE
        );
    }

    private void pickFolder() {

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
                            .toLowerCase()
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
                            .toLowerCase()
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

            decryptMode =
                    selectedName
                            .toLowerCase()
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

            persistWritePermission(
                    outputUri,
                    data
            );

            startPendingWork();

            return;
        }

        if (requestCode == PICK_OUTPUT_FOLDER) {

            outputUri = data.getData();

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
                            & Intent.FLAG_GRANT_READ_URI_PERMISSION;

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

        if (inputUri == null) {
            toast(R.string.select_file);
            return;
        }

        if (
                BiometricPasswordStore
                        .isEnabled(this)
        ) {

            unlockWithBiometric();
            return;
        }

        if (password.getText().length() == 0) {

            password.requestFocus();

            toast(
                    R.string.enter_password
            );

            return;
        }

        pendingPassword =
                password.getText()
                        .toString()
                        .toCharArray();

        pickOutput();
    }

    private void unlockWithBiometric() {

        final Cipher cipher;

        try {

            cipher =
                    BiometricPasswordStore
                            .createDecryptionCipher(this);

        } catch (Exception e) {

            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;

            password.setVisibility(
                    View.VISIBLE
            );

            BiometricPasswordStore
                    .disable(this);

            toast(
                    R.string.biometric_setup_error
            );

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

                    if (field.getText().length() == 0) {

                        field.requestFocus();

                        toast(
                                R.string.enter_password
                        );

                        return;
                    }

                    clearPendingPassword();

                    pendingPassword =
                            field.getText()
                                    .toString()
                                    .toCharArray();

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

                calculateZipTotal(
                        inputUri,
                        treeDocumentId(inputUri)
                );

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
                            )
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

            runOnUiThread(() -> {

                setBusy(false);

                getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );

                toast(
                        decrypt
                                ? R.string.decrypted
                                : R.string.encrypted
                );
            });

        } catch (
                CryptoEngine.WrongPasswordException e
        ) {

            cleanup(
                    tempInput,
                    tempZip,
                    tempOutput
            );

            deleteOutputQuietly();

            fail(
                    R.string.wrong_password
            );

        } catch (
                CryptoEngine.CorruptFileException e
        ) {

            cleanup(
                    tempInput,
                    tempZip,
                    tempOutput
            );

            deleteOutputQuietly();

            fail(
                    R.string.corrupt_file
            );

        } catch (Exception e) {

            cleanup(
                    tempInput,
                    tempZip,
                    tempOutput
            );

            deleteOutputQuietly();

            fail(
                    decrypt
                            ? R.string.error_decrypt
                            : R.string.error_encrypt
            );

        } finally {

            Arrays.fill(
                    pass,
                    '\0'
            );
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
            String parentDocumentId
    ) throws Exception {

        Uri childrenUri =
                DocumentsContract
                        .buildChildDocumentsUriUsingTree(
                                treeUri,
                                parentDocumentId
                        );

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
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

                String id =
                        c.getString(0);

                String mime =
                        c.getString(1);

                if (isDirectoryMime(mime)) {

                    calculateZipTotal(
                            treeUri,
                            id
                    );

                } else {

                    Uri childUri =
                            DocumentsContract
                                    .buildDocumentUriUsingTree(
                                            treeUri,
                                            id
                                    );

                    if (isOutputUri(childUri)) {
                        continue;
                    }

                    long size = 0;

                    if (!c.isNull(2)) {
                        size = c.getLong(2);
                    }

                    if (size > 0) {
                        zipTotalBytes += size;
                    }
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

                String id =
                        c.getString(0);

                String childName =
                        c.getString(1);

                String mime =
                        c.getString(2);

                String path =
                        relativePath.isEmpty()
                                ? childName
                                : relativePath
                                        + "/"
                                        + childName;

                Uri childUri =
                        DocumentsContract
                                .buildDocumentUriUsingTree(
                                        treeUri,
                                        id
                                );

                if (isOutputUri(childUri)) {
                    continue;
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

                        while (
                                (n = in.read(buffer))
                                        != -1
                        ) {

                            zos.write(
                                    buffer,
                                    0,
                                    n
                            );

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

        try (
                ZipFile zip =
                        new ZipFile(zipFile)
        ) {

            java.util.Enumeration<? extends ZipEntry> entries =
                    zip.entries();

            while (entries.hasMoreElements()) {

                ZipEntry entry =
                        entries.nextElement();

                if (!entry.isDirectory()) {

                    long size =
                            entry.getSize();

                    if (size > 0) {
                        total += size;
                    }
                }
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

        String originalName =
                name(inputUri);

        String folderName =
                originalName.substring(
                        0,
                        originalName.length()
                                - "_pasta.aes".length()
                );

        Uri outputFolder =
                createDirectory(
                        destinationTree,
                        folderName
                );

        if (outputFolder == null) {

            throw new Exception(
                    "Não foi possível criar a pasta de destino."
            );
        }

        try (
                InputStream fis =
                        new FileInputStream(
                                zipFile
                        );

                ZipInputStream zis =
                        new ZipInputStream(fis)
        ) {

            ZipEntry entry;

            byte[] buffer =
                    new byte[8192];

            while (
                    (entry = zis.getNextEntry())
                            != null
            ) {

                String entryName =
                        entry.getName();

                if (!isSafeZipPath(entryName)) {
                    throw new Exception(
                            "Arquivo ZIP inválido."
                    );
                }

                if (entry.isDirectory()) {

                    createDirectories(
                            outputFolder,
                            entryName
                    );

                } else {

                    int slash =
                            entryName.lastIndexOf('/');

                    String parentPath =
                            slash >= 0
                                    ? entryName.substring(
                                            0,
                                            slash
                                    )
                                    : "";

                    Uri parent =
                            parentPath.isEmpty()
                                    ? outputFolder
                                    : createDirectories(
                                            outputFolder,
                                            parentPath
                                    );

                    if (parent == null) {
                        throw new Exception(
                                "Não foi possível criar "
                                        + entryName
                        );
                    }

                    String fileName =
                            slash >= 0
                                    ? entryName.substring(
                                            slash + 1
                                    )
                                    : entryName;

                    Uri fileUri =
                            createFile(
                                    parent,
                                    fileName
                            );

                    if (fileUri == null) {
                        throw new Exception(
                                "Não foi possível criar "
                                        + fileName
                        );
                    }

                    try (
                            OutputStream out =
                                    getContentResolver()
                                            .openOutputStream(
                                                    fileUri
                                            )
                    ) {

                        if (out == null) {
                            throw new Exception(
                                    "Não foi possível escrever "
                                            + fileName
                            );
                        }

                        int n;

                        while (
                                (n = zis.read(buffer))
                                        != -1
                        ) {

                            out.write(
                                    buffer,
                                    0,
                                    n
                            );

                            unzipProcessedBytes += n;

                            updateUnzipProgressFromBytes();
                        }

                        out.flush();
                    }
                }

                zis.closeEntry();
            }
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

    private boolean isSafeZipPath(
            String path
    ) {

        if (path == null ||
                path.isEmpty()) {
            return false;
        }

        String normalized =
                path.replace(
                        '\\',
                        '/'
                );

        return !normalized.startsWith("/")
                && !normalized.contains("../")
                && !normalized.equals("..")
                && !normalized.contains("/..")
                && !normalized.contains(":/");
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

            byte[] buffer =
                    new byte[8192];

            int n;

            while (
                    (n = in.read(buffer))
                            != -1
            ) {

                out.write(
                        buffer,
                        0,
                        n
                );
            }

            out.flush();
        }
    }

    private void copyFile(
            File source,
            File destination
    ) throws Exception {

        try (
                InputStream in =
                        new FileInputStream(source);

                OutputStream out =
                        new FileOutputStream(
                                destination,
                                false
                        )
        ) {

            byte[] buffer =
                    new byte[8192];

            int n;

            while (
                    (n = in.read(buffer))
                            != -1
            ) {

                out.write(
                        buffer,
                        0,
                        n
                );
            }

            out.flush();
        }
    }

    private void copyToOutput(
            File source,
            Uri destination,
            boolean decrypting
    ) throws Exception {

        if (source == null ||
                destination == null) {

            throw new Exception(
                    "Destino inválido."
            );
        }

        long total =
                source.length();

        long done = 0;

        byte[] buffer =
                new byte[8192];

        try (
                InputStream in =
                        new FileInputStream(source);

                OutputStream out =
                        getContentResolver()
                                .openOutputStream(
                                        destination
                                )
        ) {

            if (out == null) {
                throw new Exception(
                        "Não foi possível abrir o destino."
                );
            }

            int n;

            while (
                    (n = in.read(buffer))
                            != -1
            ) {

                out.write(
                        buffer,
                        0,
                        n
                );

                done += n;

                if (total > 0) {

                    updateCryptoProgress(
                            done,
                            total,
                            decrypting
                    );
                }
            }

            out.flush();
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
                value.toLowerCase()
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

            /*
             * O botão de informações permanece disponível
             * durante as operações.
             */
            infoButton.setEnabled(true);

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

    private void fail(
            int res
    ) {

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

            Arrays.fill(
                    pendingPassword,
                    '\0'
            );

            pendingPassword = null;
        }

        password.getText().clear();
    }

    private void deleteOutputQuietly() {

        if (outputUri == null) {
            return;
        }

        try {

            if (!DocumentsContract
                    .isTreeUri(outputUri)) {

                DocumentsContract
                        .deleteDocument(
                                getContentResolver(),
                                outputUri
                        );
            }

        } catch (Exception ignored) {
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
