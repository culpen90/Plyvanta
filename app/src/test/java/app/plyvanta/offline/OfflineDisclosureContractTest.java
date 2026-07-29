package app.plyvanta.offline;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class OfflineDisclosureContractTest {
    @Test
    public void defaultUiExplainsTheCompleteOfflinePolicyBeforeConsent()
            throws Exception {
        Map<String, String> strings = readDefaultStrings();
        String card = normalized(strings.get("offline_detail"));
        String consent = normalized(strings.get("offline_rights_message"));
        String confirmation = normalized(strings.get("offline_confirm_save"));
        String library = normalized(strings.get("offline_library_policy"));
        String settings = normalized(strings.get("offline_use_policy"));
        String unknownUploader = normalized(
                strings.get("offline_unknown_uploader")
        );

        assertContainsAll(
                card,
                "deliberately locked down",
                "copyright",
                "platform-terms risk",
                "official plyvanta",
                "authorized personal offline entertainment",
                "to discourage redistribution",
                "no app-provided playable-file access",
                "export",
                "share"
        );
        assertContainsAll(
                consent,
                "platform terms",
                "copyright",
                "personal offline entertainment",
                "personal use or ownership alone does not grant platform permission",
                "platform's terms and applicable law permit you to download",
                "cannot verify that permission",
                "not intended to become a convenient way to redistribute free copies",
                "no app-provided playable-file access",
                "code is open source",
                "forks can remove",
                "code license grants no rights to media",
                "fork's choices are not official upstream policy",
                "official builds",
                "meaningful practical barrier against redistribution"
        );
        assertContainsAll(confirmation, "i'm permitted", "encrypt", "save");
        assertContainsAll(unknownUploader, "unknown uploader");
        assertContainsAll(
                library,
                "personal, on-device playback",
                "deletion only",
                "no export",
                "share",
                "backup",
                "migration"
        );
        assertContainsAll(
                settings,
                "official plyvanta",
                "authorized personal, on-device entertainment",
                "not redistribution",
                "copyright law",
                "applicable third-party service terms",
                "cannot verify or grant media rights",
                "code license grants no rights to media",
                "forks can alter",
                "not official upstream policy"
        );

        String activity = readProjectFile(
                "app/src/main/java/app/plyvanta/MainActivity.java"
        );
        assertContainsAll(
                activity,
                "body.addView(buildOfflineCard(), spacedCardParams());",
                "getString(R.string.offline_detail)",
                "offlineSaveButton.setOnClickListener(view -> promptOfflineSave());",
                "getString(R.string.offline_library_policy)",
                "getString(R.string.offline_use_policy)",
                "getString(R.string.offline_unknown_uploader)",
                "uploaderText.setText(R.string.offline_unknown_uploader)"
        );
        String prompt = sectionBetween(
                activity,
                "private void promptOfflineSave()",
                "private void requestOfflineCredential("
        );
        assertContainsAll(
                prompt,
                ".setTitle(R.string.offline_rights_title)",
                ".setMessage(R.string.offline_rights_message)",
                ".setNegativeButton(android.R.string.cancel, null)",
                "R.string.offline_confirm_save",
                "requestOfflineCredential(",
                "PendingOfflineAction.DOWNLOAD_CURRENT"
        );
    }

    @Test
    public void publicDocsExplainWhyOfficialUpstreamKeepsTheBarrier()
            throws IOException {
        String readme = normalized(readProjectFile("README.md"));
        String contract = normalized(readProjectFile(
                "docs/OFFLINE_SECURITY.md"
        ));
        String contributing = normalized(readProjectFile("CONTRIBUTING.md"));

        assertContainsAll(
                readme,
                "why the restrictions are so strict",
                "copyright and service-terms risk",
                "authorized personal offline entertainment",
                "redistribute free copies",
                "fork the code and remove these safeguards",
                "official source, builds, and accepted contributions",
                "meaningful technical and intentional barrier",
                "source-code license grants rights in plyvanta's code",
                "modified fork's choices are not official upstream policy",
                "no android app can make displayed or audible media literally "
                        + "impossible to copy"
        );
        assertContainsAll(
                contract,
                "project rationale and official upstream policy",
                "authorized personal offline entertainment",
                "not intended to be a general-purpose downloader",
                "a fork can alter or remove these controls",
                "official source, builds, documentation, and accepted contributions"
        );
        assertContainsAll(
                contributing,
                "intentional official-project policy",
                "copyright and platform-terms risk",
                "authorized personal entertainment",
                "a fork can remove these controls",
                "code rights grant no rights in downloaded media",
                "does not justify weakening the barrier in official plyvanta"
        );

        String changelog = normalized(readProjectFile("CHANGELOG.md"));
        assertContainsAll(
                changelog,
                "authorized personal offline playback",
                "mandatory pre-download acknowledgement",
                "copyright and platform-terms risk",
                "anti-redistribution purpose",
                "open-source forks"
        );
    }

    private static Map<String, String> readDefaultStrings() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        Element resources = factory.newDocumentBuilder()
                .parse(projectFile(
                        "app/src/main/res/values/strings.xml"
                ).toFile())
                .getDocumentElement();
        NodeList nodes = resources.getElementsByTagName("string");
        Map<String, String> strings = new HashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            strings.put(element.getAttribute("name"), element.getTextContent());
        }
        return strings;
    }

    private static String readProjectFile(String relativePath)
            throws IOException {
        return new String(
                Files.readAllBytes(projectFile(relativePath)),
                StandardCharsets.UTF_8
        );
    }

    private static Path projectFile(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate project file: " + relativePath);
    }

    private static String normalized(String value) {
        assertTrue("Required disclosure string is missing", value != null);
        return value
                .toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('\u2011', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String sectionBetween(
            String value,
            String startMarker,
            String endMarker
    ) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        assertTrue("Missing section start: " + startMarker, start >= 0);
        assertTrue("Missing section end: " + endMarker, end > start);
        return value.substring(start, end);
    }

    private static void assertContainsAll(
            String value,
            String... requiredPhrases
    ) {
        for (String phrase : requiredPhrases) {
            assertTrue(
                    "Missing required disclosure phrase: " + phrase,
                    value.contains(phrase)
            );
        }
    }
}
