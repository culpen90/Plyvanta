package app.plyvanta.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YouTubeUrlParserTest {
    private static final String ID = "dQw4w9WgXcQ";
    private static final String SECOND_ID = "abcdefghijk";
    private static final String PLAYLIST_ID = "PL1234567890abcdef";
    private static final String CANONICAL = "https://www.youtube.com/watch?v=" + ID;
    private static final String CANONICAL_PLAYLIST =
            "https://www.youtube.com/playlist?list=" + PLAYLIST_ID;

    @Test
    public void parsesRawVideoId() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(ID));
    }

    @Test
    public void parsesWatchUrlsOnSupportedHosts() {
        assertEquals(
                ID,
                YouTubeUrlParser.extractVideoId(
                        "https://www.youtube.com/watch?v=" + ID + "&feature=share"));
        assertEquals(
                ID,
                YouTubeUrlParser.extractVideoId("https://youtube.com/watch?list=abc&v=" + ID));
        assertEquals(
                ID, YouTubeUrlParser.extractVideoId("https://m.youtube.com/watch?v=" + ID));
        assertEquals(
                ID, YouTubeUrlParser.extractVideoId("https://music.youtube.com/watch?v=" + ID));
    }

    @Test
    public void parsesShortLiveAndEmbedUrls() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "?si=abc"));
        assertEquals(
                ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/shorts/" + ID));
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtube.com/live/" + ID));
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtube.com/embed/" + ID));
    }

    @Test
    public void parsesSchemeLessTrustedUrls() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("youtu.be/" + ID));
        assertEquals(
                ID, YouTubeUrlParser.extractVideoId("www.youtube.com/watch?v=" + ID));
    }

    @Test
    public void extractsUrlFromSharedText() {
        String shareText =
                "This is worth watching:\nhttps://youtu.be/" + ID + "?si=share-token\nEnjoy!";
        assertEquals(ID, YouTubeUrlParser.extractVideoId(shareText));

        assertEquals(
                ID,
                YouTubeUrlParser.extractVideoId(
                        "Try this one (" + CANONICAL + "). Sent from YouTube"));
    }

    @Test
    public void skipsUntrustedUrlBeforeTrustedUrlInSharedText() {
        String shareText =
                "Ignore https://example.com/watch?v="
                        + ID
                        + " and use https://youtube.com/watch?v="
                        + ID;
        assertEquals(ID, YouTubeUrlParser.extractVideoId(shareText));
    }

    @Test
    public void rejectsTrustedHostTextEmbeddedInAnUntrustedSchemeLessPath() {
        for (String input : new String[] {
                "example.com/youtube.com/watch?v=" + ID,
                "attacker.test/path/youtu.be/" + ID,
                "attacker.test\\youtube.com\\youtube.com/watch?v=" + ID,
                "attacker.test/?next=youtube.com/watch?v=" + ID,
                "attacker.test?x=youtu.be/" + ID,
                "evil:youtube.com/watch?v=" + ID,
                "ftp://youtube.com/watch?v=" + ID,
                "evilhttps://youtube.com/watch?v=" + ID,
                "evilwww.youtube.com/watch?v=" + ID
        }) {
            assertNull(input, YouTubeUrlParser.extractVideoId(input));
            assertNull(input, YouTubeUrlParser.parsePlayable(input));
        }
    }

    @Test
    public void canonicalizesEveryAcceptedForm() {
        assertEquals(CANONICAL, YouTubeUrlParser.canonicalize(ID));
        assertEquals(CANONICAL, YouTubeUrlParser.canonicalize("https://youtu.be/" + ID));
        assertEquals(
                CANONICAL,
                YouTubeUrlParser.canonicalize(
                        "Shared video: https://music.youtube.com/watch?v=" + ID));
        assertEquals(
                CANONICAL,
                YouTubeUrlParser.canonicalize(
                        "https://youtube.com/watch?v=" + ID + "&list=" + PLAYLIST_ID));
        assertNull(YouTubeUrlParser.canonicalize(CANONICAL_PLAYLIST));
    }

    @Test
    public void rejectsUntrustedAndLookalikeHosts() {
        assertNull(YouTubeUrlParser.extractVideoId("https://example.com/watch?v=" + ID));
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com.example.org/watch?v=" + ID));
        assertNull(YouTubeUrlParser.extractVideoId("https://evil-youtube.com/watch?v=" + ID));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://youtube.com@attacker.example/watch?v=" + ID));
        assertNull(YouTubeUrlParser.extractVideoId("https://subdomain.youtube.com/watch?v=" + ID));
    }

    @Test
    public void rejectsClipsAndPlaylistOnlyUrlsFromLegacyVideoParser() {
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com/clip/Ugkx123456789"));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://youtube.com/clip/Ugkx123?v=" + ID));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        CANONICAL_PLAYLIST));
    }

    @Test
    public void rejectsMalformedIdsAndUnsupportedPaths() {
        assertNull(YouTubeUrlParser.extractVideoId("tooShort"));
        assertNull(YouTubeUrlParser.extractVideoId(ID + "x"));
        assertNull(YouTubeUrlParser.extractVideoId("dQw4w9WgXc."));
        assertNull(
                YouTubeUrlParser.extractVideoId("https://youtube.com/watch?v=dQw4w9WgXc"));
        assertNull(
                YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "/unexpected"));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://youtube.com/shorts/" + ID + "/unexpected"));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://youtube.com/watch?v=" + ID + "&v=abcdefghijk"));
    }

    @Test
    public void doesNotExtractBareIdFromArbitraryText() {
        assertNull(YouTubeUrlParser.extractVideoId("The video ID is " + ID));
    }

    @Test
    public void handlesNullAndBlankInput() {
        assertNull(YouTubeUrlParser.extractVideoId(null));
        assertNull(YouTubeUrlParser.extractVideoId(""));
        assertNull(YouTubeUrlParser.extractVideoId("   \n\t"));
        assertNull(YouTubeUrlParser.canonicalize(null));
    }

    @Test
    public void validatesVideoIds() {
        assertTrue(YouTubeUrlParser.isValidVideoId(ID));
        assertTrue(YouTubeUrlParser.isValidVideoId("_-23456789A"));
        assertFalse(YouTubeUrlParser.isValidVideoId(null));
        assertFalse(YouTubeUrlParser.isValidVideoId("abcdefghij"));
        assertFalse(YouTubeUrlParser.isValidVideoId("abcdefghijk!"));
    }

    @Test
    public void parsesRawVideoIdAsSinglePlayableVideo() {
        YouTubeUrlParser.PlayableLink playable = YouTubeUrlParser.parsePlayable(ID);

        assertSingleVideo(playable, ID);
    }

    @Test
    public void parsesPlaylistOnlyUrlsOnSupportedHosts() {
        for (String input : new String[] {
                CANONICAL_PLAYLIST,
                "https://youtube.com/playlist/?list=" + PLAYLIST_ID,
                "https://m.youtube.com/playlist?list=" + PLAYLIST_ID,
                "https://music.youtube.com/playlist?list=" + PLAYLIST_ID,
                "www.youtube.com/playlist?list=" + PLAYLIST_ID
        }) {
            YouTubeUrlParser.PlayableLink playable = YouTubeUrlParser.parsePlayable(input);

            assertPlaylist(playable, null, -1, CANONICAL_PLAYLIST);
        }
    }

    @Test
    public void parsesPlaylistFromSharedTextAfterUntrustedUrl() {
        String sharedText = "Ignore https://attacker.example/playlist?list="
                + PLAYLIST_ID
                + " and play ("
                + CANONICAL_PLAYLIST
                + ").";

        assertPlaylist(
                YouTubeUrlParser.parsePlayable(sharedText),
                null,
                -1,
                CANONICAL_PLAYLIST
        );
    }

    @Test
    public void keepsWatchPlaylistContextAndNormalizesIndex() {
        YouTubeUrlParser.PlayableLink playable = YouTubeUrlParser.parsePlayable(
                "https://music.youtube.com/watch?index=0003&list="
                        + PLAYLIST_ID
                        + "&v="
                        + ID
                        + "&feature=share"
        );

        assertPlaylist(
                playable,
                ID,
                2,
                CANONICAL + "&list=" + PLAYLIST_ID + "&index=3"
        );
    }

    @Test
    public void parsesVideoRoutesAndShortLinksWithPlaylistContext() {
        for (String input : new String[] {
                "https://youtube.com/shorts/" + ID + "?list=" + PLAYLIST_ID + "&index=1",
                "https://m.youtube.com/live/" + ID + "?list=" + PLAYLIST_ID + "&index=1",
                "https://music.youtube.com/embed/" + ID + "?list=" + PLAYLIST_ID + "&index=1",
                "youtu.be/" + ID + "?list=" + PLAYLIST_ID + "&index=1"
        }) {
            assertPlaylist(
                    YouTubeUrlParser.parsePlayable(input),
                    ID,
                    0,
                    CANONICAL + "&list=" + PLAYLIST_ID + "&index=1"
            );
        }
    }

    @Test
    public void parsesShortGeneratedMixIdsOnlyWhenSeededByAVideo() {
        for (String mixId : new String[] {"RDMM", "RDGM"}) {
            assertPlaylist(
                    YouTubeUrlParser.parsePlayable(
                            CANONICAL + "&list=" + mixId),
                    ID,
                    mixId,
                    -1,
                    CANONICAL + "&list=" + mixId
            );
            assertNull(
                    YouTubeUrlParser.parsePlayable(
                            "https://youtube.com/playlist?list=" + mixId)
            );
        }
    }

    @Test
    public void parsesSingleVideoUrlsWithoutPlaylistContext() {
        for (String input : new String[] {
                CANONICAL + "&feature=share",
                "https://youtube.com/shorts/" + ID,
                "https://youtube.com/live/" + ID,
                "https://youtube.com/embed/" + ID,
                "https://youtu.be/" + ID + "?si=abc",
                "Shared video: music.youtube.com/watch?v=" + ID
        }) {
            assertSingleVideo(YouTubeUrlParser.parsePlayable(input), ID);
        }
    }

    @Test
    public void playlistOnlyIndexIsNormalizedAndKeptInCanonicalUrl() {
        YouTubeUrlParser.PlayableLink playable = YouTubeUrlParser.parsePlayable(
                CANONICAL_PLAYLIST + "&index=2"
        );

        assertPlaylist(playable, null, 1, CANONICAL_PLAYLIST + "&index=2");
    }

    @Test
    public void rejectsInvalidOrDuplicatePlaylistParameters() {
        for (String input : new String[] {
                "https://youtube.com/playlist?list=short",
                "https://youtube.com/playlist?list=PL12345+678",
                "https://youtube.com/playlist?list=" + PLAYLIST_ID + "&list=" + PLAYLIST_ID,
                "https://youtube.com/watch?v=" + ID + "&list=short",
                "https://youtube.com/watch?v=" + ID + "&list=R%44MM",
                "https://youtube.com/watch?v=" + ID + "&list=" + PLAYLIST_ID
                        + "&list=PLabcdefghij",
                "https://youtu.be/" + ID + "?list=bad%20playlist"
        }) {
            assertNull(input, YouTubeUrlParser.parsePlayable(input));
        }
    }

    @Test
    public void rejectsInvalidOrDuplicateVideoParameters() {
        for (String input : new String[] {
                "https://youtube.com/watch?v=tooShort&list=" + PLAYLIST_ID,
                "https://youtube.com/watch?v=" + ID + "&v=" + SECOND_ID
                        + "&list=" + PLAYLIST_ID,
                "https://youtube.com/playlist?list=" + PLAYLIST_ID + "&v=" + ID,
                "https://youtu.be/" + ID + "?v=" + ID + "&list=" + PLAYLIST_ID,
                "https://youtube.com/shorts/" + ID + "?v=" + ID + "&list=" + PLAYLIST_ID
        }) {
            assertNull(input, YouTubeUrlParser.parsePlayable(input));
        }
    }

    @Test
    public void rejectsInvalidOrDuplicatePlaylistIndexes() {
        for (String input : new String[] {
                CANONICAL_PLAYLIST + "&index=0",
                CANONICAL_PLAYLIST + "&index=-1",
                CANONICAL_PLAYLIST + "&index=1.5",
                CANONICAL_PLAYLIST + "&index=2147483648",
                CANONICAL_PLAYLIST + "&index=1&index=2",
                CANONICAL + "&list=" + PLAYLIST_ID + "&index="
        }) {
            assertNull(input, YouTubeUrlParser.parsePlayable(input));
        }
    }

    @Test
    public void rejectsUnsupportedRoutesAndHostLookalikesForPlayableLinks() {
        for (String input : new String[] {
                "https://example.com/playlist?list=" + PLAYLIST_ID,
                "https://youtube.com.example.org/playlist?list=" + PLAYLIST_ID,
                "https://evil-youtube.com/playlist?list=" + PLAYLIST_ID,
                "https://youtube.com@attacker.example/playlist?list=" + PLAYLIST_ID,
                "https://user@youtube.com/playlist?list=" + PLAYLIST_ID,
                "https://subdomain.youtube.com/playlist?list=" + PLAYLIST_ID,
                "https://youtube.com/clip/Ugkx123?list=" + PLAYLIST_ID,
                "https://youtube.com/channel/UC123?list=" + PLAYLIST_ID,
                "https://youtu.be/?list=" + PLAYLIST_ID
        }) {
            assertNull(input, YouTubeUrlParser.parsePlayable(input));
        }
    }

    @Test
    public void playlistIdLengthBoundsAreInclusive() {
        String minimum = "A123456789";
        String maximum = "P".repeat(128);

        assertPlaylist(
                YouTubeUrlParser.parsePlayable(
                        "https://youtube.com/playlist?list=" + minimum),
                null,
                minimum,
                -1,
                "https://www.youtube.com/playlist?list=" + minimum
        );
        assertPlaylist(
                YouTubeUrlParser.parsePlayable(
                        "https://youtube.com/playlist?list=" + maximum),
                null,
                maximum,
                -1,
                "https://www.youtube.com/playlist?list=" + maximum
        );
        assertNull(
                YouTubeUrlParser.parsePlayable(
                        "https://youtube.com/playlist?list=" + "A".repeat(9))
        );
        assertNull(
                YouTubeUrlParser.parsePlayable(
                        "https://youtube.com/playlist?list=" + "A".repeat(129))
        );
    }

    @Test
    public void handlesNullBlankAndArbitraryTextForPlayableLinks() {
        assertNull(YouTubeUrlParser.parsePlayable(null));
        assertNull(YouTubeUrlParser.parsePlayable(""));
        assertNull(YouTubeUrlParser.parsePlayable("  \n\t "));
        assertNull(YouTubeUrlParser.parsePlayable("The playlist ID is " + PLAYLIST_ID));
        assertNull(YouTubeUrlParser.parsePlayable("The video ID is " + ID));
    }

    private static void assertSingleVideo(
            YouTubeUrlParser.PlayableLink playable,
            String videoId
    ) {
        assertTrue(playable != null);
        assertFalse(playable.isPlaylist());
        assertEquals("https://www.youtube.com/watch?v=" + videoId, playable.getCanonicalUrl());
        assertNull(playable.getPlaylistUrl());
        assertEquals(videoId, playable.getVideoId());
        assertNull(playable.getPlaylistId());
        assertEquals(-1, playable.getPlaylistIndex());
    }

    private static void assertPlaylist(
            YouTubeUrlParser.PlayableLink playable,
            String videoId,
            int playlistIndex,
            String canonicalUrl
    ) {
        assertPlaylist(playable, videoId, PLAYLIST_ID, playlistIndex, canonicalUrl);
    }

    private static void assertPlaylist(
            YouTubeUrlParser.PlayableLink playable,
            String videoId,
            String playlistId,
            int playlistIndex,
            String canonicalUrl
    ) {
        assertTrue(playable != null);
        assertTrue(playable.isPlaylist());
        assertEquals(canonicalUrl, playable.getCanonicalUrl());
        assertEquals(
                "https://www.youtube.com/playlist?list=" + playlistId,
                playable.getPlaylistUrl()
        );
        assertEquals(videoId, playable.getVideoId());
        assertEquals(playlistId, playable.getPlaylistId());
        assertEquals(playlistIndex, playable.getPlaylistIndex());
    }
}
