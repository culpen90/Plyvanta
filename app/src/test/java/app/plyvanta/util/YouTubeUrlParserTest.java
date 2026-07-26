package app.plyvanta.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YouTubeUrlParserTest {
    private static final String ID = "dQw4w9WgXcQ";
    private static final String CANONICAL = "https://www.youtube.com/watch?v=" + ID;

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
    public void canonicalizesEveryAcceptedForm() {
        assertEquals(CANONICAL, YouTubeUrlParser.canonicalize(ID));
        assertEquals(CANONICAL, YouTubeUrlParser.canonicalize("https://youtu.be/" + ID));
        assertEquals(
                CANONICAL,
                YouTubeUrlParser.canonicalize(
                        "Shared video: https://music.youtube.com/watch?v=" + ID));
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
    public void rejectsClipsAndPlaylistOnlyUrls() {
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com/clip/Ugkx123456789"));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://youtube.com/clip/Ugkx123?v=" + ID));
        assertNull(
                YouTubeUrlParser.extractVideoId(
                        "https://www.youtube.com/playlist?list=PL123456789"));
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
}
