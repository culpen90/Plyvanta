package app.plyvanta;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.List;
import java.util.Locale;
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
import app.plyvanta.util.YouTubeUrlParser;

@UnstableApi
public final class MainActivity extends ComponentActivity {
    private static final String STATE_URL = "active_url";
    private static final String STATE_POSITION = "playback_position";
    private static final String STATE_PLAY_WHEN_READY = "play_when_ready";

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

        errorText = text("", 14, Color.rgb(255, 165, 150));
        errorText.setBackgroundResource(R.drawable.bg_card);
        errorText.setPadding(dp(16), dp(14), dp(16), dp(14));
        errorText.setVisibility(View.GONE);
        body.addView(errorText, spacedCardParams());

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
            showError(getString(R.string.invalid_link));
            return;
        }

        int generation = loadGeneration.incrementAndGet();
        activeUrl = canonicalUrl;
        activeVideo = null;
        errorText.setVisibility(View.GONE);
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
                        showError(humanResolveError(error));
                    }
                });
            }
        });
    }

    private void fetchSponsorSegments(String videoId, int generation) {
        List<String> categories = preferenceStore.enabledSponsorCategories();
        if (categories.isEmpty()) {
            skipController.setSegments(List.of());
            updateProtectionText();
            return;
        }

        CompletableFuture<List<SponsorSegment>> future =
                sponsorBlockClient.getSegments(videoId, categories);
        future.whenComplete((segments, error) -> runOnUiThread(() -> {
            if (generation != loadGeneration.get() || isFinishing()) {
                return;
            }
            if (error == null && segments != null) {
                skipController.setSegments(segments);
                protectionStatus.setText(
                        segments.isEmpty() ? "AD-FREE" : "AD-FREE • SPONSORS"
                );
            } else {
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
        super.onSaveInstanceState(outState);
        outState.putString(STATE_URL, activeUrl);
        outState.putLong(STATE_POSITION, player.getCurrentPosition());
        outState.putBoolean(STATE_PLAY_WHEN_READY, player.getPlayWhenReady());
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
        errorText.setVisibility(View.VISIBLE);
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
