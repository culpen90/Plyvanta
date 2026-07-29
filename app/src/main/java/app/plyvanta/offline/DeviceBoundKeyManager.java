package app.plyvanta.offline;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.security.keystore.UserNotAuthenticatedException;

import androidx.annotation.RequiresApi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

/**
 * StrongBox-only Android Keystore protection for per-item content keys.
 */
public final class DeviceBoundKeyManager implements ContentKeyProtector {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "plyvanta_offline_wrapping_v1";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int CONTENT_KEY_BYTES = 32;
    private static final int AUTHENTICATION_WINDOW_SECONDS = 30;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] ITEM_AAD_PREFIX =
            "Plyvanta/offline-content-key/v1".getBytes(StandardCharsets.US_ASCII);

    private final Context context;
    private final OfflineSecurityPolicy securityPolicy;

    public DeviceBoundKeyManager(Context context) {
        this(context, new OfflineSecurityPolicy());
    }

    DeviceBoundKeyManager(
            Context context,
            OfflineSecurityPolicy securityPolicy
    ) {
        Context supplied = Objects.requireNonNull(context, "context");
        Context applicationContext = supplied.getApplicationContext();
        this.context = applicationContext == null ? supplied : applicationContext;
        this.securityPolicy = Objects.requireNonNull(
                securityPolicy,
                "securityPolicy"
        );
    }

    @Override
    public OfflineSecurityPolicy.Decision status() {
        return securityPolicy.evaluate(context);
    }

    @Override
    public synchronized Envelope wrap(byte[] contentKey, String itemId)
            throws KeyProtectionException {
        requireEligibleDevice();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw apiTooOld();
        }
        if (contentKey == null || contentKey.length != CONTENT_KEY_BYTES) {
            throw new InvalidEnvelopeException(
                    "Content keys must contain exactly "
                            + CONTENT_KEY_BYTES + " bytes."
            );
        }
        byte[] aad = itemAad(itemId);

        try {
            SecretKey wrappingKey = getOrCreateVerifiedKey();
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(contentKey);
            return Envelope.create(cipher.getIV(), ciphertext);
        } catch (KeyProtectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw translateFailure("Unable to wrap the offline content key.", exception);
        }
    }

    @Override
    public synchronized byte[] unwrap(Envelope envelope, String itemId)
            throws KeyProtectionException {
        requireEligibleDevice();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw apiTooOld();
        }
        Objects.requireNonNull(envelope, "envelope");
        byte[] aad = itemAad(itemId);

        try {
            SecretKey wrappingKey = getOrCreateVerifiedKey();
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey,
                    new GCMParameterSpec(
                            GCM_TAG_BITS,
                            envelope.getInitializationVector()
                    )
            );
            cipher.updateAAD(aad);
            byte[] contentKey = cipher.doFinal(envelope.getCiphertext());
            if (contentKey.length != CONTENT_KEY_BYTES) {
                Arrays.fill(contentKey, (byte) 0);
                throw new InvalidEnvelopeException(
                        "The unwrapped content key has an invalid length."
                );
            }
            return contentKey;
        } catch (KeyProtectionException exception) {
            throw exception;
        } catch (Exception exception) {
            if (hasCause(exception, AEADBadTagException.class)
                    || exception instanceof BadPaddingException) {
                throw new InvalidEnvelopeException(
                        "The wrapped key is corrupt or belongs to another item.",
                        exception
                );
            }
            throw translateFailure(
                    "Unable to unwrap the offline content key.",
                    exception
            );
        }
    }

    @Override
    public synchronized void deleteKey() throws KeyUnavailableException {
        try {
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception exception) {
            throw new KeyUnavailableException(
                    "Unable to delete the offline wrapping key.",
                    exception
            );
        }
    }

    private void requireEligibleDevice() throws KeyUnavailableException {
        OfflineSecurityPolicy.Decision decision = status();
        if (!decision.isAllowed()) {
            throw new KeyUnavailableException(
                    decision.getReason() + ": " + decision.getMessage()
            );
        }
    }

    private static KeyUnavailableException apiTooOld() {
        return new KeyUnavailableException(
                "Offline key protection requires Android 9 or newer."
        );
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private SecretKey getOrCreateVerifiedKey() throws Exception {
        KeyStore keyStore = loadKeyStore();
        SecretKey key;
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Key stored = keyStore.getKey(KEY_ALIAS, null);
            if (!(stored instanceof SecretKey)) {
                deleteRejectedEntry(keyStore);
                throw new KeyUnavailableException(
                        "The offline wrapping-key entry has an invalid type."
                );
            }
            key = (SecretKey) stored;
        } else {
            key = generateStrongBoxKey();
        }

        verifyKeyProperties(keyStore, key);
        return key;
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static SecretKey generateStrongBoxKey() throws Exception {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setIsStrongBoxBacked(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                    AUTHENTICATION_WINDOW_SECONDS,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL
            );
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(
                    AUTHENTICATION_WINDOW_SECONDS
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setUnlockedDeviceRequired(true);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
        );
        generator.init(builder.build(), new SecureRandom());
        return generator.generateKey();
    }

    private static void verifyKeyProperties(
            KeyStore keyStore,
            SecretKey key
    ) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(
                key.getAlgorithm(),
                ANDROID_KEYSTORE
        );
        KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);

        boolean strongBox;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            strongBox = info.getSecurityLevel()
                    == KeyProperties.SECURITY_LEVEL_STRONGBOX;
        } else {
            // API 28-30 do not expose the StrongBox/TEE distinction through
            // KeyInfo. Generation requested StrongBox with no fallback, so a
            // hardware-backed result is the strongest available verification.
            strongBox = info.isInsideSecureHardware();
        }

        int requiredPurposes =
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
        boolean deviceCredentialType = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || info.getUserAuthenticationType()
                == KeyProperties.AUTH_DEVICE_CREDENTIAL;
        boolean valid = strongBox
                && info.getKeySize() == 256
                && info.getOrigin() == KeyProperties.ORIGIN_GENERATED
                && info.getPurposes() == requiredPurposes
                && containsExactly(
                        info.getBlockModes(),
                        KeyProperties.BLOCK_MODE_GCM
                )
                && containsExactly(
                        info.getEncryptionPaddings(),
                        KeyProperties.ENCRYPTION_PADDING_NONE
                )
                && info.isUserAuthenticationRequired()
                && info.isUserAuthenticationRequirementEnforcedBySecureHardware()
                && info.getUserAuthenticationValidityDurationSeconds()
                        == AUTHENTICATION_WINDOW_SECONDS
                && deviceCredentialType;
        if (!valid) {
            deleteRejectedEntry(keyStore);
            throw new KeyUnavailableException(
                    "StrongBox did not enforce the required offline-key policy."
            );
        }
    }

    private static void deleteRejectedEntry(KeyStore keyStore) {
        try {
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // The unsafe entry is never returned even if deletion also fails.
        }
    }

    private static boolean containsExactly(String[] values, String expected) {
        return values != null
                && values.length == 1
                && expected.equals(values[0]);
    }

    static byte[] itemAad(String itemId)
            throws InvalidEnvelopeException {
        UUID uuid;
        try {
            uuid = UUID.fromString(itemId);
        } catch (RuntimeException exception) {
            throw new InvalidEnvelopeException(
                    "Item ID must be a canonical UUID.",
                    exception
            );
        }
        if (!uuid.toString().equals(itemId)) {
            throw new InvalidEnvelopeException(
                    "Item ID must be a lowercase canonical UUID."
            );
        }
        return ByteBuffer.allocate(ITEM_AAD_PREFIX.length + 2 * Long.BYTES)
                .put(ITEM_AAD_PREFIX)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static KeyProtectionException translateFailure(
            String message,
            Exception exception
    ) {
        UserNotAuthenticatedException authenticationFailure =
                findCause(exception, UserNotAuthenticatedException.class);
        if (authenticationFailure != null) {
            return new AuthenticationRequiredException(
                    "Confirm the device credential before using offline media.",
                    authenticationFailure
            );
        }

        KeyPermanentlyInvalidatedException invalidated =
                findCause(exception, KeyPermanentlyInvalidatedException.class);
        if (invalidated != null) {
            return new KeyInvalidatedException(
                    "The device-bound offline key was permanently invalidated.",
                    invalidated
            );
        }

        StrongBoxUnavailableException strongBoxFailure =
                findCause(exception, StrongBoxUnavailableException.class);
        if (strongBoxFailure != null) {
            return new KeyUnavailableException(
                    "StrongBox could not create the offline wrapping key.",
                    strongBoxFailure
            );
        }

        return new KeyUnavailableException(message, exception);
    }

    private static boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type
    ) {
        return findCause(throwable, type) != null;
    }

    private static <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> type
    ) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
