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

import se.lublin.mumla.Settings;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.preference.MumlaCertificateGenerateTask;

/** Ensures the radio path has client identity material without showing a first-run dialog. */
public final class RadioCertificateManager {
    private RadioCertificateManager() {
    }

    public interface Callback {
        void onComplete(boolean available);
    }

    public static void ensureCertificate(Context context, Callback callback) {
        Context applicationContext = context.getApplicationContext();
        Settings settings = Settings.getInstance(applicationContext);
        if (settings.isUsingCertificate()) {
            callback.onComplete(true);
            return;
        }

        new MumlaCertificateGenerateTask(applicationContext, false) {
            @Override
            protected void onPostExecute(DatabaseCertificate result) {
                super.onPostExecute(result);
                if (result != null) {
                    settings.setDefaultCertificateId(result.getId());
                    settings.setFirstRun(false);
                }
                callback.onComplete(result != null);
            }
        }.execute();
    }
}
