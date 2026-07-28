package app.plyvanta.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class GitHubReleaseClient {
    static final int MAX_RELEASE_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_METADATA_RESPONSE_BYTES = 32 * 1024;
    static final int MAX_RELEASES = 100;
    static final int MAX_ASSETS_PER_RELEASE = 100;

    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/Plyvanta/Plyvanta/releases?per_page=100";
    private static final String USER_AGENT = "Plyvanta-Update-Checker/1 (Android)";
    private static final String APK_CONTENT_TYPE =
            "application/vnd.android.package-archive";

    private final OkHttpClient httpClient;
    private final HttpUrl releasesApiUrl;

    public GitHubReleaseClient() {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .callTimeout(30, TimeUnit.SECONDS)
                        .build(),
                HttpUrl.get(RELEASES_API_URL)
        );
    }

    GitHubReleaseClient(OkHttpClient httpClient, HttpUrl releasesApiUrl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.releasesApiUrl = Objects.requireNonNull(releasesApiUrl, "releasesApiUrl");
    }

    public UpdateRelease fetchLatestUpdate(
            long installedVersionCode,
            String installedPackageName,
            UpdateChannel channel,
            int deviceSdk
    ) throws IOException {
        if (installedVersionCode < 0L) {
            throw new IllegalArgumentException("installedVersionCode cannot be negative");
        }
        if (installedPackageName == null || installedPackageName.isBlank()) {
            throw new IllegalArgumentException("installedPackageName is required");
        }
        Objects.requireNonNull(channel, "channel");

        String releasesJson = getJson(releasesApiUrl, MAX_RELEASE_RESPONSE_BYTES);
        List<ReleaseDescriptor> releases = parseReleaseList(releasesJson, channel);
        UpdateRelease bestCandidate = null;
        for (ReleaseDescriptor release : releases) {
            AssetDescriptor metadataAsset = release.singleMetadataAsset();
            if (metadataAsset == null
                    || !UpdateRelease.isTrustedMetadataUrlForRelease(
                            metadataAsset.downloadUrl,
                            release.tagName,
                            metadataAsset.name
                    )) {
                continue;
            }

            String metadataJson = getJson(
                    HttpUrl.get(metadataAsset.downloadUrl),
                    MAX_METADATA_RESPONSE_BYTES
            );
            UpdateMetadata metadata = parseMetadata(metadataJson);
            if (metadata == null
                    || metadata.schemaVersion != 1L
                    || !installedPackageName.equals(metadata.packageName)
                    || !channel.matchesMetadata(metadata.channel)
                    || !channel.matchesVersion(metadata.versionName)
                    || !release.tagName.equals("v" + metadata.versionName)
                    || metadata.minimumSdk > deviceSdk
                    || !metadataAsset.name.equals(
                            "Plyvanta-" + metadata.versionName + "-update.json"
                    )) {
                continue;
            }

            AssetDescriptor apkAsset = release.singleAssetNamed(metadata.apkName);
            if (apkAsset == null
                    || !APK_CONTENT_TYPE.equals(apkAsset.contentType)
                    || !UpdateRelease.isTrustedApkUrlForRelease(
                            apkAsset.downloadUrl,
                            release.tagName,
                            metadata.apkName
                    )
                    || !("sha256:" + metadata.sha256).equalsIgnoreCase(apkAsset.digest)) {
                continue;
            }

            UpdateRelease candidate;
            try {
                candidate = new UpdateRelease(
                        metadata.versionCode,
                        metadata.versionName,
                        apkAsset.downloadUrl,
                        release.releaseUrl,
                        metadata.sha256
                );
            } catch (IllegalArgumentException invalidRelease) {
                continue;
            }
            if (candidate.isNewerThan(installedVersionCode)
                    && (bestCandidate == null
                    || candidate.getVersionCode() > bestCandidate.getVersionCode())) {
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private String getJson(HttpUrl url, int maximumBytes) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/vnd.github+json, application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() != 200) {
                throw new IOException(
                        "Plyvanta update request failed with HTTP " + response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Plyvanta update request returned no response body");
            }
            return readBoundedBody(body, maximumBytes);
        }
    }

    static List<ReleaseDescriptor> parseReleaseList(
            String responseBody,
            UpdateChannel channel
    ) throws IOException {
        Objects.requireNonNull(responseBody, "responseBody");
        Objects.requireNonNull(channel, "channel");
        final JSONArray releases;
        try {
            releases = new JSONArray(responseBody);
        } catch (JSONException malformedJson) {
            throw new IOException("GitHub returned malformed release JSON", malformedJson);
        }
        if (releases.length() > MAX_RELEASES) {
            throw new IOException("GitHub returned too many releases");
        }

        List<ReleaseDescriptor> parsed = new ArrayList<>();
        for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
            JSONObject object = releases.optJSONObject(releaseIndex);
            if (object == null) {
                continue;
            }
            Boolean draft = exactBoolean(object, "draft");
            Boolean prerelease = exactBoolean(object, "prerelease");
            Boolean immutable = exactBoolean(object, "immutable");
            String tagName = exactString(object, "tag_name");
            String releaseUrl = exactString(object, "html_url");
            JSONArray assets = object.optJSONArray("assets");
            if (draft == null
                    || prerelease == null
                    || immutable == null
                    || draft
                    || !immutable
                    || !channel.matchesRelease(prerelease)
                    || tagName == null
                    || releaseUrl == null
                    || !UpdateRelease.isTrustedReleasePageUrlForRelease(
                            releaseUrl,
                            tagName
                    )
                    || assets == null
                    || assets.length() > MAX_ASSETS_PER_RELEASE) {
                continue;
            }

            List<AssetDescriptor> parsedAssets = new ArrayList<>();
            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.optJSONObject(assetIndex);
                if (asset == null) {
                    continue;
                }
                String name = exactString(asset, "name");
                String contentType = exactString(asset, "content_type");
                String downloadUrl = exactString(asset, "browser_download_url");
                String digest = exactString(asset, "digest");
                if (name == null || contentType == null || downloadUrl == null) {
                    continue;
                }
                parsedAssets.add(new AssetDescriptor(
                        name,
                        contentType,
                        downloadUrl,
                        digest
                ));
            }
            parsed.add(new ReleaseDescriptor(
                    tagName,
                    releaseUrl,
                    Collections.unmodifiableList(parsedAssets)
            ));
        }
        return Collections.unmodifiableList(parsed);
    }

    static UpdateMetadata parseMetadata(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        final JSONObject object;
        try {
            object = new JSONObject(responseBody);
        } catch (JSONException malformedJson) {
            return null;
        }

        Long schemaVersion = exactLong(object, "schemaVersion");
        Long versionCode = exactLong(object, "versionCode");
        Long minimumSdk = exactLong(object, "minimumSdk");
        String packageName = exactString(object, "packageName");
        String channel = exactString(object, "channel");
        String versionName = exactString(object, "versionName");
        String apkName = exactString(object, "apkName");
        String sha256 = exactString(object, "sha256");
        if (schemaVersion == null
                || versionCode == null
                || versionCode <= 0L
                || minimumSdk == null
                || minimumSdk < 1L
                || minimumSdk > Integer.MAX_VALUE
                || packageName == null
                || channel == null
                || versionName == null
                || apkName == null
                || !apkName.endsWith(".apk")
                || sha256 == null
                || !sha256.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        return new UpdateMetadata(
                schemaVersion,
                packageName,
                channel,
                versionCode,
                versionName,
                (int) (long) minimumSdk,
                apkName,
                sha256.toLowerCase(Locale.ROOT)
        );
    }

    private static String readBoundedBody(ResponseBody body, int maximumBytes)
            throws IOException {
        long declaredLength = body.contentLength();
        if (declaredLength > maximumBytes) {
            throw new IOException("Plyvanta update response exceeds the size limit");
        }

        int initialCapacity = declaredLength >= 0L
                ? (int) Math.min(declaredLength, maximumBytes)
                : 8_192;
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8_192];
            int totalBytes = 0;
            while (true) {
                int permittedRead = Math.min(
                        buffer.length,
                        maximumBytes - totalBytes + 1
                );
                int bytesRead = input.read(buffer, 0, permittedRead);
                if (bytesRead == -1) {
                    break;
                }
                totalBytes += bytesRead;
                if (totalBytes > maximumBytes) {
                    throw new IOException("Plyvanta update response exceeds the size limit");
                }
                output.write(buffer, 0, bytesRead);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String exactString(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static Boolean exactBoolean(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Long exactLong(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        long longValue = number.longValue();
        double doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue != (double) longValue) {
            return null;
        }
        return longValue;
    }

    static final class ReleaseDescriptor {
        private final String tagName;
        private final String releaseUrl;
        private final List<AssetDescriptor> assets;

        private ReleaseDescriptor(
                String tagName,
                String releaseUrl,
                List<AssetDescriptor> assets
        ) {
            this.tagName = tagName;
            this.releaseUrl = releaseUrl;
            this.assets = assets;
        }

        private AssetDescriptor singleMetadataAsset() {
            AssetDescriptor match = null;
            for (AssetDescriptor asset : assets) {
                if (!asset.name.endsWith("-update.json")) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = asset;
            }
            return match;
        }

        private AssetDescriptor singleAssetNamed(String name) {
            AssetDescriptor match = null;
            for (AssetDescriptor asset : assets) {
                if (!asset.name.equals(name)) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = asset;
            }
            return match;
        }
    }

    private static final class AssetDescriptor {
        private final String name;
        private final String contentType;
        private final String downloadUrl;
        private final String digest;

        private AssetDescriptor(
                String name,
                String contentType,
                String downloadUrl,
                String digest
        ) {
            this.name = name;
            this.contentType = contentType;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }

    static final class UpdateMetadata {
        private final long schemaVersion;
        private final String packageName;
        private final String channel;
        private final long versionCode;
        private final String versionName;
        private final int minimumSdk;
        private final String apkName;
        private final String sha256;

        private UpdateMetadata(
                long schemaVersion,
                String packageName,
                String channel,
                long versionCode,
                String versionName,
                int minimumSdk,
                String apkName,
                String sha256
        ) {
            this.schemaVersion = schemaVersion;
            this.packageName = packageName;
            this.channel = channel;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.minimumSdk = minimumSdk;
            this.apkName = apkName;
            this.sha256 = sha256;
        }
    }
}
