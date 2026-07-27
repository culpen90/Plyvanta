package app.plyvanta.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

public final class UpdateCheckWorker extends Worker {
    private static final Object CHECK_LOCK = new Object();

    public UpdateCheckWorker(
            @NonNull Context applicationContext,
            @NonNull WorkerParameters workerParameters
    ) {
        super(applicationContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (CHECK_LOCK) {
            return runSerializedCheck();
        }
    }

    private Result runSerializedCheck() {
        Context context = getApplicationContext();
        final PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    0
            );
        } catch (PackageManager.NameNotFoundException missingOwnPackage) {
            return Result.failure();
        }

        long installedVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
        boolean debuggable =
                (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        UpdateChannel channel = UpdateChannel.forDebuggableApp(debuggable);
        UpdatePreferences preferences = new UpdatePreferences(context);
        UpdateRelease storedRelease = preferences.availableRelease();

        if (storedRelease != null && !storedRelease.isNewerThan(installedVersionCode)) {
            preferences.clearAvailableRelease();
            storedRelease = null;
            UpdateNotificationManager.cancel(context);
        }

        final UpdateRelease fetchedRelease;
        try {
            fetchedRelease = new GitHubReleaseClient().fetchLatestUpdate(
                    installedVersionCode,
                    context.getPackageName(),
                    channel,
                    Build.VERSION.SDK_INT
            );
        } catch (IOException transientFailure) {
            return Result.retry();
        }

        UpdateRelease release = fetchedRelease;
        if (release == null) {
            return Result.success();
        }
        if (storedRelease != null
                && storedRelease.getVersionCode() > release.getVersionCode()) {
            release = storedRelease;
        } else if (!preferences.storeAvailableRelease(release)) {
            return Result.retry();
        }

        if (preferences.lastNotifiedVersionCode() >= release.getVersionCode()) {
            return Result.success();
        }
        if (UpdateNotificationManager.post(context, release)) {
            if (!preferences.markNotified(release.getVersionCode())) {
                return Result.retry();
            }
        }
        return Result.success();
    }
}
