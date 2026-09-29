package se.lublin.mumla.app;

import static androidx.appcompat.app.AppCompatDelegate.setApplicationLocales;
import static androidx.core.os.LocaleListCompat.forLanguageTags;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;
import static se.lublin.mumla.Settings.PREF_LANGUAGE;
import static se.lublin.mumla.Settings.PREF_THEME;

import android.app.Application;
import android.content.SharedPreferences;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import se.lublin.mumla.radio.DeviceIdentityManager;
import se.lublin.mumla.radio.RadioConfigUpdater;
import se.lublin.mumla.radio.RadioLauncherShortcutInstaller;
import se.lublin.mumla.radio.RadioPttKeyManager;

public class MumlaApplication extends Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        // Create the radio identity before either the Activity or service needs it. This keeps
        // the identity stable across normal app launches and makes it available to config code.
        new DeviceIdentityManager(preferences).getOrCreateDeviceId();
        removeLegacyDeviceCredential();
        RadioPttKeyManager.applyDefaults(preferences);
        RadioLauncherShortcutInstaller.ensureInstalled(this, preferences, false);
        RadioConfigUpdater.start(this);
        applyTheme(preferences);
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    /** Removes the retired bearer token after upgrading from credential-based provisioning. */
    private void removeLegacyDeviceCredential() {
        File root = getNoBackupFilesDir();
        if (root == null) {
            return;
        }
        File directory = new File(root, "radio-config");
        new File(directory, "device-config-credential").delete();
        new File(directory, "device-config-credential.tmp").delete();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case PREF_LANGUAGE:
                String language = preferences.getString(PREF_LANGUAGE, "system");
                setApplicationLocales(language.equals("system") ? getEmptyLocaleList() : forLanguageTags(language));
                break;
            case PREF_THEME:
                applyTheme(preferences);
                break;
        }
    }

    private static void applyTheme(SharedPreferences preferences) {
        // Minimum starts in dark mode unless the user has explicitly selected another theme.
        switch (preferences.getString(PREF_THEME, "forceDark")) {
            case "forceLight":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "forceDark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                preferences.edit().putString(PREF_THEME, "forceDark").apply();
                break;
        }
    }
}
