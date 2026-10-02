package com.lukestudio.fileencryptor2;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.provider.OpenableColumns;

import javax.crypto.Cipher;

public class MainActivity extends FragmentActivity {
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
    private Switch biometricSwitch;
    private TextView biometricSettings;

    private boolean restoringBiometricSwitch;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);

        fileName = findViewById(R.id.fileName);
        password = findViewById(R.id.password);
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progressText);
        action = findViewById(R.id.actionButton);
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

        findViewById(R.id.openButton)
                .setOnClickListener(v -> pickInput());

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
                        /*
                         * O switch continua visualmente ligado
                         * enquanto aguardamos a confirmação.
                         *
                         * A flag impede que setChecked(true)
                         * execute novamente este listener.
                         */
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

    private void enableBiometric() {
        if (!canUseBiometric()) {
            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;
            password.setVisibility(View.VISIBLE);
            return;
        }

        showRegisterPasswordDialog();
    }

    private boolean canUseBiometric() {
        BiometricManager manager =
                BiometricManager.from(this);

        int result = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
        );

        if (result == BiometricManager.BIOMETRIC_SUCCESS) {
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.register_default_password)
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
                            password.setVisibility(View.VISIBLE);
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

            java.util.Arrays.fill(
                    newPassword,
                    '\0'
            );

            restoringBiometricSwitch = true;
            biometricSwitch.setChecked(false);
            restoringBiometricSwitch = false;

            password.setVisibility(View.VISIBLE);

            toast(R.string.biometric_setup_error);
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

                                    java.util.Arrays.fill(
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
                                java.util.Arrays.fill(
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
                .setTitle("Desativar biometria")
                .setMessage(
                        "A senha será apagada ao desativar a biometria."
                )
                .setNegativeButton(
                        android.R.string.cancel,
                        null
                )
                .setPositiveButton(
                        "Confirmar",
                        (dialog, which) -> {

                            /*
                             * Altera o switch para desligado,
                             * mas impede que o listener seja
                             * executado novamente.
                             */
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
                                        java.util.Arrays.fill(
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
            java.util.Arrays.fill(
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

        AlertDialog dialog = new AlertDialog.Builder(this)
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

                    java.util.Arrays.fill(
                            stored,
                            '\0'
                    );

                    authenticateForReplacement(
                            replacement
                    );
                })
        );

        dialog.setOnDismissListener(
                d -> java.util.Arrays.fill(
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

            java.util.Arrays.fill(
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

                                    java.util.Arrays.fill(
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
                                java.util.Arrays.fill(
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

    private void pickInput() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);

        i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        startActivityForResult(
                i,
                PICK_INPUT
        );
    }

    private void pickOutput() {
        String inputName = name(inputUri);
        String outputName;

        if (decryptMode &&
                inputName.toLowerCase().endsWith(".aes")) {

            outputName =
                    inputName.substring(
                            0,
                            inputName.length() - 4
                    );

        } else if (!decryptMode) {

            outputName =
                    inputName.toLowerCase().endsWith(".aes")
                            ? inputName
                            : inputName + ".aes";

        } else {

            outputName = inputName;
        }

        Intent i =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );

        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);

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

        if (requestCode == PICK_INPUT) {

            if (resultCode != RESULT_OK ||
                    data == null ||
                    data.getData() == null) {
                return;
            }

            inputUri = data.getData();

            try {
                int flags =
                        data.getFlags() &
                        (
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );

                if (
                        (
                                flags &
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        ) != 0
                ) {

                    getContentResolver()
                            .takePersistableUriPermission(
                                    inputUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                }

            } catch (Exception ignored) {
            }

            String selectedName = name(inputUri);

            fileName.setText(selectedName);

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

        if (requestCode == CREATE_OUTPUT) {

            if (resultCode != RESULT_OK ||
                    data == null ||
                    data.getData() == null) {

                clearPendingPassword();
                return;
            }

            outputUri = data.getData();

            try {
                int flags =
                        data.getFlags() &
                        (
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );

                if (flags != 0) {

                    getContentResolver()
                            .takePersistableUriPermission(
                                    outputUri,
                                    flags
                            );
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

            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );

            executor.execute(
                    () -> doWork(
                            pass,
                            decrypt
                    )
            );
        }
    }

    private void startWork() {
        if (inputUri == null) {
            toast(R.string.select_file);
            return;
        }

        if (BiometricPasswordStore.isEnabled(this)) {
            unlockWithBiometric();
            return;
        }

        if (password.getText().length() == 0) {
            password.requestFocus();
            toast(R.string.enter_password);
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

            password.setVisibility(View.VISIBLE);

            BiometricPasswordStore.disable(this);

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
            toast(R.string.select_file);
            return;
        }

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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.manual_password)
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
                        toast(R.string.enter_password);
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

    private void doWork(
            char[] pass,
            boolean decrypt
    ) {
        File tempInput = null;
        File tempOutput = null;

        try {

            tempInput =
                    File.createTempFile(
                            "sfe_input_",
                            ".tmp",
                            getCacheDir()
                    );

            copyUriToFile(
                    inputUri,
                    tempInput
            );

            long total = tempInput.length();

            if (total < 1) {
                throw new Exception(
                        "Arquivo vazio."
                );
            }

            String prefix =
                    decrypt
                            ? "sfe_dec_"
                            : "sfe_enc_";

            tempOutput =
                    File.createTempFile(
                            prefix,
                            ".tmp",
                            getCacheDir()
                    );

            if (decrypt) {

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
                            this::updateProgress
                    );
                }

            } else {

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
                            this::updateProgress
                    );
                }
            }

            copyToOutput(
                    tempOutput,
                    outputUri
            );

            deleteQuietly(tempInput);
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

            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
            deleteOutputQuietly();

            fail(
                    R.string.wrong_password
            );

        } catch (
                CryptoEngine.CorruptFileException e
        ) {

            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
            deleteOutputQuietly();

            fail(
                    R.string.corrupt_file
            );

        } catch (Exception e) {

            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
            deleteOutputQuietly();

            fail(
                    decrypt
                            ? R.string.error_decrypt
                            : R.string.error_encrypt
            );

        } finally {

            java.util.Arrays.fill(
                    pass,
                    '\0'
            );
        }
    }

    private void copyUriToFile(
            Uri source,
            File destination
    ) throws Exception {

        if (source == null) {
            throw new Exception(
                    "Arquivo de entrada inválido."
            );
        }

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

            if (out == null) {
                throw new Exception(
                        "Não foi possível criar o arquivo temporário."
                );
            }

            byte[] buffer = new byte[8192];
            int n;

            while ((n = in.read(buffer)) != -1) {
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
            Uri destination
    ) throws Exception {

        if (source == null ||
                destination == null) {

            throw new Exception(
                    "Destino inválido."
            );
        }

        long total = source.length();
        long done = 0;

        byte[] buffer = new byte[8192];

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

            while ((n = in.read(buffer)) != -1) {

                out.write(
                        buffer,
                        0,
                        n
                );

                done += n;

                if (total > 0) {

                    updateProgress(
                            (int) Math.min(
                                    100,
                                    done * 100 / total
                            )
                    );
                }
            }

            out.flush();
        }
    }

    private String name(Uri uri) {
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

    private void updateProgress(int value) {
        runOnUiThread(() -> {
            progress.setProgress(value);
            progressText.setText(
                    value + "%"
            );
        });
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {

            action.setEnabled(!busy);

            findViewById(R.id.openButton)
                    .setEnabled(!busy);

            biometricSwitch.setEnabled(!busy);

            biometricSettings.setEnabled(
                    !busy &&
                    BiometricPasswordStore.isEnabled(
                            this
                    )
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

            java.util.Arrays.fill(
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

            android.provider.DocumentsContract
                    .deleteDocument(
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
