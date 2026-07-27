package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateReleaseTest {
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String APK_URL =
            "https://github.com/culpen90/Plyvanta/releases/download/"
                    + "v1.0.0-debug.5/Plyvanta-1.0.0-debug.5.apk";
    private static final String RELEASE_URL =
            "https://github.com/culpen90/Plyvanta/releases/tag/v1.0.0-debug.5";

    @Test
    public void comparesUsingMonotonicAndroidVersionCode() {
        UpdateRelease release = new UpdateRelease(
                5,
                "1.0.0-debug.5",
                APK_URL,
                RELEASE_URL,
                SHA256
        );

        assertTrue(release.isNewerThan(4));
        assertFalse(release.isNewerThan(5));
        assertFalse(release.isNewerThan(6));
    }

    @Test
    public void storedReleaseIsRevalidatedBeforeUse() {
        UpdateRelease restored = UpdateRelease.restore(
                5,
                "1.0.0-debug.5",
                APK_URL,
                RELEASE_URL,
                SHA256.toUpperCase()
        );
        UpdateRelease forged = UpdateRelease.restore(
                5,
                "1.0.0-debug.5",
                "https://example.com/update.apk",
                RELEASE_URL,
                SHA256
        );

        assertEquals(SHA256, restored.getSha256());
        assertNull(forged);
    }

    @Test
    public void rejectsHttpCredentialsAndLookalikeRepositoryPaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        5,
                        "1.0.0-debug.5",
                        APK_URL.replace("https://", "http://"),
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        5,
                        "1.0.0-debug.5",
                        APK_URL.replace("github.com/", "user:pass@github.com/"),
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        5,
                        "1.0.0-debug.5",
                        APK_URL.replace("/culpen90/Plyvanta/", "/attacker/Plyvanta/"),
                        RELEASE_URL,
                        SHA256
                )
        );
    }

    @Test
    public void channelVersionFormatsAreStrict() {
        assertTrue(UpdateChannel.PREVIEW.matchesVersion("1.2.3-debug.10"));
        assertFalse(UpdateChannel.PREVIEW.matchesVersion("1.2.3"));
        assertTrue(UpdateChannel.STABLE.matchesVersion("1.2.3"));
        assertFalse(UpdateChannel.STABLE.matchesVersion("1.2.3-beta.1"));
    }
}
