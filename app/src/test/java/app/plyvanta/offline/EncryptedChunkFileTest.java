package app.plyvanta.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

public final class EncryptedChunkFileTest {
    private static final UUID ITEM_ID =
            UUID.fromString("3074a7f3-46d1-46f8-a08f-751858891bdf");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripSupportsArbitraryReadsAcrossChunkBoundaries() throws Exception {
        byte[] plaintext = patternedBytes(
                EncryptedChunkFile.CHUNK_SIZE_BYTES * 2 + 19_337
        );
        byte[] key = randomKey();
        Path encrypted = newFile("round-trip.pvlt");
        EncryptedChunkFile.write(
                encrypted,
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                key
        );

        byte[] encoded = Files.readAllBytes(encrypted);
        assertFalse(contains(
                encoded,
                Arrays.copyOfRange(plaintext, 1_001, 1_001 + 96)
        ));

        try (EncryptedChunkFile.Reader reader = EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                plaintext.length
        )) {
            assertEquals(plaintext.length, reader.length());
            assertEquals(ITEM_ID, reader.itemId());
            assertEquals(
                    EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    reader.trackRole()
            );

            assertReadMatches(reader, plaintext, 0, 7_919);
            assertReadMatches(
                    reader,
                    plaintext,
                    EncryptedChunkFile.CHUNK_SIZE_BYTES - 31,
                    257
            );
            assertReadMatches(
                    reader,
                    plaintext,
                    EncryptedChunkFile.CHUNK_SIZE_BYTES + 13,
                    EncryptedChunkFile.CHUNK_SIZE_BYTES + 91
            );
            assertReadMatches(reader, plaintext, plaintext.length - 41, 200);

            byte[] all = new byte[plaintext.length];
            int total = 0;
            while (total < all.length) {
                int requested = Math.min(13_777, all.length - total);
                int count = reader.read(total, all, total, requested);
                assertTrue(count > 0);
                total += count;
            }
            assertEquals(-1, reader.read(total, new byte[1], 0, 1));
            assertArrayEquals(plaintext, all);
        }
    }

    @Test
    public void wrongKeyFailsWhileAuthenticatingHeader() throws Exception {
        byte[] plaintext = patternedBytes(4_097);
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "wrong-key.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.VIDEO
        );

        byte[] wrongKey = key.clone();
        wrongKey[7] ^= 0x40;
        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                wrongKey,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.VIDEO,
                plaintext.length
        ));
    }

    @Test
    public void headerTamperingFailsBeforeReaderIsReturned() throws Exception {
        byte[] plaintext = patternedBytes(8_000);
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "header-tamper.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.AUDIO
        );

        flipByte(encrypted, 31L);
        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.AUDIO,
                plaintext.length
        ));
    }

    @Test
    public void chunkTamperingFailsClosedOnRead() throws Exception {
        byte[] plaintext = patternedBytes(EncryptedChunkFile.CHUNK_SIZE_BYTES + 71);
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "chunk-tamper.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.PROGRESSIVE
        );

        flipByte(encrypted, EncryptedChunkFile.HEADER_SIZE_BYTES + 123L);
        try (EncryptedChunkFile.Reader reader = EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                plaintext.length
        )) {
            assertThrows(
                    IOException.class,
                    () -> reader.read(0L, new byte[512], 0, 512)
            );
        }
    }

    @Test
    public void reorderedChunksFailBecauseIndexIsAuthenticated() throws Exception {
        byte[] plaintext = patternedBytes(EncryptedChunkFile.CHUNK_SIZE_BYTES * 2);
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "chunk-reorder.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.PROGRESSIVE
        );

        int encryptedRecordLength = Math.toIntExact(
                (Files.size(encrypted) - EncryptedChunkFile.HEADER_SIZE_BYTES) / 2L
        );
        byte[] first = new byte[encryptedRecordLength];
        byte[] second = new byte[encryptedRecordLength];
        try (RandomAccessFile file = new RandomAccessFile(encrypted.toFile(), "rw")) {
            file.seek(EncryptedChunkFile.HEADER_SIZE_BYTES);
            file.readFully(first);
            file.readFully(second);
            file.seek(EncryptedChunkFile.HEADER_SIZE_BYTES);
            file.write(second);
            file.write(first);
        }

        try (EncryptedChunkFile.Reader reader = EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                plaintext.length
        )) {
            assertThrows(
                    IOException.class,
                    () -> reader.read(0L, new byte[512], 0, 512)
            );
        }
    }

    @Test
    public void truncationAndTrailingDataAreRejectedAtOpen() throws Exception {
        byte[] plaintext = patternedBytes(EncryptedChunkFile.CHUNK_SIZE_BYTES + 9);
        byte[] key = randomKey();
        Path original = writeFile(
                "length-original.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.VIDEO
        );
        Path truncated = newFile("length-truncated.pvlt");
        Path appended = newFile("length-appended.pvlt");
        Files.copy(original, truncated);
        Files.copy(original, appended);

        try (RandomAccessFile file = new RandomAccessFile(truncated.toFile(), "rw")) {
            file.setLength(file.length() - 1L);
        }
        Files.write(
                appended,
                new byte[] {42},
                StandardOpenOption.APPEND
        );

        assertOpenFails(truncated, key, plaintext.length);
        assertOpenFails(appended, key, plaintext.length);
    }

    @Test
    public void catalogIdentityRoleAndLengthAreAuthenticatedExpectations() throws Exception {
        byte[] plaintext = patternedBytes(65_537);
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "catalog-binding.pvlt",
                plaintext,
                key,
                EncryptedChunkFile.TrackRole.VIDEO
        );

        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                key,
                UUID.randomUUID(),
                EncryptedChunkFile.TrackRole.VIDEO,
                plaintext.length
        ));
        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.AUDIO,
                plaintext.length
        ));
        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.VIDEO,
                plaintext.length + 1L
        ));
    }

    @Test
    public void shortOrOverlongInputNeverPublishesDestination() throws Exception {
        byte[] plaintext = patternedBytes(12_345);
        byte[] key = randomKey();
        Path shortDestination = newFile("short-input.pvlt");
        Path longDestination = newFile("long-input.pvlt");

        assertThrows(IOException.class, () -> EncryptedChunkFile.write(
                shortDestination,
                new ByteArrayInputStream(plaintext),
                plaintext.length + 1L,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                key
        ));
        assertThrows(IOException.class, () -> EncryptedChunkFile.write(
                longDestination,
                new ByteArrayInputStream(plaintext),
                plaintext.length - 1L,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                key
        ));
        assertFalse(Files.exists(shortDestination));
        assertFalse(Files.exists(longDestination));
    }

    @Test
    public void emptyAuthenticatedResourceRoundTrips() throws Exception {
        byte[] key = randomKey();
        Path encrypted = writeFile(
                "empty.pvlt",
                new byte[0],
                key,
                EncryptedChunkFile.TrackRole.AUDIO
        );

        assertEquals(
                EncryptedChunkFile.HEADER_SIZE_BYTES,
                Files.size(encrypted)
        );
        try (EncryptedChunkFile.Reader reader = EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.AUDIO,
                0L
        )) {
            assertEquals(-1, reader.read(0L, new byte[1], 0, 1));
            assertEquals(0, reader.read(0L, new byte[0], 0, 0));
        }
    }

    private Path writeFile(
            String name,
            byte[] plaintext,
            byte[] key,
            EncryptedChunkFile.TrackRole role
    ) throws IOException {
        Path destination = newFile(name);
        EncryptedChunkFile.write(
                destination,
                new ByteArrayInputStream(plaintext),
                plaintext.length,
                ITEM_ID,
                role,
                key
        );
        return destination;
    }

    private Path newFile(String name) throws IOException {
        return temporaryFolder.newFolder().toPath().resolve(name);
    }

    private static void assertReadMatches(
            EncryptedChunkFile.Reader reader,
            byte[] plaintext,
            int position,
            int requestedLength
    ) throws IOException {
        int expectedLength = Math.min(
                requestedLength,
                plaintext.length - position
        );
        byte[] actual = new byte[requestedLength];
        int count = reader.read(position, actual, 0, requestedLength);
        assertEquals(expectedLength, count);
        assertArrayEquals(
                Arrays.copyOfRange(plaintext, position, position + expectedLength),
                Arrays.copyOf(actual, expectedLength)
        );
    }

    private static void assertOpenFails(
            Path encrypted,
            byte[] key,
            long plaintextLength
    ) {
        assertThrows(IOException.class, () -> EncryptedChunkFile.open(
                encrypted,
                key,
                ITEM_ID,
                EncryptedChunkFile.TrackRole.VIDEO,
                plaintextLength
        ));
    }

    private static byte[] patternedBytes(int length) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) ((index * 31 + index / 251 + 17) & 0xff);
        }
        return value;
    }

    private static byte[] randomKey() {
        byte[] key = new byte[EncryptedChunkFile.KEY_SIZE_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static void flipByte(Path path, long offset) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(offset);
            int value = file.read();
            if (value < 0) {
                throw new IOException("Cannot tamper beyond end of test file");
            }
            file.seek(offset);
            file.write(value ^ 0x01);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return true;
        }
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
