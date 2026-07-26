package app.plyvanta;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.plyvanta.playback.NewPipeVideoResolver;
import app.plyvanta.playback.PlaybackSourceFactory;
import app.plyvanta.playback.ResolvedVideo;
import app.plyvanta.playback.SponsorSkipController;
import app.plyvanta.settings.PreferenceStore;
import app.plyvanta.sponsor.SponsorBlockClient;
import app.plyvanta.sponsor.SponsorSegment;
import app.plyvanta.support.DiagnosticReport;
import app.plyvanta.util.YouTubeUrlParser;

@UnstableApi
public final class MainActivity extends ComponentActivity {
    private static final String STATE_URL = "active_url";
    private static final String STATE_POSITION = "playback_position";
    private static final String STATE_PLAY_WHEN_READY = "play_when_ready";
    private static final String STATE_BUG_REPORT_STEP = "bug_report_step";
    private static final String STATE_BUG_REPORT_DRAFT = "bug_report_draft";
    private static final String STATE_BUG_REPORT_DIAGNOSTICS = "bug_report_diagnostics";
    private static final String STATE_BUG_REPORT_VIDEO = "bug_report_video";
    private static final String STATE_BUG_REPORT_FROM_ERROR = "bug_report_from_error";
    private static final String STATE_BUG_REPORT_PREVIEW = "bug_report_preview";
    private static final String STATE_BUG_REPORT_DETAIL_KEYS = "bug_report_detail_keys";
    private static final String STATE_BUG_REPORT_DETAIL_VALUES = "bug_report_detail_values";
    private static final String STATE_BUG_REPORT_VIDEO_URL = "bug_report_video_url";
    private static final String BUG_REPORT_URL =
            "https://github.com/culpen90/Plyvanta/issues/new";
    private static final int MAX_GITHUB_PREFILL_URI_CHARS = 6_000;
    private static final int BUG_REPORT_CLOSED = 0;
    private static final int BUG_REPORT_EDITING = 1;
    private static final int BUG_REPORT_REVIEWING = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();

    private FrameLayout root;
    private LinearLayout appContent;
    private LinearLayout topBar;
    private LinearLayout body;
    private FrameLayout playerContainer;
    private PlayerView playerView;
    private ImageButton fullscreenButton;
    private EditText linkInput;
    private TextView titleText;
    private TextView uploaderText;
    private TextView playbackStatus;
    private TextView protectionStatus;
    private LinearLayout errorCard;
    private TextView errorText;
    private ProgressBar loadingIndicator;
    private LinearLayout skipNotice;
    private TextView skipNoticeText;

    private ExoPlayer player;
    private PlaybackSourceFactory playbackSourceFactory;
    private SponsorSkipController skipController;
    private SponsorBlockClient sponsorBlockClient;
    private PreferenceStore preferenceStore;
    private final NewPipeVideoResolver videoResolver = new NewPipeVideoResolver();

    private ResolvedVideo activeVideo;
    private String activeUrl;
    private boolean fullscreen;
    private boolean retriedAfterPlaybackFailure;
    private long pendingSeekMs = C.TIME_UNSET;
    private boolean pendingPlayWhenReady = true;
    private String lastFailureStage;
    private String lastFailureType;
    private String sponsorLookupStatus = "Not requested";
    private int bugReportStep = BUG_REPORT_CLOSED;
    private String bugReportDraft = "";
    private boolean bugReportIncludesDiagnostics;
    private boolean bugReportIncludesVideo;
    private boolean bugReportFromError;
    private String bugReportPreview;
    private LinkedHashMap<String, String> bugReportTechnicalSnapshot;
    private String bugReportVideoUrlSnapshot;
    private EditText activeBugReportDescription;
    private CheckBox activeBugReportDiagnostics;
    private CheckBox activeBugReportVideo;
    private SponsorSegment lastSkippedSegment;
    private ViewGroup originalPlayerParent;
    private int originalPlayerIndex;
    private ViewGroup.LayoutParams originalPlayerLayoutParams;

    private final Runnable hideSkipNotice = () -> {
        if (skipNotice != null) {
            skipNotice.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> skipNotice.setVisibility(View.GONE))
                    .start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

        preferenceStore = new PreferenceStore(this);
        sponsorBlockClient = new SponsorBlockClient();
        buildPlayer();
        setContentView(buildUi());
        applySystemInsets();
        configureBackNavigation();

        if (savedInstanceState != null) {
            activeUrl = savedInstanceState.getString(STATE_URL);
            pendingSeekMs = savedInstanceState.getLong(STATE_POSITION, C.TIME_UNSET);
            pendingPlayWhenReady = savedInstanceState.getBoolean(
                    STATE_PLAY_WHEN_READY,
                    true
            );
            if (activeUrl != null) {
                linkInput.setText(activeUrl);
                startPlayback(activeUrl, false);
            }
        } else if (!handleIntent(getIntent())) {
            updateProtectionText();
        }
        restoreBugReport(savedInstanceState);
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false);
                } else {
                    finishAfterTransition();
                }
            }
        });
    }

    private void configureWindow() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getColor(R.color.ink));
    }

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                true
        );
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    loadingIndicator.setVisibility(View.GONE);
                    playbackStatus.setText(statusForReadyVideo());
                    if (pendingSeekMs != C.TIME_UNSET) {
                        player.seekTo(pendingSeekMs);
                        pendingSeekMs = C.TIME_UNSET;
                        player.setPlayWhenReady(pendingPlayWhenReady);
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    playbackStatus.setText("Buffering…");
                } else if (playbackState == Player.STATE_ENDED) {
                    playbackStatus.setText("Finished");
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playerView.setKeepScreenOn(isPlaying);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                recordFailure(
                        "playback",
                        "Media3 code " + error.errorCode + " / " + deepestType(error)
                );
                if (!retriedAfterPlaybackFailure && activeUrl != null) {
                    retriedAfterPlaybackFailure = true;
                    pendingSeekMs = Math.max(0, player.getCurrentPosition());
                    pendingPlayWhenReady = true;
                    playbackStatus.setText("Refreshing the stream…");
                    startPlayback(activeUrl, true);
                    return;
                }
                showError(humanPlaybackError(error));
            }
        });
        playbackSourceFactory = new PlaybackSourceFactory(this);
        skipController = new SponsorSkipController(player, this::onSponsorSkipped);
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.ink));

        appContent = new LinearLayout(this);
        appContent.setOrientation(LinearLayout.VERTICAL);
        root.addView(appContent, matchParent());

        topBar = buildTopBar();
        appContent.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(32));
        scrollView.addView(body, matchParentWrap());
        appContent.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        playerContainer = buildPlayerContainer();
        body.addView(playerContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(216)
        ));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(2), dp(18), dp(2), dp(16));
        titleText = text("Paste or share a YouTube link", 22, getColor(R.color.text_primary));
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleText.setMaxLines(2);
        uploaderText = text(
                "Plyvanta plays the content stream directly and skips known sponsors.",
                14,
                getColor(R.color.text_secondary)
        );
        uploaderText.setPadding(0, dp(6), 0, 0);
        playbackStatus = text(getString(R.string.ready), 13, getColor(R.color.mint));
        playbackStatus.setPadding(0, dp(8), 0, 0);
        info.addView(titleText);
        info.addView(uploaderText);
        info.addView(playbackStatus);
        body.addView(info);

        body.addView(buildInputCard(), spacedCardParams());
        body.addView(buildProtectionCard(), spacedCardParams());

        errorCard = new LinearLayout(this);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setBackgroundResource(R.drawable.bg_card);
        errorCard.setPadding(dp(16), dp(14), dp(10), dp(8));
        errorCard.setVisibility(View.GONE);
        errorText = text("", 14, Color.rgb(255, 165, 150));
        errorCard.addView(errorText);
        Button reportIssue = textButton(getString(R.string.report_this_issue));
        reportIssue.setOnClickListener(view -> showBugReport(true, "", false, false));
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(wrap(), dp(42));
        reportParams.gravity = Gravity.END;
        errorCard.addView(reportIssue, reportParams);
        body.addView(errorCard, spacedCardParams());

        loadingIndicator = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleLarge
        );
        loadingIndicator.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.coral))
        );
        loadingIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.CENTER
        );
        root.addView(loadingIndicator, loadingParams);

        skipNotice = buildSkipNotice();
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        noticeParams.setMargins(dp(16), 0, dp(16), dp(22));
        root.addView(skipNotice, noticeParams);

        return root;
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(10), 0);
        bar.setBackgroundColor(getColor(R.color.ink));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_shield_play);
        logo.setContentDescription(getString(R.string.app_name));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        TextView name = text(getString(R.string.app_name), 19, getColor(R.color.text_primary));
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView tagline = text(
                getString(R.string.app_tagline),
                10,
                getColor(R.color.text_secondary)
        );
        brand.addView(name);
        brand.addView(tagline);
        bar.addView(brand, new LinearLayout.LayoutParams(0, wrap(), 1f));

        protectionStatus = text("PROTECTED", 11, getColor(R.color.mint));
        protectionStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        protectionStatus.setBackgroundResource(R.drawable.bg_status);
        protectionStatus.setGravity(Gravity.CENTER);
        protectionStatus.setPadding(dp(12), dp(7), dp(12), dp(7));
        bar.addView(protectionStatus);

        ImageButton settings = iconButton(R.drawable.ic_settings, getString(R.string.settings));
        settings.setOnClickListener(view -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private FrameLayout buildPlayerContainer() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setContentDescription("Video player");
        container.addView(playerView, matchParent());

        fullscreenButton = iconButton(
                R.drawable.ic_fullscreen,
                getString(R.string.fullscreen)
        );
        fullscreenButton.setBackgroundColor(0x88000000);
        fullscreenButton.setOnClickListener(view -> setFullscreen(!fullscreen));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(46),
                dp(46),
                Gravity.TOP | Gravity.END
        );
        params.setMargins(0, dp(8), dp(8), 0);
        container.addView(fullscreenButton, params);
        return container;
    }

    private LinearLayout buildInputCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);

        TextView label = text("PLAY A VIDEO", 11, getColor(R.color.text_secondary));
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(2), 0, 0, dp(10));
        card.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        linkInput = new EditText(this);
        linkInput.setHint(R.string.paste_link_hint);
        linkInput.setHintTextColor(getColor(R.color.text_secondary));
        linkInput.setTextColor(getColor(R.color.text_primary));
        linkInput.setTextSize(14);
        linkInput.setSingleLine(true);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkInput.setPadding(dp(14), 0, dp(10), 0);
        linkInput.setBackgroundResource(R.drawable.bg_input);
        linkInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                playInput();
                return true;
            }
            return false;
        });
        row.addView(linkInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button play = primaryButton(getString(R.string.play));
        play.setOnClickListener(view -> playInput());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(82), dp(52));
        playParams.setMargins(dp(10), 0, 0, 0);
        row.addView(play, playParams);
        card.addView(row);

        Button paste = textButton(getString(R.string.paste));
        paste.setOnClickListener(view -> pasteClipboard());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(wrap(), dp(42));
        pasteParams.gravity = Gravity.END;
        card.addView(paste, pasteParams);
        return card;
    }

    private LinearLayout buildProtectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.bg_card);

        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield_play);
        card.addView(shield, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, 0, 0);
        TextView heading = text(
                getString(R.string.protection_active),
                17,
                getColor(R.color.text_primary)
        );
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView direct = text(
                "✓  " + getString(R.string.direct_stream_detail),
                13,
                getColor(R.color.text_secondary)
        );
        direct.setPadding(0, dp(8), 0, 0);
        TextView sponsor = text(
                "✓  " + getString(R.string.sponsor_detail),
                13,
                getColor(R.color.text_secondary)
        );
        sponsor.setPadding(0, dp(5), 0, 0);
        copy.addView(heading);
        copy.addView(direct);
        copy.addView(sponsor);
        card.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));
        return card;
    }

    private LinearLayout buildSkipNotice() {
        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.HORIZONTAL);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(dp(16), dp(10), dp(8), dp(10));
        notice.setBackgroundResource(R.drawable.bg_card);
        notice.setVisibility(View.GONE);

        skipNoticeText = text("Sponsor skipped", 14, getColor(R.color.text_primary));
        notice.addView(skipNoticeText, new LinearLayout.LayoutParams(0, wrap(), 1f));
        Button undo = textButton(getString(R.string.undo));
        undo.setTextColor(getColor(R.color.mint));
        undo.setOnClickListener(view -> undoSponsorSkip());
        notice.addView(undo, new LinearLayout.LayoutParams(wrap(), dp(44)));
        return notice;
    }

    private void playInput() {
        String canonical = YouTubeUrlParser.canonicalize(linkInput.getText().toString());
        if (canonical == null) {
            recordFailure("link validation", "Invalid or unsupported YouTube link");
            showError(getString(R.string.invalid_link));
            return;
        }
        hideKeyboard();
        linkInput.setText(canonical);
        startPlayback(canonical, false);
    }

    private void pasteClipboard() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = manager.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = data.getItemAt(0).coerceToText(this);
        if (text != null) {
            linkInput.setText(text.toString());
            linkInput.setSelection(linkInput.length());
        }
    }

    private void startPlayback(String canonicalUrl, boolean retry) {
        String videoId = YouTubeUrlParser.extractVideoId(canonicalUrl);
        if (videoId == null) {
            recordFailure("link validation", "Invalid or unsupported YouTube link");
            showError(getString(R.string.invalid_link));
            return;
        }

        if (!retry) {
            clearFailure();
            sponsorLookupStatus = "Loading";
        }
        int generation = loadGeneration.incrementAndGet();
        activeUrl = canonicalUrl;
        activeVideo = null;
        errorCard.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.VISIBLE);
        playbackStatus.setText(retry ? "Refreshing the stream…" : getString(R.string.loading_video));
        if (!retry) {
            retriedAfterPlaybackFailure = false;
            pendingSeekMs = C.TIME_UNSET;
            pendingPlayWhenReady = true;
            player.stop();
            skipController.setSegments(List.of());
        }

        fetchSponsorSegments(videoId, generation);
        int maxHeight = preferenceStore.maxHeight();
        resolverExecutor.execute(() -> {
            try {
                ResolvedVideo resolved = videoResolver.resolve(canonicalUrl, maxHeight);
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get() || isFinishing()) {
                        return;
                    }
                    beginResolvedPlayback(resolved);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get() && !isFinishing()) {
                        recordFailure("video resolution", deepestType(error));
                        showError(humanResolveError(error));
                    }
                });
            }
        });
    }

    private void fetchSponsorSegments(String videoId, int generation) {
        List<String> categories = preferenceStore.enabledSponsorCategories();
        if (categories.isEmpty()) {
            sponsorLookupStatus = "Disabled in settings";
            skipController.setSegments(List.of());
            updateProtectionText();
            return;
        }

        sponsorLookupStatus = "Loading";
        CompletableFuture<List<SponsorSegment>> future =
                sponsorBlockClient.getSegments(videoId, categories);
        future.whenComplete((segments, error) -> runOnUiThread(() -> {
            if (generation != loadGeneration.get() || isFinishing()) {
                return;
            }
            if (error == null && segments != null) {
                sponsorLookupStatus = segments.isEmpty()
                        ? "Completed; no matching segments"
                        : "Completed; " + segments.size() + " segment(s)";
                skipController.setSegments(segments);
                protectionStatus.setText(
                        segments.isEmpty() ? "AD-FREE" : "AD-FREE • SPONSORS"
                );
            } else {
                sponsorLookupStatus = "Failed";
                protectionStatus.setText("AD-FREE");
            }
        }));
    }

    private void beginResolvedPlayback(ResolvedVideo resolved) {
        activeVideo = resolved;
        titleText.setText(resolved.getTitle());
        uploaderText.setText(
                resolved.getUploader().isEmpty() ? "YouTube" : resolved.getUploader()
        );
        playbackStatus.setText("Preparing ad-free playback…");
        MediaSource source = playbackSourceFactory.create(resolved);
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void onSponsorSkipped(SponsorSegment segment) {
        lastSkippedSegment = segment;
        long seconds = Math.max(
                1,
                Math.round(segment.getEndSeconds() - segment.getStartSeconds())
        );
        String category = friendlyCategory(segment.getCategory());
        skipNoticeText.setText(
                String.format(Locale.US, "Skipped %s • %ds", category, seconds)
        );
        skipNotice.animate().cancel();
        skipNotice.setAlpha(0f);
        skipNotice.setVisibility(View.VISIBLE);
        skipNotice.animate().alpha(1f).setDuration(160).start();
        mainHandler.removeCallbacks(hideSkipNotice);
        mainHandler.postDelayed(hideSkipNotice, 5_000);
    }

    private void undoSponsorSkip() {
        if (lastSkippedSegment == null) {
            return;
        }
        skipController.suppressTemporarily(lastSkippedSegment, 20_000);
        player.seekTo(lastSkippedSegment.getStartMillis());
        player.play();
        mainHandler.removeCallbacks(hideSkipNotice);
        hideSkipNotice.run();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView section = text("SKIP CATEGORIES", 11, getColor(R.color.text_secondary));
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(section);
        content.addView(settingSwitch(
                "Paid sponsors",
                "Crowdsourced paid-promotion segments",
                preferenceStore.skipSponsor(),
                preferenceStore::setSkipSponsor
        ));
        content.addView(settingSwitch(
                "Self promotion",
                "Creator merchandise and unpaid promotion",
                preferenceStore.skipSelfPromo(),
                preferenceStore::setSkipSelfPromo
        ));
        content.addView(settingSwitch(
                "Like and subscribe reminders",
                "Calls to like, subscribe, or comment",
                preferenceStore.skipInteraction(),
                preferenceStore::setSkipInteraction
        ));
        content.addView(settingSwitch(
                "Intros",
                "Intro animations and intermissions",
                preferenceStore.skipIntro(),
                preferenceStore::setSkipIntro
        ));
        content.addView(settingSwitch(
                "Outros",
                "End cards and credits",
                preferenceStore.skipOutro(),
                preferenceStore::setSkipOutro
        ));

        TextView qualityLabel = text("MAX VIDEO QUALITY", 11, getColor(R.color.text_secondary));
        qualityLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        qualityLabel.setPadding(0, dp(20), 0, dp(6));
        content.addView(qualityLabel);

        RadioGroup quality = new RadioGroup(this);
        int[] heights = {360, 720, 1080, 2160};
        for (int height : heights) {
            RadioButton choice = new RadioButton(this);
            choice.setText(height + "p");
            choice.setTextColor(getColor(R.color.text_primary));
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.coral)
            ));
            choice.setId(height);
            choice.setChecked(preferenceStore.maxHeight() == height);
            quality.addView(choice);
        }
        quality.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId > 0) {
                preferenceStore.setMaxHeight(checkedId);
            }
        });
        content.addView(quality);

        TextView supportLabel = text(
                getString(R.string.help_and_support),
                11,
                getColor(R.color.text_secondary)
        );
        supportLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        supportLabel.setPadding(0, dp(20), 0, dp(3));
        content.addView(supportLabel);
        Button reportBug = textButton(getString(R.string.start_report));
        content.addView(settingAction(
                getString(R.string.report_a_bug),
                getString(R.string.report_a_bug_detail),
                reportBug
        ));

        TextView about = text(
                "Uses SponsorBlock data licensed under CC BY-NC-SA 4.0 from "
                        + "https://sponsor.ajay.app/.\n\n"
                        + "Playback is powered by NewPipe Extractor under GPL-3.0. "
                        + "Plyvanta is not affiliated with YouTube or SponsorBlock. "
                        + "This unofficial player may need updates when YouTube changes.",
                12,
                getColor(R.color.text_secondary)
        );
        about.setPadding(0, dp(20), 0, dp(8));
        android.text.util.Linkify.addLinks(about, android.text.util.Linkify.WEB_URLS);
        about.setMovementMethod(LinkMovementMethod.getInstance());
        content.addView(about);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Plyvanta settings")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create();
        reportBug.setOnClickListener(view -> {
            dialog.dismiss();
            showBugReport(false, "", false, false);
        });
        dialog.setOnDismissListener(ignored -> refreshSponsorPreferences());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.coral));
        });
        dialog.show();
    }

    private View settingSwitch(
            String title,
            String detail,
            boolean checked,
            java.util.function.Consumer<Boolean> setter
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, getColor(R.color.text_primary));
        TextView detailView = text(detail, 12, getColor(R.color.text_secondary));
        copy.addView(titleView);
        copy.addView(detailView);
        row.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.coral)
        ));
        toggle.setOnCheckedChangeListener((button, value) -> setter.accept(value));
        row.addView(toggle);
        return row;
    }

    private View settingAction(String title, String detail, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, getColor(R.color.text_primary));
        TextView detailView = text(detail, 12, getColor(R.color.text_secondary));
        copy.addView(titleView);
        copy.addView(detailView);
        row.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));
        row.addView(action, new LinearLayout.LayoutParams(wrap(), dp(44)));
        return row;
    }

    private void showBugReport(
            boolean fromError,
            String draft,
            boolean includeDiagnosticsInitially,
            boolean includeVideoInitially
    ) {
        if (bugReportStep == BUG_REPORT_CLOSED) {
            bugReportTechnicalSnapshot = new LinkedHashMap<>(collectTechnicalDetails());
            bugReportVideoUrlSnapshot = activeUrl;
        } else if (bugReportTechnicalSnapshot == null) {
            bugReportTechnicalSnapshot = new LinkedHashMap<>(collectTechnicalDetails());
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView intro = text(
                getString(R.string.bug_report_intro),
                14,
                getColor(R.color.text_secondary)
        );
        intro.setPadding(0, 0, 0, dp(12));
        content.addView(intro);

        EditText description = new EditText(this);
        description.setHint(R.string.bug_description_hint);
        description.setHintTextColor(getColor(R.color.text_secondary));
        description.setTextColor(getColor(R.color.text_primary));
        description.setTextSize(14);
        description.setGravity(Gravity.TOP | Gravity.START);
        description.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        description.setSingleLine(false);
        description.setMinLines(5);
        description.setMaxLines(10);
        description.setPadding(dp(14), dp(12), dp(14), dp(12));
        description.setBackgroundResource(R.drawable.bg_input);
        description.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(DiagnosticReport.MAX_DESCRIPTION_CHARS)
        });
        String initialDraft = draft;
        if (initialDraft.isBlank() && fromError && lastFailureStage != null) {
            initialDraft = "Plyvanta showed an error during "
                    + lastFailureStage
                    + ".\n\nSteps to reproduce:\n1. ";
        }
        bugReportStep = BUG_REPORT_EDITING;
        bugReportFromError = fromError;
        bugReportDraft = initialDraft;
        bugReportIncludesDiagnostics = includeDiagnosticsInitially;
        bugReportIncludesVideo = includeVideoInitially;
        bugReportPreview = null;
        activeBugReportDescription = description;
        description.setText(initialDraft);
        description.setSelection(description.length());
        content.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        CheckBox includeDiagnostics = reportCheckBox(
                getString(R.string.include_diagnostics),
                includeDiagnosticsInitially
        );
        activeBugReportDiagnostics = includeDiagnostics;
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        checkParams.setMargins(0, dp(14), 0, 0);
        content.addView(includeDiagnostics, checkParams);
        TextView diagnosticsDetail = text(
                getString(R.string.include_diagnostics_detail),
                12,
                getColor(R.color.text_secondary)
        );
        diagnosticsDetail.setPadding(dp(34), 0, 0, dp(6));
        content.addView(diagnosticsDetail);

        CheckBox includeVideo = null;
        if (bugReportVideoUrlSnapshot != null) {
            includeVideo = reportCheckBox(
                    getString(R.string.include_video_link),
                    includeVideoInitially
            );
            content.addView(includeVideo);
            TextView videoDetail = text(
                    getString(R.string.include_video_link_detail),
                    12,
                    getColor(R.color.text_secondary)
            );
            videoDetail.setPadding(dp(34), 0, 0, dp(6));
            content.addView(videoDetail);
        }
        activeBugReportVideo = includeVideo;

        TextView consent = text(
                getString(R.string.bug_report_consent),
                12,
                getColor(R.color.mint)
        );
        consent.setPadding(0, dp(12), 0, dp(4));
        content.addView(consent);

        CheckBox finalIncludeVideo = includeVideo;
        boolean[] movingToPreview = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.report_a_bug)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.review_report, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.coral));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.text_secondary));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String userDescription = description.getText().toString().trim();
                if (userDescription.isEmpty()) {
                    description.setError(getString(R.string.describe_problem_first));
                    description.requestFocus();
                    return;
                }

                boolean includeTechnicalDetails = includeDiagnostics.isChecked();
                boolean includeCurrentVideo =
                        finalIncludeVideo != null && finalIncludeVideo.isChecked();
                bugReportDraft = userDescription;
                bugReportIncludesDiagnostics = includeTechnicalDetails;
                bugReportIncludesVideo = includeCurrentVideo;
                Map<String, String> technicalDetails = includeTechnicalDetails
                        ? new LinkedHashMap<>(bugReportTechnicalSnapshot)
                        : Map.of();
                String report = DiagnosticReport.format(
                        userDescription,
                        technicalDetails,
                        includeCurrentVideo ? bugReportVideoUrlSnapshot : null
                );
                bugReportPreview = report;
                bugReportStep = BUG_REPORT_REVIEWING;
                movingToPreview[0] = true;
                dialog.dismiss();
                showBugReportPreview(
                        fromError,
                        userDescription,
                        includeTechnicalDetails,
                        includeCurrentVideo,
                        report
                );
            });
        });
        dialog.setOnDismissListener(ignored -> {
            activeBugReportDescription = null;
            activeBugReportDiagnostics = null;
            activeBugReportVideo = null;
            if (!movingToPreview[0]) {
                clearBugReportState();
            }
        });
        dialog.show();
    }

    private CheckBox reportCheckBox(String label, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(getColor(R.color.text_primary));
        checkBox.setTextSize(14);
        checkBox.setChecked(checked);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.coral)
        ));
        return checkBox;
    }

    private void showBugReportPreview(
            boolean fromError,
            String description,
            boolean includedDiagnostics,
            boolean includedVideo,
            String report
    ) {
        bugReportStep = BUG_REPORT_REVIEWING;
        bugReportFromError = fromError;
        bugReportDraft = description;
        bugReportIncludesDiagnostics = includedDiagnostics;
        bugReportIncludesVideo = includedVideo;
        bugReportPreview = report;

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView notice = text(
                getString(R.string.bug_report_public_notice),
                13,
                getColor(R.color.text_secondary)
        );
        notice.setPadding(0, 0, 0, dp(12));
        content.addView(notice);

        String title = DiagnosticReport.suggestedTitle(description);
        TextView titleLabel = text(
                getString(R.string.issue_title_label),
                11,
                getColor(R.color.text_secondary)
        );
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(titleLabel);
        TextView titlePreview = text(title, 14, getColor(R.color.text_primary));
        titlePreview.setTextIsSelectable(true);
        titlePreview.setPadding(0, dp(4), 0, dp(12));
        content.addView(titlePreview);

        TextView preview = text(report, 12, getColor(R.color.text_primary));
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackgroundResource(R.drawable.bg_input);
        content.addView(preview);

        boolean[] movingToEditor = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.review_report)
                .setView(scroll)
                .setNegativeButton(R.string.edit_report, null)
                .setNeutralButton(R.string.share_report, null)
                .setPositiveButton(R.string.open_github, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button openGitHub = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            openGitHub.setTextColor(getColor(R.color.coral));
            openGitHub.setOnClickListener(view -> openGitHubIssue(title, report));
            Button share = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            share.setTextColor(getColor(R.color.mint));
            share.setOnClickListener(view -> shareBugReport(title, report));
            Button edit = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            edit.setTextColor(getColor(R.color.text_secondary));
            edit.setOnClickListener(view -> {
                movingToEditor[0] = true;
                bugReportStep = BUG_REPORT_EDITING;
                dialog.dismiss();
                showBugReport(
                        fromError,
                        description,
                        includedDiagnostics,
                        includedVideo
                );
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (!movingToEditor[0]) {
                clearBugReportState();
            }
        });
        dialog.show();
    }

    private void openGitHubIssue(String title, String report) {
        Uri issueUri = Uri.parse(BUG_REPORT_URL)
                .buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", report)
                .build();
        if (issueUri.toString().length() > MAX_GITHUB_PREFILL_URI_CHARS) {
            Toast.makeText(
                    this,
                    R.string.long_report_share_fallback,
                    Toast.LENGTH_LONG
            ).show();
            shareBugReport(title, report);
            return;
        }
        Intent openGitHub = new Intent(Intent.ACTION_VIEW, issueUri);
        Intent chooser = Intent.createChooser(
                openGitHub,
                getString(R.string.open_github_with)
        );
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException error) {
            shareBugReport(title, report);
        }
    }

    private void shareBugReport(String title, String report) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, report);
        Intent chooser = Intent.createChooser(
                share,
                getString(R.string.bug_report_share_title)
        );
        chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{getComponentName()}
        );
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                    this,
                    R.string.no_app_for_bug_report,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private Map<String, String> collectTechnicalDetails() {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("App version", installedVersion());
        details.put("Package", getPackageName());
        details.put(
                "Build",
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        ? "Debug"
                        : "Release"
        );
        details.put(
                "Android",
                Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        );
        details.put("Device", deviceName());
        details.put(
                "Locale",
                getResources().getConfiguration().getLocales().get(0).toLanguageTag()
        );
        details.put("Orientation", orientationName());
        details.put("Quality limit", preferenceStore.maxHeight() + "p");
        details.put("Sponsor categories", enabledSponsorCategories());
        details.put("Sponsor lookup", sponsorLookupStatus);
        details.put("Player state", playerStateName());
        details.put("Resolved stream", resolvedStreamName());
        details.put("Playback retry attempted", retriedAfterPlaybackFailure ? "Yes" : "No");
        details.put(
                "Last failure",
                lastFailureStage == null
                        ? "None recorded this session"
                        : lastFailureStage + " / " + lastFailureType
        );
        return details;
    }

    private String installedVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            String versionName = info.versionName == null ? "Unknown" : info.versionName;
            return versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException error) {
            return "Unknown";
        }
    }

    private static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model.isEmpty() ? "Unknown" : model;
        }
        String combined = (manufacturer + " " + model).trim();
        return combined.isEmpty() ? "Unknown" : combined;
    }

    private String enabledSponsorCategories() {
        List<String> categories = preferenceStore.enabledSponsorCategories();
        if (categories.isEmpty()) {
            return "None";
        }
        StringBuilder label = new StringBuilder();
        for (String category : categories) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(friendlyCategory(category));
        }
        return label.toString();
    }

    private String orientationName() {
        return switch (getResources().getConfiguration().orientation) {
            case Configuration.ORIENTATION_LANDSCAPE -> "Landscape";
            case Configuration.ORIENTATION_PORTRAIT -> "Portrait";
            case Configuration.ORIENTATION_SQUARE -> "Square";
            case Configuration.ORIENTATION_UNDEFINED -> "Unknown";
            default -> "Unknown";
        };
    }

    private String playerStateName() {
        String state = switch (player.getPlaybackState()) {
            case Player.STATE_IDLE -> "Idle";
            case Player.STATE_BUFFERING -> "Buffering";
            case Player.STATE_READY -> "Ready";
            case Player.STATE_ENDED -> "Ended";
            default -> "Unknown";
        };
        if (player.isPlaying()) {
            return state + " / playing";
        }
        if (player.getPlayWhenReady()) {
            return state + " / waiting";
        }
        return state + " / paused";
    }

    private String resolvedStreamName() {
        if (activeVideo == null) {
            return activeUrl == null ? "None" : "Not resolved";
        }
        int height = activeVideo.getSelectedHeight();
        String quality = height > 0 ? " / " + height + "p" : "";
        return activeVideo.getSourceType().name().toLowerCase(Locale.US) + quality;
    }

    private void recordFailure(String stage, String type) {
        lastFailureStage = stage;
        lastFailureType = type;
    }

    private void clearFailure() {
        lastFailureStage = null;
        lastFailureType = null;
    }

    private void restoreBugReport(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        int savedStep = savedInstanceState.getInt(
                STATE_BUG_REPORT_STEP,
                BUG_REPORT_CLOSED
        );
        if (savedStep != BUG_REPORT_EDITING && savedStep != BUG_REPORT_REVIEWING) {
            return;
        }

        String savedDraft = savedInstanceState.getString(STATE_BUG_REPORT_DRAFT, "");
        boolean savedDiagnostics =
                savedInstanceState.getBoolean(STATE_BUG_REPORT_DIAGNOSTICS, false);
        boolean savedVideo = savedInstanceState.getBoolean(STATE_BUG_REPORT_VIDEO, false);
        boolean savedFromError =
                savedInstanceState.getBoolean(STATE_BUG_REPORT_FROM_ERROR, false);
        String savedPreview = savedInstanceState.getString(STATE_BUG_REPORT_PREVIEW);
        String savedVideoUrl = savedInstanceState.getString(STATE_BUG_REPORT_VIDEO_URL);
        ArrayList<String> detailKeys =
                savedInstanceState.getStringArrayList(STATE_BUG_REPORT_DETAIL_KEYS);
        ArrayList<String> detailValues =
                savedInstanceState.getStringArrayList(STATE_BUG_REPORT_DETAIL_VALUES);
        LinkedHashMap<String, String> savedTechnicalSnapshot = new LinkedHashMap<>();
        if (detailKeys != null && detailValues != null) {
            int detailCount = Math.min(detailKeys.size(), detailValues.size());
            for (int index = 0; index < detailCount; index++) {
                savedTechnicalSnapshot.put(detailKeys.get(index), detailValues.get(index));
            }
        }
        bugReportStep = savedStep;
        bugReportDraft = savedDraft;
        bugReportIncludesDiagnostics = savedDiagnostics;
        bugReportIncludesVideo = savedVideo;
        bugReportFromError = savedFromError;
        bugReportPreview = savedPreview;
        bugReportVideoUrlSnapshot = savedVideoUrl;
        bugReportTechnicalSnapshot = savedTechnicalSnapshot.isEmpty()
                ? null
                : savedTechnicalSnapshot;
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (savedStep == BUG_REPORT_REVIEWING && savedPreview != null) {
                showBugReportPreview(
                        savedFromError,
                        savedDraft,
                        savedDiagnostics,
                        savedVideo,
                        savedPreview
                );
            } else {
                showBugReport(
                        savedFromError,
                        savedDraft,
                        savedDiagnostics,
                        savedVideo
                );
            }
        });
    }

    private void captureBugReportEditorState() {
        if (bugReportStep != BUG_REPORT_EDITING) {
            return;
        }
        if (activeBugReportDescription != null) {
            bugReportDraft = activeBugReportDescription.getText().toString();
        }
        if (activeBugReportDiagnostics != null) {
            bugReportIncludesDiagnostics = activeBugReportDiagnostics.isChecked();
        }
        bugReportIncludesVideo =
                activeBugReportVideo != null && activeBugReportVideo.isChecked();
    }

    private void clearBugReportState() {
        bugReportStep = BUG_REPORT_CLOSED;
        bugReportDraft = "";
        bugReportIncludesDiagnostics = false;
        bugReportIncludesVideo = false;
        bugReportFromError = false;
        bugReportPreview = null;
        bugReportTechnicalSnapshot = null;
        bugReportVideoUrlSnapshot = null;
        activeBugReportDescription = null;
        activeBugReportDiagnostics = null;
        activeBugReportVideo = null;
    }

    private void refreshSponsorPreferences() {
        updateProtectionText();
        if (activeVideo != null) {
            fetchSponsorSegments(activeVideo.getVideoId(), loadGeneration.get());
        }
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String candidate = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            candidate = intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            candidate = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        String canonical = YouTubeUrlParser.canonicalize(candidate);
        if (canonical == null) {
            return false;
        }
        linkInput.setText(canonical);
        startPlayback(canonical, false);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        skipController.start();
    }

    @Override
    protected void onStop() {
        skipController.stop();
        if (player.isPlaying()) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureBugReportEditorState();
        outState.putString(STATE_URL, activeUrl);
        outState.putLong(STATE_POSITION, player.getCurrentPosition());
        outState.putBoolean(STATE_PLAY_WHEN_READY, player.getPlayWhenReady());
        outState.putInt(STATE_BUG_REPORT_STEP, bugReportStep);
        if (bugReportStep != BUG_REPORT_CLOSED) {
            outState.putString(STATE_BUG_REPORT_DRAFT, bugReportDraft);
            outState.putBoolean(
                    STATE_BUG_REPORT_DIAGNOSTICS,
                    bugReportIncludesDiagnostics
            );
            outState.putBoolean(STATE_BUG_REPORT_VIDEO, bugReportIncludesVideo);
            outState.putBoolean(STATE_BUG_REPORT_FROM_ERROR, bugReportFromError);
            outState.putString(STATE_BUG_REPORT_PREVIEW, bugReportPreview);
            outState.putString(STATE_BUG_REPORT_VIDEO_URL, bugReportVideoUrlSnapshot);
            if (bugReportTechnicalSnapshot != null) {
                outState.putStringArrayList(
                        STATE_BUG_REPORT_DETAIL_KEYS,
                        new ArrayList<>(bugReportTechnicalSnapshot.keySet())
                );
                outState.putStringArrayList(
                        STATE_BUG_REPORT_DETAIL_VALUES,
                        new ArrayList<>(bugReportTechnicalSnapshot.values())
                );
            }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fullscreen && newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen(false);
        }
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled) {
            return;
        }
        fullscreen = enabled;
        if (enabled) {
            originalPlayerParent = (ViewGroup) playerContainer.getParent();
            originalPlayerIndex = originalPlayerParent.indexOfChild(playerContainer);
            originalPlayerLayoutParams = playerContainer.getLayoutParams();
            originalPlayerParent.removeView(playerContainer);
            appContent.setVisibility(View.GONE);
            root.addView(playerContainer, matchParent());
            fullscreenButton.setContentDescription(getString(R.string.exit_fullscreen));
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemBars();
        } else {
            ((ViewGroup) playerContainer.getParent()).removeView(playerContainer);
            originalPlayerParent.addView(
                    playerContainer,
                    originalPlayerIndex,
                    originalPlayerLayoutParams
            );
            appContent.setVisibility(View.VISIBLE);
            fullscreenButton.setContentDescription(getString(R.string.fullscreen));
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            showSystemBars();
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void applySystemInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(dp(18), bars.top, dp(10), 0);
                ViewGroup.LayoutParams topParams = topBar.getLayoutParams();
                topParams.height = dp(58) + bars.top;
                topBar.setLayoutParams(topParams);
                appContent.setPadding(0, 0, 0, bars.bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showError(String message) {
        loadingIndicator.setVisibility(View.GONE);
        playbackStatus.setText("Unable to play");
        errorText.setText(message);
        errorCard.setVisibility(View.VISIBLE);
    }

    private String statusForReadyVideo() {
        if (activeVideo == null) {
            return "Ad-free playback";
        }
        int height = activeVideo.getSelectedHeight();
        return height > 0
                ? "Ad-free • " + height + "p"
                : "Ad-free playback";
    }

    private void updateProtectionText() {
        protectionStatus.setText(
                preferenceStore.enabledSponsorCategories().isEmpty()
                        ? "AD-FREE"
                        : "AD-FREE • SPONSORS"
        );
    }

    private String humanResolveError(Throwable error) {
        String message = deepestMessage(error);
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("age") || lower.contains("sign in")) {
            return "This video requires sign-in or age verification, which Plyvanta does not "
                    + "store or request.";
        }
        if (lower.contains("private") || lower.contains("members")) {
            return "This video is private or members-only.";
        }
        if (lower.contains("region") || lower.contains("country")) {
            return "This video is not available in your region.";
        }
        return "Plyvanta could not resolve this video. YouTube sometimes changes its playback "
                + "format; try again or update the app.\n\n" + message;
    }

    private String humanPlaybackError(PlaybackException error) {
        String detail = deepestMessage(error);
        return getString(R.string.playback_error)
                + " The stream may have expired or be restricted.\n\n"
                + detail;
    }

    private static String deepestMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String deepestType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static String friendlyCategory(String category) {
        return switch (category) {
            case "selfpromo" -> "self-promotion";
            case "interaction" -> "subscribe reminder";
            case "intro" -> "intro";
            case "outro" -> "outro";
            default -> "sponsor";
        };
    }

    private void hideKeyboard() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        return button;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(getColor(R.color.ink));
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_primary_button);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private Button textButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(getColor(R.color.coral));
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(0);
        button.setMinWidth(0);
        return button;
    }

    private LinearLayout.LayoutParams spacedCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private FrameLayout.LayoutParams matchParentWrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
