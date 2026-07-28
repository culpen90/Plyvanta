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
    private static final String REPOSITORY = "Plyvanta/Plyvanta";
    private static final String APK_URL =
            "https://github.com/Plyvanta/Plyvanta/releases/download/"
                    + "v1.2.0/Plyvanta-1.2.0.apk";
    private static final String RELEASE_URL =
            "https://github.com/Plyvanta/Plyvanta/releases/tag/v1.2.0";

    @Test
    public void comparesExactReleaseUsingMonotonicAndroidVersionCode() {
        UpdateRelease release = new UpdateRelease(
                1_002_000L,
                "1.2.0",
                REPOSITORY,
                APK_URL,
                RELEASE_URL,
                SHA256
        );

        assertTrue(release.isNewerThan(1_001_000L));
        assertFalse(release.isNewerThan(1_002_000L));
        assertFalse(release.isNewerThan(1_003_000L));
    }

    @Test
    public void storedReleaseIsRevalidatedAgainstItsResolvedRepository() {
        UpdateRelease restored = UpdateRelease.restore(
                1_002_000L,
                "1.2.0",
                REPOSITORY,
                APK_URL,
                RELEASE_URL,
                SHA256.toUpperCase()
        );
        UpdateRelease forged = UpdateRelease.restore(
                1_002_000L,
                "1.2.0",
                REPOSITORY,
                "https://example.com/Plyvanta-1.2.0.apk",
                RELEASE_URL,
                SHA256
        );
        UpdateRelease mixedRepository = UpdateRelease.restore(
                1_002_000L,
                "1.2.0",
                "attacker/Plyvanta",
                APK_URL,
                RELEASE_URL,
                SHA256
        );

        assertEquals(SHA256, restored.getSha256());
        assertEquals(REPOSITORY, restored.getRepositoryFullName());
        assertNull(forged);
        assertNull(mixedRepository);
    }

    @Test
    public void rejectsHttpCredentialsQueriesAndLookalikeRepositoryPaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        1_002_000L,
                        "1.2.0",
                        REPOSITORY,
                        APK_URL.replace("https://", "http://"),
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        1_002_000L,
                        "1.2.0",
                        REPOSITORY,
                        APK_URL.replace(
                                "/Plyvanta/Plyvanta/",
                                "/Plyvanta%2FPlyvanta/"
                        ),
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        1_002_000L,
                        "1.2.0",
                        REPOSITORY,
                        APK_URL.replace("github.com/", "user:pass@github.com/"),
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        1_002_000L,
                        "1.2.0",
                        REPOSITORY,
                        APK_URL + "?download=1",
                        RELEASE_URL,
                        SHA256
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateRelease(
                        1_002_000L,
                        "1.2.0",
                        REPOSITORY,
                        APK_URL.replace("/Plyvanta/Plyvanta/", "/attacker/Plyvanta/"),
                        RELEASE_URL,
                        SHA256
                )
        );
    }

    @Test
    public void assetApiUrlRequiresCanonicalCoordinatesAndNumericAssetId() {
        String assetApiUrl =
                "https://api.github.com/repos/Plyvanta/Plyvanta/releases/assets/492922383";

        assertTrue(UpdateRelease.isTrustedAssetApiUrl(assetApiUrl, REPOSITORY));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace(
                        "/Plyvanta/Plyvanta/",
                        "/attacker/Plyvanta/"
                ),
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace("492922383", "asset"),
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace("492922383", "0"),
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace("492922383", "00"),
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl + "?download=1",
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace(
                        "/Plyvanta/Plyvanta/",
                        "/Plyvanta%2FPlyvanta/"
                ),
                REPOSITORY
        ));
        assertFalse(UpdateRelease.isTrustedAssetApiUrl(
                assetApiUrl.replace("api.github.com", "example.com"),
                REPOSITORY
        ));
    }

    @Test
    public void repositoryFullNameRequiresExactlyTwoSafeComponents() {
        assertTrue(UpdateRelease.isTrustedRepositoryFullName(REPOSITORY));
        assertTrue(UpdateRelease.isTrustedRepositoryFullName("owner-name/repo.name"));
        assertFalse(UpdateRelease.isTrustedRepositoryFullName(null));
        assertFalse(UpdateRelease.isTrustedRepositoryFullName("Plyvanta"));
        assertFalse(UpdateRelease.isTrustedRepositoryFullName("Plyvanta/Plyvanta/extra"));
        assertFalse(UpdateRelease.isTrustedRepositoryFullName("../Plyvanta"));
        assertFalse(UpdateRelease.isTrustedRepositoryFullName("Plyvanta/repo name"));
    }

    @Test
    public void channelVersionFormatsAndNumericOrderingAreStrict() {
        assertTrue(UpdateChannel.PREVIEW.matchesVersion("1.2.3-debug.10"));
        assertFalse(UpdateChannel.PREVIEW.matchesVersion("1.2.3"));
        assertTrue(UpdateChannel.STABLE.matchesVersion("1.2.3"));
        assertFalse(UpdateChannel.STABLE.matchesVersion("1.2.3-beta.1"));

        assertTrue(UpdateChannel.STABLE.isNewerVersion("1.2.0", "1.1.0"));
        assertTrue(UpdateChannel.STABLE.isNewerVersion("1.10.0", "1.9.99"));
        assertFalse(UpdateChannel.STABLE.isNewerVersion("1.2.0", "1.2.0"));
        assertFalse(UpdateChannel.STABLE.isNewerVersion("1.1.0", "1.2.0"));
        assertFalse(UpdateChannel.STABLE.isNewerVersion(
                "1.2.0-debug.1",
                "1.1.0"
        ));

        assertTrue(UpdateChannel.PREVIEW.isNewerVersion(
                "1.2.0-debug.10",
                "1.2.0-debug.9"
        ));
        assertFalse(UpdateChannel.PREVIEW.isNewerVersion(
                "1.2.0-debug.9",
                "1.2.0-debug.10"
        ));
    }
}
