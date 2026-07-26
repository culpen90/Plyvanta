package app.plyvanta.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DiagnosticReportTest {
    @Test
    public void descriptionOnlyReportContainsNoOptionalSections() {
        String report = DiagnosticReport.format(
                "Playback stopped after a few seconds.",
                Map.of(),
                null
        );

        assertTrue(report.contains("## What happened\n\nPlayback stopped after a few seconds."));
        assertFalse(report.contains("## Technical details"));
        assertFalse(report.contains("## Current video link"));
    }

    @Test
    public void technicalDetailsKeepTheirStableAllowlistOrder() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("App version", "1.0.0-debug.2 (2)");
        details.put("Android", "16 (API 36)");
        details.put("Player state", "Buffering");

        String report = DiagnosticReport.format("The video never started.", details, null);

        int app = report.indexOf("**App version:**");
        int android = report.indexOf("**Android:**");
        int player = report.indexOf("**Player state:**");
        assertTrue(app > 0);
        assertTrue(android > app);
        assertTrue(player > android);
    }

    @Test
    public void currentVideoIsIncludedOnlyWhenExplicitlyProvided() {
        String videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        String excluded = DiagnosticReport.format("Wrong quality.", Map.of(), null);
        String included = DiagnosticReport.format("Wrong quality.", Map.of(), videoUrl);

        assertFalse(excluded.contains(videoUrl));
        assertFalse(excluded.contains("## Current video link"));
        assertTrue(included.contains("## Current video link"));
        assertTrue(included.contains(videoUrl));
    }

    @Test
    public void technicalValuesDefensivelyRedactCommonSecrets() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(
                "Unexpected URL",
                "https://r1.googlevideo.com/videoplayback?expire=123&sig=secret"
        );
        details.put("Request header", "Authorization: Bearer secret-token");
        details.put("Contact", "person@example.com");
        details.put("Path", "/Users/person/private/report.txt");

        String report = DiagnosticReport.format("A request failed.", details, null);

        assertFalse(report.contains("googlevideo"));
        assertFalse(report.contains("secret-token"));
        assertFalse(report.contains("person@example.com"));
        assertFalse(report.contains("/Users/person"));
        assertTrue(report.contains("[redacted link]"));
        assertTrue(report.contains("[redacted email]"));
        assertTrue(report.contains("[redacted path]"));
    }

    @Test
    public void detailCountAndTextLengthsAreBounded() {
        Map<String, String> details = new LinkedHashMap<>();
        for (int index = 0; index < DiagnosticReport.MAX_DETAIL_COUNT + 5; index++) {
            details.put("Detail " + index, "x".repeat(500));
        }

        String report = DiagnosticReport.format(
                "d".repeat(DiagnosticReport.MAX_DESCRIPTION_CHARS + 500),
                details,
                null
        );

        assertFalse(report.contains("Detail " + DiagnosticReport.MAX_DETAIL_COUNT));
        assertFalse(report.contains("x".repeat(DiagnosticReport.MAX_DETAIL_VALUE_CHARS + 1)));
        assertTrue(report.length() < 12_000);
    }

    @Test
    public void suggestedTitleUsesFirstMeaningfulLineAndIsBounded() {
        String title = DiagnosticReport.suggestedTitle(
                "\n\n" + "Video playback freezes unexpectedly ".repeat(6) + "\nMore detail"
        );

        assertTrue(title.startsWith("[Bug] Video playback freezes unexpectedly"));
        assertTrue(title.endsWith("…"));
        assertTrue(title.length() <= 79);
    }
}
