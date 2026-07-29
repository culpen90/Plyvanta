package app.plyvanta.offline;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

/**
 * Fail-closed eligibility checks for device-bound offline media.
 *
 * <p>The policy decision is kept separate from its Android probes so every branch can be
 * exercised by ordinary JVM tests. These checks are defense in depth; they are not a claim that
 * an Android process can reliably identify every compromised device.</p>
 */
public final class OfflineSecurityPolicy {
    public static final int MINIMUM_API_LEVEL = Build.VERSION_CODES.P;

    public enum Reason {
        ALLOWED("Offline storage is available on this device."),
        API_TOO_OLD("Offline storage requires Android 9 or newer."),
        SECURE_LOCK_REQUIRED(
                "Set a secure screen lock before using offline storage."
        ),
        STRONGBOX_REQUIRED(
                "This device does not provide the required StrongBox security hardware."
        ),
        DEBUGGABLE_APP(
                "Offline storage is disabled in debuggable app builds."
        ),
        DEBUGGER_ATTACHED(
                "Offline storage is disabled while a debugger is attached."
        ),
        TEST_KEYS_DETECTED(
                "Offline storage is disabled on devices built with test or development keys."
        ),
        ROOT_INDICATORS_DETECTED(
                "Offline storage is disabled because device-integrity checks failed."
        );

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public interface Environment {
        int apiLevel();

        boolean hasSecureLock();

        boolean hasStrongBox();

        boolean isAppDebuggable();

        boolean isDebuggerAttached();

        boolean hasTestKeys();

        boolean hasRootIndicators();
    }

    public static final class Decision {
        private final Reason reason;

        private Decision(Reason reason) {
            this.reason = Objects.requireNonNull(reason);
        }

        /**
         * Creates an explicit decision for injected non-Android implementations.
         */
        public static Decision forReason(Reason reason) {
            return new Decision(reason);
        }

        public boolean isAllowed() {
            return reason == Reason.ALLOWED;
        }

        public Reason getReason() {
            return reason;
        }

        public String getMessage() {
            return reason.getMessage();
        }

        @Override
        public String toString() {
            return "Decision{" + reason + '}';
        }
    }

    public Decision evaluate(Context context) {
        return evaluate(new AndroidEnvironment(context));
    }

    public Decision evaluate(Environment environment) {
        Objects.requireNonNull(environment, "environment");

        if (environment.apiLevel() < MINIMUM_API_LEVEL) {
            return new Decision(Reason.API_TOO_OLD);
        }
        if (!environment.hasSecureLock()) {
            return new Decision(Reason.SECURE_LOCK_REQUIRED);
        }
        if (!environment.hasStrongBox()) {
            return new Decision(Reason.STRONGBOX_REQUIRED);
        }
        if (environment.isAppDebuggable()) {
            return new Decision(Reason.DEBUGGABLE_APP);
        }
        if (environment.isDebuggerAttached()) {
            return new Decision(Reason.DEBUGGER_ATTACHED);
        }
        if (environment.hasTestKeys()) {
            return new Decision(Reason.TEST_KEYS_DETECTED);
        }
        if (environment.hasRootIndicators()) {
            return new Decision(Reason.ROOT_INDICATORS_DETECTED);
        }
        return new Decision(Reason.ALLOWED);
    }

    private static final class AndroidEnvironment implements Environment {
        // Inlined value of PackageManager.FEATURE_STRONGBOX_KEYSTORE. Passing
        // an unknown feature string is safe on API 26-27, where it returns false.
        private static final String FEATURE_STRONGBOX_KEYSTORE =
                "android.hardware.strongbox_keystore";
        private static final String[] ROOT_PATHS = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/bin/.ext/.su",
                "/system/xbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/data/local/bin/su",
                "/data/local/xbin/su",
                "/su/bin/su",
                "/sbin/.magisk",
                "/data/adb/magisk",
                "/data/adb/ksu",
                "/system/addon.d/99-magisk.sh"
        };

        private final Context context;

        private AndroidEnvironment(Context context) {
            Context applicationContext = Objects.requireNonNull(
                    context,
                    "context"
            ).getApplicationContext();
            this.context = applicationContext == null
                    ? context
                    : applicationContext;
        }

        @Override
        public int apiLevel() {
            return Build.VERSION.SDK_INT;
        }

        @Override
        public boolean hasSecureLock() {
            KeyguardManager keyguardManager =
                    context.getSystemService(KeyguardManager.class);
            return keyguardManager != null && keyguardManager.isDeviceSecure();
        }

        @Override
        public boolean hasStrongBox() {
            return context.getPackageManager().hasSystemFeature(
                    FEATURE_STRONGBOX_KEYSTORE
            );
        }

        @Override
        public boolean isAppDebuggable() {
            return (context.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }

        @Override
        public boolean isDebuggerAttached() {
            return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
        }

        @Override
        public boolean hasTestKeys() {
            String tags = Build.TAGS;
            if (tags == null) {
                return false;
            }
            String normalized = tags.toLowerCase(Locale.US);
            return normalized.contains("test-keys")
                    || normalized.contains("dev-keys");
        }

        @Override
        public boolean hasRootIndicators() {
            for (String path : ROOT_PATHS) {
                try {
                    if (new File(path).exists()) {
                        return true;
                    }
                } catch (SecurityException ignored) {
                    // An inaccessible path is normal under Android's app sandbox.
                }
            }
            return false;
        }
    }
}
