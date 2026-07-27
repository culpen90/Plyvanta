package app.plyvanta.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.util.Objects;

public final class UpdateChecker {
    private static final Object CHECK_LOCK = new Object();

    public enum Status {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public static final class Result {
        private final Status status;
        private final UpdateRelease checkedRelease;
        private final UpdateRelease availableRelease;

        private Result(
                Status status,
                UpdateRelease checkedRelease,
                UpdateRelease availableRelease
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.checkedRelease = checkedRelease;
            this.availableRelease = availableRelease;
        }

        public Status getStatus() {
            return status;
        }

        public UpdateRelease getCheckedRelease() {
            return checkedRelease;
        }

        public UpdateRelease getAvailableRelease() {
            return availableRelease;
        }
    }

    interface InstalledAppSource {
        InstalledApp load() throws InstalledAppUnavailableException;
    }

    interface ReleaseSource {
        UpdateRelease fetchLatestUpdate(
                long installedVersionCode,
                String installedPackageName,
                UpdateChannel channel,
                int deviceSdk
        ) throws IOException;
    }

    interface ReleaseStore {
        UpdateRelease availableRelease();

        void clearAvailableRelease();

        boolean storeAvailableRelease(UpdateRelease release);
    }

    interface NotificationCanceller {
        void cancel();
    }

    interface Completion<T> {
        T complete(Result result);
    }

    static final class InstalledApp {
        private final long versionCode;
        private final String packageName;
        private final UpdateChannel channel;
        private final int deviceSdk;

        InstalledApp(
                long versionCode,
                String packageName,
                UpdateChannel channel,
                int deviceSdk
        ) {
            this.versionCode = versionCode;
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.deviceSdk = deviceSdk;
        }
    }

    static final class InstalledAppUnavailableException extends Exception {
        InstalledAppUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private final InstalledAppSource installedAppSource;
    private final ReleaseSource releaseSource;
    private final ReleaseStore releaseStore;
    private final NotificationCanceller notificationCanceller;

    public UpdateChecker(Context context) {
        this(
                new AndroidInstalledAppSource(applicationContext(context)),
                new GitHubReleaseClient()::fetchLatestUpdate,
                new PreferencesReleaseStore(
                        new UpdatePreferences(applicationContext(context))
                ),
                () -> UpdateNotificationManager.cancel(applicationContext(context))
        );
    }

    UpdateChecker(
            InstalledAppSource installedAppSource,
            ReleaseSource releaseSource,
            ReleaseStore releaseStore,
            NotificationCanceller notificationCanceller
    ) {
        this.installedAppSource =
                Objects.requireNonNull(installedAppSource, "installedAppSource");
        this.releaseSource = Objects.requireNonNull(releaseSource, "releaseSource");
        this.releaseStore = Objects.requireNonNull(releaseStore, "releaseStore");
        this.notificationCanceller =
                Objects.requireNonNull(notificationCanceller, "notificationCanceller");
    }

    public Result check() {
        return checkAndComplete(result -> result);
    }

    <T> T checkAndComplete(Completion<T> completion) {
        Objects.requireNonNull(completion, "completion");
        synchronized (CHECK_LOCK) {
            return completion.complete(runSerializedCheck());
        }
    }

    private Result runSerializedCheck() {
        final InstalledApp installedApp;
        try {
            installedApp = installedAppSource.load();
        } catch (InstalledAppUnavailableException missingOwnPackage) {
            return new Result(Status.PERMANENT_FAILURE, null, null);
        }

        UpdateRelease storedRelease = releaseStore.availableRelease();
        if (storedRelease != null
                && !storedRelease.isNewerThan(installedApp.versionCode)) {
            releaseStore.clearAvailableRelease();
            storedRelease = null;
            notificationCanceller.cancel();
        }

        final UpdateRelease fetchedRelease;
        try {
            fetchedRelease = releaseSource.fetchLatestUpdate(
                    installedApp.versionCode,
                    installedApp.packageName,
                    installedApp.channel,
                    installedApp.deviceSdk
            );
        } catch (IOException transientFailure) {
            return new Result(Status.RETRYABLE_FAILURE, null, storedRelease);
        }

        UpdateRelease availableRelease = fetchedRelease;
        if (fetchedRelease == null) {
            return new Result(Status.SUCCESS, null, storedRelease);
        }
        if (storedRelease != null
                && storedRelease.getVersionCode() > fetchedRelease.getVersionCode()) {
            availableRelease = storedRelease;
        } else if (!releaseStore.storeAvailableRelease(fetchedRelease)) {
            return new Result(
                    Status.RETRYABLE_FAILURE,
                    fetchedRelease,
                    storedRelease
            );
        }
        return new Result(Status.SUCCESS, fetchedRelease, availableRelease);
    }

    private static Context applicationContext(Context context) {
        Context checkedContext = Objects.requireNonNull(context, "context");
        Context applicationContext = checkedContext.getApplicationContext();
        return applicationContext == null ? checkedContext : applicationContext;
    }

    private static final class AndroidInstalledAppSource implements InstalledAppSource {
        private final Context context;

        private AndroidInstalledAppSource(Context context) {
            this.context = context;
        }

        @Override
        public InstalledApp load() throws InstalledAppUnavailableException {
            final PackageInfo packageInfo;
            try {
                packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(),
                        0
                );
            } catch (PackageManager.NameNotFoundException missingOwnPackage) {
                throw new InstalledAppUnavailableException(missingOwnPackage);
            }

            long installedVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            boolean debuggable =
                    (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            return new InstalledApp(
                    installedVersionCode,
                    context.getPackageName(),
                    UpdateChannel.forDebuggableApp(debuggable),
                    Build.VERSION.SDK_INT
            );
        }
    }

    private static final class PreferencesReleaseStore implements ReleaseStore {
        private final UpdatePreferences preferences;

        private PreferencesReleaseStore(UpdatePreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public UpdateRelease availableRelease() {
            return preferences.availableRelease();
        }

        @Override
        public void clearAvailableRelease() {
            preferences.clearAvailableRelease();
        }

        @Override
        public boolean storeAvailableRelease(UpdateRelease release) {
            return preferences.storeAvailableRelease(release);
        }
    }
}
