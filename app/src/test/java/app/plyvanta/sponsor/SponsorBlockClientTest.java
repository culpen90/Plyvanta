package app.plyvanta.sponsor;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SponsorBlockClientTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final HttpUrl TEST_BASE_URL = HttpUrl.get("https://unit.test/");

    @Test
    public void requestUsesFourCharacterHashAndRepeatedExplicitFilters() {
        SponsorBlockClient client = clientReturning(404, "");

        Request request = client.buildRequest(
                VIDEO_ID,
                Arrays.asList("sponsor", "interaction", "sponsor")
        );

        assertEquals("GET", request.method());
        assertEquals("/api/skipSegments/5f6b", request.url().encodedPath());
        assertEquals(
                Arrays.asList("interaction", "sponsor"),
                request.url().queryParameterValues("category")
        );
        assertEquals("skip", request.url().queryParameter("actionType"));
        assertEquals("YouTube", request.url().queryParameter("service"));
        assertNull(request.url().queryParameter("videoID"));
        assertFalse(request.url().toString().contains(VIDEO_ID));
        assertNotNull(request.header("User-Agent"));
        assertTrue(request.header("User-Agent").startsWith("Plyvanta/"));
        assertNull(request.header("Authorization"));
    }

    @Test
    public void sha256PrefixMatchesKnownDigest() {
        assertEquals("5f6b", SponsorBlockClient.hashPrefix(VIDEO_ID));
        assertEquals("ca2f", SponsorBlockClient.hashPrefix("abcdefghijk"));
    }

    @Test
    public void parsesOnlyExactVideoAndRequestedSkipActionsThenSortsAndMerges()
            throws IOException {
        String response = "["
                + "{\"videoID\":\"abcdefghijk\",\"segments\":["
                + segment("wrong-video", 1, 50, "sponsor", "skip")
                + "]},"
                + "{\"videoID\":\"" + VIDEO_ID + "\",\"segments\":["
                + segment("uuid-b", 10, 20, "sponsor", "skip") + ","
                + segment("uuid-c", 18, 25, "selfpromo", "skip") + ","
                + segment("uuid-a", 0, 5, "sponsor", "skip") + ","
                + segment("wrong-action", 30, 35, "sponsor", "mute") + ","
                + segment("wrong-category", 40, 45, "interaction", "skip") + ","
                + segment("negative", -2, 1, "sponsor", "skip") + ","
                + segment("zero", 7, 7, "sponsor", "skip") + ","
                + "{\"UUID\":\"string-range\",\"category\":\"sponsor\","
                + "\"actionType\":\"skip\",\"segment\":[\"1\",2]}"
                + "]}"
                + "]";

        List<SponsorSegment> segments = SponsorBlockClient.parseResponseBody(
                response,
                VIDEO_ID,
                Arrays.asList("selfpromo", "sponsor")
        );

        assertEquals(2, segments.size());
        assertEquals("uuid-a", segments.get(0).getUuid());
        assertEquals(0.0d, segments.get(0).getStartSeconds(), 0.0d);
        assertEquals(5.0d, segments.get(0).getEndSeconds(), 0.0d);
        assertEquals(10.0d, segments.get(1).getStartSeconds(), 0.0d);
        assertEquals(25.0d, segments.get(1).getEndSeconds(), 0.0d);
        assertEquals(Arrays.asList("uuid-b", "uuid-c"), segments.get(1).getUuids());
        assertEquals(Arrays.asList("sponsor", "selfpromo"), segments.get(1).getCategories());
        assertThrows(UnsupportedOperationException.class, () -> segments.clear());
    }

    @Test
    public void exactVideoMayAppearInMoreThanOnePrivacyBucketEntry() throws IOException {
        String response = "["
                + "{\"videoID\":\"" + VIDEO_ID + "\",\"segments\":["
                + segment("uuid-a", 0, 1, "sponsor", "skip")
                + "]},"
                + "{\"videoID\":\"" + VIDEO_ID + "\",\"segments\":["
                + segment("uuid-b", 2, 3, "sponsor", "skip")
                + "]}"
                + "]";

        List<SponsorSegment> segments = SponsorBlockClient.parseResponseBody(
                response,
                VIDEO_ID,
                Collections.singletonList("sponsor")
        );

        assertEquals(2, segments.size());
        assertEquals("uuid-a", segments.get(0).getUuid());
        assertEquals("uuid-b", segments.get(1).getUuid());
    }

    @Test
    public void duplicateExactVideoBucketsShareOneAggregateSegmentLimit() {
        StringBuilder response = new StringBuilder("[");
        int firstBucketSize = SponsorBlockClient.MAX_SEGMENTS_PER_VIDEO / 2;
        int secondBucketSize =
                SponsorBlockClient.MAX_SEGMENTS_PER_VIDEO - firstBucketSize + 1;
        appendBucketWithSegments(response, firstBucketSize, 0);
        response.append(',');
        appendBucketWithSegments(response, secondBucketSize, firstBucketSize);
        response.append(']');

        assertThrows(
                IOException.class,
                () -> SponsorBlockClient.parseResponseBody(
                        response.toString(),
                        VIDEO_ID,
                        Collections.singletonList("sponsor")
                )
        );
    }

    @Test
    public void notFoundIsEmptyAndCachedPerVideoAndNormalizedCategories() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        SponsorBlockClient client = clientReturning(calls, 404, "");

        List<SponsorSegment> first = client.fetchSegments(
                VIDEO_ID,
                Arrays.asList("sponsor", "selfpromo")
        );
        List<SponsorSegment> second = client.fetchSegments(
                VIDEO_ID,
                Arrays.asList("selfpromo", "sponsor")
        );
        client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"));

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    public void successfulExactVideoResultIsCached() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String response = "[{\"videoID\":\"" + VIDEO_ID + "\",\"segments\":["
                + segment("uuid-a", 1, 2, "sponsor", "skip")
                + "]}]";
        SponsorBlockClient client = clientReturning(calls, 200, response);

        List<SponsorSegment> first = client.fetchSegments(
                VIDEO_ID,
                Collections.singletonList("sponsor")
        );
        List<SponsorSegment> second = client.fetchSegments(
                VIDEO_ID,
                Collections.singletonList("sponsor")
        );

        assertEquals(1, first.size());
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    public void cacheExpires() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        FixedResponseInterceptor interceptor = new FixedResponseInterceptor(calls, 404, "");
        SponsorBlockClient client = new SponsorBlockClient(
                new OkHttpClient.Builder().addInterceptor(interceptor).build(),
                TEST_BASE_URL,
                clock,
                100L,
                4
        );

        client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"));
        clock.nowMillis = 99L;
        client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"));
        clock.nowMillis = 100L;
        client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"));

        assertEquals(2, calls.get());
    }

    @Test
    public void oversizedSuccessfulResponseIsRejectedAndNotCached() {
        AtomicInteger calls = new AtomicInteger();
        char[] oversizedChars = new char[SponsorBlockClient.MAX_RESPONSE_BYTES + 1];
        Arrays.fill(oversizedChars, ' ');
        SponsorBlockClient client = clientReturning(calls, 200, new String(oversizedChars));

        assertThrows(
                IOException.class,
                () -> client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"))
        );
        assertThrows(
                IOException.class,
                () -> client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"))
        );
        assertEquals(2, calls.get());
    }

    @Test
    public void oversizedUnknownLengthResponseIsAlsoRejected() {
        char[] oversizedChars = new char[SponsorBlockClient.MAX_RESPONSE_BYTES + 1];
        Arrays.fill(oversizedChars, ' ');
        String oversizedBody = new String(oversizedChars);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(new UnknownLengthResponseBody(oversizedBody))
                        .build())
                .build();
        SponsorBlockClient client = new SponsorBlockClient(httpClient, TEST_BASE_URL);

        assertThrows(
                IOException.class,
                () -> client.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"))
        );
    }

    @Test
    public void malformedSuccessAndOtherStatusesAreErrors() {
        SponsorBlockClient malformed = clientReturning(200, "{not-json");
        SponsorBlockClient rateLimited = clientReturning(429, "too many requests");

        assertThrows(
                IOException.class,
                () -> malformed.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"))
        );
        assertThrows(
                IOException.class,
                () -> rateLimited.fetchSegments(VIDEO_ID, Collections.singletonList("sponsor"))
        );
    }

    @Test
    public void rejectsInvalidInputsBeforeSending() {
        AtomicInteger calls = new AtomicInteger();
        SponsorBlockClient client = clientReturning(calls, 404, "");

        assertThrows(
                IllegalArgumentException.class,
                () -> client.fetchSegments("too-short", Collections.singletonList("sponsor"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> client.fetchSegments(VIDEO_ID, Collections.emptyList())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> client.fetchSegments(VIDEO_ID, Collections.singletonList("bad category"))
        );
        assertEquals(0, calls.get());
    }

    private static SponsorBlockClient clientReturning(int statusCode, String body) {
        return clientReturning(new AtomicInteger(), statusCode, body);
    }

    private static SponsorBlockClient clientReturning(
            AtomicInteger calls,
            int statusCode,
            String body
    ) {
        return new SponsorBlockClient(
                new OkHttpClient.Builder()
                        .addInterceptor(new FixedResponseInterceptor(calls, statusCode, body))
                        .build(),
                TEST_BASE_URL
        );
    }

    private static String segment(
            String uuid,
            double start,
            double end,
            String category,
            String actionType
    ) {
        return "{\"UUID\":\"" + uuid + "\","
                + "\"category\":\"" + category + "\","
                + "\"actionType\":\"" + actionType + "\","
                + "\"segment\":[" + start + "," + end + "]}";
    }

    private static void appendBucketWithSegments(
            StringBuilder output,
            int segmentCount,
            int uuidOffset
    ) {
        output.append("{\"videoID\":\"").append(VIDEO_ID).append("\",\"segments\":[");
        for (int index = 0; index < segmentCount; index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(segment(
                    "uuid-" + (uuidOffset + index),
                    index * 2.0d,
                    index * 2.0d + 1.0d,
                    "sponsor",
                    "skip"
            ));
        }
        output.append("]}");
    }

    private static final class FixedResponseInterceptor implements Interceptor {
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

        private final AtomicInteger calls;
        private final int statusCode;
        private final String body;
        private FixedResponseInterceptor(AtomicInteger calls, int statusCode, String body) {
            this.calls = calls;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public Response intercept(Chain chain) {
            calls.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("fixture")
                    .body(ResponseBody.create(body, JSON))
                    .build();
        }
    }

    private static final class MutableClock implements LongSupplier {
        private long nowMillis;

        @Override
        public long getAsLong() {
            return nowMillis;
        }
    }

    private static final class UnknownLengthResponseBody extends ResponseBody {
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

        private final byte[] content;

        private UnknownLengthResponseBody(String content) {
            this.content = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public MediaType contentType() {
            return JSON;
        }

        @Override
        public long contentLength() {
            return -1L;
        }

        @Override
        public BufferedSource source() {
            return new Buffer().write(content);
        }
    }
}
