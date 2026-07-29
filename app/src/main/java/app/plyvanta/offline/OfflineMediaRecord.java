package app.plyvanta.offline;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable, URL-free metadata for one encrypted offline video.
 *
 * <p>Track lengths describe authenticated plaintext lengths needed for random access. The
 * encrypted container independently authenticates and validates its exact ciphertext size.</p>
 */
public final class OfflineMediaRecord {
    public static final int MAX_TITLE_UTF8_BYTES = 1_024;
    public static final int MAX_UPLOADER_UTF8_BYTES = 512;
    public static final int MAX_MIME_UTF8_BYTES = 96;
    public static final long MAX_DURATION_SECONDS = 7L * 24L * 60L * 60L;
    public static final int MAX_SELECTED_HEIGHT = 8_640;
    public static final long MAX_PLAINTEXT_TRACK_BYTES =
            128L * 1024L * 1024L * 1024L;
    public static final long MIN_CREATED_AT_EPOCH_MILLIS = 1_104_537_600_000L;
    public static final long MAX_CREATED_AT_EPOCH_MILLIS = 253_402_300_799_999L;

    private static final Pattern VIDEO_ID =
            Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern MIME_TYPE = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+-]{0,31}/"
                    + "[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$"
    );

    public enum SourceType {
        PROGRESSIVE,
        MERGED
    }

    private final UUID itemId;
    private final String videoId;
    private final String title;
    private final String uploader;
    private final long durationSeconds;
    private final SourceType sourceType;
    private final String videoMimeType;
    private final String audioMimeType;
    private final int selectedHeight;
    private final long videoPlaintextLength;
    private final long audioPlaintextLength;
    private final long createdAtEpochMillis;

    public OfflineMediaRecord(
            UUID itemId,
            String videoId,
            String title,
            String uploader,
            long durationSeconds,
            SourceType sourceType,
            String videoMimeType,
            String audioMimeType,
            int selectedHeight,
            long videoPlaintextLength,
            long audioPlaintextLength,
            long createdAtEpochMillis
    ) {
        this.itemId = validateItemId(itemId);
        this.videoId = validateVideoId(videoId);
        this.title = validateText(
                "title",
                title,
                false,
                MAX_TITLE_UTF8_BYTES
        );
        this.uploader = validateText(
                "uploader",
                uploader,
                true,
                MAX_UPLOADER_UTF8_BYTES
        );
        if (durationSeconds <= 0L
                || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "durationSeconds is outside the supported range"
            );
        }
        this.durationSeconds = durationSeconds;
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.videoMimeType = validateMimeType(
                "videoMimeType",
                videoMimeType,
                "video/"
        );
        if (selectedHeight < 0 || selectedHeight > MAX_SELECTED_HEIGHT) {
            throw new IllegalArgumentException(
                    "selectedHeight is outside the supported range"
            );
        }
        this.selectedHeight = selectedHeight;
        this.videoPlaintextLength = validateTrackLength(
                "videoPlaintextLength",
                videoPlaintextLength,
                false
        );

        if (sourceType == SourceType.PROGRESSIVE) {
            if (audioMimeType != null || audioPlaintextLength != 0L) {
                throw new IllegalArgumentException(
                        "progressive records cannot contain a separate audio track"
                );
            }
            this.audioMimeType = null;
            this.audioPlaintextLength = 0L;
        } else {
            this.audioMimeType = validateMimeType(
                    "audioMimeType",
                    audioMimeType,
                    "audio/"
            );
            this.audioPlaintextLength = validateTrackLength(
                    "audioPlaintextLength",
                    audioPlaintextLength,
                    false
            );
        }

        if (createdAtEpochMillis < MIN_CREATED_AT_EPOCH_MILLIS
                || createdAtEpochMillis > MAX_CREATED_AT_EPOCH_MILLIS) {
            throw new IllegalArgumentException(
                    "createdAtEpochMillis is outside the supported range"
            );
        }
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public UUID getItemId() {
        return itemId;
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

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getVideoMimeType() {
        return videoMimeType;
    }

    public String getAudioMimeType() {
        return audioMimeType;
    }

    public int getSelectedHeight() {
        return selectedHeight;
    }

    public long getVideoPlaintextLength() {
        return videoPlaintextLength;
    }

    public long getAudioPlaintextLength() {
        return audioPlaintextLength;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static UUID validateItemId(UUID itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.version() != 4 || itemId.variant() != 2) {
            throw new IllegalArgumentException(
                    "itemId must be an RFC 4122 random UUID"
            );
        }
        return itemId;
    }

    private static String validateVideoId(String videoId) {
        Objects.requireNonNull(videoId, "videoId");
        if (!VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException(
                    "videoId must be an 11-character media identifier"
            );
        }
        return videoId;
    }

    private static String validateText(
            String field,
            String value,
            boolean allowEmpty,
            int maxUtf8Bytes
    ) {
        Objects.requireNonNull(value, field);
        if ((!allowEmpty && value.isEmpty())
                || !value.equals(value.trim())
                || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
            throw new IllegalArgumentException(field + " is invalid or too long");
        }
        for (int index = 0; index < value.length();) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            field + " contains malformed Unicode"
                    );
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(
                        field + " contains malformed Unicode"
                );
            }

            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(
                        field + " contains disallowed control characters"
                );
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    private static String validateMimeType(
            String field,
            String value,
            String requiredPrefix
    ) {
        Objects.requireNonNull(value, field);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_MIME_UTF8_BYTES
                || !value.startsWith(requiredPrefix)
                || !MIME_TYPE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static long validateTrackLength(
            String field,
            long value,
            boolean allowEmpty
    ) {
        if (value < (allowEmpty ? 0L : 1L)
                || value > MAX_PLAINTEXT_TRACK_BYTES) {
            throw new IllegalArgumentException(
                    field + " is outside the supported range"
            );
        }
        return value;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof OfflineMediaRecord)) {
            return false;
        }
        OfflineMediaRecord other = (OfflineMediaRecord) candidate;
        return durationSeconds == other.durationSeconds
                && selectedHeight == other.selectedHeight
                && videoPlaintextLength == other.videoPlaintextLength
                && audioPlaintextLength == other.audioPlaintextLength
                && createdAtEpochMillis == other.createdAtEpochMillis
                && itemId.equals(other.itemId)
                && videoId.equals(other.videoId)
                && title.equals(other.title)
                && uploader.equals(other.uploader)
                && sourceType == other.sourceType
                && videoMimeType.equals(other.videoMimeType)
                && Objects.equals(audioMimeType, other.audioMimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                itemId,
                videoId,
                title,
                uploader,
                durationSeconds,
                sourceType,
                videoMimeType,
                audioMimeType,
                selectedHeight,
                videoPlaintextLength,
                audioPlaintextLength,
                createdAtEpochMillis
        );
    }

    @Override
    public String toString() {
        return "OfflineMediaRecord{itemId=" + itemId
                + ", videoId=" + videoId
                + ", sourceType=" + sourceType + '}';
    }
}
