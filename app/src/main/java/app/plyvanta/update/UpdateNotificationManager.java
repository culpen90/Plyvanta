package app.plyvanta.update;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Objects;

import app.plyvanta.R;

public final class UpdateNotificationManager {
    public static final String ACTION_SHOW_UPDATE =
            "app.plyvanta.action.SHOW_UPDATE";
    public static final String CHANNEL_ID = "plyvanta_app_updates";

    private static final int NOTIFICATION_ID = 0x50554C59;
    private static final String EXTRA_NAVIGATION_TOKEN =
            "app.plyvanta.extra.UPDATE_NAVIGATION_TOKEN";

    private UpdateNotificationManager() {
    }

    public static void createChannel(Context context) {
        Objects.requireNonNull(context, "context");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(
                context.getString(R.string.update_notification_channel_detail)
        );
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    public static boolean notificationsAllowed(Context context) {
        Objects.requireNonNull(context, "context");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static boolean post(Context context, UpdateRelease release) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(release, "release");
        createChannel(context);
        if (!notificationsAllowed(context)) {
            return false;
        }

        String navigationToken =
                new UpdatePreferences(context).notificationIntentToken();
        if (navigationToken == null) {
            return false;
        }
        Intent intent = new Intent()
                .setClassName(context, "app.plyvanta.MainActivity")
                .setAction(ACTION_SHOW_UPDATE)
                .putExtra(EXTRA_NAVIGATION_TOKEN, navigationToken)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update_notification)
                .setColor(context.getColor(R.color.coral))
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(
                        R.string.update_notification_text,
                        release.getVersionName()
                ))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        try {
            manager.notify(NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException notificationsBlocked) {
            return false;
        }
    }

    public static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    public static boolean isTrustedTapIntent(Context context, Intent intent) {
        if (intent == null || !ACTION_SHOW_UPDATE.equals(intent.getAction())) {
            return false;
        }
        return new UpdatePreferences(context).matchesNotificationIntentToken(
                intent.getStringExtra(EXTRA_NAVIGATION_TOKEN)
        );
    }
}
