/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jackpal.androidterm.util.TermSettings;
import jackpal.androidterm.util.ThemeMode;

public class TermPreferences extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static class TermPreferencesFragment extends PreferenceFragmentCompat {
        private ActivityResultLauncher<Intent> mFontPickerLauncher;
        private Preference mFontFilePreference;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mFontPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    copyFontFile(result.getData().getData());
                }
            });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            mFontFilePreference = findPreference("font_file");
            mFontFilePreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"});
                mFontPickerLauncher.launch(intent);
                return true;
            });
            findPreference("font_reset").setOnPreferenceClickListener(preference -> {
                resetFontFile();
                return true;
            });
            updateFontSummary();
        }

        private void copyFontFile(Uri uri) {
            String displayName = null;
            try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            }
            String lowerName = displayName.toLowerCase(java.util.Locale.ROOT);
            String extension = lowerName.endsWith(".otf") ? ".otf" : ".ttf";
            if (!lowerName.endsWith(".ttf") && !lowerName.endsWith(".otf")) {
                Toast.makeText(requireContext(), R.string.font_file_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            File target = new File(requireContext().getFilesDir(), "custom_font" + extension);
            try (InputStream input = requireContext().getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException e) {
                Toast.makeText(requireContext(), R.string.font_file_error, Toast.LENGTH_LONG).show();
                return;
            }
            getPreferenceManager().getSharedPreferences().edit().putString(TermSettings.FONT_PATH_KEY, target.getAbsolutePath()).apply();
            updateFontSummary();
        }

        private void resetFontFile() {
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            String fontPath = prefs.getString(TermSettings.FONT_PATH_KEY, "");
            // Clear the reference synchronously before deleting the
            // file, so no reader can ever see a path whose file is
            // already gone.
            prefs.edit().remove(TermSettings.FONT_PATH_KEY).commit();
            if (!fontPath.isEmpty()) {
                new File(fontPath).delete();
            }
            updateFontSummary();
        }

        private void updateFontSummary() {
            String fontPath = getPreferenceManager().getSharedPreferences().getString(TermSettings.FONT_PATH_KEY, "");
            mFontFilePreference.setSummary(fontPath.isEmpty() ? getString(R.string.summary_font_file_preference) : new File(fontPath).getName());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeMode.apply(this);

        setContentView(R.layout.term_preferences_activity);
        jackpal.androidterm.util.EdgeToEdge.apply(this, findViewById(R.id.prefs_root));
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.preferences_container, new TermPreferencesFragment()).commit();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (ThemeMode.PREF_THEME_MODE.equals(key) || ThemeMode.PREF_DYNAMIC_COLOR.equals(key)) {
            ThemeMode.apply(this);
        }
        if (FloatingBubbleService.PREF_FLOATING_BUBBLE.equals(key)) {
            if (!sharedPreferences.getBoolean(key, true)) {
                stopService(new android.content.Intent(this, FloatingBubbleService.class));
            } else if (!FloatingBubbleService.canDrawOverlays(this)) {
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }
}
