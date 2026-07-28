package app.plyvanta.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses playable links from the YouTube URL forms accepted by Plyvanta.
 *
 * <p>This class deliberately uses an allow-list for hosts and routes. In particular, finding a
 * {@code v} query parameter is not enough to make an arbitrary URL a YouTube video URL.
 */
public final class YouTubeUrlParser {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{10,128}");
    private static final Pattern SEEDED_MIX_ID =
            Pattern.compile("RD[A-Za-z0-9_-]{0,126}");
    private static final Pattern PATH_VIDEO =
            Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?$");
    private static final Pattern URL_IN_TEXT =
            Pattern.compile(
                    "(?i)(?<![-A-Za-z0-9._@/\\\\=?&#%:])"
                            + "(?:(?:https?://|//|www\\.)[^\\s<>]+"
                            + "|(?:m\\.|music\\.)?youtube\\.com/[^\\s<>]+"
                            + "|youtu\\.be/[^\\s<>]+)");

    private static final String CANONICAL_PREFIX = "https://www.youtube.com/watch?v=";
    private static final String CANONICAL_PLAYLIST_PREFIX =
            "https://www.youtube.com/playlist?list=";
    private static final int INVALID_PLAYLIST_INDEX = Integer.MIN_VALUE;

    private YouTubeUrlParser() {}

    /**
     * Parses a video or playlist from a raw video ID, supported URL, or text containing one.
     *
     * <p>A video link with a {@code list} parameter is a playlist link whose canonical URL keeps
     * both the video and playlist context. YouTube's one-based {@code index} parameter is exposed
     * as a zero-based index. Playlist URLs without an index return {@code -1}.
     *
     * @param input text to inspect
     * @return an immutable playable link, or {@code null} if no trusted playable URL is present
     */
    public static PlayableLink parsePlayable(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (isValidVideoId(trimmed)) {
            return videoLink(trimmed, null, -1);
        }

        PlayableLink direct = parsePlayableUrlCandidate(stripTrailingPunctuation(trimmed));
        if (direct != null) {
            return direct;
        }

        Matcher matcher = URL_IN_TEXT.matcher(trimmed);
        while (matcher.find()) {
            PlayableLink playable =
                    parsePlayableUrlCandidate(stripTrailingPunctuation(matcher.group()));
            if (playable != null) {
                return playable;
            }
        }
        return null;
    }

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

    private static PlayableLink parsePlayableUrlCandidate(String candidate) {
        URI uri = parseTrustedUri(candidate);
        if (uri == null) {
            return null;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isShortHost(host)) {
            return parseShortPlayable(uri);
        }
        return parseYouTubePlayable(uri);
    }

    private static PlayableLink parseShortPlayable(URI uri) {
        String videoId = videoIdFromShortPath(uri.getRawPath());
        if (videoId == null) {
            return null;
        }

        QueryParameters parameters = QueryParameters.parse(uri.getRawQuery());
        if (parameters.videoCount != 0) {
            return null;
        }
        return videoLinkFromParameters(videoId, parameters);
    }

    private static PlayableLink parseYouTubePlayable(URI uri) {
        String path = uri.getRawPath();
        if (path == null) {
            return null;
        }

        QueryParameters parameters = QueryParameters.parse(uri.getRawQuery());
        if (path.equals("/watch") || path.equals("/watch/")) {
            if (parameters.videoCount != 1
                    || !isValidVideoId(parameters.videoValue)) {
                return null;
            }
            return videoLinkFromParameters(parameters.videoValue, parameters);
        }

        if (path.equals("/playlist") || path.equals("/playlist/")) {
            if (parameters.videoCount != 0
                    || parameters.playlistCount != 1
                    || !isValidPlaylistId(parameters.playlistValue)) {
                return null;
            }
            int playlistIndex = parsePlaylistIndex(parameters);
            return playlistIndex == INVALID_PLAYLIST_INDEX
                    ? null
                    : playlistLink(parameters.playlistValue, playlistIndex);
        }

        Matcher pathMatcher = PATH_VIDEO.matcher(path);
        if (!pathMatcher.matches() || parameters.videoCount != 0) {
            return null;
        }
        return videoLinkFromParameters(pathMatcher.group(1), parameters);
    }

    private static PlayableLink videoLinkFromParameters(
            String videoId,
            QueryParameters parameters
    ) {
        if (parameters.playlistCount == 0) {
            // Preserve the established behavior of ignoring unrelated query parameters.
            return videoLink(videoId, null, -1);
        }
        if (parameters.playlistCount != 1
                || (!isValidPlaylistId(parameters.playlistValue)
                && !isValidSeededMixId(parameters.playlistValue))) {
            return null;
        }

        int playlistIndex = parsePlaylistIndex(parameters);
        return playlistIndex == INVALID_PLAYLIST_INDEX
                ? null
                : videoLink(videoId, parameters.playlistValue, playlistIndex);
    }

    private static PlayableLink videoLink(
            String videoId,
            String playlistId,
            int playlistIndex
    ) {
        String canonical = CANONICAL_PREFIX + videoId;
        String playlistUrl = null;
        if (playlistId != null) {
            playlistUrl = CANONICAL_PLAYLIST_PREFIX + playlistId;
            canonical += "&list=" + playlistId + canonicalIndexSuffix(playlistIndex);
        }
        return new PlayableLink(
                canonical,
                playlistUrl,
                videoId,
                playlistId,
                playlistIndex
        );
    }

    private static PlayableLink playlistLink(String playlistId, int playlistIndex) {
        String playlistUrl = CANONICAL_PLAYLIST_PREFIX + playlistId;
        return new PlayableLink(
                playlistUrl + canonicalIndexSuffix(playlistIndex),
                playlistUrl,
                null,
                playlistId,
                playlistIndex
        );
    }

    private static String canonicalIndexSuffix(int playlistIndex) {
        return playlistIndex < 0 ? "" : "&index=" + ((long) playlistIndex + 1L);
    }

    private static int parsePlaylistIndex(QueryParameters parameters) {
        if (parameters.indexCount == 0) {
            return -1;
        }
        if (parameters.indexCount != 1) {
            return INVALID_PLAYLIST_INDEX;
        }

        String value = parameters.indexValue;
        if (value == null || value.isEmpty() || value.length() > 10) {
            return INVALID_PLAYLIST_INDEX;
        }
        long oneBasedIndex = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return INVALID_PLAYLIST_INDEX;
            }
            oneBasedIndex = oneBasedIndex * 10L + (character - '0');
        }
        if (oneBasedIndex < 1L || oneBasedIndex > Integer.MAX_VALUE) {
            return INVALID_PLAYLIST_INDEX;
        }
        return (int) oneBasedIndex - 1;
    }

    private static boolean isValidPlaylistId(String value) {
        return value != null && PLAYLIST_ID.matcher(value).matches();
    }

    private static boolean isValidSeededMixId(String value) {
        return value != null && SEEDED_MIX_ID.matcher(value).matches();
    }

    private static String parseUrlCandidate(String candidate) {
        URI uri = parseTrustedUri(candidate);
        if (uri == null) {
            return null;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isShortHost(host)) {
            return parseShortUrl(uri);
        }
        return parseYouTubeUrl(uri);
    }

    private static String parseShortUrl(URI uri) {
        return videoIdFromShortPath(uri.getRawPath());
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

    private static String videoIdFromShortPath(String path) {
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

    private static URI parseTrustedUri(String candidate) {
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
        if (host == null || uri.getRawUserInfo() != null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        return isShortHost(host) || isYouTubeHost(host) ? uri : null;
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

    /** Immutable trusted video or playlist reference. */
    public static final class PlayableLink {
        private final String canonicalUrl;
        private final String playlistUrl;
        private final String videoId;
        private final String playlistId;
        private final int playlistIndex;

        private PlayableLink(
                String canonicalUrl,
                String playlistUrl,
                String videoId,
                String playlistId,
                int playlistIndex
        ) {
            this.canonicalUrl = canonicalUrl;
            this.playlistUrl = playlistUrl;
            this.videoId = videoId;
            this.playlistId = playlistId;
            this.playlistIndex = playlistIndex;
        }

        /** Returns whether this link carries playlist context. */
        public boolean isPlaylist() {
            return playlistId != null;
        }

        /** Returns the normalized URL representing the original video/playlist selection. */
        public String getCanonicalUrl() {
            return canonicalUrl;
        }

        /** Returns the normalized playlist-only URL, or {@code null} for a single video. */
        public String getPlaylistUrl() {
            return playlistUrl;
        }

        /** Returns the video ID, or {@code null} for a playlist-only URL. */
        public String getVideoId() {
            return videoId;
        }

        /** Returns the playlist ID, or {@code null} for a single video. */
        public String getPlaylistId() {
            return playlistId;
        }

        /** Returns the zero-based playlist index, or {@code -1} when none was supplied. */
        public int getPlaylistIndex() {
            return playlistIndex;
        }
    }

    private static final class QueryParameters {
        private int videoCount;
        private String videoValue;
        private int playlistCount;
        private String playlistValue;
        private int indexCount;
        private String indexValue;

        private static QueryParameters parse(String rawQuery) {
            QueryParameters result = new QueryParameters();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return result;
            }

            for (String parameter : rawQuery.split("&", -1)) {
                int equals = parameter.indexOf('=');
                String key = equals >= 0 ? parameter.substring(0, equals) : parameter;
                String value = equals >= 0 ? parameter.substring(equals + 1) : "";
                switch (key) {
                    case "v" -> {
                        result.videoCount++;
                        result.videoValue = value;
                    }
                    case "list" -> {
                        result.playlistCount++;
                        result.playlistValue = value;
                    }
                    case "index" -> {
                        result.indexCount++;
                        result.indexValue = value;
                    }
                    default -> {
                        // Other YouTube parameters do not affect playable identity.
                    }
                }
            }
            return result;
        }
    }
}
