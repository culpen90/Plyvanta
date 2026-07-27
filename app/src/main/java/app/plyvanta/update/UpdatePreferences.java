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
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEXADECIMAL = "0123456789abcdef".toCharArray();

    private final SharedPreferences preferences;

    public UpdatePreferences(Context context) {
        Objects.requireNonNull(context, "context");
        preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public UpdateRelease availableRelease() {
        UpdateRelease release = UpdateRelease.restore(
                preferences.getLong(KEY_VERSION_CODE, 0L),
                preferences.getString(KEY_VERSION_NAME, null),
                preferences.getString(KEY_APK_URL, null),
                preferences.getString(KEY_RELEASE_URL, null),
                preferences.getString(KEY_SHA256, null)
        );
        if (release == null && preferences.contains(KEY_VERSION_CODE)) {
            clearAvailableRelease();
        }
        return release;
    }

    public boolean storeAvailableRelease(UpdateRelease release) {
        Objects.requireNonNull(release, "release");
        return preferences.edit()
                .putLong(KEY_VERSION_CODE, release.getVersionCode())
                .putString(KEY_VERSION_NAME, release.getVersionName())
                .putString(KEY_APK_URL, release.getApkUrl())
                .putString(KEY_RELEASE_URL, release.getReleaseUrl())
                .putString(KEY_SHA256, release.getSha256())
                .commit();
    }

    public void clearAvailableRelease() {
        preferences.edit()
                .remove(KEY_VERSION_CODE)
                .remove(KEY_VERSION_NAME)
                .remove(KEY_APK_URL)
                .remove(KEY_RELEASE_URL)
                .remove(KEY_SHA256)
                .apply();
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
