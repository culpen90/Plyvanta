package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class GitHubReleaseClientTest {
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String API_URL = "https://unit.test/releases";

    @Test
    public void findsNewerPreviewFromMatchingMetadataAndApk() throws IOException {
        FixtureInterceptor fixture = new FixtureInterceptor(
                releaseList(false, true, "v1.0.0-debug.5", standardAssets(
                        "1.0.0-debug.5",
                        SHA256
                )),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );
        GitHubReleaseClient client = client(fixture);

        UpdateRelease update = client.fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        );

        assertNotNull(update);
        assertEquals(5L, update.getVersionCode());
        assertEquals("1.0.0-debug.5", update.getVersionName());
        assertEquals(
                "https://github.com/culpen90/Plyvanta/releases/download/"
                        + "v1.0.0-debug.5/Plyvanta-1.0.0-debug.5.apk",
                update.getApkUrl()
        );
        assertEquals(2, fixture.calls.get());
    }

    @Test
    public void equalInstalledVersionDoesNotOfferAnUpdate() throws IOException {
        FixtureInterceptor fixture = new FixtureInterceptor(
                releaseList(false, true, "v1.0.0-debug.4", standardAssets(
                        "1.0.0-debug.4",
                        SHA256
                )),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        4,
                        "1.0.0-debug.4",
                        "Plyvanta-1.0.0-debug.4.apk",
                        SHA256
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        );

        assertNull(update);
    }

    @Test
    public void selectsHighestVersionCodeAcrossEligibleReleases() throws IOException {
        String versionFive = "1.0.0-debug.5";
        String versionSix = "1.0.0-debug.6";
        FixtureInterceptor fixture = new FixtureInterceptor(
                combineReleaseLists(
                        releaseList(
                                false,
                                true,
                                "v" + versionFive,
                                standardAssets(versionFive, SHA256)
                        ),
                        releaseList(
                                false,
                                true,
                                "v" + versionSix,
                                standardAssets(versionSix, SHA256)
                        )
                ),
                Map.of(
                        "Plyvanta-" + versionFive + "-update.json",
                        metadata(
                                "app.plyvanta.debug",
                                "preview",
                                5,
                                versionFive,
                                "Plyvanta-" + versionFive + ".apk",
                                SHA256
                        ),
                        "Plyvanta-" + versionSix + "-update.json",
                        metadata(
                                "app.plyvanta.debug",
                                "preview",
                                6,
                                versionSix,
                                "Plyvanta-" + versionSix + ".apk",
                                SHA256
                        )
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        );

        assertNotNull(update);
        assertEquals(6L, update.getVersionCode());
        assertEquals(versionSix, update.getVersionName());
        assertEquals(3, fixture.calls.get());
    }

    @Test
    public void previewAndStableChannelsStaySeparate() throws IOException {
        FixtureInterceptor previewRelease = new FixtureInterceptor(
                releaseList(false, true, "v1.1.0-debug.1", standardAssets(
                        "1.1.0-debug.1",
                        SHA256
                )),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        10,
                        "1.1.0-debug.1",
                        "Plyvanta-1.1.0-debug.1.apk",
                        SHA256
                )
        );

        UpdateRelease stableUpdate = client(previewRelease).fetchLatestUpdate(
                4,
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNull(stableUpdate);
        assertEquals(1, previewRelease.calls.get());
    }

    @Test
    public void draftReleaseIsIgnoredWithoutFetchingMetadata() throws IOException {
        FixtureInterceptor fixture = new FixtureInterceptor(
                releaseList(true, true, "v1.0.0-debug.5", standardAssets(
                        "1.0.0-debug.5",
                        SHA256
                )),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        );

        assertNull(update);
        assertEquals(1, fixture.calls.get());
    }

    @Test
    public void mutableReleaseIsIgnoredWithoutFetchingMetadata() throws IOException {
        String mutableRelease = releaseList(
                false,
                true,
                "v1.0.0-debug.5",
                standardAssets("1.0.0-debug.5", SHA256)
        ).replace("\"immutable\":true", "\"immutable\":false");
        FixtureInterceptor fixture = new FixtureInterceptor(
                mutableRelease,
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );

        assertNull(client(fixture).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        ));
        assertEquals(1, fixture.calls.get());
    }

    @Test
    public void wrongPackageAndUnsupportedSdkAreRejected() throws IOException {
        String releases = releaseList(
                false,
                true,
                "v1.0.0-debug.5",
                standardAssets("1.0.0-debug.5", SHA256)
        );
        FixtureInterceptor wrongPackage = new FixtureInterceptor(
                releases,
                metadata(
                        "example.attacker",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );
        FixtureInterceptor unsupportedSdk = new FixtureInterceptor(
                releases,
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                ).replace("\"minimumSdk\":26", "\"minimumSdk\":99")
        );

        assertNull(client(wrongPackage).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        ));
        assertNull(client(unsupportedSdk).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        ));
    }

    @Test
    public void digestMismatchAndForgedApkUrlAreRejected() throws IOException {
        String mismatchedAssets = standardAssets(
                "1.0.0-debug.5",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        FixtureInterceptor digestMismatch = new FixtureInterceptor(
                releaseList(false, true, "v1.0.0-debug.5", mismatchedAssets),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );
        String forgedAssets = standardAssets("1.0.0-debug.5", SHA256)
                .replace(
                        "https://github.com/culpen90/Plyvanta/releases/download/"
                                + "v1.0.0-debug.5/Plyvanta-1.0.0-debug.5.apk",
                        "https://example.com/Plyvanta-1.0.0-debug.5.apk"
                );
        FixtureInterceptor forgedUrl = new FixtureInterceptor(
                releaseList(false, true, "v1.0.0-debug.5", forgedAssets),
                metadata(
                        "app.plyvanta.debug",
                        "preview",
                        5,
                        "1.0.0-debug.5",
                        "Plyvanta-1.0.0-debug.5.apk",
                        SHA256
                )
        );

        assertNull(client(digestMismatch).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        ));
        assertNull(client(forgedUrl).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        ));
    }

    @Test
    public void malformedAndOversizedReleaseResponsesFailClosed() {
        FixtureInterceptor malformed = new FixtureInterceptor("{not-json", "{}");
        char[] oversized = new char[GitHubReleaseClient.MAX_RELEASE_RESPONSE_BYTES + 1];
        Arrays.fill(oversized, ' ');
        FixtureInterceptor tooLarge = new FixtureInterceptor(new String(oversized), "{}");

        assertThrows(
                IOException.class,
                () -> client(malformed).fetchLatestUpdate(
                        4,
                        "app.plyvanta.debug",
                        UpdateChannel.PREVIEW,
                        36
                )
        );
        assertThrows(
                IOException.class,
                () -> client(tooLarge).fetchLatestUpdate(
                        4,
                        "app.plyvanta.debug",
                        UpdateChannel.PREVIEW,
                        36
                )
        );
    }

    @Test
    public void requestIsAnonymousAndIdentifiesTheReadOnlyClient() throws IOException {
        FixtureInterceptor fixture = new FixtureInterceptor("[]", "{}");

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                4,
                "app.plyvanta.debug",
                UpdateChannel.PREVIEW,
                36
        );

        assertNull(update);
        assertTrue(fixture.lastUserAgent.startsWith("Plyvanta-Update-Checker/"));
        assertNull(fixture.lastAuthorization);
    }

    private static GitHubReleaseClient client(FixtureInterceptor fixture) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(fixture)
                .build();
        return new GitHubReleaseClient(httpClient, HttpUrl.get(API_URL));
    }

    private static String releaseList(
            boolean draft,
            boolean prerelease,
            String tag,
            String assets
    ) {
        return "[{"
                + "\"draft\":" + draft + ","
                + "\"prerelease\":" + prerelease + ","
                + "\"immutable\":true,"
                + "\"tag_name\":\"" + tag + "\","
                + "\"html_url\":\"https://github.com/culpen90/Plyvanta/releases/tag/"
                + tag + "\","
                + "\"assets\":[" + assets + "]"
                + "}]";
    }

    private static String standardAssets(String versionName, String digest) {
        String tag = "v" + versionName;
        String base = "https://github.com/culpen90/Plyvanta/releases/download/"
                + tag + "/";
        return asset(
                "Plyvanta-" + versionName + "-update.json",
                "application/json",
                base + "Plyvanta-" + versionName + "-update.json",
                null
        ) + "," + asset(
                "Plyvanta-" + versionName + ".apk",
                "application/vnd.android.package-archive",
                base + "Plyvanta-" + versionName + ".apk",
                "sha256:" + digest
        );
    }

    private static String combineReleaseLists(String first, String second) {
        return first.substring(0, first.length() - 1)
                + ","
                + second.substring(1);
    }

    private static String asset(
            String name,
            String contentType,
            String url,
            String digest
    ) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"content_type\":\"" + contentType + "\","
                + "\"browser_download_url\":\"" + url + "\","
                + "\"digest\":" + (digest == null ? "null" : "\"" + digest + "\"")
                + "}";
    }

    private static String metadata(
            String packageName,
            String channel,
            long versionCode,
            String versionName,
            String apkName,
            String sha256
    ) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"packageName\":\"" + packageName + "\","
                + "\"channel\":\"" + channel + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"versionName\":\"" + versionName + "\","
                + "\"minimumSdk\":26,"
                + "\"apkName\":\"" + apkName + "\","
                + "\"sha256\":\"" + sha256 + "\""
                + "}";
    }

    private static final class FixtureInterceptor implements Interceptor {
        private static final MediaType JSON =
                MediaType.get("application/json; charset=utf-8");

        private final String releases;
        private final Map<String, String> metadataByAssetName;
        private final AtomicInteger calls = new AtomicInteger();
        private String lastUserAgent;
        private String lastAuthorization;

        private FixtureInterceptor(String releases, String metadata) {
            this(releases, Map.of("*", metadata));
        }

        private FixtureInterceptor(
                String releases,
                Map<String, String> metadataByAssetName
        ) {
            this.releases = releases;
            this.metadataByAssetName = metadataByAssetName;
        }

        @Override
        public Response intercept(Chain chain) {
            calls.incrementAndGet();
            lastUserAgent = chain.request().header("User-Agent");
            lastAuthorization = chain.request().header("Authorization");
            String body;
            if (chain.request().url().host().equals("unit.test")) {
                body = releases;
            } else {
                String assetName = chain.request().url().pathSegments().get(
                        chain.request().url().pathSegments().size() - 1
                );
                body = metadataByAssetName.get(assetName);
                if (body == null) {
                    body = metadataByAssetName.get("*");
                }
                if (body == null) {
                    body = "{}";
                }
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create(body, JSON))
                    .build();
        }
    }
}
