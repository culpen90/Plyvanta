package app.plyvanta.update;

import java.util.regex.Pattern;

enum UpdateChannel {
    PREVIEW(
            "preview",
            true,
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)-debug\\.(\\d+)")
    ),
    STABLE(
            "stable",
            false,
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)")
    );

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

    boolean isNewerVersion(String candidateVersion, String installedVersion) {
        java.util.regex.Matcher candidate = versionPattern.matcher(
                candidateVersion == null ? "" : candidateVersion
        );
        java.util.regex.Matcher installed = versionPattern.matcher(
                installedVersion == null ? "" : installedVersion
        );
        if (!candidate.matches() || !installed.matches()) {
            return false;
        }
        for (int component = 1; component <= candidate.groupCount(); component++) {
            int comparison = compareNumericComponent(
                    candidate.group(component),
                    installed.group(component)
            );
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return false;
    }

    private static int compareNumericComponent(String first, String second) {
        String normalizedFirst = stripLeadingZeroes(first);
        String normalizedSecond = stripLeadingZeroes(second);
        if (normalizedFirst.length() != normalizedSecond.length()) {
            return Integer.compare(normalizedFirst.length(), normalizedSecond.length());
        }
        return normalizedFirst.compareTo(normalizedSecond);
    }

    private static String stripLeadingZeroes(String value) {
        int firstNonZero = 0;
        while (firstNonZero < value.length() - 1
                && value.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return value.substring(firstNonZero);
    }
}
