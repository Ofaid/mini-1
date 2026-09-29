/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import se.lublin.mumla.R;
import se.lublin.mumla.app.MumlaActivity;

/** Installs the legacy Launcher3 recovery shortcut used by API-22 radio firmware. */
public final class RadioLauncherShortcutInstaller {
    public static final String ACTION_PROVISION_SHORTCUT =
            "se.lublin.mumla.action.PROVISION_LAUNCHER_SHORTCUT";
    private static final String PREF_SHORTCUT_REQUESTED = "radio_launcher_shortcut_requested";
    private static final String INSTALL_SHORTCUT_ACTION =
            "com.android.launcher.action.INSTALL_SHORTCUT";

    private RadioLauncherShortcutInstaller() {
    }

    public static void ensureInstalled(Context context, SharedPreferences preferences, boolean force) {
        String profile = RadioDeviceProfile.detectCurrent();
        if (!RadioPttKeyManager.isRadioProfile(profile)) {
            return;
        }
        if (!force && preferences.getBoolean(PREF_SHORTCUT_REQUESTED, false)) {
            return;
        }

        Intent launchMinimum = new Intent(context, MumlaActivity.class);
        launchMinimum.setAction(Intent.ACTION_MAIN);
        launchMinimum.addCategory(Intent.CATEGORY_LAUNCHER);
        launchMinimum.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        Intent installShortcut = new Intent(INSTALL_SHORTCUT_ACTION);
        installShortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchMinimum);
        installShortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, context.getString(R.string.app_name));
        installShortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(context, R.mipmap.ic_launcher));
        installShortcut.putExtra("duplicate", false);
        context.sendBroadcast(installShortcut);
        preferences.edit().putBoolean(PREF_SHORTCUT_REQUESTED, true).apply();
    }
}
