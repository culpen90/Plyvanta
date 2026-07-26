package app.plyvanta.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PreferenceStore {
    private static final String FILE_NAME = "plyvanta_preferences";
    private static final String KEY_SPONSOR = "skip_sponsor";
    private static final String KEY_SELF_PROMO = "skip_self_promo";
    private static final String KEY_INTERACTION = "skip_interaction";
    private static final String KEY_INTRO = "skip_intro";
    private static final String KEY_OUTRO = "skip_outro";
    private static final String KEY_MAX_HEIGHT = "max_height";

    private final SharedPreferences preferences;

    public PreferenceStore(Context context) {
        preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public boolean skipSponsor() {
        return preferences.getBoolean(KEY_SPONSOR, true);
    }

    public void setSkipSponsor(boolean value) {
        preferences.edit().putBoolean(KEY_SPONSOR, value).apply();
    }

    public boolean skipSelfPromo() {
        return preferences.getBoolean(KEY_SELF_PROMO, false);
    }

    public void setSkipSelfPromo(boolean value) {
        preferences.edit().putBoolean(KEY_SELF_PROMO, value).apply();
    }

    public boolean skipInteraction() {
        return preferences.getBoolean(KEY_INTERACTION, false);
    }

    public void setSkipInteraction(boolean value) {
        preferences.edit().putBoolean(KEY_INTERACTION, value).apply();
    }

    public boolean skipIntro() {
        return preferences.getBoolean(KEY_INTRO, false);
    }

    public void setSkipIntro(boolean value) {
        preferences.edit().putBoolean(KEY_INTRO, value).apply();
    }

    public boolean skipOutro() {
        return preferences.getBoolean(KEY_OUTRO, false);
    }

    public void setSkipOutro(boolean value) {
        preferences.edit().putBoolean(KEY_OUTRO, value).apply();
    }

    public int maxHeight() {
        return preferences.getInt(KEY_MAX_HEIGHT, 1080);
    }

    public void setMaxHeight(int value) {
        preferences.edit().putInt(KEY_MAX_HEIGHT, value).apply();
    }

    public List<String> enabledSponsorCategories() {
        List<String> categories = new ArrayList<>();
        if (skipSponsor()) {
            categories.add("sponsor");
        }
        if (skipSelfPromo()) {
            categories.add("selfpromo");
        }
        if (skipInteraction()) {
            categories.add("interaction");
        }
        if (skipIntro()) {
            categories.add("intro");
        }
        if (skipOutro()) {
            categories.add("outro");
        }
        return Collections.unmodifiableList(categories);
    }
}
