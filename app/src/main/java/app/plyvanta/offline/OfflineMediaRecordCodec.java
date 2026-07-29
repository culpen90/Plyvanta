package app.plyvanta.offline;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, bounded codecs for URL-free offline metadata.
 */
public final class OfflineMediaRecordCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 8 * 1024;

    private static final int BINARY_MAGIC = 0x5056524d; // PVRM
    private static final int TYPE_PROGRESSIVE = 1;
    private static final int TYPE_MERGED = 2;
    private static final Set<String> JSON_KEYS = Set.of(
            "schemaVersion",
            "itemId",
            "videoId",
            "title",
            "uploader",
            "durationSeconds",
            "sourceType",
            "videoMimeType",
            "audioMimeType",
            "selectedHeight",
            "videoPlaintextLength",
            "audioPlaintextLength",
            "createdAtEpochMillis"
    );

    private OfflineMediaRecordCodec() {
    }

    public static byte[] encodeBinary(OfflineMediaRecord record)
            throws CodecException {
        if (record == null) {
            throw new CodecException("Record is required.");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(BINARY_MAGIC);
            output.writeByte(SCHEMA_VERSION);
            output.writeLong(record.getItemId().getMostSignificantBits());
            output.writeLong(record.getItemId().getLeastSignificantBits());
            output.writeByte(typeCode(record.getSourceType()));
            writeString(output, record.getVideoId());
            writeString(output, record.getTitle());
            writeString(output, record.getUploader());
            output.writeLong(record.getDurationSeconds());
            writeString(output, record.getVideoMimeType());
            writeNullableString(output, record.getAudioMimeType());
            output.writeInt(record.getSelectedHeight());
            output.writeLong(record.getVideoPlaintextLength());
            output.writeLong(record.getAudioPlaintextLength());
            output.writeLong(record.getCreatedAtEpochMillis());
            output.flush();

            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new CodecException("Encoded record exceeds the size limit.");
            }
            return encoded;
        } catch (CodecException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CodecException("Unable to encode the record.", exception);
        }
    }

    public static OfflineMediaRecord decodeBinary(byte[] encoded)
            throws CodecException {
        requireEncodedBytes(encoded);

        try {
            DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(encoded)
            );
            if (input.readInt() != BINARY_MAGIC) {
                throw new CodecException("Binary record magic is invalid.");
            }
            if (input.readUnsignedByte() != SCHEMA_VERSION) {
                throw new CodecException(
                        "Binary record schema version is unsupported."
                );
            }
            UUID itemId = new UUID(input.readLong(), input.readLong());
            OfflineMediaRecord.SourceType sourceType =
                    sourceType(input.readUnsignedByte());
            String videoId = readString(input, 11);
            String title = readString(
                    input,
                    OfflineMediaRecord.MAX_TITLE_UTF8_BYTES
            );
            String uploader = readString(
                    input,
                    OfflineMediaRecord.MAX_UPLOADER_UTF8_BYTES
            );
            long durationSeconds = input.readLong();
            String videoMimeType = readString(
                    input,
                    OfflineMediaRecord.MAX_MIME_UTF8_BYTES
            );
            String audioMimeType = readNullableString(
                    input,
                    OfflineMediaRecord.MAX_MIME_UTF8_BYTES
            );
            int selectedHeight = input.readInt();
            long videoPlaintextLength = input.readLong();
            long audioPlaintextLength = input.readLong();
            long createdAtEpochMillis = input.readLong();
            if (input.available() != 0) {
                throw new CodecException(
                        "Binary record contains trailing data."
                );
            }

            return createRecord(
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
        } catch (CodecException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CodecException("Binary record is malformed.", exception);
        }
    }

    public static byte[] encodeJson(OfflineMediaRecord record)
            throws CodecException {
        if (record == null) {
            throw new CodecException("Record is required.");
        }
        try {
            JSONObject object = new JSONObject();
            object.put("schemaVersion", SCHEMA_VERSION);
            object.put("itemId", record.getItemId().toString());
            object.put("videoId", record.getVideoId());
            object.put("title", record.getTitle());
            object.put("uploader", record.getUploader());
            object.put("durationSeconds", record.getDurationSeconds());
            object.put("sourceType", record.getSourceType().name());
            object.put("videoMimeType", record.getVideoMimeType());
            object.put(
                    "audioMimeType",
                    record.getAudioMimeType() == null
                            ? JSONObject.NULL
                            : record.getAudioMimeType()
            );
            object.put("selectedHeight", record.getSelectedHeight());
            object.put(
                    "videoPlaintextLength",
                    record.getVideoPlaintextLength()
            );
            object.put(
                    "audioPlaintextLength",
                    record.getAudioPlaintextLength()
            );
            object.put(
                    "createdAtEpochMillis",
                    record.getCreatedAtEpochMillis()
            );

            byte[] encoded = object.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new CodecException("Encoded record exceeds the size limit.");
            }
            return encoded;
        } catch (JSONException exception) {
            throw new CodecException("Unable to encode the record.", exception);
        }
    }

    public static OfflineMediaRecord decodeJson(byte[] encoded)
            throws CodecException {
        requireEncodedBytes(encoded);

        try {
            String json = decodeUtf8(encoded);
            if (json.isEmpty() || !json.equals(json.trim())) {
                throw new CodecException(
                        "JSON record must use a bounded canonical object."
                );
            }
            JSONObject object = new JSONObject(json);
            requireExactJsonKeys(object);
            if (requireInt(object, "schemaVersion") != SCHEMA_VERSION) {
                throw new CodecException(
                        "JSON record schema version is unsupported."
                );
            }

            String itemIdText = requireString(object, "itemId");
            UUID itemId = UUID.fromString(itemIdText);
            if (!itemId.toString().equals(itemIdText)) {
                throw new CodecException(
                        "JSON itemId is not a canonical UUID."
                );
            }

            String sourceTypeText = requireString(object, "sourceType");
            OfflineMediaRecord.SourceType sourceType;
            try {
                sourceType = OfflineMediaRecord.SourceType.valueOf(
                        sourceTypeText
                );
            } catch (IllegalArgumentException exception) {
                throw new CodecException(
                        "JSON sourceType is unsupported.",
                        exception
                );
            }

            Object audioValue = object.get("audioMimeType");
            String audioMimeType;
            if (audioValue == JSONObject.NULL) {
                audioMimeType = null;
            } else if (audioValue instanceof String) {
                audioMimeType = (String) audioValue;
            } else {
                throw new CodecException(
                        "JSON audioMimeType has the wrong type."
                );
            }

            OfflineMediaRecord record = createRecord(
                    itemId,
                    requireString(object, "videoId"),
                    requireString(object, "title"),
                    requireString(object, "uploader"),
                    requireLong(object, "durationSeconds"),
                    sourceType,
                    requireString(object, "videoMimeType"),
                    audioMimeType,
                    requireInt(object, "selectedHeight"),
                    requireLong(object, "videoPlaintextLength"),
                    requireLong(object, "audioPlaintextLength"),
                    requireLong(object, "createdAtEpochMillis")
            );
            if (!Arrays.equals(encoded, encodeJson(record))) {
                throw new CodecException(
                        "JSON record is not in canonical encoded form."
                );
            }
            return record;
        } catch (CodecException exception) {
            throw exception;
        } catch (JSONException | RuntimeException exception) {
            throw new CodecException("JSON record is malformed.", exception);
        }
    }

    private static OfflineMediaRecord createRecord(
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
    ) throws CodecException {
        try {
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
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CodecException(
                    "Record fields violate the metadata contract.",
                    exception
            );
        }
    }

    private static void requireEncodedBytes(byte[] encoded)
            throws CodecException {
        if (encoded == null
                || encoded.length == 0
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new CodecException("Encoded record has an invalid size.");
        }
    }

    private static int typeCode(OfflineMediaRecord.SourceType sourceType) {
        return sourceType == OfflineMediaRecord.SourceType.PROGRESSIVE
                ? TYPE_PROGRESSIVE
                : TYPE_MERGED;
    }

    private static OfflineMediaRecord.SourceType sourceType(int code)
            throws CodecException {
        if (code == TYPE_PROGRESSIVE) {
            return OfflineMediaRecord.SourceType.PROGRESSIVE;
        }
        if (code == TYPE_MERGED) {
            return OfflineMediaRecord.SourceType.MERGED;
        }
        throw new CodecException("Binary record source type is unsupported.");
    }

    private static void writeString(DataOutputStream output, String value)
            throws IOException, CodecException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) {
            throw new CodecException("String field exceeds the binary limit.");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static void writeNullableString(
            DataOutputStream output,
            String value
    ) throws IOException, CodecException {
        if (value == null) {
            output.writeByte(0);
            return;
        }
        output.writeByte(1);
        writeString(output, value);
    }

    private static String readString(DataInputStream input, int maxBytes)
            throws IOException, CodecException {
        int length = input.readUnsignedShort();
        if (length > maxBytes || length > input.available()) {
            throw new CodecException("Binary string length is invalid.");
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return decodeUtf8(encoded);
    }

    private static String readNullableString(
            DataInputStream input,
            int maxBytes
    ) throws IOException, CodecException {
        int present = input.readUnsignedByte();
        if (present == 0) {
            return null;
        }
        if (present != 1) {
            throw new CodecException(
                    "Binary optional-string marker is invalid."
            );
        }
        return readString(input, maxBytes);
    }

    private static String decodeUtf8(byte[] encoded) throws CodecException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CodecException("Text contains malformed UTF-8.", exception);
        }
    }

    private static void requireExactJsonKeys(JSONObject object)
            throws CodecException {
        if (object.length() != JSON_KEYS.size()) {
            throw new CodecException(
                    "JSON record has missing or unknown fields."
            );
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (!JSON_KEYS.contains(keys.next())) {
                throw new CodecException(
                        "JSON record contains an unknown field."
                );
            }
        }
    }

    private static String requireString(JSONObject object, String key)
            throws JSONException, CodecException {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new CodecException("JSON " + key + " has the wrong type.");
        }
        return (String) value;
    }

    private static int requireInt(JSONObject object, String key)
            throws JSONException, CodecException {
        Object value = object.get(key);
        if (!(value instanceof Integer)) {
            throw new CodecException("JSON " + key + " has the wrong type.");
        }
        return (Integer) value;
    }

    private static long requireLong(JSONObject object, String key)
            throws JSONException, CodecException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new CodecException("JSON " + key + " has the wrong type.");
        }
        return ((Number) value).longValue();
    }

    public static final class CodecException extends IOException {
        public CodecException(String message) {
            super(message);
        }

        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
