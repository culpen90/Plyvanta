package app.plyvanta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.plyvanta.update.UpdateChecker;

public final class ManualUpdateCheckControllerTest {
    @Test
    public void startsCheckingOnceAndRejectsDuplicateStart() {
        ManualUpdateCheckController controller = new ManualUpdateCheckController();

        assertEquals(
                ManualUpdateCheckController.State.IDLE,
                controller.getState()
        );
        assertTrue(controller.start());
        assertEquals(
                ManualUpdateCheckController.State.CHECKING,
                controller.getState()
        );
        assertFalse(controller.start());
        assertEquals(
                ManualUpdateCheckController.State.CHECKING,
                controller.getState()
        );
    }

    @Test
    public void successfulCheckWithoutAvailableReleaseShowsUpToDate() {
        ManualUpdateCheckController controller = checkingController();

        assertEquals(
                ManualUpdateCheckController.Completion.SHOW_FEEDBACK,
                controller.complete(UpdateChecker.Status.SUCCESS, false)
        );
        assertEquals(
                ManualUpdateCheckController.State.UP_TO_DATE,
                controller.getState()
        );
        assertTrue(controller.start());
    }

    @Test
    public void retryableFailureShowsErrorAndAllowsRetry() {
        assertErrorAndRetry(UpdateChecker.Status.RETRYABLE_FAILURE);
    }

    @Test
    public void permanentFailureShowsErrorAndAllowsRetry() {
        assertErrorAndRetry(UpdateChecker.Status.PERMANENT_FAILURE);
    }

    @Test
    public void missingResultShowsErrorAndAllowsRetry() {
        assertErrorAndRetry(null);
    }

    @Test
    public void successfulCheckWithAvailableReleaseRoutesToPromptAndIdle() {
        ManualUpdateCheckController controller = checkingController();

        assertEquals(
                ManualUpdateCheckController.Completion.SHOW_UPDATE,
                controller.complete(UpdateChecker.Status.SUCCESS, true)
        );
        assertEquals(
                ManualUpdateCheckController.State.IDLE,
                controller.getState()
        );
    }

    private static ManualUpdateCheckController checkingController() {
        ManualUpdateCheckController controller = new ManualUpdateCheckController();
        assertTrue(controller.start());
        return controller;
    }

    private static void assertErrorAndRetry(UpdateChecker.Status status) {
        ManualUpdateCheckController controller = checkingController();

        assertEquals(
                ManualUpdateCheckController.Completion.SHOW_FEEDBACK,
                controller.complete(status, false)
        );
        assertEquals(
                ManualUpdateCheckController.State.ERROR,
                controller.getState()
        );
        assertTrue(controller.start());
        assertEquals(
                ManualUpdateCheckController.State.CHECKING,
                controller.getState()
        );
    }
}
