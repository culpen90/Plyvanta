package app.plyvanta.playback;

import java.util.Objects;

/**
 * Stable YouTube metadata for one position in a playlist.
 *
 * <p>The canonical URL is a video page URL, not an extracted media URL, so it
 * remains safe to resolve only when this queue position is ready to play.
 */
public final class PlaylistEntry {
    private final String videoId;
    private final String canonicalUrl;
    private final String title;
    private final String uploader;
    private final long durationSeconds;
    private final String thumbnailUrl;

    public PlaylistEntry(
            String videoId,
            String canonicalUrl,
            String title,
            String uploader,
            long durationSeconds,
            String thumbnailUrl
    ) {
        this.videoId = requireNotBlank(videoId, "videoId");
        this.canonicalUrl = requireNotBlank(canonicalUrl, "canonicalUrl");
        this.title = requireNotBlank(title, "title");
        this.uploader = uploader == null ? "" : uploader;
        this.durationSeconds = Math.max(0L, durationSeconds);
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getUploader() {
        return uploader;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    private static String requireNotBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}
