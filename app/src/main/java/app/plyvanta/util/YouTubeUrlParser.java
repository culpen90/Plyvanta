package app.plyvanta.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses video IDs from the YouTube URL forms accepted by Plyvanta.
 *
 * <p>This class deliberately uses an allow-list for hosts and routes. In particular, finding a
 * {@code v} query parameter is not enough to make an arbitrary URL a YouTube video URL.
 */
public final class YouTubeUrlParser {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PATH_VIDEO =
            Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?$");
    private static final Pattern URL_IN_TEXT =
            Pattern.compile(
                    "(?i)(?:https?://|//|www\\.)[^\\s<>]+"
                            + "|(?<![A-Za-z0-9._@-])(?:m\\.|music\\.)?youtube\\.com/[^\\s<>]+"
                            + "|(?<![A-Za-z0-9._@-])youtu\\.be/[^\\s<>]+");

    private static final String CANONICAL_PREFIX = "https://www.youtube.com/watch?v=";

    private YouTubeUrlParser() {}

    /**
     * Extracts a YouTube video ID from a raw ID, supported URL, or text containing a supported URL.
     *
     * @param input text to inspect
     * @return the 11-character video ID, or {@code null} if no trusted video URL is present
     */
    public static String extractVideoId(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (isValidVideoId(trimmed)) {
            return trimmed;
        }

        String direct = parseUrlCandidate(stripTrailingPunctuation(trimmed));
        if (direct != null) {
            return direct;
        }

        Matcher matcher = URL_IN_TEXT.matcher(trimmed);
        while (matcher.find()) {
            String videoId = parseUrlCandidate(stripTrailingPunctuation(matcher.group()));
            if (videoId != null) {
                return videoId;
            }
        }
        return null;
    }

    /**
     * Converts a supported input to a stable YouTube watch URL.
     *
     * @return a canonical HTTPS watch URL, or {@code null} when the input is unsupported
     */
    public static String canonicalize(String input) {
        String videoId = extractVideoId(input);
        return videoId == null ? null : CANONICAL_PREFIX + videoId;
    }

    /** Returns whether {@code value} is exactly one syntactically valid YouTube video ID. */
    public static boolean isValidVideoId(String value) {
        return value != null && VIDEO_ID.matcher(value).matches();
    }

    private static String parseUrlCandidate(String candidate) {
        if (candidate == null || candidate.isEmpty() || containsWhitespace(candidate)) {
            return null;
        }

        String withScheme = candidate;
        if (candidate.startsWith("//")) {
            withScheme = "https:" + candidate;
        } else if (!startsWithHttpScheme(candidate)) {
            withScheme = "https://" + candidate;
        }

        final URI uri;
        try {
            uri = new URI(withScheme);
        } catch (URISyntaxException exception) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        if (isShortHost(host)) {
            return parseShortUrl(uri);
        }
        if (!isYouTubeHost(host)) {
            return null;
        }
        return parseYouTubeUrl(uri);
    }

    private static String parseShortUrl(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.length() < 2) {
            return null;
        }

        String withoutLeadingSlash = path.substring(1);
        if (withoutLeadingSlash.endsWith("/")) {
            withoutLeadingSlash =
                    withoutLeadingSlash.substring(0, withoutLeadingSlash.length() - 1);
        }
        return isValidVideoId(withoutLeadingSlash) ? withoutLeadingSlash : null;
    }

    private static String parseYouTubeUrl(URI uri) {
        String path = uri.getRawPath();
        if (path == null) {
            return null;
        }

        if (path.equals("/watch") || path.equals("/watch/")) {
            return findWatchVideoId(uri.getRawQuery());
        }

        Matcher pathMatcher = PATH_VIDEO.matcher(path);
        return pathMatcher.matches() ? pathMatcher.group(1) : null;
    }

    private static String findWatchVideoId(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }

        String found = null;
        for (String parameter : rawQuery.split("&", -1)) {
            int equals = parameter.indexOf('=');
            String key = equals >= 0 ? parameter.substring(0, equals) : parameter;
            if (!key.equals("v")) {
                continue;
            }

            // Duplicate video IDs are ambiguous, so reject the URL instead of choosing one.
            if (found != null) {
                return null;
            }
            String value = equals >= 0 ? parameter.substring(equals + 1) : "";
            if (!isValidVideoId(value)) {
                return null;
            }
            found = value;
        }
        return found;
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtube.com")
                || host.equals("www.youtube.com")
                || host.equals("m.youtube.com")
                || host.equals("music.youtube.com");
    }

    private static boolean isShortHost(String host) {
        return host.equals("youtu.be") || host.equals("www.youtu.be");
    }

    private static boolean startsWithHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
                || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character == '.'
                    || character == ','
                    || character == '!'
                    || character == '?'
                    || character == ';'
                    || character == ':'
                    || character == ')'
                    || character == ']'
                    || character == '}'
                    || character == '\''
                    || character == '"') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }
}
