package com.lukestudio.fileencryptor2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.content.Intent;
import android.net.Uri;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Toast;


public class DialogManager {

    private final Activity activity;

    public DialogManager(Activity activity) {
        this.activity = activity;
    }

    /**
     * Exibe o aviso de segurança somente na primeira abertura do aplicativo.
     */
    public void showFirstLaunchSecurityNotice() {

        SharedPreferences prefs =
                activity.getSharedPreferences(
                        "SFileEncryptorPrefs",
                        Activity.MODE_PRIVATE
                );

        // Não exibe novamente se o usuário já confirmou.
        if (prefs.getBoolean("security_notice_shown", false)) {
            return;
        }

        View dialogView =
                activity.getLayoutInflater().inflate(
                        R.layout.dialog_security_notice,
                        null
                );

        CheckBox checkBox =
                dialogView.findViewById(
                        R.id.securityNoticeCheck
                );

        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.allerte_title)
                        .setView(dialogView)

                        // Começa desabilitado.
                        .setPositiveButton(
                                R.string.allerte_okay,
                                null
                        )

                        // Cancela e fecha o aplicativo.
                        .setNegativeButton(
                                R.string.allerte_cancel,
                                (d, which) -> activity.finish()
                        )

                        // Não permite fechar pressionando voltar.
                        .setCancelable(false)
                        .create();

        // Também não permite fechar tocando fora do diálogo.
        dialog.setCanceledOnTouchOutside(false);

        dialog.setOnShowListener(d -> {

            Button continueButton =
                    dialog.getButton(
                            AlertDialog.BUTTON_POSITIVE
                    );

            // O usuário precisa marcar a caixa primeiro.
            continueButton.setEnabled(false);

            checkBox.setOnCheckedChangeListener(
                    (buttonView, isChecked) ->
                            continueButton.setEnabled(isChecked)
            );

            /*
             * O botão positivo foi criado pelo AlertDialog,
             * mas o listener é substituído aqui para controlar
             * quando a confirmação será registrada.
             */
            continueButton.setOnClickListener(v -> {

                prefs.edit()
                        .putBoolean(
                                "security_notice_shown",
                                true
                        )
                        .apply();

                dialog.dismiss();
            });
        });

        dialog.show();
    }


    public void showHelp() {

        new AlertDialog.Builder(activity)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_message)
                .setPositiveButton(
                        android.R.string.ok,
                        null
                )
                .show();
    }


    public void showCrypto() {

    AlertDialog cryp =
            new AlertDialog.Builder(activity)
                    .setTitle(
                            R.string.crypto_title
                    )
                    .setMessage(
                            activity.getString(
                                    R.string.crypto_message
                            )
                    )
                    .setNeutralButton(
                            R.string.source_code,
                            (d, which) -> {

                                try {

                                    Intent in =
                                            new Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                            "https://github.com/LukeSsnake/S-File-Encryptor"
                                                    )
                                            );

                                    activity.startActivity(in);

                                } catch (Exception e) {
                                    // Falha ao abrir o navegador.
                                }
                            }
                    )
                    .setPositiveButton(
                            R.string.okay,
                            null
                    )
                    .create();

    cryp.show();
}


    public void showSupport() {

    AlertDialog dialog =
            new AlertDialog.Builder(activity)
                    .setTitle(
                            R.string.support_title
                    )
                    .setMessage(
                            activity.getString(
                                    R.string.support_message,
                                    activity.getString(
                                            R.string.pix_email
                                    )
                            )
                    )
                    .setNeutralButton(
                            R.string.rate_app,
                            (d, which) -> {

                                try {

                                    Intent intent =
                                            new Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                            "market://details?id=com.lukestudio.fileencryptor2"
                                                    )
                                            );

                                    activity.startActivity(intent);

                                } catch (Exception e) {

                                    try {

                                        Intent intent =
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(
                                                                "https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2"
                                                        )
                                                );

                                        activity.startActivity(intent);

                                    } catch (Exception ignored) {
                                        // Não foi possível abrir a Play Store.
                                    }
                                }
                            }
                    )
                    .setPositiveButton(
                            R.string.copy_pix,
                            (d, which) -> {

                                ClipboardManager clipboard =
                                        (ClipboardManager)
                                                activity.getSystemService(
                                                        Context.CLIPBOARD_SERVICE
                                                );

                                if (clipboard != null) {

                                    clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                    "PIX",
                                                    activity.getString(
                                                            R.string.pix_email
                                                    )
                                            )
                                    );

                                    Toast.makeText(
                                            activity,
                                            R.string.pix_copied,
                                            Toast.LENGTH_SHORT
                                    ).show();
                                }
                            }
                    )
                    .setNegativeButton(
                            R.string.donate,
                            (d, which) -> {

                                try {

                                    Intent intent =
                                            new Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                            "https://buymeacoffee.com/lukess"
                                                    )
                                            );

                                    activity.startActivity(intent);

                                } catch (Exception ignored) {
                                    // Não foi possível abrir o link.
                                }
                            }
                    )
                    .create();

    dialog.show();
}


    /**
     * Diálogo relacionado à remoção de metadados.
     */
    public void showMetadataDialog() {

        // Mover aqui o código atual do diálogo de metadados.
    }
}
