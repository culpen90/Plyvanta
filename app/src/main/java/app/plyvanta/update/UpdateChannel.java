package app.plyvanta.update;

import java.util.regex.Pattern;

enum UpdateChannel {
    PREVIEW("preview", true, Pattern.compile("\\d+\\.\\d+\\.\\d+-debug\\.\\d+")),
    STABLE("stable", false, Pattern.compile("\\d+\\.\\d+\\.\\d+"));

    private final String metadataName;
    private final boolean prerelease;
    private final Pattern versionPattern;

    UpdateChannel(String metadataName, boolean prerelease, Pattern versionPattern) {
        this.metadataName = metadataName;
        this.prerelease = prerelease;
        this.versionPattern = versionPattern;
    }

    static UpdateChannel forDebuggableApp(boolean debuggable) {
        return debuggable ? PREVIEW : STABLE;
    }

    boolean matchesRelease(boolean releaseIsPrerelease) {
        return prerelease == releaseIsPrerelease;
    }

    boolean matchesMetadata(String channelName) {
        return metadataName.equals(channelName);
    }

    boolean matchesVersion(String versionName) {
        return versionName != null && versionPattern.matcher(versionName).matches();
    }
}
