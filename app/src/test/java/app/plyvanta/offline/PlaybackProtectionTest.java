package app.plyvanta.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackProtectionTest {
    @Test
    public void overlayProtectionStartsOnAndroid12() {
        assertFalse(PlaybackProtection.shouldHideOverlayWindows(30));
        assertTrue(PlaybackProtection.shouldHideOverlayWindows(31));
        assertTrue(PlaybackProtection.shouldHideOverlayWindows(36));
    }

    @Test
    public void recentsProtectionStartsOnAndroid13() {
        assertFalse(PlaybackProtection.shouldDisableRecentsScreenshots(32));
        assertTrue(PlaybackProtection.shouldDisableRecentsScreenshots(33));
        assertTrue(PlaybackProtection.shouldDisableRecentsScreenshots(36));
    }

    @Test
    public void contentSensitivityStartsOnAndroid15() {
        assertFalse(PlaybackProtection.shouldMarkContentSensitive(34));
        assertTrue(PlaybackProtection.shouldMarkContentSensitive(35));
        assertTrue(PlaybackProtection.shouldMarkContentSensitive(36));
    }
}
