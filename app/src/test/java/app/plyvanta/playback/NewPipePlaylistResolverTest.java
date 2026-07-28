package app.plyvanta.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NewPipePlaylistResolverTest {
    private static final String PLAYLIST_URL =
            "https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL1234567890";
    private static final String RESOLVED_URL =
            "https://www.youtube.com/playlist?list=PL0987654321";
    private static final String A = "aaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbb";
    private static final String C = "ccccccccccc";
    private static final String D = "ddddddddddd";

    @Test
    public void normalPlaylistLoadsEveryPageInOrderAndPreservesDuplicates()
            throws Exception {
        FakePageSource source = new FakePageSource(initial(
                false,
                5L,
                List.of(item(A), item(B)),
                "page-2"
        ));
        source.pages.put(
                "page-2",
                page(List.of(item(B), item(C)), "page-3")
        );
        source.pages.put("page-3", page(List.of(item(D)), null));

        // Normal finite playlists are not subject to the mix safety limits.
        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 1, 1).resolve(PLAYLIST_URL);

        assertEquals(RESOLVED_URL, resolved.getUrl());
        assertEquals("Road trip", resolved.getTitle());
        assertEquals("Playlist owner", resolved.getUploader());
        assertEquals(5L, resolved.getReportedStreamCount());
        assertTrue(resolved.isComplete());
        assertFalse(resolved.isMix());
        assertEquals(
                List.of(A, B, B, C, D),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertEquals(
                "https://www.youtube.com/watch?v=" + A,
                resolved.getEntries().get(0).getCanonicalUrl()
        );
        assertEquals(List.of("page-2", "page-3"), source.requestedContinuations);
        assertEquals(List.of(PLAYLIST_URL, PLAYLIST_URL), source.nextPageUrls);
    }

    @Test
    public void skipsInvalidAndKnownUnavailableItemsWhileKeepingUnknownItems()
            throws Exception {
        StreamInfoItem invalidUrl = item(A);
        invalidUrl = itemWithUrl(
                "https://example.com/watch?v=" + A,
                "Wrong host",
                StreamType.VIDEO_STREAM
        );
        StreamInfoItem noStream = itemWithUrl(
                "https://www.youtube.com/watch?v=" + B,
                "Removed",
                StreamType.NONE
        );
        StreamInfoItem membership = item(C);
        membership.setContentAvailability(ContentAvailability.MEMBERSHIP);
        StreamInfoItem paid = item(D);
        paid.setContentAvailability(ContentAvailability.PAID);
        StreamInfoItem upcoming = item("eeeeeeeeeee");
        upcoming.setContentAvailability(ContentAvailability.UPCOMING);

        StreamInfoItem available = item(A);
        available.setContentAvailability(ContentAvailability.AVAILABLE);
        available.setUploaderName("Uploader");
        available.setDuration(42L);
        available.setThumbnails(List.of(
                new Image("https://img.test/low.jpg", 90, 160, Image.ResolutionLevel.LOW),
                new Image("https://img.test/high.jpg", 720, 1280, Image.ResolutionLevel.HIGH)
        ));
        StreamInfoItem unknown = item(B);
        unknown.setContentAvailability(ContentAvailability.UNKNOWN);

        FakePageSource source = new FakePageSource(initial(
                false,
                7L,
                List.of(
                        invalidUrl,
                        noStream,
                        membership,
                        paid,
                        upcoming,
                        available,
                        unknown
                ),
                null
        ));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 10).resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        PlaylistEntry first = resolved.getEntries().get(0);
        assertEquals("Uploader", first.getUploader());
        assertEquals(42L, first.getDurationSeconds());
        assertEquals("https://img.test/high.jpg", first.getThumbnailUrl());
        assertTrue(resolved.isComplete());
    }

    @Test
    public void laterPageFailureReturnsUsablePartialPlaylist() throws Exception {
        FakePageSource source = new FakePageSource(initial(
                false,
                3L,
                List.of(item(A), item(B)),
                "page-2"
        ));
        source.failures.put("page-2", new IOException("offline"));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 10).resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertFalse(resolved.isComplete());
        assertEquals(List.of("page-2"), source.requestedContinuations);
    }

    @Test
    public void repeatedContinuationStopsWithAUsablePartialPlaylist()
            throws Exception {
        Page first = new Page(
                "https://www.youtube.com/youtubei/v1/browse",
                "continuation",
                List.of("id"),
                Map.of("cookie", "value"),
                new byte[] {1, 2, 3}
        );
        Page repeated = new Page(
                "https://www.youtube.com/youtubei/v1/browse",
                "continuation",
                List.of("id"),
                Map.of("cookie", "value"),
                new byte[] {1, 2, 3}
        );
        FakePageSource source = new FakePageSource(initial(
                false,
                3L,
                List.of(item(A)),
                first
        ));
        source.pages.put(first, page(List.of(item(B)), repeated));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 10).resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertFalse(resolved.isComplete());
        assertEquals(List.of(first), source.requestedContinuations);
    }

    @Test
    public void normalPlaylistStopsAtTheFinitePageSafetyBound()
            throws Exception {
        FakePageSource source = new FakePageSource(initial(
                false,
                3L,
                List.of(item(A)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(B)), "page-3"));
        source.pages.put("page-3", page(List.of(item(C)), null));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 10, 2)
                        .resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertFalse(resolved.isComplete());
        assertEquals(List.of("page-2"), source.requestedContinuations);
    }

    @Test
    public void initialPageFailureIsNotConvertedToAnEmptyPlaylist() {
        FakePageSource source = new FakePageSource(null);
        source.initialFailure = new IOException("offline");
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        IOException failure = assertThrows(
                IOException.class,
                () -> resolver.resolve(PLAYLIST_URL)
        );

        assertEquals("offline", failure.getMessage());
    }

    @Test
    public void emptyPlayableQueueIsRejected() {
        FakePageSource source = new FakePageSource(initial(
                false,
                1L,
                List.of(itemWithUrl(
                        "https://example.com/watch?v=" + A,
                        "Wrong host",
                        StreamType.VIDEO_STREAM
                )),
                null
        ));
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(PLAYLIST_URL)
        );

        assertTrue(failure.getMessage().contains("playable YouTube videos"));
    }

    @Test
    public void mixStopsAtPageBoundAndReportsIncomplete() throws Exception {
        FakePageSource source = new FakePageSource(initial(
                true,
                ListExtractor.ITEM_COUNT_INFINITE,
                List.of(item(A)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(B)), "page-3"));
        source.pages.put("page-3", page(List.of(item(C)), null));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 2).resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertTrue(resolved.isMix());
        assertFalse(resolved.isComplete());
        assertEquals(ListExtractor.ITEM_COUNT_INFINITE, resolved.getReportedStreamCount());
        assertEquals(List.of("page-2"), source.requestedContinuations);
    }

    @Test
    public void mixStopsAtPlayableEntryBoundWithoutReordering() throws Exception {
        FakePageSource source = new FakePageSource(initial(
                true,
                ListExtractor.ITEM_COUNT_INFINITE,
                List.of(item(A), item(B), item(C)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(D)), null));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 2, 10).resolve(PLAYLIST_URL);

        assertEquals(
                List.of(A, B),
                resolved.getEntries().stream()
                        .map(PlaylistEntry::getVideoId)
                        .toList()
        );
        assertFalse(resolved.isComplete());
        assertTrue(source.requestedContinuations.isEmpty());
    }

    @Test
    public void naturallyEndingMixIsComplete() throws Exception {
        FakePageSource source = new FakePageSource(initial(
                true,
                ListExtractor.ITEM_COUNT_INFINITE,
                List.of(item(A)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(B)), null));

        ResolvedPlaylist resolved =
                new NewPipePlaylistResolver(source, 10, 10).resolve(PLAYLIST_URL);

        assertEquals(2, resolved.getEntries().size());
        assertTrue(resolved.isMix());
        assertTrue(resolved.isComplete());
    }

    @Test
    public void interruptionStopsBeforeLoadingAContinuation() {
        FakePageSource source = new FakePageSource(initial(
                false,
                2L,
                List.of(item(A)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(B)), null));
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class,
                    () -> resolver.resolve(PLAYLIST_URL)
            );
            assertTrue(source.requestedContinuations.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void interruptionAfterInitialLoadStopsBeforeItemsAreRead() {
        FakePageSource source = new FakePageSource(initial(
                false,
                2L,
                List.of(item(A)),
                "page-2"
        ));
        source.interruptAfterInitial = true;
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> resolver.resolve(PLAYLIST_URL)
            );
            assertTrue(source.requestedContinuations.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void interruptionAfterContinuationLoadStopsBeforeItemsAreRead() {
        FakePageSource source = new FakePageSource(initial(
                false,
                2L,
                List.of(item(A)),
                "page-2"
        ));
        source.pages.put("page-2", page(List.of(item(B)), null));
        source.interruptAfterNext = true;
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> resolver.resolve(PLAYLIST_URL)
            );
            assertEquals(List.of("page-2"), source.requestedContinuations);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void interruptionDuringItemConversionStopsTheAppend() {
        StreamInfoItem interruptingItem = new StreamInfoItem(
                0,
                "https://youtu.be/" + A,
                "Interrupting video",
                StreamType.VIDEO_STREAM
        ) {
            @Override
            public StreamType getStreamType() {
                Thread.currentThread().interrupt();
                return super.getStreamType();
            }
        };
        FakePageSource source = new FakePageSource(initial(
                false,
                1L,
                List.of(interruptingItem),
                null
        ));
        NewPipePlaylistResolver resolver =
                new NewPipePlaylistResolver(source, 10, 10);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> resolver.resolve(PLAYLIST_URL)
            );
        } finally {
            Thread.interrupted();
        }
    }

    private static NewPipePlaylistResolver.InitialPage initial(
            boolean mix,
            long reportedCount,
            List<StreamInfoItem> items,
            Object continuation
    ) {
        return new NewPipePlaylistResolver.InitialPage(
                RESOLVED_URL,
                "Road trip",
                "Playlist owner",
                mix,
                reportedCount,
                items,
                continuation
        );
    }

    private static NewPipePlaylistResolver.SourcePage page(
            List<StreamInfoItem> items,
            Object continuation
    ) {
        return new NewPipePlaylistResolver.SourcePage(items, continuation);
    }

    private static StreamInfoItem item(String videoId) {
        return itemWithUrl(
                "https://youtu.be/" + videoId,
                "Video " + videoId,
                StreamType.VIDEO_STREAM
        );
    }

    private static StreamInfoItem itemWithUrl(
            String url,
            String title,
            StreamType streamType
    ) {
        return new StreamInfoItem(0, url, title, streamType);
    }

    private static final class FakePageSource
            implements NewPipePlaylistResolver.PageSource {
        private final NewPipePlaylistResolver.InitialPage initial;
        private final Map<Object, NewPipePlaylistResolver.SourcePage> pages =
                new HashMap<>();
        private final Map<Object, Exception> failures = new HashMap<>();
        private final List<Object> requestedContinuations = new ArrayList<>();
        private final List<String> nextPageUrls = new ArrayList<>();
        private Exception initialFailure;
        private boolean interruptAfterInitial;
        private boolean interruptAfterNext;

        private FakePageSource(NewPipePlaylistResolver.InitialPage initial) {
            this.initial = initial;
        }

        @Override
        public NewPipePlaylistResolver.InitialPage loadInitial(String playlistUrl)
                throws Exception {
            if (initialFailure != null) {
                throw initialFailure;
            }
            if (interruptAfterInitial) {
                Thread.currentThread().interrupt();
            }
            return initial;
        }

        @Override
        public NewPipePlaylistResolver.SourcePage loadNext(
                String playlistUrl,
                Object continuation
        ) throws Exception {
            requestedContinuations.add(continuation);
            nextPageUrls.add(playlistUrl);
            Exception failure = failures.get(continuation);
            if (failure != null) {
                throw failure;
            }
            NewPipePlaylistResolver.SourcePage page = pages.get(continuation);
            if (page == null) {
                throw new AssertionError(
                        "Unexpected continuation: " + continuation
                );
            }
            if (interruptAfterNext) {
                Thread.currentThread().interrupt();
            }
            return page;
        }
    }
}
