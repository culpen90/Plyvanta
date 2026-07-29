package app.plyvanta.offline;

import android.app.Activity;
import android.os.Build;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

/**
 * Applies the platform protections available for sensitive offline playback.
 *
 * <p>Call {@link #protectActivity(Activity)} before {@code Activity.setContentView(...)} and
 * {@link #protectVideoSurface(PlayerView)} before the player view is attached to a window.
 */
public final class PlaybackProtection {
    private PlaybackProtection() {
    }

    /**
     * Blocks screenshots and non-secure displays, hides application overlays where supported,
     * and prevents the activity's content from being used for the recents thumbnail.
     */
    public static void protectActivity(@NonNull Activity activity) {
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        if (canHideOverlayWindows()) {
            window.setHideOverlayWindows(true);
        }
        if (canDisableRecentsScreenshots()) {
            activity.setRecentsScreenshotEnabled(false);
        }
    }

    /**
     * Marks Media3's video surface secure before it can be attached to a window.
     *
     * <p>This fails closed for a missing, incompatible, or already attached surface because
     * silently continuing would leave protected playback capturable.
     */
    @OptIn(markerClass = UnstableApi.class)
    public static void protectVideoSurface(@NonNull PlayerView playerView) {
        View videoSurface = playerView.getVideoSurfaceView();
        if (!(videoSurface instanceof SurfaceView)) {
            throw new IllegalStateException(
                    "Protected playback requires PlayerView to use a SurfaceView.");
        }

        SurfaceView surfaceView = (SurfaceView) videoSurface;
        if (surfaceView.isAttachedToWindow()) {
            throw new IllegalStateException(
                    "The video surface must be protected before it is attached to a window.");
        }
        surfaceView.setSecure(true);
    }

    /**
     * Marks app content as sensitive so Android 15+ hides it from remote screen-share viewers.
     */
    public static void protectSensitiveView(@NonNull View view) {
        if (canMarkContentSensitive()) {
            view.setContentSensitivity(View.CONTENT_SENSITIVITY_SENSITIVE);
        }
    }

    static boolean shouldHideOverlayWindows(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.S;
    }

    static boolean shouldDisableRecentsScreenshots(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU;
    }

    static boolean shouldMarkContentSensitive(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private static boolean canHideOverlayWindows() {
        return shouldHideOverlayWindows(Build.VERSION.SDK_INT);
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private static boolean canDisableRecentsScreenshots() {
        return shouldDisableRecentsScreenshots(Build.VERSION.SDK_INT);
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static boolean canMarkContentSensitive() {
        return shouldMarkContentSensitive(Build.VERSION.SDK_INT);
    }
}
