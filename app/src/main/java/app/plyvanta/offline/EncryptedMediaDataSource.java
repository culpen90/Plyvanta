package app.plyvanta.offline;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Media3 data source that exposes an authenticated {@link EncryptedChunkFile} as seekable media.
 *
 * <p>Only opaque {@code plyvanta-vault://} identifiers reach the resolver. This class never
 * interprets a URI as a filesystem path, and it never writes decrypted bytes to disk. A
 * {@link ProgressiveMediaSource} can use {@link Factory} directly.
 */
@UnstableApi
public final class EncryptedMediaDataSource extends BaseDataSource {
    public static final String URI_SCHEME = "plyvanta-vault";

    /**
     * Resolves a validated opaque vault URI to app-private catalog data.
     */
    @FunctionalInterface
    public interface ResourceResolver {
        Resource resolve(Uri uri) throws IOException;
    }

    /**
     * Acquires a fresh in-memory copy of a resource's unwrapped 256-bit data-encryption key.
     *
     * <p>The returned array is cleared immediately after the encrypted reader copies it.
     */
    @FunctionalInterface
    public interface KeyProvider {
        byte[] acquireKey() throws IOException;
    }

    /**
     * Tracks the lifetime of an opened encrypted reader.
     *
     * <p>A playback owner can use this hook to revoke every open file descriptor and clear every
     * derived key when its session closes. Implementations must not retain plaintext.</p>
     */
    public interface ReaderObserver {
        void onReaderOpened(EncryptedChunkFile.Reader reader) throws IOException;

        void onReaderClosed(EncryptedChunkFile.Reader reader);

        void onReaderOpenFinished();

        void onBeforeRead() throws IOException;
    }

    /**
     * Trusted catalog entry returned by {@link ResourceResolver}.
     */
    public static final class Resource {
        private static final ReaderObserver NO_OP_READER_OBSERVER =
                new ReaderObserver() {
                    @Override
                    public void onReaderOpened(
                            EncryptedChunkFile.Reader reader
                    ) {
                        // No owner-level lifetime tracking was requested.
                    }

                    @Override
                    public void onReaderClosed(
                            EncryptedChunkFile.Reader reader
                    ) {
                        // No owner-level lifetime tracking was requested.
                    }

                    @Override
                    public void onReaderOpenFinished() {
                        // No owner-level lifetime tracking was requested.
                    }

                    @Override
                    public void onBeforeRead() {
                        // No per-read policy was requested.
                    }
                };

        private final Path encryptedFile;
        private final UUID itemId;
        private final EncryptedChunkFile.TrackRole trackRole;
        private final long authenticatedPlaintextLength;
        private final KeyProvider keyProvider;
        private final ReaderObserver readerObserver;

        Resource(
                Path encryptedFile,
                UUID itemId,
                EncryptedChunkFile.TrackRole trackRole,
                long authenticatedPlaintextLength,
                KeyProvider keyProvider
        ) {
            this(
                    encryptedFile,
                    itemId,
                    trackRole,
                    authenticatedPlaintextLength,
                    keyProvider,
                    NO_OP_READER_OBSERVER
            );
        }

        Resource(
                Path encryptedFile,
                UUID itemId,
                EncryptedChunkFile.TrackRole trackRole,
                long authenticatedPlaintextLength,
                KeyProvider keyProvider,
                ReaderObserver readerObserver
        ) {
            this.encryptedFile = Objects.requireNonNull(
                    encryptedFile,
                    "encryptedFile"
            ).toAbsolutePath().normalize();
            this.itemId = Objects.requireNonNull(itemId, "itemId");
            this.trackRole = Objects.requireNonNull(trackRole, "trackRole");
            if (authenticatedPlaintextLength < 0L) {
                throw new IllegalArgumentException(
                        "authenticatedPlaintextLength must not be negative"
                );
            }
            this.authenticatedPlaintextLength = authenticatedPlaintextLength;
            this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
            this.readerObserver = Objects.requireNonNull(
                    readerObserver,
                    "readerObserver"
            );
        }

        byte[] acquireKeyForReader() throws IOException {
            return keyProvider.acquireKey();
        }

        void finishReaderOpen() {
            readerObserver.onReaderOpenFinished();
        }
    }

    public static final class Factory implements DataSource.Factory {
        private final ResourceResolver resolver;

        public Factory(ResourceResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        @Override
        public EncryptedMediaDataSource createDataSource() {
            return new EncryptedMediaDataSource(resolver);
        }
    }

    private final ResourceResolver resolver;

    @Nullable
    private EncryptedChunkFile.Reader reader;
    @Nullable
    private ReaderObserver activeReaderObserver;
    @Nullable
    private Uri openedUri;
    private long readPosition;
    private long bytesRemaining;
    private boolean transferStarted;

    EncryptedMediaDataSource(ResourceResolver resolver) {
        super(false);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Objects.requireNonNull(dataSpec, "dataSpec");
        if (reader != null || transferStarted) {
            throw new IOException("Encrypted media data source is already open.");
        }
        validateVaultUri(dataSpec.uri);
        transferInitializing(dataSpec);

        Resource resource = Objects.requireNonNull(
                resolver.resolve(dataSpec.uri),
                "ResourceResolver returned null"
        );

        byte[] acquiredKey = null;
        EncryptedChunkFile.Reader openedReader = null;
        ReaderObserver openedReaderObserver = null;
        boolean observerRegistered = false;
        boolean readerOpenLeaseActive = false;
        try {
            acquiredKey = resource.acquireKeyForReader();
            readerOpenLeaseActive = true;
            if (acquiredKey == null) {
                throw new IOException("Vault key provider returned no key.");
            }
            if (acquiredKey.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                throw new IOException("Vault key provider returned an invalid key.");
            }
            openedReader = EncryptedChunkFile.open(
                    resource.encryptedFile,
                    acquiredKey,
                    resource.itemId,
                    resource.trackRole,
                    resource.authenticatedPlaintextLength
            );

            long position = dataSpec.position;
            long requestedLength = dataSpec.length;
            long readableLength = resolveReadableLength(
                    position,
                    requestedLength,
                    openedReader.length()
            );

            openedReaderObserver = resource.readerObserver;
            openedReaderObserver.onReaderOpened(openedReader);
            observerRegistered = true;

            reader = openedReader;
            openedReader = null;
            activeReaderObserver = openedReaderObserver;
            openedReaderObserver = null;
            observerRegistered = false;
            openedUri = dataSpec.uri;
            readPosition = position;
            bytesRemaining = readableLength;
            transferStarted = true;
            try {
                transferStarted(dataSpec);
            } catch (RuntimeException | Error callbackFailure) {
                resetAfterFailedTransferStart(callbackFailure);
                throw callbackFailure;
            }
            return resolveReportedOpenLength(
                    requestedLength,
                    readableLength
            );
        } finally {
            try {
                if (acquiredKey != null) {
                    Arrays.fill(acquiredKey, (byte) 0);
                }
                if (openedReader != null) {
                    try {
                        openedReader.close();
                    } finally {
                        if (observerRegistered
                                && openedReaderObserver != null) {
                            openedReaderObserver.onReaderClosed(openedReader);
                        }
                    }
                }
            } finally {
                if (readerOpenLeaseActive) {
                    resource.finishReaderOpen();
                }
            }
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if (reader == null || !transferStarted) {
            throw new IOException("Encrypted media data source is not open.");
        }
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT;
        }

        ReaderObserver readObserver = activeReaderObserver;
        if (readObserver == null) {
            throw new IOException(
                    "Encrypted media reader has no lifecycle owner."
            );
        }
        readObserver.onBeforeRead();
        int requested = (int) Math.min((long) length, bytesRemaining);
        int count = reader.read(readPosition, buffer, offset, requested);
        if (count <= 0) {
            throw new IOException("Encrypted media ended before its authenticated length.");
        }
        readPosition += count;
        bytesRemaining -= count;
        bytesTransferred(count);
        return count;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return openedUri;
    }

    @Override
    public void close() throws IOException {
        IOException closeFailure = null;
        EncryptedChunkFile.Reader closingReader = reader;
        ReaderObserver closingObserver = activeReaderObserver;
        reader = null;
        activeReaderObserver = null;
        openedUri = null;
        readPosition = 0L;
        bytesRemaining = 0L;

        if (closingReader != null) {
            try {
                closingReader.close();
            } catch (IOException exception) {
                closeFailure = exception;
            } finally {
                if (closingObserver != null) {
                    closingObserver.onReaderClosed(closingReader);
                }
            }
        }
        if (transferStarted) {
            transferStarted = false;
            try {
                transferEnded();
            } catch (RuntimeException | Error callbackFailure) {
                if (closeFailure != null) {
                    callbackFailure.addSuppressed(closeFailure);
                }
                throw callbackFailure;
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void resetAfterFailedTransferStart(Throwable callbackFailure) {
        EncryptedChunkFile.Reader failedReader = reader;
        ReaderObserver failedObserver = activeReaderObserver;
        reader = null;
        activeReaderObserver = null;
        openedUri = null;
        readPosition = 0L;
        bytesRemaining = 0L;

        if (failedReader != null) {
            try {
                failedReader.close();
            } catch (IOException closeFailure) {
                callbackFailure.addSuppressed(closeFailure);
            } finally {
                if (failedObserver != null) {
                    try {
                        failedObserver.onReaderClosed(failedReader);
                    } catch (RuntimeException observerFailure) {
                        callbackFailure.addSuppressed(observerFailure);
                    }
                }
            }
        }

        if (transferStarted) {
            transferStarted = false;
            try {
                transferEnded();
            } catch (RuntimeException | Error endFailure) {
                callbackFailure.addSuppressed(endFailure);
            }
        }
    }

    private static void validateVaultUri(Uri uri) throws IOException {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        if (scheme == null
                || !URI_SCHEME.equals(scheme.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IOException("Encrypted media URI is invalid.");
        }
    }

    static long resolveReadableLength(
            long position,
            long requestedLength,
            long resourceLength
    ) throws IOException {
        if (resourceLength < 0L) {
            throw new IOException(
                    "Authenticated encrypted media length is invalid."
            );
        }
        if (position < 0L || position > resourceLength) {
            throw new DataSourceException(
                    "Requested encrypted media position is out of range.",
                    DataSourceException.POSITION_OUT_OF_RANGE
            );
        }
        if (requestedLength == C.LENGTH_UNSET) {
            return resourceLength - position;
        }
        if (requestedLength < 0L) {
            throw new IOException(
                    "Requested encrypted media length is invalid."
            );
        }
        return Math.min(requestedLength, resourceLength - position);
    }

    static long resolveReportedOpenLength(
            long requestedLength,
            long readableLength
    ) {
        return requestedLength == C.LENGTH_UNSET
                ? readableLength
                : requestedLength;
    }
}
