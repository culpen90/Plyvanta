package app.plyvanta.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflineSecurityPolicyTest {
    private final OfflineSecurityPolicy policy = new OfflineSecurityPolicy();

    @Test
    public void permitsOnlyTheCompleteSecureEnvironment() {
        OfflineSecurityPolicy.Decision decision =
                policy.evaluate(new FakeEnvironment());

        assertTrue(decision.isAllowed());
        assertEquals(
                OfflineSecurityPolicy.Reason.ALLOWED,
                decision.getReason()
        );
        assertFalse(decision.getMessage().isBlank());
    }

    @Test
    public void rejectsAndroidBeforeApi28() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.apiLevel = 27;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.API_TOO_OLD
        );
    }

    @Test
    public void rejectsMissingSecureDeviceLock() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.secureLock = false;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.SECURE_LOCK_REQUIRED
        );
    }

    @Test
    public void rejectsDevicesWithoutStrongBox() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.strongBox = false;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.STRONGBOX_REQUIRED
        );
    }

    @Test
    public void rejectsDebuggableApps() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.appDebuggable = true;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.DEBUGGABLE_APP
        );
    }

    @Test
    public void rejectsAnAttachedOrWaitingDebugger() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.debuggerAttached = true;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.DEBUGGER_ATTACHED
        );
    }

    @Test
    public void rejectsTestOrDevelopmentSigningTags() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.testKeys = true;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.TEST_KEYS_DETECTED
        );
    }

    @Test
    public void rejectsRootIndicators() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.rootIndicators = true;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.ROOT_INDICATORS_DETECTED
        );
    }

    @Test
    public void reportsTheFirstFailedPrerequisiteDeterministically() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.apiLevel = 27;
        environment.secureLock = false;
        environment.strongBox = false;
        environment.appDebuggable = true;
        environment.debuggerAttached = true;
        environment.testKeys = true;
        environment.rootIndicators = true;

        assertRejected(
                environment,
                OfflineSecurityPolicy.Reason.API_TOO_OLD
        );
    }

    private void assertRejected(
            OfflineSecurityPolicy.Environment environment,
            OfflineSecurityPolicy.Reason expectedReason
    ) {
        OfflineSecurityPolicy.Decision decision = policy.evaluate(environment);

        assertFalse(decision.isAllowed());
        assertEquals(expectedReason, decision.getReason());
        assertFalse(decision.getMessage().isBlank());
    }

    private static final class FakeEnvironment
            implements OfflineSecurityPolicy.Environment {
        private int apiLevel = 36;
        private boolean secureLock = true;
        private boolean strongBox = true;
        private boolean appDebuggable;
        private boolean debuggerAttached;
        private boolean testKeys;
        private boolean rootIndicators;

        @Override
        public int apiLevel() {
            return apiLevel;
        }

        @Override
        public boolean hasSecureLock() {
            return secureLock;
        }

        @Override
        public boolean hasStrongBox() {
            return strongBox;
        }

        @Override
        public boolean isAppDebuggable() {
            return appDebuggable;
        }

        @Override
        public boolean isDebuggerAttached() {
            return debuggerAttached;
        }

        @Override
        public boolean hasTestKeys() {
            return testKeys;
        }

        @Override
        public boolean hasRootIndicators() {
            return rootIndicators;
        }
    }
}
