package cz.teacherfriend.redpen;

import android.content.Context;
import android.content.SharedPreferences;

/** Uživatelské nastavení aplikace. */
public final class Prefs {

    public static final String[] MODEL_IDS = {"claude-opus-5", "claude-sonnet-5"};
    public static final String[] MODEL_LABELS = {
            "Claude Opus 5 – nejpřesnější",
            "Claude Sonnet 5 – rychlejší a levnější"
    };

    public static final String[] SCALE_IDS = {"czech5", "percent", "points", "none"};
    public static final String[] SCALE_LABELS = {
            "Známka 1–5 (česká škola)",
            "Procenta",
            "Body",
            "Bez známky, jen opravy"
    };

    public static final String[] STRICTNESS_IDS = {"lenient", "standard", "strict"};
    public static final String[] STRICTNESS_LABELS = {"Mírná", "Běžná", "Přísná"};

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String apiKey() { return sp.getString("api_key", "").trim(); }
    public String model() { return sp.getString("model", MODEL_IDS[0]); }
    public String scale() { return sp.getString("scale", SCALE_IDS[0]); }
    public String strictness() { return sp.getString("strictness", "standard"); }
    public String language() { return sp.getString("language", "čeština"); }
    public boolean numbers() { return sp.getBoolean("numbers", true); }
    public boolean checks() { return sp.getBoolean("checks", true); }
    public boolean summaryPage() { return sp.getBoolean("summary_page", true); }

    public void save(String apiKey, String model, String scale, String strictness, String language,
                     boolean numbers, boolean checks, boolean summaryPage) {
        sp.edit()
                .putString("api_key", apiKey.trim())
                .putString("model", model)
                .putString("scale", scale)
                .putString("strictness", strictness)
                .putString("language", language.trim().isEmpty() ? "čeština" : language.trim())
                .putBoolean("numbers", numbers)
                .putBoolean("checks", checks)
                .putBoolean("summary_page", summaryPage)
                .apply();
    }

    static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }
}
