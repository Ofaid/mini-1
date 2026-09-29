/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

/** Notification policy that keeps managed-radio operation free of transient heads-up alerts. */
public final class RadioNotificationPolicy {
    private RadioNotificationPolicy() {
    }

    public static boolean shouldShowChatNotification(boolean chatNotificationsEnabled,
                                                     boolean managedRadioDevice) {
        return chatNotificationsEnabled && !managedRadioDevice;
    }
}
