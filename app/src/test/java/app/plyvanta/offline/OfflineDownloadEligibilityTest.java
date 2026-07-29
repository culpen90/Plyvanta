package app.plyvanta.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.plyvanta.playback.ResolvedVideo;

public final class OfflineDownloadEligibilityTest {
    private static final String VIDEO_URL =
            "https://r1---sn.example.googlevideo.com/videoplayback?id=one";
    private static final String AUDIO_URL =
            "https://r2---sn.example.googlevideo.com/videoplayback?id=two";

    @Test
    public void finiteProgressiveAndMergedTrustedStreamsAreAllowed() {
        assertTrue(OfflineDownloadEligibility.evaluate(progressive(VIDEO_URL)).isAllowed());
        assertTrue(OfflineDownloadEligibility.evaluate(merged()).isAllowed());
    }

    @Test
    public void segmentedAndLiveSourcesAreRejected() {
        ResolvedVideo hls = video(
                ResolvedVideo.SourceType.HLS,
                "https://manifest.example.test/api/manifest/hls_variant/test",
                "application/x-mpegURL",
                null,
                null,
                120L
        );

        assertEquals(
                OfflineDownloadEligibility.Reason.LIVE_OR_SEGMENTED_STREAM,
                OfflineDownloadEligibility.evaluate(hls).getReason()
        );
    }

    @Test
    public void mediaUrlPolicyRejectsDowngradeCredentialsAndForeignHosts() {
        assertTrue(OfflineDownloadEligibility.isTrustedMediaUrl(VIDEO_URL));
        assertFalse(OfflineDownloadEligibility.isTrustedMediaUrl(
                "http://r1.googlevideo.com/videoplayback"
        ));
        assertFalse(OfflineDownloadEligibility.isTrustedMediaUrl(
                "https://user@r1.googlevideo.com/videoplayback"
        ));
        assertFalse(OfflineDownloadEligibility.isTrustedMediaUrl(
                "https://googlevideo.com.attacker.example/videoplayback"
        ));
        assertFalse(OfflineDownloadEligibility.isTrustedMediaUrl(
                "https://example.com/video.mp4"
        ));
    }

    @Test
    public void mergedSourceRequiresTrustedAudioTrack() {
        ResolvedVideo missingAudio = video(
                ResolvedVideo.SourceType.MERGED,
                VIDEO_URL,
                "video/mp4",
                null,
                null,
                120L
        );
        ResolvedVideo foreignAudio = video(
                ResolvedVideo.SourceType.MERGED,
                VIDEO_URL,
                "video/mp4",
                "https://example.com/audio.m4a",
                "audio/mp4",
                120L
        );

        assertEquals(
                OfflineDownloadEligibility.Reason.INVALID_TRACK_LAYOUT,
                OfflineDownloadEligibility.evaluate(missingAudio).getReason()
        );
        assertEquals(
                OfflineDownloadEligibility.Reason.UNTRUSTED_MEDIA_URL,
                OfflineDownloadEligibility.evaluate(foreignAudio).getReason()
        );
    }

    @Test
    public void zeroDurationAndWrongMimeTypeAreRejected() {
        ResolvedVideo zeroDuration = video(
                ResolvedVideo.SourceType.PROGRESSIVE,
                VIDEO_URL,
                "video/mp4",
                null,
                null,
                0L
        );
        ResolvedVideo wrongMime = video(
                ResolvedVideo.SourceType.PROGRESSIVE,
                VIDEO_URL,
                "audio/mp4",
                null,
                null,
                120L
        );

        assertEquals(
                OfflineDownloadEligibility.Reason.INVALID_DURATION,
                OfflineDownloadEligibility.evaluate(zeroDuration).getReason()
        );
        assertEquals(
                OfflineDownloadEligibility.Reason.INVALID_MEDIA_TYPE,
                OfflineDownloadEligibility.evaluate(wrongMime).getReason()
        );
    }

    private static ResolvedVideo progressive(String url) {
        return video(
                ResolvedVideo.SourceType.PROGRESSIVE,
                url,
                "video/mp4",
                null,
                null,
                120L
        );
    }

    private static ResolvedVideo merged() {
        return video(
                ResolvedVideo.SourceType.MERGED,
                VIDEO_URL,
                "video/mp4",
                AUDIO_URL,
                "audio/mp4",
                120L
        );
    }

    private static ResolvedVideo video(
            ResolvedVideo.SourceType type,
            String videoUrl,
            String videoMime,
            String audioUrl,
            String audioMime,
            long durationSeconds
    ) {
        return new ResolvedVideo(
                "abcdefghijk",
                "Example",
                "Uploader",
                durationSeconds,
                null,
                type,
                videoUrl,
                videoMime,
                audioUrl,
                audioMime,
                1080
        );
    }
}
