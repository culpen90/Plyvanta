package app.plyvanta.offline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Domain-separated key derivation and authenticated metadata encryption for the offline vault.
 */
final class OfflineCrypto {
    static final String PURPOSE_METADATA = "metadata";
    static final String PURPOSE_PROGRESSIVE = "progressive";
    static final String PURPOSE_VIDEO = "video";
    static final String PURPOSE_AUDIO = "audio";

    private static final Set<String> PURPOSES = Set.of(
            PURPOSE_METADATA,
            PURPOSE_PROGRESSIVE,
            PURPOSE_VIDEO,
            PURPOSE_AUDIO
    );
    private static final int MAGIC = 0x50564d44; // PVMD
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int HEADER_BYTES =
            Integer.BYTES + Byte.BYTES + Byte.BYTES + Short.BYTES + Integer.BYTES;
    private static final int MAX_PLAINTEXT_BYTES =
            OfflineMediaRecordCodec.MAX_ENCODED_BYTES;
    private static final int MAX_ENCODED_BYTES =
            HEADER_BYTES + NONCE_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES;
    private static final byte[] DERIVATION_PREFIX =
            "Plyvanta offline key derivation v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METADATA_AAD_PREFIX =
            "Plyvanta offline metadata v1\0".getBytes(StandardCharsets.UTF_8);

    private OfflineCrypto() {
    }

    static byte[] deriveKey(byte[] contentKey, String purpose)
            throws GeneralSecurityException {
        validateContentKey(contentKey);
        if (!PURPOSES.contains(purpose)) {
            throw new IllegalArgumentException("Unknown offline key purpose.");
        }

        byte[] ownedKey = contentKey.clone();
        byte[] purposeBytes = purpose.getBytes(StandardCharsets.US_ASCII);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ownedKey, "HmacSHA256"));
            mac.update(DERIVATION_PREFIX);
            mac.update(purposeBytes);
            byte[] derived = mac.doFinal();
            if (derived.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                Arrays.fill(derived, (byte) 0);
                throw new GeneralSecurityException(
                        "Offline key derivation returned an invalid length."
                );
            }
            return derived;
        } finally {
            Arrays.fill(ownedKey, (byte) 0);
            Arrays.fill(purposeBytes, (byte) 0);
        }
    }

    static byte[] encryptMetadata(
            byte[] plaintext,
            byte[] contentKey,
            UUID itemId
    ) throws IOException {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(itemId, "itemId");
        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IOException("Offline metadata is empty or oversized.");
        }

        byte[] metadataKey = null;
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = null;
        new SecureRandom().nextBytes(nonce);
        try {
            metadataKey = deriveKey(contentKey, PURPOSE_METADATA);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(metadataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce)
            );
            cipher.updateAAD(metadataAad(itemId));
            ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(HEADER_BYTES + nonce.length + ciphertext.length)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(MAGIC)
                    .put((byte) VERSION)
                    .put((byte) nonce.length)
                    .putShort((short) 0)
                    .putInt(ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to encrypt offline metadata.", exception);
        } finally {
            wipe(metadataKey);
            wipe(nonce);
            wipe(ciphertext);
        }
    }

    static byte[] decryptMetadata(
            byte[] encoded,
            byte[] contentKey,
            UUID itemId
    ) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(itemId, "itemId");
        if (encoded.length < HEADER_BYTES + NONCE_BYTES + TAG_BYTES
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Encrypted offline metadata has an invalid length.");
        }

        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] metadataKey = null;
        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
            if (input.getInt() != MAGIC || Byte.toUnsignedInt(input.get()) != VERSION) {
                throw new IOException("Encrypted offline metadata format is unsupported.");
            }
            int nonceLength = Byte.toUnsignedInt(input.get());
            if (input.getShort() != 0 || nonceLength != NONCE_BYTES) {
                throw new IOException("Encrypted offline metadata header is invalid.");
            }
            int ciphertextLength = input.getInt();
            if (ciphertextLength < TAG_BYTES
                    || ciphertextLength > MAX_PLAINTEXT_BYTES + TAG_BYTES
                    || input.remaining() != nonceLength + ciphertextLength) {
                throw new IOException("Encrypted offline metadata fields are invalid.");
            }
            nonce = new byte[nonceLength];
            ciphertext = new byte[ciphertextLength];
            input.get(nonce);
            input.get(ciphertext);

            metadataKey = deriveKey(contentKey, PURPOSE_METADATA);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(metadataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce)
            );
            cipher.updateAAD(metadataAad(itemId));
            byte[] plaintext = cipher.doFinal(ciphertext);
            if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
                wipe(plaintext);
                throw new IOException("Decrypted offline metadata is invalid.");
            }
            return plaintext;
        } catch (AEADBadTagException exception) {
            throw new IOException(
                    "Offline metadata failed authentication.",
                    exception
            );
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IOException("Unable to decrypt offline metadata.", exception);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
            wipe(metadataKey);
        }
    }

    private static byte[] metadataAad(UUID itemId) {
        return ByteBuffer.allocate(METADATA_AAD_PREFIX.length + 2 * Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(METADATA_AAD_PREFIX)
                .putLong(itemId.getMostSignificantBits())
                .putLong(itemId.getLeastSignificantBits())
                .array();
    }

    private static void validateContentKey(byte[] contentKey) {
        if (contentKey == null
                || contentKey.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Offline content keys must contain exactly "
                            + EncryptedChunkFile.KEY_SIZE_BYTES + " bytes."
            );
        }
    }

    static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
