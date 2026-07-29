package app.plyvanta.offline;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

import app.plyvanta.playback.ResolvedVideo;

/**
 * Fail-closed validation for the direct streams that may enter the encrypted offline vault.
 */
public final class OfflineDownloadEligibility {
    private static final int MAX_DIRECT_URL_CHARS = 8_192;

    public enum Reason {
        ALLOWED,
        LIVE_OR_SEGMENTED_STREAM,
        INVALID_DURATION,
        INVALID_TRACK_LAYOUT,
        UNTRUSTED_MEDIA_URL,
        INVALID_MEDIA_TYPE
    }

    public static final class Decision {
        private final Reason reason;

        private Decision(Reason reason) {
            this.reason = Objects.requireNonNull(reason);
        }

        public boolean isAllowed() {
            return reason == Reason.ALLOWED;
        }

        public Reason getReason() {
            return reason;
        }
    }

    private OfflineDownloadEligibility() {
    }

    public static Decision evaluate(ResolvedVideo video) {
        Objects.requireNonNull(video, "video");
        if (video.getSourceType() != ResolvedVideo.SourceType.PROGRESSIVE
                && video.getSourceType() != ResolvedVideo.SourceType.MERGED) {
            return new Decision(Reason.LIVE_OR_SEGMENTED_STREAM);
        }
        if (video.getDurationSeconds() <= 0L
                || video.getDurationSeconds()
                > OfflineMediaRecord.MAX_DURATION_SECONDS) {
            return new Decision(Reason.INVALID_DURATION);
        }
        if (!isVideoMimeType(video.getVideoMimeType())) {
            return new Decision(Reason.INVALID_MEDIA_TYPE);
        }
        if (!isTrustedMediaUrl(video.getVideoUrl())) {
            return new Decision(Reason.UNTRUSTED_MEDIA_URL);
        }

        if (video.getSourceType() == ResolvedVideo.SourceType.PROGRESSIVE) {
            if (video.getAudioUrl() != null || video.getAudioMimeType() != null) {
                return new Decision(Reason.INVALID_TRACK_LAYOUT);
            }
        } else if (video.getAudioUrl() == null
                || !isAudioMimeType(video.getAudioMimeType())) {
            return new Decision(Reason.INVALID_TRACK_LAYOUT);
        } else if (!isTrustedMediaUrl(video.getAudioUrl())) {
            return new Decision(Reason.UNTRUSTED_MEDIA_URL);
        }
        return new Decision(Reason.ALLOWED);
    }

    public static boolean isTrustedMediaUrl(String value) {
        if (value == null
                || value.length() == 0
                || value.length() > MAX_DIRECT_URL_CHARS) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null
                    || uri.getRawPath().isEmpty()) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.equals("googlevideo.com")
                    || normalizedHost.endsWith(".googlevideo.com");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean isVideoMimeType(String value) {
        return value != null
                && value.length() <= OfflineMediaRecord.MAX_MIME_UTF8_BYTES
                && value.startsWith("video/");
    }

    private static boolean isAudioMimeType(String value) {
        return value != null
                && value.length() <= OfflineMediaRecord.MAX_MIME_UTF8_BYTES
                && value.startsWith("audio/");
    }
}
