package app.plyvanta.offline;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated, seekable encrypted media file.
 *
 * <p>The caller must supply a fresh 256-bit data-encryption key for every file. Plaintext is read
 * directly from the supplied stream into one bounded in-memory chunk, encrypted, and only then
 * written to disk. The completed file contains a fixed authenticated header followed by fixed-size
 * AES-GCM records. The final record may contain less than one full plaintext chunk.
 *
 * <p>The format reserves chunk index {@code 0xffffffff} for the header authentication tag. Data
 * chunks use indices {@code 0} through {@code 0xfffffffe}. A nonce is the file's random eight-byte
 * prefix followed by the unsigned 32-bit record index.
 */
public final class EncryptedChunkFile {
    public static final int CHUNK_SIZE_BYTES = 256 * 1024;
    public static final int KEY_SIZE_BYTES = 32;

    static final int HEADER_SIZE_BYTES = 68;

    private static final byte[] MAGIC = new byte[] {
            'P', 'L', 'Y', 'V', 'L', 'T', '0', '1'
    };
    private static final int FORMAT_VERSION = 1;
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE;
    private static final int HEADER_PREFIX_BYTES = HEADER_SIZE_BYTES - GCM_TAG_BYTES;
    private static final long HEADER_AUTH_INDEX = 0xffff_ffffL;
    private static final long MAX_DATA_CHUNKS = HEADER_AUTH_INDEX;
    private static final byte[] EMPTY = new byte[0];

    private EncryptedChunkFile() {
    }

    public enum TrackRole {
        PROGRESSIVE(1),
        VIDEO(2),
        AUDIO(3);

        private final int encodedValue;

        TrackRole(int encodedValue) {
            this.encodedValue = encodedValue;
        }

        private static TrackRole fromEncodedValue(int encodedValue) throws IOException {
            for (TrackRole role : values()) {
                if (role.encodedValue == encodedValue) {
                    return role;
                }
            }
            throw new IOException("Encrypted media header has an unknown track role.");
        }
    }

    /**
     * Encrypts {@code expectedPlaintextLength} bytes and atomically publishes the completed file.
     *
     * <p>The destination must not already exist. A short or overlong input fails the operation and
     * removes the encrypted partial file. The supplied key remains owned by the caller and is not
     * modified; the internal key copy and all internal plaintext buffers are cleared on exit.
     */
    public static void write(
            Path destination,
            InputStream plaintext,
            long expectedPlaintextLength,
            UUID itemId,
            TrackRole trackRole,
            byte[] key
    ) throws IOException {
        write(
                destination,
                plaintext,
                expectedPlaintextLength,
                itemId,
                trackRole,
                key,
                new SecureRandom()
        );
    }

    static void write(
            Path destination,
            InputStream plaintext,
            long expectedPlaintextLength,
            UUID itemId,
            TrackRole trackRole,
            byte[] key,
            SecureRandom secureRandom
    ) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(trackRole, "trackRole");
        Objects.requireNonNull(secureRandom, "secureRandom");
        validatePlaintextLength(expectedPlaintextLength);

        byte[] ownedKey = copyAndValidateKey(key);
        byte[] plaintextChunk = new byte[CHUNK_SIZE_BYTES];
        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        secureRandom.nextBytes(noncePrefix);

        Path partial = null;
        boolean published = false;
        try {
            Path absoluteDestination = destination.toAbsolutePath().normalize();
            Path parent = absoluteDestination.getParent();
            Path fileName = absoluteDestination.getFileName();
            if (parent == null || fileName == null) {
                throw new IOException("Encrypted media destination must name a file.");
            }
            Files.createDirectories(parent);
            if (Files.exists(absoluteDestination)) {
                throw new FileAlreadyExistsException(absoluteDestination.toString());
            }

            partial = Files.createTempFile(
                    parent,
                    "." + fileName + ".",
                    ".partial"
            );
            writeEncryptedFile(
                    partial,
                    plaintext,
                    expectedPlaintextLength,
                    itemId,
                    trackRole,
                    noncePrefix,
                    ownedKey,
                    plaintextChunk
            );
            try {
                Files.move(partial, absoluteDestination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, absoluteDestination);
            }
            published = true;
        } finally {
            wipe(ownedKey);
            wipe(plaintextChunk);
            wipe(noncePrefix);
            if (!published && partial != null) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException ignored) {
                    // The partial contains ciphertext only. Preserve the primary failure.
                }
            }
        }
    }

    /**
     * Opens and authenticates an encrypted media file against its catalog metadata.
     *
     * <p>Header authentication, item identity, track role, expected plaintext length, and exact
     * encrypted file length are all checked before the reader is returned.
     */
    static Reader open(
            Path path,
            byte[] key,
            UUID expectedItemId,
            TrackRole expectedTrackRole,
            long expectedPlaintextLength
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expectedItemId, "expectedItemId");
        Objects.requireNonNull(expectedTrackRole, "expectedTrackRole");
        if (expectedPlaintextLength < 0L) {
            throw new IllegalArgumentException("expectedPlaintextLength must not be negative");
        }

        byte[] ownedKey = copyAndValidateKey(key);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            Header header = readAndAuthenticateHeader(channel, ownedKey);
            if (!header.itemId.equals(expectedItemId)
                    || header.trackRole != expectedTrackRole
                    || header.plaintextLength != expectedPlaintextLength) {
                throw new IOException("Encrypted media does not match its catalog entry.");
            }

            long expectedFileLength = encryptedFileLength(header.plaintextLength);
            if (channel.size() != expectedFileLength) {
                throw new IOException("Encrypted media length is invalid.");
            }

            Reader reader = new Reader(channel, ownedKey, header);
            channel = null;
            ownedKey = null;
            return reader;
        } finally {
            if (channel != null) {
                channel.close();
            }
            wipe(ownedKey);
        }
    }

    private static void writeEncryptedFile(
            Path partial,
            InputStream plaintext,
            long expectedPlaintextLength,
            UUID itemId,
            TrackRole trackRole,
            byte[] noncePrefix,
            byte[] key,
            byte[] plaintextChunk
    ) throws IOException {
        byte[] headerPrefix = buildHeaderPrefix(
                noncePrefix,
                itemId,
                trackRole,
                expectedPlaintextLength
        );
        byte[] headerTag = null;
        try {
            headerTag = encryptRecord(
                    key,
                    noncePrefix,
                    HEADER_AUTH_INDEX,
                    buildHeaderAad(headerPrefix),
                    EMPTY,
                    0
            );
            if (headerTag.length != GCM_TAG_BYTES) {
                throw new IOException("Encrypted media header tag has an invalid length.");
            }

            try (FileChannel output = FileChannel.open(
                    partial,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writeFully(output, ByteBuffer.wrap(headerPrefix));
                writeFully(output, ByteBuffer.wrap(headerTag));

                long remaining = expectedPlaintextLength;
                long chunkIndex = 0L;
                while (remaining > 0L) {
                    int chunkLength = (int) Math.min(CHUNK_SIZE_BYTES, remaining);
                    readExactly(plaintext, plaintextChunk, chunkLength);
                    byte[] encryptedChunk = null;
                    try {
                        encryptedChunk = encryptRecord(
                                key,
                                noncePrefix,
                                chunkIndex,
                                buildChunkAad(
                                        itemId,
                                        trackRole,
                                        expectedPlaintextLength,
                                        chunkIndex,
                                        chunkLength
                                ),
                                plaintextChunk,
                                chunkLength
                        );
                        writeFully(output, ByteBuffer.wrap(encryptedChunk));
                    } finally {
                        Arrays.fill(plaintextChunk, 0, chunkLength, (byte) 0);
                        wipe(encryptedChunk);
                    }
                    remaining -= chunkLength;
                    chunkIndex++;
                }

                if (plaintext.read() != -1) {
                    throw new IOException(
                            "Plaintext stream is longer than its authenticated expected length."
                    );
                }
                output.force(true);
            }
        } finally {
            wipe(headerPrefix);
            wipe(headerTag);
        }
    }

    private static Header readAndAuthenticateHeader(
            FileChannel channel,
            byte[] key
    ) throws IOException {
        byte[] encodedHeader = new byte[HEADER_SIZE_BYTES];
        readFullyAt(channel, 0L, encodedHeader);

        byte[] headerPrefix = Arrays.copyOf(encodedHeader, HEADER_PREFIX_BYTES);
        byte[] headerTag = Arrays.copyOfRange(
                encodedHeader,
                HEADER_PREFIX_BYTES,
                HEADER_SIZE_BYTES
        );
        wipe(encodedHeader);

        byte[] noncePrefix = null;
        try {
            ByteBuffer input = ByteBuffer.wrap(headerPrefix).order(ByteOrder.BIG_ENDIAN);
            byte[] magic = new byte[MAGIC.length];
            input.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Encrypted media has an invalid format marker.");
            }
            int version = input.getInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Encrypted media format version is unsupported.");
            }
            int chunkSize = input.getInt();
            if (chunkSize != CHUNK_SIZE_BYTES) {
                throw new IOException("Encrypted media chunk size is unsupported.");
            }
            TrackRole trackRole = TrackRole.fromEncodedValue(input.getInt());
            noncePrefix = new byte[NONCE_PREFIX_BYTES];
            input.get(noncePrefix);
            UUID itemId = new UUID(input.getLong(), input.getLong());
            long plaintextLength = input.getLong();
            validatePlaintextLength(plaintextLength);

            authenticateHeader(key, noncePrefix, headerPrefix, headerTag);
            Header header = new Header(
                    noncePrefix,
                    itemId,
                    trackRole,
                    plaintextLength
            );
            noncePrefix = null;
            return header;
        } finally {
            wipe(noncePrefix);
            wipe(headerPrefix);
            wipe(headerTag);
        }
    }

    private static byte[] buildHeaderPrefix(
            byte[] noncePrefix,
            UUID itemId,
            TrackRole trackRole,
            long plaintextLength
    ) {
        return ByteBuffer.allocate(HEADER_PREFIX_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(FORMAT_VERSION)
                .putInt(CHUNK_SIZE_BYTES)
                .putInt(trackRole.encodedValue)
                .put(noncePrefix)
                .putLong(itemId.getMostSignificantBits())
                .putLong(itemId.getLeastSignificantBits())
                .putLong(plaintextLength)
                .array();
    }

    private static byte[] buildHeaderAad(byte[] headerPrefix) {
        return ByteBuffer.allocate(HEADER_PREFIX_BYTES + 2 * Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(headerPrefix)
                .putInt((int) HEADER_AUTH_INDEX)
                .putInt(0)
                .array();
    }

    private static byte[] buildChunkAad(
            UUID itemId,
            TrackRole trackRole,
            long plaintextLength,
            long chunkIndex,
            int plainChunkLength
    ) {
        return ByteBuffer.allocate(
                        MAGIC.length
                                + 2 * Integer.BYTES
                                + 2 * Long.BYTES
                                + Integer.BYTES
                                + Long.BYTES
                                + 2 * Integer.BYTES
                )
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(FORMAT_VERSION)
                .putInt(CHUNK_SIZE_BYTES)
                .putLong(itemId.getMostSignificantBits())
                .putLong(itemId.getLeastSignificantBits())
                .putInt(trackRole.encodedValue)
                .putLong(plaintextLength)
                .putInt((int) chunkIndex)
                .putInt(plainChunkLength)
                .array();
    }

    private static byte[] encryptRecord(
            byte[] key,
            byte[] noncePrefix,
            long chunkIndex,
            byte[] aad,
            byte[] plaintext,
            int plaintextLength
    ) throws IOException {
        try {
            Cipher cipher = newCipher(
                    Cipher.ENCRYPT_MODE,
                    key,
                    noncePrefix,
                    chunkIndex
            );
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext, 0, plaintextLength);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to encrypt media.", exception);
        } finally {
            wipe(aad);
        }
    }

    private static void authenticateHeader(
            byte[] key,
            byte[] noncePrefix,
            byte[] headerPrefix,
            byte[] headerTag
    ) throws IOException {
        byte[] aad = buildHeaderAad(headerPrefix);
        try {
            Cipher cipher = newCipher(
                    Cipher.DECRYPT_MODE,
                    key,
                    noncePrefix,
                    HEADER_AUTH_INDEX
            );
            cipher.updateAAD(aad);
            byte[] plaintext = cipher.doFinal(headerTag);
            if (plaintext.length != 0) {
                wipe(plaintext);
                throw new IOException("Encrypted media header is invalid.");
            }
        } catch (AEADBadTagException exception) {
            throw authenticationFailure(exception);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to authenticate encrypted media.", exception);
        } finally {
            wipe(aad);
        }
    }

    private static Cipher newCipher(
            int mode,
            byte[] key,
            byte[] noncePrefix,
            long chunkIndex
    ) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(NONCE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(noncePrefix)
                .putInt((int) chunkIndex)
                .array();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    mode,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce)
            );
            return cipher;
        } finally {
            wipe(nonce);
        }
    }

    private static byte[] copyAndValidateKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("AES-256 key must contain exactly 32 bytes");
        }
        return key.clone();
    }

    private static void validatePlaintextLength(long plaintextLength) throws IOException {
        if (plaintextLength < 0L) {
            throw new IOException("Plaintext length must not be negative.");
        }
        long chunkCount = chunkCount(plaintextLength);
        if (chunkCount > MAX_DATA_CHUNKS) {
            throw new IOException("Plaintext is too large for the encrypted media format.");
        }
        encryptedFileLength(plaintextLength);
    }

    private static long encryptedFileLength(long plaintextLength) throws IOException {
        try {
            return Math.addExact(
                    HEADER_SIZE_BYTES,
                    Math.addExact(
                            plaintextLength,
                            Math.multiplyExact(chunkCount(plaintextLength), GCM_TAG_BYTES)
                    )
            );
        } catch (ArithmeticException exception) {
            throw new IOException("Encrypted media length overflows the file format.", exception);
        }
    }

    private static long chunkCount(long plaintextLength) {
        return plaintextLength == 0L
                ? 0L
                : ((plaintextLength - 1L) / CHUNK_SIZE_BYTES) + 1L;
    }

    private static int chunkPlaintextLength(long plaintextLength, long chunkIndex)
            throws IOException {
        try {
            long start = Math.multiplyExact(chunkIndex, (long) CHUNK_SIZE_BYTES);
            long remaining = plaintextLength - start;
            if (remaining <= 0L) {
                throw new IOException("Encrypted media chunk index is out of range.");
            }
            return (int) Math.min(CHUNK_SIZE_BYTES, remaining);
        } catch (ArithmeticException exception) {
            throw new IOException("Encrypted media chunk offset overflows.", exception);
        }
    }

    private static long encryptedChunkOffset(long chunkIndex) throws IOException {
        try {
            return Math.addExact(
                    HEADER_SIZE_BYTES,
                    Math.multiplyExact(
                            chunkIndex,
                            (long) CHUNK_SIZE_BYTES + GCM_TAG_BYTES
                    )
            );
        } catch (ArithmeticException exception) {
            throw new IOException("Encrypted media chunk offset overflows.", exception);
        }
    }

    private static void readExactly(InputStream input, byte[] destination, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int count = input.read(destination, total, length - total);
            if (count < 0) {
                throw new EOFException(
                        "Plaintext stream ended before its authenticated expected length."
                );
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException(
                            "Plaintext stream ended before its authenticated expected length."
                    );
                }
                destination[total] = (byte) value;
                total++;
            } else {
                total += count;
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int noProgressCount = 0;
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer);
            if (count == 0) {
                noProgressCount++;
                if (noProgressCount > 16) {
                    throw new IOException("Encrypted media write made no progress.");
                }
            } else {
                noProgressCount = 0;
            }
        }
    }

    private static void readFullyAt(FileChannel channel, long position, byte[] destination)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(destination);
        long offset = position;
        int noProgressCount = 0;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset);
            if (count < 0) {
                throw new EOFException("Encrypted media is truncated.");
            }
            if (count == 0) {
                noProgressCount++;
                if (noProgressCount > 16) {
                    throw new IOException("Encrypted media read made no progress.");
                }
            } else {
                noProgressCount = 0;
                offset += count;
            }
        }
    }

    private static IOException authenticationFailure(Throwable cause) {
        return new IOException("Encrypted media authentication failed.", cause);
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class Header {
        private final byte[] noncePrefix;
        private final UUID itemId;
        private final TrackRole trackRole;
        private final long plaintextLength;

        private Header(
                byte[] noncePrefix,
                UUID itemId,
                TrackRole trackRole,
                long plaintextLength
        ) {
            this.noncePrefix = noncePrefix;
            this.itemId = itemId;
            this.trackRole = trackRole;
            this.plaintextLength = plaintextLength;
        }
    }

    public static final class Reader implements Closeable {
        private final FileChannel channel;
        private final byte[] key;
        private final byte[] noncePrefix;
        private final UUID itemId;
        private final TrackRole trackRole;
        private final long plaintextLength;

        private byte[] cachedPlaintext;
        private long cachedChunkIndex = -1L;
        private boolean closed;

        private Reader(FileChannel channel, byte[] key, Header header) {
            this.channel = channel;
            this.key = key;
            this.noncePrefix = header.noncePrefix;
            this.itemId = header.itemId;
            this.trackRole = header.trackRole;
            this.plaintextLength = header.plaintextLength;
        }

        public long length() {
            return plaintextLength;
        }

        public UUID itemId() {
            return itemId;
        }

        public TrackRole trackRole() {
            return trackRole;
        }

        /**
         * Reads authenticated plaintext at an arbitrary absolute position.
         *
         * @return bytes read, {@code -1} at end of input, or {@code 0} for a zero-length request
         */
        public synchronized int read(
                long position,
                byte[] destination,
                int offset,
                int length
        ) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (position < 0L) {
                throw new IllegalArgumentException("position must not be negative");
            }
            if (offset < 0
                    || length < 0
                    || offset > destination.length
                    || length > destination.length - offset) {
                throw new IndexOutOfBoundsException("Invalid destination range");
            }
            if (length == 0) {
                return 0;
            }
            if (position >= plaintextLength) {
                return -1;
            }

            int total = (int) Math.min((long) length, plaintextLength - position);
            int copied = 0;
            long currentPosition = position;
            while (copied < total) {
                long chunkIndex = currentPosition / CHUNK_SIZE_BYTES;
                int positionInChunk = (int) (currentPosition % CHUNK_SIZE_BYTES);
                byte[] chunk = loadChunk(chunkIndex);
                int count = Math.min(total - copied, chunk.length - positionInChunk);
                System.arraycopy(
                        chunk,
                        positionInChunk,
                        destination,
                        offset + copied,
                        count
                );
                copied += count;
                currentPosition += count;
            }
            return copied;
        }

        private byte[] loadChunk(long chunkIndex) throws IOException {
            if (cachedChunkIndex == chunkIndex && cachedPlaintext != null) {
                return cachedPlaintext;
            }
            clearCachedPlaintext();

            int plainLength = chunkPlaintextLength(plaintextLength, chunkIndex);
            byte[] encrypted = new byte[plainLength + GCM_TAG_BYTES];
            byte[] aad = buildChunkAad(
                    itemId,
                    trackRole,
                    plaintextLength,
                    chunkIndex,
                    plainLength
            );
            try {
                readFullyAt(channel, encryptedChunkOffset(chunkIndex), encrypted);
                Cipher cipher = newCipher(
                        Cipher.DECRYPT_MODE,
                        key,
                        noncePrefix,
                        chunkIndex
                );
                cipher.updateAAD(aad);
                byte[] decrypted = cipher.doFinal(encrypted);
                if (decrypted.length != plainLength) {
                    wipe(decrypted);
                    throw new IOException("Encrypted media chunk has an invalid length.");
                }
                cachedPlaintext = decrypted;
                cachedChunkIndex = chunkIndex;
                return cachedPlaintext;
            } catch (AEADBadTagException exception) {
                throw authenticationFailure(exception);
            } catch (GeneralSecurityException exception) {
                throw new IOException("Unable to decrypt encrypted media.", exception);
            } finally {
                wipe(encrypted);
                wipe(aad);
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Encrypted media reader is closed.");
            }
        }

        private void clearCachedPlaintext() {
            wipe(cachedPlaintext);
            cachedPlaintext = null;
            cachedChunkIndex = -1L;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            clearCachedPlaintext();
            wipe(key);
            wipe(noncePrefix);
            channel.close();
        }
    }
}
