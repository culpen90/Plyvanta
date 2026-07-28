package app.plyvanta.update;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.regex.Pattern;

public final class UpdatePreferences {
    private static final String FILE_NAME = "plyvanta_updates";
    private static final String KEY_VERSION_CODE = "available_version_code";
    private static final String KEY_VERSION_NAME = "available_version_name";
    private static final String KEY_REPOSITORY_FULL_NAME =
            "available_repository_full_name";
    private static final String KEY_APK_URL = "available_apk_url";
    private static final String KEY_RELEASE_URL = "available_release_url";
    private static final String KEY_SHA256 = "available_sha256";
    private static final String KEY_LAST_NOTIFIED_VERSION_CODE =
            "last_notified_version_code";
    private static final String KEY_PERMISSION_EXPLANATION_SHOWN =
            "permission_explanation_shown";
    private static final String KEY_PERMISSION_REQUEST_ATTEMPTED =
            "permission_request_attempted";
    private static final String KEY_NOTIFICATION_INTENT_TOKEN =
            "notification_intent_token";
    private static final String[] HISTORICAL_OFFICIAL_REPOSITORIES = {
            "Plyvanta/Plyvanta",
            "culpen90/Plyvanta"
    };
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEXADECIMAL = "0123456789abcdef".toCharArray();

    private final SharedPreferences preferences;

    public UpdatePreferences(Context context) {
        Objects.requireNonNull(context, "context");
        preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public UpdateRelease availableRelease() {
        boolean repositoryFullNameStored =
                preferences.contains(KEY_REPOSITORY_FULL_NAME);
        UpdateRelease release = restoreStoredRelease(
                preferences.getLong(KEY_VERSION_CODE, 0L),
                preferences.getString(KEY_VERSION_NAME, null),
                repositoryFullNameStored,
                preferences.getString(KEY_REPOSITORY_FULL_NAME, null),
                preferences.getString(KEY_APK_URL, null),
                preferences.getString(KEY_RELEASE_URL, null),
                preferences.getString(KEY_SHA256, null)
        );
        if (release == null) {
            if (hasStoredRelease()) {
                clearAvailableRelease();
            }
            return null;
        }
        if (!repositoryFullNameStored
                && !preferences.edit()
                .putString(
                        KEY_REPOSITORY_FULL_NAME,
                        release.getRepositoryFullName()
                )
                .commit()) {
            clearAvailableRelease();
            return null;
        }
        return release;
    }

    static UpdateRelease restoreStoredRelease(
            long versionCode,
            String versionName,
            boolean repositoryFullNameStored,
            String repositoryFullName,
            String apkUrl,
            String releaseUrl,
            String sha256
    ) {
        if (repositoryFullNameStored) {
            return UpdateRelease.restore(
                    versionCode,
                    versionName,
                    repositoryFullName,
                    apkUrl,
                    releaseUrl,
                    sha256
            );
        }

        UpdateRelease matchedRelease = null;
        for (String historicalRepository : HISTORICAL_OFFICIAL_REPOSITORIES) {
            UpdateRelease candidate = UpdateRelease.restore(
                    versionCode,
                    versionName,
                    historicalRepository,
                    apkUrl,
                    releaseUrl,
                    sha256
            );
            if (candidate == null) {
                continue;
            }
            if (matchedRelease != null) {
                return null;
            }
            matchedRelease = candidate;
        }
        return matchedRelease;
    }

    public boolean storeAvailableRelease(UpdateRelease release) {
        Objects.requireNonNull(release, "release");
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_VERSION_CODE, release.getVersionCode())
                .putString(KEY_VERSION_NAME, release.getVersionName())
                .putString(
                        KEY_REPOSITORY_FULL_NAME,
                        release.getRepositoryFullName()
                )
                .putString(KEY_APK_URL, release.getApkUrl())
                .putString(KEY_RELEASE_URL, release.getReleaseUrl())
                .putString(KEY_SHA256, release.getSha256());
        if (shouldResetLastNotifiedVersion(
                lastNotifiedVersionCode(),
                release.getVersionCode()
        )) {
            editor.remove(KEY_LAST_NOTIFIED_VERSION_CODE);
        }
        return editor.commit();
    }

    static boolean shouldResetLastNotifiedVersion(
            long lastNotifiedVersionCode,
            long availableVersionCode
    ) {
        return lastNotifiedVersionCode > availableVersionCode;
    }

    public void clearAvailableRelease() {
        preferences.edit()
                .remove(KEY_VERSION_CODE)
                .remove(KEY_VERSION_NAME)
                .remove(KEY_REPOSITORY_FULL_NAME)
                .remove(KEY_APK_URL)
                .remove(KEY_RELEASE_URL)
                .remove(KEY_SHA256)
                .apply();
    }

    private boolean hasStoredRelease() {
        return preferences.contains(KEY_VERSION_CODE)
                || preferences.contains(KEY_VERSION_NAME)
                || preferences.contains(KEY_REPOSITORY_FULL_NAME)
                || preferences.contains(KEY_APK_URL)
                || preferences.contains(KEY_RELEASE_URL)
                || preferences.contains(KEY_SHA256);
    }

    public long lastNotifiedVersionCode() {
        return preferences.getLong(KEY_LAST_NOTIFIED_VERSION_CODE, 0L);
    }

    public boolean markNotified(long versionCode) {
        if (versionCode <= 0L) {
            throw new IllegalArgumentException("versionCode must be positive");
        }
        return preferences.edit()
                .putLong(KEY_LAST_NOTIFIED_VERSION_CODE, versionCode)
                .commit();
    }

    public boolean permissionExplanationShown() {
        return preferences.getBoolean(KEY_PERMISSION_EXPLANATION_SHOWN, false);
    }

    public void markPermissionExplanationShown() {
        preferences.edit().putBoolean(KEY_PERMISSION_EXPLANATION_SHOWN, true).apply();
    }

    public boolean permissionRequestAttempted() {
        return preferences.getBoolean(KEY_PERMISSION_REQUEST_ATTEMPTED, false);
    }

    public void markPermissionRequestAttempted() {
        preferences.edit().putBoolean(KEY_PERMISSION_REQUEST_ATTEMPTED, true).apply();
    }

    public synchronized String notificationIntentToken() {
        String existing = preferences.getString(KEY_NOTIFICATION_INTENT_TOKEN, null);
        if (existing != null && TOKEN_PATTERN.matcher(existing).matches()) {
            return existing;
        }

        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        StringBuilder token = new StringBuilder(randomBytes.length * 2);
        for (byte value : randomBytes) {
            int unsigned = value & 0xff;
            token.append(HEXADECIMAL[unsigned >>> 4]);
            token.append(HEXADECIMAL[unsigned & 0x0f]);
        }
        String generated = token.toString();
        return preferences.edit()
                .putString(KEY_NOTIFICATION_INTENT_TOKEN, generated)
                .commit()
                ? generated
                : null;
    }

    public boolean matchesNotificationIntentToken(String candidate) {
        if (candidate == null || !TOKEN_PATTERN.matcher(candidate).matches()) {
            return false;
        }
        String stored = preferences.getString(KEY_NOTIFICATION_INTENT_TOKEN, null);
        return candidate.equals(stored);
    }
}
