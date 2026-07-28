package app.plyvanta;

import app.plyvanta.update.UpdateChecker;

final class ManualUpdateCheckController {
    enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        UNVERIFIED,
        ERROR
    }

    enum Completion {
        SHOW_FEEDBACK,
        SHOW_UPDATE
    }

    private State state = State.IDLE;

    State getState() {
        return state;
    }

    boolean start() {
        if (state == State.CHECKING) {
            return false;
        }
        state = State.CHECKING;
        return true;
    }

    void resetFeedbackWhenUpdateIsAvailable(boolean updateAvailable) {
        if (updateAvailable && state != State.CHECKING) {
            state = State.IDLE;
        }
    }

    Completion complete(UpdateChecker.Status status, boolean updateAvailable) {
        if (status == UpdateChecker.Status.SUCCESS && updateAvailable) {
            state = State.IDLE;
            return Completion.SHOW_UPDATE;
        }
        if (status == UpdateChecker.Status.SUCCESS) {
            state = State.UP_TO_DATE;
        } else if (status == UpdateChecker.Status.UNVERIFIED_RELEASE) {
            state = State.UNVERIFIED;
        } else {
            state = State.ERROR;
        }
        return Completion.SHOW_FEEDBACK;
    }
}
