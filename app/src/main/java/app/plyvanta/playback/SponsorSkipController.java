package app.plyvanta.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.plyvanta.sponsor.SponsorSegment;

/**
 * Seeks over SponsorBlock ranges and also catches manual seeks into a range.
 */
public final class SponsorSkipController {
    public interface Listener {
        void onSegmentSkipped(SponsorSegment segment);
    }

    private static final long POLL_INTERVAL_MS = 180;
    private static final long SKIP_COOLDOWN_MS = 2_500;

    private final Player player;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> suppressedUntil = new HashMap<>();
    private List<SponsorSegment> segments = Collections.emptyList();
    private boolean running;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            checkPosition();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public SponsorSkipController(Player player, Listener listener) {
        this.player = player;
        this.listener = listener;
    }

    public void setSegments(List<SponsorSegment> newSegments) {
        segments = newSegments == null ? Collections.emptyList() : List.copyOf(newSegments);
        suppressedUntil.clear();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        handler.post(poll);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    public void suppressTemporarily(SponsorSegment segment, long durationMs) {
        suppressedUntil.put(
                segment.getUuid(),
                SystemClock.elapsedRealtime() + Math.max(durationMs, SKIP_COOLDOWN_MS)
        );
    }

    private void checkPosition() {
        if (segments.isEmpty()
                || player.getPlaybackState() == Player.STATE_IDLE
                || player.getPlaybackState() == Player.STATE_ENDED
                || player.isPlayingAd()) {
            return;
        }

        long positionMs = player.getCurrentPosition();
        long durationMs = player.getDuration();
        long now = SystemClock.elapsedRealtime();
        suppressedUntil.entrySet().removeIf(entry -> entry.getValue() < now);

        for (SponsorSegment segment : segments) {
            long startMs = segment.getStartMillis();
            long endMs = segment.getEndMillis();
            if (positionMs + 80 < startMs || positionMs >= endMs - 40) {
                continue;
            }
            Long suppressed = suppressedUntil.get(segment.getUuid());
            if (suppressed != null && suppressed >= now) {
                continue;
            }

            long seekTarget = endMs + 80;
            if (durationMs != C.TIME_UNSET && durationMs > 0) {
                seekTarget = Math.min(seekTarget, durationMs);
            }
            suppressedUntil.put(segment.getUuid(), now + SKIP_COOLDOWN_MS);
            player.seekTo(seekTarget);
            listener.onSegmentSkipped(segment);
            break;
        }
    }
}
