package app.plyvanta.extractor;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Small, stateless downloader adapter used by NewPipe Extractor.
 */
public final class OkHttpDownloader extends Downloader {
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) "
                    + "Gecko/20100101 Firefox/140.0";

    private final OkHttpClient client;

    public OkHttpDownloader() {
        client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());
        boolean hasUserAgent = false;

        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("User-Agent")) {
                hasUserAgent = true;
            }
            for (String value : entry.getValue()) {
                builder.addHeader(entry.getKey(), value);
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DESKTOP_USER_AGENT);
        }

        byte[] data = request.dataToSend();
        String method = request.httpMethod();
        if ("GET".equalsIgnoreCase(method)) {
            builder.get();
        } else if ("HEAD".equalsIgnoreCase(method)) {
            builder.head();
        } else {
            MediaType mediaType = null;
            String contentType = builder.build().header("Content-Type");
            if (contentType != null) {
                mediaType = MediaType.parse(contentType);
            }
            RequestBody body = data == null
                    ? RequestBody.create(new byte[0], mediaType)
                    : RequestBody.create(data, mediaType);
            builder.method(method, body);
        }

        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    body,
                    response.request().url().toString()
            );
        }
    }
}
