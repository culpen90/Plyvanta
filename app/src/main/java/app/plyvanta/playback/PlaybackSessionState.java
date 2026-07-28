package app.plyvanta.playback;

/**
 * Pure state rules that keep foreground visibility and logical playback intent
 * separate from the transport's temporary state.
 */
public final class PlaybackSessionState {
    public static final long NO_PENDING_SEEK = Long.MIN_VALUE;

    private boolean activityStarted;
    private boolean intendedPlayWhenReady = true;

    /** Marks the activity as visible enough for playback. */
    public void onStart() {
        activityStarted = true;
    }

    /** Prevents transport changes while stopped from replacing the user's intent. */
    public void onStop() {
        activityStarted = false;
    }

    /** Records an explicit item-start or restored playback intent. */
    public void requestPlayWhenReady(boolean playWhenReady) {
        intendedPlayWhenReady = playWhenReady;
    }

    /**
     * Records player-control changes only while the activity is started.
     *
     * <p>The activity sets the transport to paused after {@link #onStop()}, so
     * that lifecycle-only pause must not erase an intended resume.
     */
    public void onPlayerPlayWhenReadyChanged(boolean playWhenReady) {
        if (activityStarted) {
            intendedPlayWhenReady = playWhenReady;
        }
    }

    /** Returns whether the transport may currently honor the logical play intent. */
    public boolean shouldPlayWhenReady() {
        return activityStarted && intendedPlayWhenReady;
    }

    /** Returns the logical intent to persist across activity recreation. */
    public boolean intendedPlayWhenReady() {
        return intendedPlayWhenReady;
    }

    /** Converts a persisted player position into a pending seek, rejecting sentinels. */
    public static long restoredPosition(long savedPositionMs) {
        return savedPositionMs < 0 ? NO_PENDING_SEEK : savedPositionMs;
    }

    /** Returns whether a real item-scoped seek is waiting to be applied. */
    public static boolean hasPendingSeek(long pendingSeekMs) {
        return pendingSeekMs != NO_PENDING_SEEK;
    }

    /**
     * Keeps a restored seek only when the re-resolved queue recovered the same
     * video identity.
     */
    public static long seekForRestoredVideo(
            long pendingSeekMs,
            String restoredVideoId,
            String selectedVideoId
    ) {
        if (!hasPendingSeek(pendingSeekMs)
                || restoredVideoId == null
                || !restoredVideoId.equals(selectedVideoId)) {
            return NO_PENDING_SEEK;
        }
        return pendingSeekMs;
    }

    /**
     * Selects a position only when a video ID anchors the index to the compacted
     * playable queue.
     */
    public static int anchoredPlaylistIndex(
            int requestedIndex,
            int sourceIndex,
            String videoIdAnchor
    ) {
        if (videoIdAnchor == null || videoIdAnchor.isBlank()) {
            return -1;
        }
        return requestedIndex >= 0 ? requestedIndex : sourceIndex;
    }

    /**
     * Returns an item-scoped position for saved state.
     *
     * <p>A pending restore/retry seek is authoritative. Until the selected
     * item's media has been installed, the player's position still belongs to
     * the previous item and must not be persisted.
     */
    public static long positionToSave(
            boolean selectedMediaPrepared,
            long pendingSeekMs,
            long playerPositionMs
    ) {
        if (hasPendingSeek(pendingSeekMs)) {
            return Math.max(0L, pendingSeekMs);
        }
        return selectedMediaPrepared ? Math.max(0L, playerPositionMs) : 0L;
    }
}
