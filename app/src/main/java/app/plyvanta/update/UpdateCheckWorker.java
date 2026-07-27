package app.plyvanta.update;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class UpdateCheckWorker extends Worker {
    private static final Object WORKER_LOCK = new Object();

    public UpdateCheckWorker(
            @NonNull Context applicationContext,
            @NonNull WorkerParameters workerParameters
    ) {
        super(applicationContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (WORKER_LOCK) {
            return runSerializedCheck();
        }
    }

    private Result runSerializedCheck() {
        Context context = getApplicationContext();
        return new UpdateChecker(context).checkAndComplete(
                checkResult -> completeSerializedCheck(context, checkResult)
        );
    }

    private Result completeSerializedCheck(
            Context context,
            UpdateChecker.Result checkResult
    ) {
        if (checkResult.getStatus() == UpdateChecker.Status.PERMANENT_FAILURE) {
            return Result.failure();
        }
        if (checkResult.getStatus() == UpdateChecker.Status.RETRYABLE_FAILURE) {
            return Result.retry();
        }

        if (checkResult.getCheckedRelease() == null) {
            return Result.success();
        }

        UpdateRelease release = checkResult.getAvailableRelease();
        UpdatePreferences preferences = new UpdatePreferences(context);
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
