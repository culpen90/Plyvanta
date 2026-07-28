package app.plyvanta.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateDownloadVerificationTest {
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Test
    public void appliesOnlyTheCurrentResultWhileResumed() {
        assertTrue(UpdateDownloadVerification.canApplyResult(7L, 7L, true));
        assertFalse(UpdateDownloadVerification.canApplyResult(6L, 7L, true));
        assertFalse(UpdateDownloadVerification.canApplyResult(7L, 7L, false));
        assertFalse(UpdateDownloadVerification.canApplyResult(0L, 0L, true));
    }

    @Test
    public void opensOnlyTheExactReleaseReturnedByTheFreshCheck() {
        UpdateRelease checked = release("Plyvanta/Plyvanta", "1.2.0", 1_002_000L);

        assertSame(
                UpdateDownloadVerification.Action.OPEN_VERIFIED_RELEASE,
                UpdateDownloadVerification.decide(
                        UpdateChecker.Status.SUCCESS,
                        checked,
                        checked
                )
        );
    }

    @Test
    public void staleOwnerCoordinatesRequireARefreshedPrompt() {
        UpdateRelease stale = release("culpen90/Plyvanta", "1.2.0", 1_002_000L);
        UpdateRelease canonical = release(
                "Plyvanta/Plyvanta",
                "1.2.0",
                1_002_000L
        );

        assertSame(
                UpdateDownloadVerification.Action.SHOW_REFRESHED_RELEASE,
                UpdateDownloadVerification.decide(
                        UpdateChecker.Status.SUCCESS,
                        stale,
                        canonical
                )
        );
    }

    @Test
    public void failedOrUnverifiedChecksNeverOpenStoredUrls() {
        UpdateRelease displayed = release(
                "attacker/Plyvanta",
                "1.2.0",
                1_002_000L
        );

        assertSame(
                UpdateDownloadVerification.Action.SHOW_ERROR,
                UpdateDownloadVerification.decide(
                        UpdateChecker.Status.RETRYABLE_FAILURE,
                        displayed,
                        null
                )
        );
        assertSame(
                UpdateDownloadVerification.Action.SHOW_ERROR,
                UpdateDownloadVerification.decide(
                        UpdateChecker.Status.UNVERIFIED_RELEASE,
                        displayed,
                        null
                )
        );
    }

    private static UpdateRelease release(
            String repositoryFullName,
            String versionName,
            long versionCode
    ) {
        return new UpdateRelease(
                versionCode,
                versionName,
                repositoryFullName,
                "https://github.com/" + repositoryFullName
                        + "/releases/download/v" + versionName
                        + "/Plyvanta-" + versionName + ".apk",
                "https://github.com/" + repositoryFullName
                        + "/releases/tag/v" + versionName,
                SHA256
        );
    }
}
