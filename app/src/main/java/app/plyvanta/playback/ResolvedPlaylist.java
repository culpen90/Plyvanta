package app.plyvanta.playback;

import java.util.List;
import java.util.Objects;

/** Immutable playlist metadata and its playable YouTube queue entries. */
public final class ResolvedPlaylist {
    private final String url;
    private final String title;
    private final String uploader;
    private final List<PlaylistEntry> entries;
    private final boolean complete;
    private final boolean mix;
    private final long reportedStreamCount;

    public ResolvedPlaylist(
            String url,
            String title,
            String uploader,
            List<PlaylistEntry> entries,
            boolean complete,
            boolean mix,
            long reportedStreamCount
    ) {
        this.url = requireNotBlank(url, "url");
        this.title = requireNotBlank(title, "title");
        this.uploader = uploader == null ? "" : uploader;
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (this.entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resolved playlist must contain at least one playable video."
            );
        }
        this.complete = complete;
        this.mix = mix;
        this.reportedStreamCount = reportedStreamCount;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getUploader() {
        return uploader;
    }

    public List<PlaylistEntry> getEntries() {
        return entries;
    }

    /**
     * Returns whether every available continuation page was loaded.
     *
     * <p>This is false when a later page failed, a continuation repeated, or a
     * playlist safety limit was reached. Entries already returned remain usable
     * in each case.
     */
    public boolean isComplete() {
        return complete;
    }

    public boolean isMix() {
        return mix;
    }

    /**
     * Returns NewPipe's reported count, including its negative sentinel values
     * for unknown or infinite lists.
     */
    public long getReportedStreamCount() {
        return reportedStreamCount;
    }

    private static String requireNotBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}
