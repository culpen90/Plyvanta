package app.plyvanta.update;

import java.util.Objects;

public final class UpdateDownloadVerification {
    public enum Action {
        OPEN_VERIFIED_RELEASE,
        SHOW_REFRESHED_RELEASE,
        SHOW_ERROR
    }

    private UpdateDownloadVerification() {
    }

    public static boolean canApplyResult(
            long resultGeneration,
            long currentGeneration,
            boolean resumed
    ) {
        return resumed
                && resultGeneration > 0L
                && resultGeneration == currentGeneration;
    }

    public static Action decide(
            UpdateChecker.Status status,
            UpdateRelease displayedRelease,
            UpdateRelease checkedRelease
    ) {
        Objects.requireNonNull(displayedRelease, "displayedRelease");
        if (status != UpdateChecker.Status.SUCCESS || checkedRelease == null) {
            return Action.SHOW_ERROR;
        }
        return displayedRelease.equals(checkedRelease)
                ? Action.OPEN_VERIFIED_RELEASE
                : Action.SHOW_REFRESHED_RELEASE;
    }
}
