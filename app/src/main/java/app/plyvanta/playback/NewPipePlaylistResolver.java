package app.plyvanta.playback;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import app.plyvanta.util.YouTubeUrlParser;

/**
 * Resolves a YouTube playlist into stable video-page entries. Run on a worker
 * thread because the production page source performs blocking extraction.
 */
public final class NewPipePlaylistResolver {
    private static final String VIDEO_URL_PREFIX =
            "https://www.youtube.com/watch?v=";
    private static final int DEFAULT_MAX_MIX_ENTRIES = 200;
    private static final int DEFAULT_MAX_MIX_PAGES = 20;
    private static final int DEFAULT_MAX_PLAYLIST_PAGES = 100;

    interface PageSource {
        InitialPage loadInitial(String playlistUrl) throws Exception;

        SourcePage loadNext(String playlistUrl, Object continuation) throws Exception;
    }

    static class SourcePage {
        final List<StreamInfoItem> items;
        final Object continuation;

        SourcePage(List<StreamInfoItem> items, Object continuation) {
            this.items = List.copyOf(Objects.requireNonNull(items, "items"));
            this.continuation = continuation;
        }
    }

    static final class InitialPage extends SourcePage {
        private final String url;
        private final String title;
        private final String uploader;
        private final boolean mix;
        private final long reportedStreamCount;

        InitialPage(
                String url,
                String title,
                String uploader,
                boolean mix,
                long reportedStreamCount,
                List<StreamInfoItem> items,
                Object continuation
        ) {
            super(items, continuation);
            this.url = url;
            this.title = title;
            this.uploader = uploader;
            this.mix = mix;
            this.reportedStreamCount = reportedStreamCount;
        }
    }

    private final PageSource pageSource;
    private final int maxMixEntries;
    private final int maxMixPages;
    private final int maxPlaylistPages;

    public NewPipePlaylistResolver() {
        this(
                new ExtractorPageSource(),
                DEFAULT_MAX_MIX_ENTRIES,
                DEFAULT_MAX_MIX_PAGES,
                DEFAULT_MAX_PLAYLIST_PAGES
        );
    }

    NewPipePlaylistResolver(
            PageSource pageSource,
            int maxMixEntries,
            int maxMixPages
    ) {
        this(
                pageSource,
                maxMixEntries,
                maxMixPages,
                DEFAULT_MAX_PLAYLIST_PAGES
        );
    }

    NewPipePlaylistResolver(
            PageSource pageSource,
            int maxMixEntries,
            int maxMixPages,
            int maxPlaylistPages
    ) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
        if (maxMixEntries < 1) {
            throw new IllegalArgumentException("maxMixEntries must be positive.");
        }
        if (maxMixPages < 1) {
            throw new IllegalArgumentException("maxMixPages must be positive.");
        }
        if (maxPlaylistPages < 1) {
            throw new IllegalArgumentException("maxPlaylistPages must be positive.");
        }
        this.maxMixEntries = maxMixEntries;
        this.maxMixPages = maxMixPages;
        this.maxPlaylistPages = maxPlaylistPages;
    }

    public ResolvedPlaylist resolve(String playlistUrl) throws Exception {
        if (playlistUrl == null || playlistUrl.isBlank()) {
            throw new IllegalArgumentException("playlistUrl must not be blank.");
        }

        throwIfInterrupted();
        final InitialPage initial;
        try {
            initial = Objects.requireNonNull(
                    pageSource.loadInitial(playlistUrl),
                    "The initial playlist page must not be null."
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        throwIfInterrupted();
        String resolvedUrl = valueOrFallback(initial.url, playlistUrl);
        String title = valueOrFallback(initial.title, "YouTube playlist");
        String uploader = initial.uploader == null ? "" : initial.uploader;

        ArrayList<PlaylistEntry> entries = new ArrayList<>();
        boolean truncated = appendPlayableItems(
                entries,
                initial.items,
                initial.mix ? maxMixEntries : Integer.MAX_VALUE
        );
        boolean complete = !truncated;
        int loadedPageCount = 1;
        Object continuation = initial.continuation;
        Set<Object> seenContinuations = new HashSet<>();

        while (!truncated && continuation != null) {
            throwIfInterrupted();
            int maximumPageCount = initial.mix ? maxMixPages : maxPlaylistPages;
            if ((initial.mix && entries.size() >= maxMixEntries)
                    || loadedPageCount >= maximumPageCount) {
                complete = false;
                break;
            }
            if (!seenContinuations.add(continuationKey(continuation))) {
                complete = false;
                break;
            }

            final SourcePage page;
            try {
                page = Objects.requireNonNull(
                        pageSource.loadNext(playlistUrl, continuation),
                        "A playlist continuation page must not be null."
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception laterPageFailure) {
                complete = false;
                break;
            }
            throwIfInterrupted();

            loadedPageCount++;
            truncated = appendPlayableItems(
                    entries,
                    page.items,
                    initial.mix ? maxMixEntries : Integer.MAX_VALUE
            );
            continuation = page.continuation;
            if (truncated) {
                complete = false;
            }
        }
        throwIfInterrupted();

        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "The playlist did not contain any playable YouTube videos."
            );
        }

        return new ResolvedPlaylist(
                resolvedUrl,
                title,
                uploader,
                entries,
                complete,
                initial.mix,
                initial.reportedStreamCount
        );
    }

    private static boolean appendPlayableItems(
            List<PlaylistEntry> destination,
            List<StreamInfoItem> source,
            int maximumEntries
    ) throws InterruptedException {
        throwIfInterrupted();
        for (StreamInfoItem item : source) {
            throwIfInterrupted();
            PlaylistEntry entry = toPlaylistEntry(item);
            throwIfInterrupted();
            if (entry == null) {
                continue;
            }
            if (destination.size() >= maximumEntries) {
                return true;
            }
            destination.add(entry);
        }
        throwIfInterrupted();
        return false;
    }

    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Playlist loading was cancelled.");
        }
    }

    private static Object continuationKey(Object continuation) {
        if (continuation instanceof Page) {
            return new PageContinuationKey((Page) continuation);
        }
        return continuation;
    }

    private static PlaylistEntry toPlaylistEntry(StreamInfoItem item) {
        if (item == null || item.getStreamType() == StreamType.NONE) {
            return null;
        }

        ContentAvailability availability = item.getContentAvailability();
        if (availability != null
                && availability != ContentAvailability.UNKNOWN
                && availability != ContentAvailability.AVAILABLE) {
            return null;
        }

        String videoId = YouTubeUrlParser.extractVideoId(item.getUrl());
        if (videoId == null) {
            return null;
        }

        return new PlaylistEntry(
                videoId,
                VIDEO_URL_PREFIX + videoId,
                valueOrFallback(item.getName(), "YouTube video"),
                item.getUploaderName(),
                item.getDuration(),
                bestThumbnailUrl(item.getThumbnails())
        );
    }

    private static String bestThumbnailUrl(List<Image> thumbnails) {
        if (thumbnails == null) {
            return null;
        }
        for (int index = thumbnails.size() - 1; index >= 0; index--) {
            Image image = thumbnails.get(index);
            if (image != null && image.getUrl() != null && !image.getUrl().isBlank()) {
                return image.getUrl();
            }
        }
        return null;
    }

    private static String valueOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class PageContinuationKey {
        private final String url;
        private final String id;
        private final List<String> ids;
        private final Map<String, String> cookies;
        private final byte[] body;

        PageContinuationKey(Page page) {
            url = page.getUrl();
            id = page.getId();
            ids = page.getIds() == null
                    ? null
                    : new ArrayList<>(page.getIds());
            cookies = page.getCookies() == null
                    ? null
                    : new HashMap<>(page.getCookies());
            body = page.getBody() == null ? null : page.getBody().clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PageContinuationKey)) {
                return false;
            }
            PageContinuationKey that = (PageContinuationKey) other;
            return Objects.equals(url, that.url)
                    && Objects.equals(id, that.id)
                    && Objects.equals(ids, that.ids)
                    && Objects.equals(cookies, that.cookies)
                    && Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(url, id, ids, cookies);
            return 31 * result + Arrays.hashCode(body);
        }
    }

    private static final class ExtractorPageSource implements PageSource {
        @Override
        public InitialPage loadInitial(String playlistUrl) throws Exception {
            PlaylistInfo info = PlaylistInfo.getInfo(
                    ServiceList.YouTube,
                    playlistUrl
            );
            PlaylistInfo.PlaylistType playlistType = info.getPlaylistType();
            long reportedStreamCount = info.getStreamCount();
            boolean mix = (playlistType != null
                    && playlistType != PlaylistInfo.PlaylistType.NORMAL)
                    || reportedStreamCount == ListExtractor.ITEM_COUNT_INFINITE;
            return new InitialPage(
                    info.getUrl(),
                    info.getName(),
                    info.getUploaderName(),
                    mix,
                    reportedStreamCount,
                    safeItems(info.getRelatedItems()),
                    info.hasNextPage() ? info.getNextPage() : null
            );
        }

        @Override
        public SourcePage loadNext(String playlistUrl, Object continuation)
                throws Exception {
            if (!(continuation instanceof Page)) {
                throw new IllegalArgumentException(
                        "NewPipe playlist continuation had an unexpected type."
                );
            }
            ListExtractor.InfoItemsPage<StreamInfoItem> page =
                    PlaylistInfo.getMoreItems(
                            ServiceList.YouTube,
                            playlistUrl,
                            (Page) continuation
                    );
            return new SourcePage(
                    safeItems(page.getItems()),
                    page.hasNextPage() ? page.getNextPage() : null
            );
        }

        private static List<StreamInfoItem> safeItems(
                List<StreamInfoItem> items
        ) {
            return items == null ? List.of() : items;
        }
    }
}
