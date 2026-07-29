package app.plyvanta.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class OfflineMediaRecordCodecTest {
    private static final UUID ITEM_ID = UUID.fromString(
            "123e4567-e89b-42d3-a456-426614174000"
    );
    private static final long CREATED_AT = 1_754_000_000_000L;

    @Test
    public void progressiveRecordRoundTripsThroughBothCodecs() throws Exception {
        OfflineMediaRecord expected = progressiveRecord();

        assertEquals(
                expected,
                OfflineMediaRecordCodec.decodeBinary(
                        OfflineMediaRecordCodec.encodeBinary(expected)
                )
        );
        assertEquals(
                expected,
                OfflineMediaRecordCodec.decodeJson(
                        OfflineMediaRecordCodec.encodeJson(expected)
                )
        );
    }

    @Test
    public void mergedRecordRoundTripsThroughBothCodecs() throws Exception {
        OfflineMediaRecord expected = mergedRecord();

        assertEquals(
                expected,
                OfflineMediaRecordCodec.decodeBinary(
                        OfflineMediaRecordCodec.encodeBinary(expected)
                )
        );
        assertEquals(
                expected,
                OfflineMediaRecordCodec.decodeJson(
                        OfflineMediaRecordCodec.encodeJson(expected)
                )
        );
    }

    @Test
    public void codecContainsMetadataOnlyAndNoTransferFields() throws Exception {
        byte[] encoded = OfflineMediaRecordCodec.encodeJson(mergedRecord());
        String json = new String(encoded, StandardCharsets.UTF_8);
        String normalized = json.toLowerCase();

        assertFalse(normalized.contains("url"));
        assertFalse(normalized.contains("path"));
        assertFalse(normalized.contains("thumbnail"));
        assertFalse(normalized.contains("ciphertext"));
        assertTrue(normalized.contains("videoplaintextlength"));
        assertTrue(normalized.contains("audioplaintextlength"));
    }

    @Test
    public void binaryDecoderRejectsEveryTruncation() throws Exception {
        byte[] encoded = OfflineMediaRecordCodec.encodeBinary(mergedRecord());

        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(
                    "Accepted truncation at " + length,
                    OfflineMediaRecordCodec.CodecException.class,
                    () -> OfflineMediaRecordCodec.decodeBinary(truncated)
            );
        }
    }

    @Test
    public void binaryDecoderRejectsMagicVersionTypeAndTrailingData()
            throws Exception {
        byte[] encoded = OfflineMediaRecordCodec.encodeBinary(mergedRecord());

        byte[] wrongMagic = encoded.clone();
        wrongMagic[0] ^= 0x01;
        assertBinaryRejected(wrongMagic);

        byte[] wrongVersion = encoded.clone();
        wrongVersion[4] = 2;
        assertBinaryRejected(wrongVersion);

        byte[] wrongType = encoded.clone();
        wrongType[4 + 1 + 2 * Long.BYTES] = 99;
        assertBinaryRejected(wrongType);

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertBinaryRejected(trailing);
    }

    @Test
    public void binaryDecoderRejectsOversizedAndMalformedUtf8Fields()
            throws Exception {
        byte[] encoded = OfflineMediaRecordCodec.encodeBinary(mergedRecord());
        int firstStringLengthOffset = 4 + 1 + 2 * Long.BYTES + 1;

        byte[] oversized = encoded.clone();
        oversized[firstStringLengthOffset] = 0x7f;
        oversized[firstStringLengthOffset + 1] = (byte) 0xff;
        assertBinaryRejected(oversized);

        byte[] malformedUtf8 = encoded.clone();
        int firstStringOffset = firstStringLengthOffset + Short.BYTES;
        malformedUtf8[firstStringOffset] = (byte) 0xc0;
        assertBinaryRejected(malformedUtf8);
    }

    @Test
    public void jsonDecoderRejectsMissingUnknownAndWrongTypeFields()
            throws Exception {
        String valid = json(mergedRecord());

        String unknown = valid.substring(0, valid.length() - 1)
                + ",\"downloadUrl\":\"https://example.test/media\"}";
        assertJsonRejected(unknown);

        String missing = valid.replace(
                ",\"uploader\":\"Fixture uploader\"",
                ""
        );
        assertJsonRejected(missing);

        String wrongType = valid.replace(
                "\"durationSeconds\":3600",
                "\"durationSeconds\":\"3600\""
        );
        assertJsonRejected(wrongType);

        String duplicate = valid.substring(0, valid.length() - 1)
                + ",\"schemaVersion\":1}";
        assertJsonRejected(duplicate);
    }

    @Test
    public void jsonDecoderRejectsBadSchemaUuidAndSourceType() throws Exception {
        String valid = json(mergedRecord());

        assertJsonRejected(valid.replace(
                "\"schemaVersion\":1",
                "\"schemaVersion\":2"
        ));
        assertJsonRejected(valid.replace(
                ITEM_ID.toString(),
                "123e4567-e89b-12d3-a456-426614174000"
        ));
        assertJsonRejected(valid.replace(
                "\"sourceType\":\"MERGED\"",
                "\"sourceType\":\"HLS\""
        ));
    }

    @Test
    public void jsonDecoderRejectsMalformedUtf8AndOversizedInput()
            throws Exception {
        byte[] malformed = {(byte) 0xc0, (byte) 0xaf};
        assertThrows(
                OfflineMediaRecordCodec.CodecException.class,
                () -> OfflineMediaRecordCodec.decodeJson(malformed)
        );

        byte[] oversized = new byte[
                OfflineMediaRecordCodec.MAX_ENCODED_BYTES + 1
        ];
        Arrays.fill(oversized, (byte) ' ');
        assertThrows(
                OfflineMediaRecordCodec.CodecException.class,
                () -> OfflineMediaRecordCodec.decodeJson(oversized)
        );
    }

    @Test
    public void recordRejectsInvalidIdentityAndText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> record(
                        UUID.fromString(
                                "123e4567-e89b-12d3-a456-426614174000"
                        ),
                        "aaaaaaaaaaa",
                        "Fixture title",
                        "Fixture uploader",
                        3_600L,
                        OfflineMediaRecord.SourceType.PROGRESSIVE,
                        "video/mp4",
                        null,
                        1_080,
                        120_000_000L,
                        0L,
                        CREATED_AT
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithVideoId("too-short")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithTitle("Control\ncharacter")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithTitle("Malformed \ud800")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithTitle(" padded ")
        );
    }

    @Test
    public void recordRejectsInvalidMediaShapeAndBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> progressiveWithAudio("audio/mp4", 1L)
        );
        assertThrows(
                NullPointerException.class,
                () -> record(
                        ITEM_ID,
                        "aaaaaaaaaaa",
                        "Fixture title",
                        "Fixture uploader",
                        3_600L,
                        OfflineMediaRecord.SourceType.MERGED,
                        "video/mp4",
                        null,
                        1_080,
                        120_000_000L,
                        12_000_000L,
                        CREATED_AT
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithDuration(0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithDuration(
                        OfflineMediaRecord.MAX_DURATION_SECONDS + 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithVideoMime("application/octet-stream")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithHeight(
                        OfflineMediaRecord.MAX_SELECTED_HEIGHT + 1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithVideoLength(0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> copyWithVideoLength(
                        OfflineMediaRecord.MAX_PLAINTEXT_TRACK_BYTES + 1L
                )
        );
    }

    @Test
    public void encodedValuesAreDefensiveAndStable() throws Exception {
        OfflineMediaRecord record = progressiveRecord();
        byte[] first = OfflineMediaRecordCodec.encodeBinary(record);
        byte[] expected = first.clone();
        first[0] ^= 0x01;

        assertArrayEquals(
                expected,
                OfflineMediaRecordCodec.encodeBinary(record)
        );
    }

    private static OfflineMediaRecord progressiveRecord() {
        return record(
                ITEM_ID,
                "aaaaaaaaaaa",
                "Fixture title",
                "",
                3_600L,
                OfflineMediaRecord.SourceType.PROGRESSIVE,
                "video/mp4",
                null,
                1_080,
                120_000_000L,
                0L,
                CREATED_AT
        );
    }

    private static OfflineMediaRecord mergedRecord() {
        return record(
                ITEM_ID,
                "bbbbbbbbbbb",
                "Fixture title",
                "Fixture uploader",
                3_600L,
                OfflineMediaRecord.SourceType.MERGED,
                "video/webm",
                "audio/webm",
                2_160,
                240_000_000L,
                24_000_000L,
                CREATED_AT
        );
    }

    private static OfflineMediaRecord record(
            UUID itemId,
            String videoId,
            String title,
            String uploader,
            long durationSeconds,
            OfflineMediaRecord.SourceType sourceType,
            String videoMimeType,
            String audioMimeType,
            int selectedHeight,
            long videoPlaintextLength,
            long audioPlaintextLength,
            long createdAtEpochMillis
    ) {
        return new OfflineMediaRecord(
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

    private static OfflineMediaRecord copyWithVideoId(String value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                value,
                base.getTitle(),
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                base.getVideoMimeType(),
                base.getAudioMimeType(),
                base.getSelectedHeight(),
                base.getVideoPlaintextLength(),
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord copyWithTitle(String value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                value,
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                base.getVideoMimeType(),
                base.getAudioMimeType(),
                base.getSelectedHeight(),
                base.getVideoPlaintextLength(),
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord copyWithDuration(long value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                base.getTitle(),
                base.getUploader(),
                value,
                base.getSourceType(),
                base.getVideoMimeType(),
                base.getAudioMimeType(),
                base.getSelectedHeight(),
                base.getVideoPlaintextLength(),
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord copyWithVideoMime(String value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                base.getTitle(),
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                value,
                base.getAudioMimeType(),
                base.getSelectedHeight(),
                base.getVideoPlaintextLength(),
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord copyWithHeight(int value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                base.getTitle(),
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                base.getVideoMimeType(),
                base.getAudioMimeType(),
                value,
                base.getVideoPlaintextLength(),
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord copyWithVideoLength(long value) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                base.getTitle(),
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                base.getVideoMimeType(),
                base.getAudioMimeType(),
                base.getSelectedHeight(),
                value,
                base.getAudioPlaintextLength(),
                base.getCreatedAtEpochMillis()
        );
    }

    private static OfflineMediaRecord progressiveWithAudio(
            String audioMime,
            long audioLength
    ) {
        OfflineMediaRecord base = progressiveRecord();
        return record(
                base.getItemId(),
                base.getVideoId(),
                base.getTitle(),
                base.getUploader(),
                base.getDurationSeconds(),
                base.getSourceType(),
                base.getVideoMimeType(),
                audioMime,
                base.getSelectedHeight(),
                base.getVideoPlaintextLength(),
                audioLength,
                base.getCreatedAtEpochMillis()
        );
    }

    private static String json(OfflineMediaRecord record) throws Exception {
        return new String(
                OfflineMediaRecordCodec.encodeJson(record),
                StandardCharsets.UTF_8
        );
    }

    private static void assertBinaryRejected(byte[] encoded) {
        assertThrows(
                OfflineMediaRecordCodec.CodecException.class,
                () -> OfflineMediaRecordCodec.decodeBinary(encoded)
        );
    }

    private static void assertJsonRejected(String json) {
        assertThrows(
                OfflineMediaRecordCodec.CodecException.class,
                () -> OfflineMediaRecordCodec.decodeJson(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );
    }
}
