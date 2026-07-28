package app.plyvanta.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class PlaylistQueueTest {
    private static final String A = "aaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbb";
    private static final String C = "ccccccccccc";

    @Test
    public void matchingRequestedIndexSelectsTheIntendedDuplicate() {
        ResolvedPlaylist playlist = playlist(
                entry(A),
                entry(B),
                entry(A),
                entry(C)
        );

        PlaylistQueue queue = new PlaylistQueue(playlist, 2, A);

        assertSame(playlist, queue.getPlaylist());
        assertEquals(2, queue.position());
        assertEquals(A, queue.current().getVideoId());
        assertEquals(4, queue.size());
    }

    @Test
    public void startingVideoRepairsAMismatchedRequestedIndex() {
        PlaylistQueue queue = new PlaylistQueue(
                playlist(entry(A), entry(B), entry(C)),
                0,
                C
        );

        assertEquals(2, queue.position());
        assertEquals(C, queue.current().getVideoId());
    }

    @Test
    public void closestMatchingDuplicateIsUsedWhenIndexShifted() {
        PlaylistQueue queue = new PlaylistQueue(
                playlist(entry(A), entry(B), entry(A), entry(C), entry(A)),
                3,
                A
        );

        assertEquals(2, queue.position());
    }

    @Test
    public void missingStartingVideoFallsBackToValidRequestedIndex() {
        PlaylistQueue queue = new PlaylistQueue(
                playlist(entry(A), entry(B), entry(C)),
                1,
                "zzzzzzzzzzz"
        );

        assertEquals(1, queue.position());
        assertEquals(B, queue.current().getVideoId());
    }

    @Test
    public void invalidIndexUsesStartingVideoOrFirstEntry() {
        ResolvedPlaylist playlist = playlist(entry(A), entry(B), entry(C));

        PlaylistQueue matchingVideo = new PlaylistQueue(playlist, -1, C);
        PlaylistQueue noMatch = new PlaylistQueue(
                playlist,
                Integer.MAX_VALUE,
                "zzzzzzzzzzz"
        );

        assertEquals(2, matchingVideo.position());
        assertEquals(0, noMatch.position());
    }

    @Test
    public void nextAndPreviousMoveWithoutCrossingQueueBoundaries() {
        PlaylistQueue queue = new PlaylistQueue(
                playlist(entry(A), entry(B), entry(C)),
                1,
                B
        );

        assertTrue(queue.hasPrevious());
        assertTrue(queue.hasNext());
        assertTrue(queue.previous());
        assertEquals(A, queue.current().getVideoId());
        assertFalse(queue.hasPrevious());
        assertFalse(queue.previous());
        assertEquals(0, queue.position());

        assertTrue(queue.next());
        assertEquals(B, queue.current().getVideoId());
        assertTrue(queue.next());
        assertEquals(C, queue.current().getVideoId());
        assertFalse(queue.hasNext());
        assertFalse(queue.next());
        assertEquals(2, queue.position());
        assertEquals(C, queue.current().getVideoId());
    }

    @Test
    public void resolvedPlaylistRejectsEmptyAndDefensivelyCopiesEntries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedPlaylist(
                        "https://www.youtube.com/playlist?list=PL1234567890",
                        "Playlist",
                        "Owner",
                        List.of(),
                        true,
                        false,
                        0L
                )
        );

        ArrayList<PlaylistEntry> mutableEntries = new ArrayList<>();
        mutableEntries.add(entry(A));
        ResolvedPlaylist playlist = new ResolvedPlaylist(
                "https://www.youtube.com/playlist?list=PL1234567890",
                "Playlist",
                "Owner",
                mutableEntries,
                true,
                false,
                1L
        );
        mutableEntries.clear();

        assertEquals(1, playlist.getEntries().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> playlist.getEntries().clear()
        );
    }

    private static ResolvedPlaylist playlist(PlaylistEntry... entries) {
        return new ResolvedPlaylist(
                "https://www.youtube.com/playlist?list=PL1234567890",
                "Playlist",
                "Owner",
                List.of(entries),
                true,
                false,
                entries.length
        );
    }

    private static PlaylistEntry entry(String videoId) {
        return new PlaylistEntry(
                videoId,
                "https://www.youtube.com/watch?v=" + videoId,
                "Video " + videoId,
                "Uploader",
                60L,
                null
        );
    }
}
