package app.plyvanta.playback;

import java.util.List;
import java.util.Objects;

/** Mutable position over one immutable resolved playlist. */
public final class PlaylistQueue {
    private final ResolvedPlaylist playlist;
    private final List<PlaylistEntry> entries;
    private int position;

    public PlaylistQueue(
            ResolvedPlaylist playlist,
            int requestedZeroBasedIndex,
            String startingVideoId
    ) {
        this.playlist = Objects.requireNonNull(playlist, "playlist");
        this.entries = playlist.getEntries();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "A playlist queue must contain at least one playable video."
            );
        }
        position = startingPosition(requestedZeroBasedIndex, startingVideoId);
    }

    public PlaylistQueue(ResolvedPlaylist playlist) {
        this(playlist, 0, null);
    }

    public ResolvedPlaylist getPlaylist() {
        return playlist;
    }

    public PlaylistEntry current() {
        return entries.get(position);
    }

    public int position() {
        return position;
    }

    public int size() {
        return entries.size();
    }

    public boolean hasNext() {
        return position + 1 < entries.size();
    }

    /**
     * Advances to the next entry and returns whether the position changed.
     * At the end, the current position is left unchanged.
     */
    public boolean next() {
        if (!hasNext()) {
            return false;
        }
        position++;
        return true;
    }

    public boolean hasPrevious() {
        return position > 0;
    }

    /**
     * Moves to the previous entry and returns whether the position changed.
     * At the beginning, the current position is left unchanged.
     */
    public boolean previous() {
        if (!hasPrevious()) {
            return false;
        }
        position--;
        return true;
    }

    private int startingPosition(int requestedIndex, String startingVideoId) {
        boolean requestedIndexIsValid =
                requestedIndex >= 0 && requestedIndex < entries.size();
        boolean hasStartingVideoId =
                startingVideoId != null && !startingVideoId.isBlank();

        if (requestedIndexIsValid
                && (!hasStartingVideoId
                || entries.get(requestedIndex).getVideoId().equals(startingVideoId))) {
            return requestedIndex;
        }

        if (hasStartingVideoId) {
            int matchingIndex = closestMatchingIndex(
                    startingVideoId,
                    requestedIndexIsValid ? requestedIndex : -1
            );
            if (matchingIndex >= 0) {
                return matchingIndex;
            }
        }

        return requestedIndexIsValid ? requestedIndex : 0;
    }

    private int closestMatchingIndex(String videoId, int requestedIndex) {
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < entries.size(); index++) {
            if (!entries.get(index).getVideoId().equals(videoId)) {
                continue;
            }
            if (requestedIndex < 0) {
                return index;
            }
            int distance = Math.abs(index - requestedIndex);
            if (distance < bestDistance) {
                bestIndex = index;
                bestDistance = distance;
            }
        }
        return bestIndex;
    }
}
