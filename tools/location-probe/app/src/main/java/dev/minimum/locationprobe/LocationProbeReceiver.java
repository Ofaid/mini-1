package dev.minimum.locationprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Returns the latest private probe snapshot to an explicit ADB broadcast. */
public final class LocationProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String result = context.getSharedPreferences(
                LocationProbeActivity.PREFS, Context.MODE_PRIVATE)
                .getString(LocationProbeActivity.RESULT, "{}");
        setResultCode(Activity.RESULT_OK);
        setResultData(result);
    }
}
