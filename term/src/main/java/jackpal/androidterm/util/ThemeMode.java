package jackpal.androidterm.util;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;

public class ThemeMode {
    public static final String PREF_THEME_MODE = "theme_mode";
    public static final String PREF_DYNAMIC_COLOR = "dynamic_color";

    public static void apply(Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        String mode = prefs.getString(PREF_THEME_MODE, "system");
        int night_mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (mode.equals("light")) {
            night_mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if (mode.equals("dark")) {
            night_mode = AppCompatDelegate.MODE_NIGHT_YES;
        }
        AppCompatDelegate.setDefaultNightMode(night_mode);
        if (prefs.getBoolean(PREF_DYNAMIC_COLOR, false)) {
            DynamicColors.applyToActivityIfAvailable(activity);
        }
    }
}
