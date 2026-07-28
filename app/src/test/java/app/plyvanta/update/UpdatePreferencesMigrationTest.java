package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdatePreferencesMigrationTest {
    private static final long VERSION_CODE = 1_002_000L;
    private static final String VERSION_NAME = "1.2.0";
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Test
    public void restoresCurrentSchemaUsingItsStoredRepository() {
        String repository = "FutureOwner/Plyvanta";

        UpdateRelease release = restore(
                true,
                repository,
                apkUrl(repository),
                releaseUrl(repository),
                SHA256
        );

        assertEquals(repository, release.getRepositoryFullName());
    }

    @Test
    public void migratesLegacyTupleForCurrentOfficialRepository() {
        assertLegacyRepositoryMigrates("Plyvanta/Plyvanta");
    }

    @Test
    public void migratesLegacyTupleForPreviousOfficialRepository() {
        assertLegacyRepositoryMigrates("culpen90/Plyvanta");
    }

    @Test
    public void rejectsLegacyTupleForForeignRepository() {
        String repository = "attacker/Plyvanta";

        assertNull(restore(
                false,
                null,
                apkUrl(repository),
                releaseUrl(repository),
                SHA256
        ));
    }

    @Test
    public void rejectsIncompleteOrMixedLegacyTuple() {
        assertNull(restore(
                false,
                null,
                null,
                releaseUrl("Plyvanta/Plyvanta"),
                SHA256
        ));
        assertNull(restore(
                false,
                null,
                apkUrl("Plyvanta/Plyvanta"),
                releaseUrl("culpen90/Plyvanta"),
                SHA256
        ));
    }

    @Test
    public void doesNotTreatMalformedCurrentSchemaAsLegacy() {
        assertNull(restore(
                true,
                null,
                apkUrl("Plyvanta/Plyvanta"),
                releaseUrl("Plyvanta/Plyvanta"),
                SHA256
        ));
    }

    @Test
    public void lowerAuthoritativeReleaseResetsHigherNotificationHistory() {
        assertTrue(UpdatePreferences.shouldResetLastNotifiedVersion(
                1_003_000L,
                1_002_000L
        ));
        assertFalse(UpdatePreferences.shouldResetLastNotifiedVersion(
                1_002_000L,
                1_002_000L
        ));
        assertFalse(UpdatePreferences.shouldResetLastNotifiedVersion(
                1_001_000L,
                1_002_000L
        ));
    }

    private static void assertLegacyRepositoryMigrates(String repository) {
        UpdateRelease release = restore(
                false,
                null,
                apkUrl(repository),
                releaseUrl(repository),
                SHA256
        );

        assertEquals(repository, release.getRepositoryFullName());
    }

    private static UpdateRelease restore(
            boolean repositoryFullNameStored,
            String repositoryFullName,
            String apkUrl,
            String releaseUrl,
            String sha256
    ) {
        return UpdatePreferences.restoreStoredRelease(
                VERSION_CODE,
                VERSION_NAME,
                repositoryFullNameStored,
                repositoryFullName,
                apkUrl,
                releaseUrl,
                sha256
        );
    }

    private static String apkUrl(String repository) {
        return "https://github.com/" + repository
                + "/releases/download/v" + VERSION_NAME
                + "/Plyvanta-" + VERSION_NAME + ".apk";
    }

    private static String releaseUrl(String repository) {
        return "https://github.com/" + repository
                + "/releases/tag/v" + VERSION_NAME;
    }
}
