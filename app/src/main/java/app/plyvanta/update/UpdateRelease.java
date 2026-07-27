package app.plyvanta.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class UpdateRelease {
    private static final String GITHUB_HOST = "github.com";
    private static final String RELEASE_DOWNLOAD_PREFIX =
            "/culpen90/Plyvanta/releases/download/";
    private static final String RELEASE_PAGE_PREFIX =
            "/culpen90/Plyvanta/releases/tag/";
    private static final Pattern VERSION_NAME_PATTERN = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:-debug\\.\\d+)?"
    );
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final long versionCode;
    private final String versionName;
    private final String apkUrl;
    private final String releaseUrl;
    private final String sha256;

    UpdateRelease(
            long versionCode,
            String versionName,
            String apkUrl,
            String releaseUrl,
            String sha256
    ) {
        if (versionCode <= 0L) {
            throw new IllegalArgumentException("versionCode must be positive");
        }
        if (versionName == null
                || !VERSION_NAME_PATTERN.matcher(versionName).matches()) {
            throw new IllegalArgumentException("versionName is invalid");
        }
        if (!isTrustedDownloadUrl(apkUrl, ".apk")) {
            throw new IllegalArgumentException("APK URL is not a trusted Plyvanta release URL");
        }
        if (!isTrustedReleasePageUrl(releaseUrl)) {
            throw new IllegalArgumentException(
                    "release URL is not a trusted Plyvanta release URL"
            );
        }
        String normalizedSha = sha256 == null
                ? ""
                : sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalizedSha).matches()) {
            throw new IllegalArgumentException("sha256 is invalid");
        }
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.releaseUrl = releaseUrl;
        this.sha256 = normalizedSha;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getApkUrl() {
        return apkUrl;
    }

    public String getReleaseUrl() {
        return releaseUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isNewerThan(long installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    static UpdateRelease restore(
            long versionCode,
            String versionName,
            String apkUrl,
            String releaseUrl,
            String sha256
    ) {
        try {
            return new UpdateRelease(
                    versionCode,
                    versionName,
                    apkUrl,
                    releaseUrl,
                    sha256
            );
        } catch (IllegalArgumentException invalidStoredRelease) {
            return null;
        }
    }

    static boolean isTrustedMetadataUrl(String value) {
        return isTrustedDownloadUrl(value, "-update.json");
    }

    static boolean isTrustedApkUrlForRelease(
            String value,
            String tagName,
            String apkName
    ) {
        if (!isTrustedDownloadUrl(value, ".apk")
                || tagName == null
                || apkName == null) {
            return false;
        }
        URI uri = parseHttpsGithubUri(value);
        String expectedPath = RELEASE_DOWNLOAD_PREFIX + tagName + "/" + apkName;
        return uri != null && expectedPath.equals(uri.getPath());
    }

    static boolean isTrustedMetadataUrlForRelease(
            String value,
            String tagName,
            String metadataName
    ) {
        if (!isTrustedMetadataUrl(value)
                || tagName == null
                || metadataName == null) {
            return false;
        }
        URI uri = parseHttpsGithubUri(value);
        String expectedPath = RELEASE_DOWNLOAD_PREFIX + tagName + "/" + metadataName;
        return uri != null && expectedPath.equals(uri.getPath());
    }

    static boolean isTrustedReleasePageUrlForRelease(String value, String tagName) {
        if (tagName == null) {
            return false;
        }
        URI uri = parseHttpsGithubUri(value);
        return uri != null
                && (RELEASE_PAGE_PREFIX + tagName).equals(uri.getPath());
    }

    private static boolean isTrustedDownloadUrl(String value, String requiredSuffix) {
        URI uri = parseHttpsGithubUri(value);
        if (uri == null || uri.getPath() == null) {
            return false;
        }
        return uri.getPath().startsWith(RELEASE_DOWNLOAD_PREFIX)
                && uri.getPath().endsWith(requiredSuffix);
    }

    private static boolean isTrustedReleasePageUrl(String value) {
        URI uri = parseHttpsGithubUri(value);
        return uri != null
                && uri.getPath() != null
                && uri.getPath().startsWith(RELEASE_PAGE_PREFIX)
                && uri.getPath().length() > RELEASE_PAGE_PREFIX.length();
    }

    private static URI parseHttpsGithubUri(String value) {
        if (value == null) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalidUri) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !GITHUB_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getFragment() != null) {
            return null;
        }
        return uri;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UpdateRelease)) {
            return false;
        }
        UpdateRelease that = (UpdateRelease) other;
        return versionCode == that.versionCode
                && versionName.equals(that.versionName)
                && apkUrl.equals(that.apkUrl)
                && releaseUrl.equals(that.releaseUrl)
                && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionCode, versionName, apkUrl, releaseUrl, sha256);
    }
}
