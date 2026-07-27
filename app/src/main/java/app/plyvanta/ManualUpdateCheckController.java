package app.plyvanta;

import app.plyvanta.update.UpdateChecker;

final class ManualUpdateCheckController {
    enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
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

    Completion complete(UpdateChecker.Status status, boolean updateAvailable) {
        if (status == UpdateChecker.Status.SUCCESS && updateAvailable) {
            state = State.IDLE;
            return Completion.SHOW_UPDATE;
        }
        state = status == UpdateChecker.Status.SUCCESS
                ? State.UP_TO_DATE
                : State.ERROR;
        return Completion.SHOW_FEEDBACK;
    }
}
