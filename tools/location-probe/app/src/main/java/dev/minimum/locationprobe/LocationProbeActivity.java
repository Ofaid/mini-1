package dev.minimum.locationprobe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;

import org.json.JSONObject;

/** Temporary API-22 probe used only during supervised provisioning diagnostics. */
@SuppressWarnings("deprecation")
public final class LocationProbeActivity extends Activity
        implements LocationListener, GpsStatus.Listener {
    static final String PREFS = "probe";
    static final String RESULT = "result";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private LocationManager manager;
    private Location gpsLocation;
    private Location networkLocation;
    private int satellites;
    private int almanac;
    private int ephemeris;
    private int usedInFix;
    private float maxSnr;
    private long startedElapsed;

    private final Runnable snapshot = new Runnable() {
        @Override
        public void run() {
            persist();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("Testing GPS and network location...");
        setContentView(status);
        startedElapsed = SystemClock.elapsedRealtime();
        manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            persistError("location-service-unavailable");
            return;
        }
        try {
            manager.addGpsStatusListener(this);
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this);
            }
            persist();
            handler.post(snapshot);
        } catch (SecurityException error) {
            persistError("location-permission-denied");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(snapshot);
        if (manager != null) {
            manager.removeUpdates(this);
            manager.removeGpsStatusListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        synchronized (lock) {
            if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                gpsLocation = new Location(location);
            } else if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
                networkLocation = new Location(location);
            }
            persistLocked();
        }
    }

    @Override public void onProviderEnabled(String provider) { persist(); }
    @Override public void onProviderDisabled(String provider) { persist(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { persist(); }

    @Override
    public void onGpsStatusChanged(int event) {
        if (manager == null) {
            return;
        }
        GpsStatus status = manager.getGpsStatus(null);
        int satelliteCount = 0;
        int almanacCount = 0;
        int ephemerisCount = 0;
        int usedCount = 0;
        float strongestSnr = 0f;
        for (GpsSatellite satellite : status.getSatellites()) {
            satelliteCount++;
            if (satellite.hasAlmanac()) almanacCount++;
            if (satellite.hasEphemeris()) ephemerisCount++;
            if (satellite.usedInFix()) usedCount++;
            strongestSnr = Math.max(strongestSnr, satellite.getSnr());
        }
        synchronized (lock) {
            satellites = satelliteCount;
            almanac = almanacCount;
            ephemeris = ephemerisCount;
            usedInFix = usedCount;
            maxSnr = strongestSnr;
            persistLocked();
        }
    }

    private void persist() {
        synchronized (lock) {
            persistLocked();
        }
    }

    private void persistLocked() {
        try {
            JSONObject result = new JSONObject();
            result.put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed);
            result.put("gpsEnabled", manager != null
                    && manager.isProviderEnabled(LocationManager.GPS_PROVIDER));
            result.put("networkEnabled", manager != null
                    && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            result.put("satellites", satellites);
            result.put("almanac", almanac);
            result.put("ephemeris", ephemeris);
            result.put("usedInFix", usedInFix);
            result.put("maxSnr", maxSnr);
            addLocation(result, "gps", gpsLocation);
            addLocation(result, "network", networkLocation);
            preferences().edit().putString(RESULT, result.toString()).commit();
        } catch (Exception error) {
            persistError("probe-snapshot-failed");
        }
    }

    private static void addLocation(JSONObject result, String name, Location location)
            throws Exception {
        result.put(name + "Fix", location != null);
        if (location != null) {
            result.put(name + "Latitude", location.getLatitude());
            result.put(name + "Longitude", location.getLongitude());
            result.put(name + "AccuracyM", location.hasAccuracy() ? location.getAccuracy() : -1f);
            result.put(name + "AgeMs", Math.max(0L, System.currentTimeMillis() - location.getTime()));
        }
    }

    private void persistError(String error) {
        try {
            JSONObject result = new JSONObject();
            result.put("error", error);
            preferences().edit().putString(RESULT, result.toString()).commit();
        } catch (Exception ignored) {
            // The report receiver will return an empty object if persistence also fails.
        }
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
