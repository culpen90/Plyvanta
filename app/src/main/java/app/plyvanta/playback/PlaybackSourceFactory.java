package app.plyvanta.playback;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

import java.util.Map;

import app.plyvanta.extractor.OkHttpDownloader;

@UnstableApi
public final class PlaybackSourceFactory {
    private final DefaultMediaSourceFactory mediaSourceFactory;

    public PlaybackSourceFactory(Context context) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(OkHttpDownloader.DESKTOP_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Map.of(
                        "Accept-Language", "en-US,en;q=0.8"
                ));
        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(context, httpFactory);
        mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
    }

    public MediaSource create(ResolvedVideo video) {
        MediaItem videoItem = mediaItem(
                video.getVideoUrl(),
                video.getVideoMimeType(),
                video.getTitle(),
                video.getUploader()
        );
        MediaSource primary = mediaSourceFactory.createMediaSource(videoItem);

        if (video.getSourceType() != ResolvedVideo.SourceType.MERGED
                || video.getAudioUrl() == null) {
            return primary;
        }

        MediaItem audioItem = mediaItem(
                video.getAudioUrl(),
                video.getAudioMimeType(),
                video.getTitle(),
                video.getUploader()
        );
        MediaSource audio = mediaSourceFactory.createMediaSource(audioItem);
        return new MergingMediaSource(primary, audio);
    }

    private static MediaItem mediaItem(
            String url,
            String mimeType,
            String title,
            String artist
    ) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build();
        return new MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .build();
    }
}
