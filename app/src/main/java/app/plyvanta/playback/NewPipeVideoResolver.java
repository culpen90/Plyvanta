package app.plyvanta.playback;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.Comparator;
import java.util.List;

/**
 * Resolves a YouTube page into content-only media streams. Run on a worker thread.
 */
public final class NewPipeVideoResolver {
    public ResolvedVideo resolve(String canonicalUrl, int preferredMaxHeight) throws Exception {
        StreamInfo info = StreamInfo.getInfo(canonicalUrl);

        String thumbnailUrl = null;
        List<Image> thumbnails = info.getThumbnails();
        if (thumbnails != null && !thumbnails.isEmpty()) {
            thumbnailUrl = thumbnails.get(thumbnails.size() - 1).getUrl();
        }

        if (isLive(info.getStreamType()) && notBlank(info.getHlsUrl())) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.HLS,
                    info.getHlsUrl(),
                    "application/x-mpegURL",
                    null,
                    null,
                    0
            );
        }

        VideoStream progressive = chooseVideo(info.getVideoStreams(), preferredMaxHeight, false);
        VideoStream videoOnly = chooseVideo(
                info.getVideoOnlyStreams(),
                preferredMaxHeight,
                true
        );
        AudioStream audio = chooseAudio(info.getAudioStreams());

        // Prefer a single progressive stream when quality is equal, but do not
        // let a low-resolution progressive stream prevent the user's higher
        // quality limit from selecting a merged video/audio pair.
        boolean mergedIsHigherQuality = videoOnly != null
                && audio != null
                && (progressive == null
                || effectiveHeight(videoOnly) > effectiveHeight(progressive));
        if (mergedIsHigherQuality) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.MERGED,
                    videoOnly.getContent(),
                    mimeType(videoOnly.getFormat(), "video/mp4"),
                    audio.getContent(),
                    mimeType(audio.getFormat(), "audio/mp4"),
                    effectiveHeight(videoOnly)
            );
        }
        if (progressive != null) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.PROGRESSIVE,
                    progressive.getContent(),
                    mimeType(progressive.getFormat(), "video/mp4"),
                    null,
                    null,
                    effectiveHeight(progressive)
            );
        }
        if (videoOnly != null && audio != null) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.MERGED,
                    videoOnly.getContent(),
                    mimeType(videoOnly.getFormat(), "video/mp4"),
                    audio.getContent(),
                    mimeType(audio.getFormat(), "audio/mp4"),
                    effectiveHeight(videoOnly)
            );
        }

        if (notBlank(info.getHlsUrl())) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.HLS,
                    info.getHlsUrl(),
                    "application/x-mpegURL",
                    null,
                    null,
                    0
            );
        }
        if (notBlank(info.getDashMpdUrl())) {
            return result(
                    info,
                    thumbnailUrl,
                    ResolvedVideo.SourceType.DASH,
                    info.getDashMpdUrl(),
                    "application/dash+xml",
                    null,
                    null,
                    0
            );
        }

        throw new IllegalStateException("No playable video stream was returned.");
    }

    private static ResolvedVideo result(
            StreamInfo info,
            String thumbnailUrl,
            ResolvedVideo.SourceType type,
            String videoUrl,
            String videoMime,
            String audioUrl,
            String audioMime,
            int selectedHeight
    ) {
        return new ResolvedVideo(
                info.getId(),
                info.getName(),
                info.getUploaderName(),
                info.getDuration(),
                thumbnailUrl,
                type,
                videoUrl,
                videoMime,
                audioUrl,
                audioMime,
                selectedHeight
        );
    }

    private static VideoStream chooseVideo(
            List<VideoStream> streams,
            int preferredMaxHeight,
            boolean requireVideoOnly
    ) {
        if (streams == null) {
            return null;
        }
        Comparator<VideoStream> byQuality = Comparator
                .comparingInt(NewPipeVideoResolver::effectiveHeight)
                .thenComparingInt(VideoStream::getBitrate);

        VideoStream bestWithinLimit = streams.stream()
                .filter(VideoStream::isUrl)
                .filter(stream -> stream.isVideoOnly() == requireVideoOnly)
                .filter(stream -> effectiveHeight(stream) <= preferredMaxHeight)
                .max(byQuality)
                .orElse(null);
        if (bestWithinLimit != null) {
            return bestWithinLimit;
        }

        return streams.stream()
                .filter(VideoStream::isUrl)
                .filter(stream -> stream.isVideoOnly() == requireVideoOnly)
                .min(byQuality)
                .orElse(null);
    }

    private static AudioStream chooseAudio(List<AudioStream> streams) {
        if (streams == null) {
            return null;
        }
        return streams.stream()
                .filter(AudioStream::isUrl)
                .max(Comparator.comparingInt(stream -> Math.max(
                        stream.getAverageBitrate(),
                        stream.getBitrate()
                )))
                .orElse(null);
    }

    private static int effectiveHeight(VideoStream stream) {
        if (stream.getHeight() > 0) {
            return stream.getHeight();
        }
        String resolution = stream.getResolution();
        if (resolution != null) {
            String digits = resolution.replaceAll("[^0-9].*$", "");
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                // Use the neutral fallback below.
            }
        }
        return 0;
    }

    private static boolean isLive(StreamType streamType) {
        return streamType == StreamType.LIVE_STREAM
                || streamType == StreamType.AUDIO_LIVE_STREAM;
    }

    private static String mimeType(MediaFormat format, String fallback) {
        return format == null ? fallback : format.mimeType;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
