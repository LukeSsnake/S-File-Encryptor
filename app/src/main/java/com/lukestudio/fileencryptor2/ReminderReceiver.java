
package com.lukestudio.fileencryptor2;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID =
            "sfe_security_reminders";

    private static final int NOTIFICATION_ID = 9001;
    private static final int ALARM_REQUEST_CODE = 1001;

    private static final String PREFS_NAME =
            "sfe_reminder_prefs";

    private static final String KEY_NEXT_REMINDER =
            "next_reminder_time";

    // 90 horas em milissegundos.
    private static final long REMINDER_INTERVAL =
            90L * 60L * 60L * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {

        createNotificationChannel(context);

        NotificationManager manager =
                (NotificationManager)
                        context.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (manager != null) {

            NotificationCompat.Builder notification =
                    new NotificationCompat.Builder(
                            context,
                            CHANNEL_ID
                    )
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle("S-File-Encryptor")
                    .setContentText(
                            "Não deixe seus arquivos correndo riscos, proteja-os!"
                    )
                    .setPriority(
                            NotificationCompat.PRIORITY_DEFAULT
                    )
                    .setAutoCancel(true);

            manager.notify(
                    NOTIFICATION_ID,
                    notification.build()
            );
        }

        // Programa a próxima notificação para 90 horas depois.
        long nextTime =
                System.currentTimeMillis() + REMINDER_INTERVAL;

        saveNextReminder(context, nextTime);

        scheduleAlarm(context, nextTime);
    }

    public static void scheduleFirstReminder(Context context) {

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                );

        long nextTime =
                prefs.getLong(KEY_NEXT_REMINDER, 0L);

        // Primeira abertura: inicia a contagem de 90 horas.
        if (nextTime == 0L) {

            nextTime =
                    System.currentTimeMillis()
                            + REMINDER_INTERVAL;

            saveNextReminder(context, nextTime);
        }

        // Recupera o horário salvo, inclusive após reinicialização.
        scheduleAlarm(context, nextTime);
    }

    private static void saveNextReminder(
            Context context,
            long time) {

        context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        )
        .edit()
        .putLong(KEY_NEXT_REMINDER, time)
        .apply();
    }

    private static void scheduleAlarm(
            Context context,
            long triggerTime) {

        AlarmManager alarmManager =
                (AlarmManager)
                        context.getSystemService(
                                Context.ALARM_SERVICE
                        );

        if (alarmManager == null) {
            return;
        }

        Intent intent =
                new Intent(
                        context,
                        ReminderReceiver.class
                );

        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(
                        context,
                        ALARM_REQUEST_CODE,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
            );

        } else {

            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
            );
        }
    }

    private static void createNotificationChannel(
            Context context) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Lembretes de segurança",
                            NotificationManager.IMPORTANCE_DEFAULT
                    );

            channel.setDescription(
                    "Lembretes periódicos do S-File-Encryptor"
            );

            NotificationManager manager =
                    (NotificationManager)
                            context.getSystemService(
                                    Context.NOTIFICATION_SERVICE
                            );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
