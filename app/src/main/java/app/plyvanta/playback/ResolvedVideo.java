package app.plyvanta.playback;

import java.util.Objects;

public final class ResolvedVideo {
    public enum SourceType {
        PROGRESSIVE,
        MERGED,
        HLS,
        DASH
    }

    private final String videoId;
    private final String title;
    private final String uploader;
    private final long durationSeconds;
    private final String thumbnailUrl;
    private final SourceType sourceType;
    private final String videoUrl;
    private final String videoMimeType;
    private final String audioUrl;
    private final String audioMimeType;
    private final int selectedHeight;

    public ResolvedVideo(
            String videoId,
            String title,
            String uploader,
            long durationSeconds,
            String thumbnailUrl,
            SourceType sourceType,
            String videoUrl,
            String videoMimeType,
            String audioUrl,
            String audioMimeType,
            int selectedHeight
    ) {
        this.videoId = Objects.requireNonNull(videoId);
        this.title = Objects.requireNonNull(title);
        this.uploader = uploader == null ? "" : uploader;
        this.durationSeconds = durationSeconds;
        this.thumbnailUrl = thumbnailUrl;
        this.sourceType = Objects.requireNonNull(sourceType);
        this.videoUrl = Objects.requireNonNull(videoUrl);
        this.videoMimeType = videoMimeType;
        this.audioUrl = audioUrl;
        this.audioMimeType = audioMimeType;
        this.selectedHeight = selectedHeight;
    }

    public String getVideoId() {
        return videoId;
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

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public String getVideoMimeType() {
        return videoMimeType;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public String getAudioMimeType() {
        return audioMimeType;
    }

    public int getSelectedHeight() {
        return selectedHeight;
    }
}
