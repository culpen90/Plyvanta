package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import app.plyvanta.MainActivity;
import app.plyvanta.R;

@RunWith(AndroidJUnit4.class)
public final class UpdateNotificationNavigationTest {
    private static final long WAIT_TIMEOUT_MS = 120_000L;
    private static final String PREFERENCES_NAME = "plyvanta_updates";

    private final AtomicReference<Activity> launchedActivity = new AtomicReference<>();

    private Instrumentation instrumentation;
    private Context context;
    private NotificationManager notificationManager;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        notificationManager = context.getSystemService(NotificationManager.class);
        assertNotNull(notificationManager);

        WorkManager.getInstance(context)
                .cancelAllWork()
                .getResult()
                .get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        notificationManager.cancelAll();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS
            );
        }
        // The headless emulator has no audio device, so use the production channel
        // identity and importance without attempting to play its default sound.
        notificationManager.deleteNotificationChannel(UpdateNotificationManager.CHANNEL_ID);
        NotificationChannel testChannel = new NotificationChannel(
                UpdateNotificationManager.CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        testChannel.setDescription(
                context.getString(R.string.update_notification_channel_detail)
        );
        testChannel.setSound(null, null);
        testChannel.setShowBadge(true);
        notificationManager.createNotificationChannel(testChannel);
    }

    @After
    public void tearDown() {
        notificationManager.cancelAll();
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        Activity activity = launchedActivity.get();
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finishAndRemoveTask);
        }
        UpdateScheduler.schedule(context);
    }

    @Test
    public void notificationOpensUpdateInColdAndWarmActivityThenDownloadsTrustedApk()
            throws Exception {
        long installedVersionCode = installedVersionCode();
        long offeredVersionCode = installedVersionCode + 1L;
        String offeredVersionName = "999.0.0-debug." + offeredVersionCode;
        UpdateRelease release = new UpdateRelease(
                offeredVersionCode,
                offeredVersionName,
                "https://github.com/culpen90/Plyvanta/releases/download/"
                        + "v" + offeredVersionName + "/Plyvanta-"
                        + offeredVersionName + ".apk",
                "https://github.com/culpen90/Plyvanta/releases/tag/v"
                        + offeredVersionName,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        assertTrue(new UpdatePreferences(context).storeAvailableRelease(release));

        PendingIntent updateIntent = postAndFindUpdateIntent(release);
        Instrumentation.ActivityMonitor activityMonitor = instrumentation.addMonitor(
                MainActivity.class.getName(),
                null,
                false
        );
        Activity activity;
        try {
            updateIntent.send();
            activity = instrumentation.waitForMonitorWithTimeout(
                    activityMonitor,
                    WAIT_TIMEOUT_MS
            );
        } finally {
            instrumentation.removeMonitor(activityMonitor);
        }
        launchedActivity.set(activity);
        assertNotNull("Notification did not open MainActivity", activity);
        assertTrue(waitForText(
                context.getString(R.string.update_dialog_title, release.getVersionName())
        ));
        assertTrue(waitForText(context.getString(R.string.download_update)));
        assertNull(readActivityIntentAction(activity));

        assertTrue(clickText(context.getString(R.string.later)));
        assertTrue(waitUntilTextIsAbsent(
                context.getString(R.string.update_dialog_title, release.getVersionName())
        ));

        postAndFindUpdateIntent(release).send();
        assertTrue(waitForText(
                context.getString(R.string.update_dialog_title, release.getVersionName())
        ));
        assertNull(readActivityIntentAction(activity));
        assertSame(activity, resumedMainActivity());

        CapturingActivityMonitor downloadMonitor = new CapturingActivityMonitor();
        instrumentation.addMonitor(downloadMonitor);
        try {
            assertTrue(clickText(context.getString(R.string.download_update)));
            assertTrue(waitForMonitorHit(downloadMonitor));
            Intent downloadIntent = downloadMonitor.capturedIntent();
            assertNotNull(downloadIntent);
            assertEquals(Intent.ACTION_VIEW, downloadIntent.getAction());
            assertEquals(release.getApkUrl(), downloadIntent.getDataString());
            assertTrue(downloadIntent.hasCategory(Intent.CATEGORY_BROWSABLE));
        } finally {
            instrumentation.removeMonitor(downloadMonitor);
        }
    }

    private PendingIntent postAndFindUpdateIntent(UpdateRelease release) {
        assertTrue(UpdateNotificationManager.post(context, release));
        StatusBarNotification updateNotification = null;
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        while (updateNotification == null && SystemClock.uptimeMillis() < deadline) {
            for (StatusBarNotification candidate
                    : notificationManager.getActiveNotifications()) {
                Notification notification = candidate.getNotification();
                if (UpdateNotificationManager.CHANNEL_ID.equals(
                        notification.getChannelId()
                )) {
                    updateNotification = candidate;
                    break;
                }
            }
            if (updateNotification == null) {
                SystemClock.sleep(100L);
            }
        }
        assertNotNull("No update notification was posted", updateNotification);
        Notification notification = updateNotification.getNotification();
        assertEquals(
                context.getString(R.string.update_notification_title),
                notification.extras.getString(Notification.EXTRA_TITLE)
        );
        assertEquals(
                context.getString(
                        R.string.update_notification_text,
                        release.getVersionName()
                ),
                notification.extras.getString(Notification.EXTRA_TEXT)
        );
        assertNotNull(notification.contentIntent);
        return notification.contentIntent;
    }

    private String readActivityIntentAction(Activity activity) {
        AtomicReference<String> action = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> action.set(activity.getIntent().getAction()));
        return action.get();
    }

    private Activity resumedMainActivity() {
        AtomicReference<Activity> resumed = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            Collection<Activity> activities =
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : activities) {
                if (activity instanceof MainActivity) {
                    resumed.set(activity);
                    return;
                }
            }
        });
        return resumed.get();
    }

    private long installedVersionCode() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        }
        return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0)
                .versionCode;
    }

    private boolean waitForText(String text) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNodeWithText(text) != null) {
                return true;
            }
            SystemClock.sleep(100L);
        }
        return false;
    }

    private boolean waitUntilTextIsAbsent(String text) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNodeWithText(text) == null) {
                return true;
            }
            SystemClock.sleep(100L);
        }
        return false;
    }

    private boolean clickText(String text) {
        AccessibilityNodeInfo node = findNodeWithText(text);
        return node != null
                && node.isClickable()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findNodeWithText(String expectedText) {
        AccessibilityNodeInfo root =
                instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            CharSequence nodeText = node.getText();
            if (nodeText != null
                    && "System UI isn't responding".contentEquals(nodeText)) {
                throw new AssertionError(
                        "Device System UI is unresponsive; reboot the test device"
                );
            }
            if (nodeText != null
                    && expectedText.equalsIgnoreCase(nodeText.toString())) {
                return node;
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        return null;
    }

    private boolean waitForMonitorHit(CapturingActivityMonitor monitor) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (monitor.capturedIntent() != null) {
                return true;
            }
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static final class CapturingActivityMonitor
            extends Instrumentation.ActivityMonitor {
        private final AtomicReference<Intent> capturedIntent = new AtomicReference<>();
        private final Instrumentation.ActivityResult blockedResult =
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);

        CapturingActivityMonitor() {
            super();
        }

        @Override
        public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (!Intent.ACTION_VIEW.equals(intent.getAction())
                    || !intent.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                return null;
            }
            capturedIntent.set(new Intent(intent));
            return blockedResult;
        }

        Intent capturedIntent() {
            return capturedIntent.get();
        }
    }
}
