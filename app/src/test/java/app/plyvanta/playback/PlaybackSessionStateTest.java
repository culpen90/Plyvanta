package app.plyvanta.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackSessionStateTest {
    private static final String A = "aaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbb";

    @Test
    public void autoplayIntentWaitsForForegroundAndResumesWhenStartedAgain() {
        PlaybackSessionState state = new PlaybackSessionState();

        assertFalse(state.shouldPlayWhenReady());
        state.onStart();
        assertTrue(state.shouldPlayWhenReady());

        state.onStop();
        state.onPlayerPlayWhenReadyChanged(false);
        assertFalse(state.shouldPlayWhenReady());
        assertTrue(state.intendedPlayWhenReady());

        state.onStart();
        assertTrue(state.shouldPlayWhenReady());
    }

    @Test
    public void userPauseRemainsPausedAcrossLifecycleChanges() {
        PlaybackSessionState state = new PlaybackSessionState();
        state.onStart();
        state.onPlayerPlayWhenReadyChanged(false);

        state.onStop();
        state.onStart();

        assertFalse(state.intendedPlayWhenReady());
        assertFalse(state.shouldPlayWhenReady());
    }

    @Test
    public void requestWhileStoppedIsDeferredUntilStart() {
        PlaybackSessionState state = new PlaybackSessionState();
        state.requestPlayWhenReady(false);
        state.requestPlayWhenReady(true);

        assertFalse(state.shouldPlayWhenReady());
        state.onStart();
        assertTrue(state.shouldPlayWhenReady());
    }

    @Test
    public void unpreparedSelectionNeverSavesThePreviousPlayerPosition() {
        assertEquals(
                0L,
                PlaybackSessionState.positionToSave(
                        false,
                        PlaybackSessionState.NO_PENDING_SEEK,
                        600_000L
                )
        );
        assertEquals(
                42_000L,
                PlaybackSessionState.positionToSave(
                        true,
                        PlaybackSessionState.NO_PENDING_SEEK,
                        42_000L
                )
        );
    }

    @Test
    public void pendingRestoreOrRetryPositionIsAuthoritativeUntilConsumed() {
        assertEquals(
                75_000L,
                PlaybackSessionState.positionToSave(false, 75_000L, 600_000L)
        );
        assertEquals(
                75_000L,
                PlaybackSessionState.positionToSave(true, 75_000L, 0L)
        );
    }

    @Test
    public void restoredSeekRequiresTheSameVideoIdentity() {
        assertEquals(
                30_000L,
                PlaybackSessionState.seekForRestoredVideo(30_000L, A, A)
        );
        assertEquals(
                PlaybackSessionState.NO_PENDING_SEEK,
                PlaybackSessionState.seekForRestoredVideo(30_000L, A, B)
        );
        assertEquals(
                PlaybackSessionState.NO_PENDING_SEEK,
                PlaybackSessionState.seekForRestoredVideo(30_000L, null, A)
        );
    }

    @Test
    public void playlistIndexRequiresAVideoAnchor() {
        assertEquals(
                -1,
                PlaybackSessionState.anchoredPlaylistIndex(4, 2, null)
        );
        assertEquals(
                -1,
                PlaybackSessionState.anchoredPlaylistIndex(4, 2, " ")
        );
        assertEquals(
                4,
                PlaybackSessionState.anchoredPlaylistIndex(4, 2, A)
        );
        assertEquals(
                2,
                PlaybackSessionState.anchoredPlaylistIndex(-1, 2, A)
        );
    }

    @Test
    public void negativeSavedPositionBecomesNoPendingSeek() {
        assertEquals(
                PlaybackSessionState.NO_PENDING_SEEK,
                PlaybackSessionState.restoredPosition(-1L)
        );
        assertFalse(
                PlaybackSessionState.hasPendingSeek(
                        PlaybackSessionState.NO_PENDING_SEEK
                )
        );
        assertEquals(0L, PlaybackSessionState.restoredPosition(0L));
        assertTrue(PlaybackSessionState.hasPendingSeek(0L));
    }
}
