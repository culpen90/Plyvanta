package app.plyvanta.support;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Formats the user-reviewed, text-only report handed to another app.
 *
 * <p>Technical values are deliberately bounded and defensively scrubbed. The caller must still
 * provide an explicit allowlist rather than throwable messages, logs, or serialized app objects.
 */
public final class DiagnosticReport {
    public static final int MAX_DESCRIPTION_CHARS = 4_000;
    static final int MAX_DETAIL_COUNT = 20;
    static final int MAX_DETAIL_KEY_CHARS = 80;
    static final int MAX_DETAIL_VALUE_CHARS = 240;
    static final int MAX_VIDEO_URL_CHARS = 256;
    private static final int MAX_TITLE_SUMMARY_CHARS = 72;

    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern LOCAL_PATH = Pattern.compile(
            "(?i)(?:/(?:Users|home|data/user)/|[A-Z]:\\\\)\\S+"
    );

    private DiagnosticReport() {
    }

    public static String format(
            String description,
            Map<String, String> technicalDetails,
            String currentVideoUrl
    ) {
        StringBuilder report = new StringBuilder();
        report.append("## What happened\n\n");
        String normalizedDescription = normalizeDescription(description);
        report.append(normalizedDescription.isEmpty()
                ? "(No description provided.)"
                : normalizedDescription);

        if (technicalDetails != null && !technicalDetails.isEmpty()) {
            appendTechnicalDetails(report, technicalDetails);
        }

        String normalizedVideoUrl = trimToLength(currentVideoUrl, MAX_VIDEO_URL_CHARS);
        if (!normalizedVideoUrl.isEmpty()) {
            report.append("\n\n## Current video link\n\n");
            report.append(normalizedVideoUrl);
        }

        report.append("\n\n---\n");
        report.append("Generated locally by Plyvanta and reviewed before sharing.");
        return report.toString();
    }

    public static String suggestedTitle(String description) {
        String normalized = normalizeDescription(description);
        String firstLine = "Bug report";
        for (String line : normalized.split("\n")) {
            if (!line.trim().isEmpty()) {
                firstLine = line.trim();
                break;
            }
        }
        firstLine = firstLine.replaceAll("\\s+", " ");
        if (firstLine.length() > MAX_TITLE_SUMMARY_CHARS) {
            firstLine = firstLine.substring(0, MAX_TITLE_SUMMARY_CHARS - 1).trim() + "…";
        }
        return "[Bug] " + firstLine;
    }

    private static void appendTechnicalDetails(
            StringBuilder report,
            Map<String, String> technicalDetails
    ) {
        report.append("\n\n## Technical details\n");
        int count = 0;
        for (Map.Entry<String, String> entry : technicalDetails.entrySet()) {
            if (count >= MAX_DETAIL_COUNT) {
                break;
            }
            String key = singleLine(entry.getKey(), MAX_DETAIL_KEY_CHARS);
            String value = safeTechnicalValue(entry.getValue());
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            report.append("\n- **")
                    .append(key.replace("*", "\\*"))
                    .append(":** ")
                    .append(value);
            count++;
        }
    }

    private static String normalizeDescription(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder safe = new StringBuilder(Math.min(
                normalized.length(),
                MAX_DESCRIPTION_CHARS
        ));
        for (int index = 0; index < normalized.length()
                && safe.length() < MAX_DESCRIPTION_CHARS; index++) {
            char character = normalized.charAt(index);
            if (character == '\n' || character == '\t' || !Character.isISOControl(character)) {
                safe.append(character);
            }
        }
        return safe.toString().trim();
    }

    private static String safeTechnicalValue(String value) {
        String normalized = singleLine(value, MAX_DETAIL_VALUE_CHARS);
        String lower = normalized.toLowerCase(java.util.Locale.US);
        if (lower.contains("authorization")
                || lower.contains("bearer ")
                || lower.contains("cookie:")
                || lower.contains("cookie=")) {
            return "[redacted]";
        }
        normalized = URL.matcher(normalized).replaceAll("[redacted link]");
        normalized = EMAIL.matcher(normalized).replaceAll("[redacted email]");
        return LOCAL_PATH.matcher(normalized).replaceAll("[redacted path]");
    }

    private static String singleLine(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length() && result.length() < maxLength; index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                if (!previousWhitespace && result.length() > 0) {
                    result.append(' ');
                    previousWhitespace = true;
                }
            } else {
                result.append(character);
                previousWhitespace = false;
            }
        }
        return result.toString().trim();
    }

    private static String trimToLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
