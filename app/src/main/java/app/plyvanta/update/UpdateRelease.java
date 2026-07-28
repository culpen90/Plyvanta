package app.plyvanta.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class UpdateRelease {
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_API_HOST = "api.github.com";
    private static final Pattern REPOSITORY_COMPONENT_PATTERN =
            Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern VERSION_NAME_PATTERN = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:-debug\\.\\d+)?"
    );
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final long versionCode;
    private final String versionName;
    private final String repositoryFullName;
    private final String apkUrl;
    private final String releaseUrl;
    private final String sha256;

    UpdateRelease(
            long versionCode,
            String versionName,
            String repositoryFullName,
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
        if (!isTrustedRepositoryFullName(repositoryFullName)) {
            throw new IllegalArgumentException("repository full name is invalid");
        }
        String tagName = "v" + versionName;
        String apkName = "Plyvanta-" + versionName + ".apk";
        if (!isTrustedApkUrlForRelease(
                apkUrl,
                repositoryFullName,
                tagName,
                apkName
        )) {
            throw new IllegalArgumentException("APK URL is not a trusted Plyvanta release URL");
        }
        if (!isTrustedReleasePageUrlForRelease(
                releaseUrl,
                repositoryFullName,
                tagName
        )) {
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
        this.repositoryFullName = repositoryFullName;
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

    public String getRepositoryFullName() {
        return repositoryFullName;
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
            String repositoryFullName,
            String apkUrl,
            String releaseUrl,
            String sha256
    ) {
        try {
            return new UpdateRelease(
                    versionCode,
                    versionName,
                    repositoryFullName,
                    apkUrl,
                    releaseUrl,
                    sha256
            );
        } catch (IllegalArgumentException invalidStoredRelease) {
            return null;
        }
    }

    static boolean isTrustedRepositoryFullName(String value) {
        if (value == null) {
            return false;
        }
        String[] components = value.split("/", -1);
        if (components.length != 2) {
            return false;
        }
        for (String component : components) {
            if (!REPOSITORY_COMPONENT_PATTERN.matcher(component).matches()
                    || ".".equals(component)
                    || "..".equals(component)) {
                return false;
            }
        }
        return true;
    }

    static boolean isTrustedAssetApiUrl(String value, String repositoryFullName) {
        if (!isTrustedRepositoryFullName(repositoryFullName)) {
            return false;
        }
        URI uri = parseHttpsUri(value, GITHUB_API_HOST);
        if (uri == null || uri.getRawPath() == null) {
            return false;
        }
        String prefix = "/repos/" + repositoryFullName + "/releases/assets/";
        if (!uri.getRawPath().startsWith(prefix)) {
            return false;
        }
        String assetId = uri.getRawPath().substring(prefix.length());
        if (assetId.isEmpty()) {
            return false;
        }
        boolean nonZeroDigitFound = false;
        for (int index = 0; index < assetId.length(); index++) {
            char digit = assetId.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
            nonZeroDigitFound |= digit != '0';
        }
        return nonZeroDigitFound;
    }

    static boolean isTrustedApkUrlForRelease(
            String value,
            String repositoryFullName,
            String tagName,
            String apkName
    ) {
        return isTrustedDownloadUrlForRelease(
                value,
                repositoryFullName,
                tagName,
                apkName,
                ".apk"
        );
    }

    static boolean isTrustedMetadataUrlForRelease(
            String value,
            String repositoryFullName,
            String tagName,
            String metadataName
    ) {
        return isTrustedDownloadUrlForRelease(
                value,
                repositoryFullName,
                tagName,
                metadataName,
                "-update.json"
        );
    }

    static boolean isTrustedReleasePageUrlForRelease(
            String value,
            String repositoryFullName,
            String tagName
    ) {
        if (!isTrustedRepositoryFullName(repositoryFullName) || tagName == null) {
            return false;
        }
        URI uri = parseHttpsUri(value, GITHUB_HOST);
        return uri != null
                && ("/" + repositoryFullName + "/releases/tag/" + tagName)
                .equals(uri.getRawPath());
    }

    private static boolean isTrustedDownloadUrlForRelease(
            String value,
            String repositoryFullName,
            String tagName,
            String assetName,
            String requiredSuffix
    ) {
        if (!isTrustedRepositoryFullName(repositoryFullName)
                || tagName == null
                || assetName == null
                || !assetName.endsWith(requiredSuffix)
                || assetName.contains("/")) {
            return false;
        }
        URI uri = parseHttpsUri(value, GITHUB_HOST);
        String expectedPath = "/" + repositoryFullName
                + "/releases/download/" + tagName + "/" + assetName;
        return uri != null && expectedPath.equals(uri.getRawPath());
    }

    private static URI parseHttpsUri(String value, String expectedHost) {
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
                || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
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
                && repositoryFullName.equals(that.repositoryFullName)
                && apkUrl.equals(that.apkUrl)
                && releaseUrl.equals(that.releaseUrl)
                && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                versionCode,
                versionName,
                repositoryFullName,
                apkUrl,
                releaseUrl,
                sha256
        );
    }
}
