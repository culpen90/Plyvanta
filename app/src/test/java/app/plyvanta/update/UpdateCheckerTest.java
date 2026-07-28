package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class UpdateCheckerTest {
    private static final long INSTALLED_VERSION_CODE = 4L;
    private static final String PACKAGE_NAME = "app.plyvanta.debug";
    private static final int DEVICE_SDK = 36;
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Test
    public void compatibleReleaseIsStoredAndReturned() {
        UpdateRelease fetchedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> {
                    assertEquals(INSTALLED_VERSION_CODE, installedVersionCode);
                    assertEquals(PACKAGE_NAME, packageName);
                    assertSame(UpdateChannel.PREVIEW, channel);
                    assertEquals(DEVICE_SDK, deviceSdk);
                    return fetchedRelease;
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(fetchedRelease, result.getAvailableRelease());
        assertSame(fetchedRelease, store.release);
        assertEquals(1, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void upToDateHasNoCheckedOrAvailableRelease() {
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> null,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertEquals(0, store.storeCalls);
    }

    @Test
    public void compatibleStoredReleaseRemainsAvailableWhenFreshFetchFindsNothing() {
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> null,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertEquals(0, store.storeCalls);
    }

    @Test
    public void ioFailureIsRetryable() {
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> {
                    throw new IOException("offline");
                },
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertEquals(0, store.storeCalls);
    }

    @Test
    public void missingInstalledPackageIsPermanentFailure() {
        AtomicInteger fetchCalls = new AtomicInteger();
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        UpdateChecker checker = checker(
                () -> {
                    throw new UpdateChecker.InstalledAppUnavailableException(
                            new IllegalStateException("missing package")
                    );
                },
                (installedVersionCode, packageName, channel, deviceSdk) -> {
                    fetchCalls.incrementAndGet();
                    return release(5L);
                },
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.PERMANENT_FAILURE, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertEquals(0, fetchCalls.get());
        assertEquals(0, store.readCalls);
    }

    @Test
    public void higherStoredReleaseRemainsEffectiveCandidate() {
        UpdateRelease storedRelease = release(7L);
        UpdateRelease fetchedRelease = release(6L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> fetchedRelease,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertSame(storedRelease, store.release);
        assertEquals(0, store.storeCalls);
    }

    @Test
    public void staleStoredReleaseIsClearedAndNotificationCancelled() {
        FakeReleaseStore store = new FakeReleaseStore(
                release(INSTALLED_VERSION_CODE),
                true
        );
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> null,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.clearCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void storeFailureIsRetryableAndDoesNotExposeUnpersistedRelease() {
        UpdateRelease fetchedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(null, false);
        UpdateChecker checker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> fetchedRelease,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.storeCalls);
    }

    @Test
    public void completionKeepsSharedLockUntilTransactionFinishes() throws Exception {
        CountDownLatch firstCompletionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCompletion = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondReleaseSourceEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<UpdateChecker.Result> firstFuture = null;
        Future<UpdateChecker.Result> secondFuture = null;

        UpdateChecker firstChecker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> null,
                new FakeReleaseStore(null, true),
                new AtomicBoolean()
        );
        UpdateChecker secondChecker = checker(
                installedAppSource(),
                (installedVersionCode, packageName, channel, deviceSdk) -> {
                    secondReleaseSourceEntered.countDown();
                    return null;
                },
                new FakeReleaseStore(null, true),
                new AtomicBoolean()
        );

        try {
            firstFuture = executor.submit(() -> firstChecker.checkAndComplete(result -> {
                firstCompletionEntered.countDown();
                awaitLatch(releaseFirstCompletion);
                return result;
            }));
            assertTrue(firstCompletionEntered.await(5, TimeUnit.SECONDS));

            secondFuture = executor.submit(() -> {
                secondAttemptStarted.countDown();
                return secondChecker.check();
            });
            assertTrue(secondAttemptStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    "Second release source entered while the first completion held the lock",
                    secondReleaseSourceEntered.await(500, TimeUnit.MILLISECONDS)
            );

            releaseFirstCompletion.countDown();
            assertSame(
                    UpdateChecker.Status.SUCCESS,
                    firstFuture.get(5, TimeUnit.SECONDS).getStatus()
            );
            assertTrue(secondReleaseSourceEntered.await(5, TimeUnit.SECONDS));
            assertSame(
                    UpdateChecker.Status.SUCCESS,
                    secondFuture.get(5, TimeUnit.SECONDS).getStatus()
            );
        } finally {
            releaseFirstCompletion.countDown();
            if (firstFuture != null) {
                firstFuture.cancel(true);
            }
            if (secondFuture != null) {
                secondFuture.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static UpdateChecker checker(
            UpdateChecker.InstalledAppSource installedAppSource,
            UpdateChecker.ReleaseSource releaseSource,
            FakeReleaseStore store,
            AtomicBoolean notificationCancelled
    ) {
        return new UpdateChecker(
                installedAppSource,
                releaseSource,
                store,
                () -> notificationCancelled.set(true)
        );
    }

    private static UpdateChecker.InstalledAppSource installedAppSource() {
        return () -> new UpdateChecker.InstalledApp(
                INSTALLED_VERSION_CODE,
                PACKAGE_NAME,
                UpdateChannel.PREVIEW,
                DEVICE_SDK
        );
    }

    private static UpdateRelease release(long versionCode) {
        String versionName = "9.0.0-debug." + versionCode;
        return new UpdateRelease(
                versionCode,
                versionName,
                "https://github.com/Plyvanta/Plyvanta/releases/download/v"
                        + versionName + "/Plyvanta-" + versionName + ".apk",
                "https://github.com/Plyvanta/Plyvanta/releases/tag/v" + versionName,
                SHA256
        );
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", interrupted);
        }
    }

    private static final class FakeReleaseStore implements UpdateChecker.ReleaseStore {
        private UpdateRelease release;
        private final boolean storeSucceeds;
        private int readCalls;
        private int clearCalls;
        private int storeCalls;

        private FakeReleaseStore(UpdateRelease release, boolean storeSucceeds) {
            this.release = release;
            this.storeSucceeds = storeSucceeds;
        }

        @Override
        public UpdateRelease availableRelease() {
            readCalls++;
            return release;
        }

        @Override
        public void clearAvailableRelease() {
            clearCalls++;
            release = null;
        }

        @Override
        public boolean storeAvailableRelease(UpdateRelease newRelease) {
            storeCalls++;
            if (storeSucceeds) {
                release = newRelease;
            }
            return storeSucceeds;
        }
    }
}
