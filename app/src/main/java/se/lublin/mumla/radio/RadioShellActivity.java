/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.ServerConnectTask;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.MumlaTrustStore;

/** Single-screen radio client driven by the Last Known Good Minimum config. */
public final class RadioShellActivity extends AppCompatActivity {
    private static final String TAG = RadioShellActivity.class.getSimpleName();
    public static final String EXTRA_CONNECT_ON_PTT =
            "se.lublin.mumla.extra.CONNECT_ON_PTT";
    public static final String EXTRA_TOGGLE_IDENTITY =
            "se.lublin.mumla.extra.TOGGLE_IDENTITY";
    private static final int MICROPHONE_PERMISSION_REQUEST = 73;
    private static final int COLOR_READY = Color.rgb(5, 48, 38);
    private static final int COLOR_RX = Color.rgb(0, 60, 68);
    private static final int COLOR_BUSY = Color.rgb(82, 50, 0);
    private static final int COLOR_TX = Color.rgb(92, 7, 20);
    private static final int COLOR_OFFLINE = Color.rgb(25, 28, 33);
    private static final int COLOR_ERROR = Color.rgb(82, 8, 18);
    private static final int COLOR_CHANNEL_BADGE = Color.rgb(244, 194, 64);
    private static final int COLOR_CHANNEL_TEXT = Color.rgb(25, 28, 33);
    private static final String AUTOMATION_STATE_READY = "minimum-state-ready";
    private static final String PREF_SELECTED_CHANNEL_ID = "radio_selected_channel_id";

    private IMumlaService service;
    private MumlaDatabase database;
    private Settings settings;
    private RadioConnectionConfig config;
    private int selectedRoomIndex;
    private boolean bound;
    private boolean destroyed;
    private boolean connectRequested;
    private boolean reconnectAfterDisconnect;
    private boolean retryAfterConfiguredCertificateTrust;
    private boolean configReceiverRegistered;
    private boolean pendingConfigTrial;
    private int pendingConfigConnectionFailures;
    private boolean pendingConfigIoInFlight;
    private boolean connectionRetrySuspended;
    private boolean joinedConfiguredRoom;
    private int connectionAttempt;
    private long txStartedElapsedRealtime;
    private boolean connectOnPttRequest;
    private int pendingExitKey = KeyEvent.KEYCODE_UNKNOWN;
    private int pendingRoomKey = KeyEvent.KEYCODE_UNKNOWN;
    private int pendingRoomDirection;
    private long pendingExitStartedAt = -1L;
    private long pendingRoomStartedAt = -1L;
    private int pendingIdentityKey = KeyEvent.KEYCODE_UNKNOWN;
    private long pendingIdentityStartedAt = -1L;
    private boolean identityHoldCompleted;
    private boolean identityOverlayVisible;
    private long lastIdentityToggleElapsedRealtime;
    private int currentStatusColor = COLOR_OFFLINE;

    private final Runnable protectedExitAction = () -> {
        pendingExitKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingExitStartedAt = -1L;
        openRecoveryDashboard();
    };
    private final Runnable protectedExitPromptTick = new Runnable() {
        @Override
        public void run() {
            if (pendingExitKey == KeyEvent.KEYCODE_UNKNOWN || destroyed) {
                return;
            }
            setStatus(COLOR_BUSY, getString(R.string.radio_hold_to_exit));
            uiHandler.postDelayed(this, 250L);
        }
    };
    private final Runnable roomChangeAction = () -> {
        int direction = pendingRoomDirection;
        pendingRoomKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingRoomDirection = 0;
        pendingRoomStartedAt = -1L;
        if (direction != 0) {
            selectRelativeRoom(direction);
        }
    };
    private final Runnable identityToggleAction = () -> {
        if (pendingIdentityKey == KeyEvent.KEYCODE_UNKNOWN || destroyed) {
            return;
        }
        identityHoldCompleted = true;
        toggleIdentityOverlay();
    };

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable radioTrafficRefresh = () -> {
        if (destroyed || service == null) {
            return;
        }
        showTrafficOrReady();
        maybeApplyPendingConfiguration();
    };
    private final Runnable txTimerTick = new Runnable() {
        @Override
        public void run() {
            if (txStartedElapsedRealtime <= 0L || destroyed) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - txStartedElapsedRealtime;
            long minutes = elapsed / 60000L;
            long seconds = (elapsed / 1000L) % 60L;
            long tenths = (elapsed / 100L) % 10L;
            txTimerView.setText(String.format(java.util.Locale.ROOT,
                    "%02d:%02d.%d", minutes, seconds, tenths));
            uiHandler.postDelayed(this, 100L);
        }
    };

    private LinearLayout rootView;
    private TextView serviceNameView;
    private TextView statusView;
    private TextView detailView;
    private TextView txTimerView;
    private TextView roomView;
    private TextView identityView;
    private TextView identityOverlayView;
    private ProgressBar connectionProgress;
    private boolean compactLayout;

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RadioConfigUpdater.ACTION_CONFIG_PENDING.equals(intent.getAction())) {
                maybeApplyPendingConfiguration();
            } else if (MumlaService.ACTION_RADIO_PTT_FAILURE.equals(intent.getAction())) {
                stopTxTimer();
                setStatus(COLOR_ERROR, getString(R.string.radio_tx_failed));
                detailView.setText(R.string.radio_tx_failed_detail);
                statusView.postDelayed(RadioShellActivity.this::updateFromService, 2500L);
            }
        }
    };

    private final HumlaObserver observer = new HumlaObserver() {
        @Override
        public void onConnecting() {
            joinedConfiguredRoom = false;
            connectionAttempt++;
            setStatus(COLOR_BUSY, getString(R.string.radio_connecting));
            detailView.setText(getString(R.string.radio_connection_attempt, connectionAttempt));
        }

        @Override
        public void onConnected() {
            connectRequested = false;
            connectionAttempt = 0;
            connectionRetrySuspended = false;
            joinedConfiguredRoom = false;
            updateServiceRoomReady(false);
            joinSelectedRoom();
            updateFromService();
            if (!pendingConfigTrial) {
                maybeApplyPendingConfiguration();
            }
        }

        @Override
        public void onDisconnected(HumlaException error) {
            connectRequested = false;
            joinedConfiguredRoom = false;
            updateServiceRoomReady(false);
            stopTxTimer();
            if (retryAfterConfiguredCertificateTrust) {
                retryAfterConfiguredCertificateTrust = false;
                setStatus(COLOR_BUSY, getString(R.string.radio_certificate_trusted));
                statusView.postDelayed(RadioShellActivity.this::maybeConnect, 400);
                return;
            }
            if (pendingConfigTrial && error != null) {
                boolean networkConnected = RadioConfigUpdater.isNetworkConnected(
                        RadioShellActivity.this);
                if (error.getReason() == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
                        && networkConnected) {
                    pendingConfigConnectionFailures++;
                    Log.w(TAG, "Pending config trial connection failure "
                            + pendingConfigConnectionFailures + "/"
                            + PendingConfigTrialPolicy.MAX_CONNECTED_NETWORK_FAILURES);
                }
                if (PendingConfigTrialPolicy.shouldReject(error.getReason(), networkConnected,
                        pendingConfigConnectionFailures)) {
                    Log.w(TAG, "Pending config trial rejected after bounded connection checks");
                    failPendingConfiguration();
                    return;
                }
            }
            if (reconnectAfterDisconnect) {
                reconnectAfterDisconnect = false;
                maybeConnect();
                return;
            }
            if (connectionRetrySuspended) {
                if (service != null) {
                    service.cancelReconnect();
                }
                setStatus(COLOR_ERROR, getString(R.string.radio_connection_blocked));
                return;
            }
            if (service != null && service.isReconnecting()) {
                setStatus(COLOR_BUSY, getString(R.string.radio_reconnecting));
                detailView.setText(getString(R.string.radio_retry_forever));
            } else {
                setStatus(COLOR_OFFLINE, getString(R.string.radio_offline));
            }
            maybeApplyPendingConfiguration();
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            trustConfiguredCertificate(chain);
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            if (isSelf(user)) {
                updateChannelAliasView();
                joinedConfiguredRoom = isSelectedRoom(newChannel);
                updateServiceRoomReady(joinedConfiguredRoom);
                scheduleRadioTrafficRefresh();
                if (pendingConfigTrial && isSelectedRoom(newChannel)) {
                    commitPendingConfiguration();
                }
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (service == null || user == null) {
                return;
            }
            if (isSelf(user)) {
                if (isAudibleTalkState(user.getTalkState())) {
                    startTxTimer();
                } else {
                    stopTxTimer();
                    scheduleRadioTrafficRefresh();
                }
                return;
            }

            scheduleRadioTrafficRefresh();
        }

        @Override
        public void onUserRemoved(IUser user, String reason) {
            scheduleRadioTrafficRefresh();
        }

        @Override
        public void onPermissionDenied(String reason) {
            setStatus(COLOR_ERROR, getString(R.string.radio_access_denied));
            if (pendingConfigTrial) {
                failPendingConfiguration();
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MumlaService.MumlaBinder) binder).getService();
            // Service-owned room readiness survives Activity stop/start (including screen-off).
            // Connection and channel observers still clear it when the actual radio state changes.
            service.registerObserver(observer);
            applyServicePttPolicy();
            updateFromService();
            maybeApplyPendingConfiguration();
            maybeConnect();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            stopTxTimer();
            setStatus(COLOR_OFFLINE, getString(R.string.radio_service_stopped));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = Settings.getInstance(this);
        database = new MumlaSQLiteDatabase(this);
        database.open();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Dedicated radio Back always returns to the controlled recovery dashboard.
                openRecoveryDashboard();
            }
        });
        buildUi();
        acceptIdentityToggleIntent(getIntent());
        acceptPttRecoveryIntent(getIntent());
        loadConfiguration();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIdentityToggleIntent(intent);
        acceptPttRecoveryIntent(intent);
        maybeConnect();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!configReceiverRegistered) {
            IntentFilter appEvents = new IntentFilter(RadioConfigUpdater.ACTION_CONFIG_PENDING);
            appEvents.addAction(MumlaService.ACTION_RADIO_PTT_FAILURE);
            ContextCompat.registerReceiver(this, configReceiver,
                    appEvents,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            configReceiverRegistered = true;
        }
        if (!bound) {
            bound = bindService(new Intent(this, MumlaService.class), serviceConnection,
                    BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        releasePtt();
        uiHandler.removeCallbacks(radioTrafficRefresh);
        cancelPendingHardwareActions(false);
        if (configReceiverRegistered) {
            unregisterReceiver(configReceiver);
            configReceiverRegistered = false;
        }
        if (bound) {
            if (service != null) {
                service.unregisterObserver(observer);
            }
            unbindService(serviceConnection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopTxTimer();
        database.close();
        super.onDestroy();
    }

    private void openRecoveryDashboard() {
        Intent recoveryDashboard = new Intent(this, MinimumHomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(recoveryDashboard);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        RadioKeyDiagnostics.record(this, "activity", event);
        String profile = RadioDeviceProfile.detectCurrent();
        if (event != null && isIdentityToggleEvent(event)) {
            RadioKeyDiagnostics.record(this, "identity-toggle", event);
            if (RadioDeviceProfile.RYKS.equals(profile)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    toggleIdentityOverlay();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                beginIdentityToggle(event.getKeyCode(), event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                finishIdentityToggle(event.getKeyCode(), event);
            }
            return true;
        }
        if (event != null && isProtectedExitKey(event.getKeyCode())) {
            RadioKeyDiagnostics.record(this, "protected-exit", event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                beginProtectedExit(event.getKeyCode(), event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                finishProtectedExit(event.getKeyCode(), event);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (RadioPttKeyManager.isConfiguredPttEvent(event, settings)
                && !RadioPttKeyManager.isMediaStyleKey(keyCode)) {
            if (event.getRepeatCount() == 0) {
                if (RadioPttRecoveryGuard.isReleaseRequired()) {
                    return true;
                }
                boolean readyForConfiguredRoom = service != null
                        && service.isConnected() && joinedConfiguredRoom;
                if (!readyForConfiguredRoom) {
                    requestConnectionFromPtt();
                }
                if (service != null) {
                    service.onTalkKeyDown();
                }
            }
            return true;
        }
        int roomDirection = RadioKeyActionPolicy.roomDirection(
                RadioDeviceProfile.detectCurrent(), event);
        if (roomDirection != 0) {
            beginRoomChange(keyCode, roomDirection, event);
            return true;
        }
        if (isIdentityToggleKey(keyCode)) {
            beginIdentityToggle(keyCode, event);
            return true;
        }
        if (isConfirmKey(keyCode)) {
            if (event.getRepeatCount() == 0) {
                joinSelectedRoom();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENDCALL) {
            if (event.getRepeatCount() == 0 && config != null) {
                RadioConnectionConfig.Channel previous = getSelectedChannel();
                selectedRoomIndex = config.getDefaultChannelIndex();
                persistSelectedChannel();
                activateSelectedChannel(previous);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (RadioPttKeyManager.isConfiguredPttEvent(event, settings)
                && !RadioPttKeyManager.isMediaStyleKey(keyCode)) {
            RadioPttRecoveryGuard.noteRelease();
            releasePtt();
            return true;
        }
        int roomDirection = RadioKeyActionPolicy.roomDirection(
                RadioDeviceProfile.detectCurrent(), event);
        if (roomDirection != 0) {
            finishRoomChange(keyCode, roomDirection, event);
            return true;
        }
        if (isIdentityToggleKey(keyCode)) {
            finishIdentityToggle(keyCode, event);
            return true;
        }
        if (isNavigationKey(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                maybeConnect();
            } else {
                setStatus(COLOR_ERROR, getString(R.string.radio_microphone_required));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void buildUi() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(COLOR_OFFLINE);
        getWindow().setNavigationBarColor(COLOR_OFFLINE);

        boolean compact = getResources().getConfiguration().screenHeightDp <= 160
                || getResources().getConfiguration().screenWidthDp <= 160;
        compactLayout = compact;
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.CENTER);
        rootView.setPadding(dp(5), dp(3), dp(5), dp(3));

        serviceNameView = textView(compact ? 8 : 11, Color.LTGRAY);
        serviceNameView.setGravity(Gravity.CENTER);
        serviceNameView.setVisibility(View.INVISIBLE);
        rootView.addView(serviceNameView, new LinearLayout.LayoutParams(-1, -2));

        connectionProgress = new ProgressBar(this);
        connectionProgress.setIndeterminate(true);
        rootView.addView(connectionProgress,
                new LinearLayout.LayoutParams(compact ? dp(18) : dp(28),
                        compact ? dp(18) : dp(28)));

        statusView = textView(compact ? 25 : 34, Color.WHITE);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(false);
        statusView.setMaxLines(compact ? 2 : 4);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setIncludeFontPadding(false);
        rootView.addView(statusView, new LinearLayout.LayoutParams(-1, 0, 1));

        txTimerView = textView(compact ? 21 : 30, Color.WHITE);
        txTimerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        txTimerView.setGravity(Gravity.CENTER);
        txTimerView.setVisibility(View.GONE);
        rootView.addView(txTimerView, new LinearLayout.LayoutParams(-1, -2));

        detailView = textView(compact ? 8 : 12, Color.LTGRAY);
        detailView.setGravity(Gravity.CENTER);
        detailView.setSingleLine(false);
        detailView.setMaxLines(2);
        rootView.addView(detailView, new LinearLayout.LayoutParams(-1, -2));

        roomView = textView(compact ? 16 : 20, COLOR_CHANNEL_TEXT);
        roomView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        roomView.setGravity(Gravity.CENTER);
        roomView.setSingleLine(true);
        roomView.setEllipsize(TextUtils.TruncateAt.END);
        roomView.setPadding(dp(compact ? 4 : 8), dp(compact ? 2 : 4),
                dp(compact ? 4 : 8), dp(compact ? 2 : 4));
        GradientDrawable channelBadge = new GradientDrawable();
        channelBadge.setColor(COLOR_CHANNEL_BADGE);
        channelBadge.setCornerRadius(dp(4));
        roomView.setBackground(channelBadge);
        LinearLayout.LayoutParams roomParams = new LinearLayout.LayoutParams(-1, -2);
        roomParams.setMargins(0, dp(1), 0, dp(1));
        rootView.addView(roomView, roomParams);

        identityView = textView(8, Color.GRAY);
        identityView.setGravity(Gravity.CENTER);
        String deviceId = new DeviceIdentityManager(
                PreferenceManager.getDefaultSharedPreferences(this)).getOrCreateDeviceId();
        identityView.setText(deviceId + " · " + RadioDeviceProfile.detectCurrent());
        identityView.setVisibility(View.INVISIBLE);
        rootView.addView(identityView, new LinearLayout.LayoutParams(-1, -2));

        identityOverlayView = textView(compact ? 30 : 48, Color.WHITE);
        identityOverlayView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        identityOverlayView.setGravity(Gravity.CENTER);
        identityOverlayView.setText(deviceId);
        identityOverlayView.setContentDescription(deviceId);
        identityOverlayView.setVisibility(View.GONE);

        FrameLayout shell = new FrameLayout(this);
        shell.addView(rootView, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(identityOverlayView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        setStatus(COLOR_OFFLINE, getString(R.string.radio_loading_config));
    }

    private TextView textView(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        return view;
    }

    private void loadConfiguration() {
        new Thread(() -> {
            try {
                RadioConnectionConfig loaded = RadioConnectionConfig.fromJson(
                        new RadioConfigRepository(this).loadActiveOrDefault());
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    applyConfigurationToUi(loaded);
                    maybeApplyPendingConfiguration();
                    maybeConnect();
                });
            } catch (IOException | JSONException | RuntimeException error) {
                runOnUiThread(() -> setStatus(COLOR_ERROR,
                        getString(R.string.radio_config_invalid)));
            }
        }, "minimum-radio-startup").start();
    }

    private void applyConfigurationToUi(RadioConnectionConfig loaded) {
        config = loaded;
        applyServicePttPolicy();
        connectionRetrySuspended = false;
        joinedConfiguredRoom = false;
        updateServiceRoomReady(false);
        String savedChannelId = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_SELECTED_CHANNEL_ID, null);
        selectedRoomIndex = loaded.findChannelIndex(savedChannelId);
        if (selectedRoomIndex < 0) {
            selectedRoomIndex = loaded.getDefaultChannelIndex();
            if (!pendingConfigTrial) {
                persistSelectedChannel();
            }
        }
        serviceNameView.setText(loaded.getServiceName());
        updateChannelAliasView();
        if (!loaded.isAutoConnect()) {
            setStatus(COLOR_OFFLINE, getString(R.string.radio_not_provisioned));
        }
    }

    /** Staged remote config is trialled only while neither local TX nor remote RX is active. */
    private void maybeApplyPendingConfiguration() {
        if (destroyed || config == null || service == null || pendingConfigTrial
                || pendingConfigIoInFlight) {
            return;
        }
        RadioConfigRepository repository = new RadioConfigRepository(this);
        if (!repository.hasPending()) {
            return;
        }
        if (!isRadioIdleForConfig()) {
            setStatus(COLOR_BUSY, getString(R.string.radio_waiting_for_idle));
            return;
        }

        pendingConfigIoInFlight = true;
        setStatus(COLOR_BUSY, getString(R.string.radio_config_testing));
        new Thread(() -> {
            try {
                JSONObject pending = repository.loadPending();
                RadioConnectionConfig candidate = pending == null
                        ? null : RadioConnectionConfig.fromJson(pending);
                runOnUiThread(() -> {
                    pendingConfigIoInFlight = false;
                    if (destroyed || candidate == null) {
                        return;
                    }
                    beginPendingConfigurationTrial(candidate);
                });
            } catch (IOException | JSONException | RuntimeException error) {
                try {
                    repository.discardPending();
                } catch (IOException ignored) {
                    // The next startup will retry or reject the same pending file safely.
                }
                runOnUiThread(() -> {
                    pendingConfigIoInFlight = false;
                    setStatus(COLOR_ERROR, getString(R.string.radio_config_invalid));
                });
            }
        }, "minimum-radio-config-load-pending").start();
    }

    private boolean isRadioIdleForConfig() {
        if (service == null) {
            return false;
        }
        HumlaService.ConnectionState state = service.getConnectionState();
        boolean transitioning = state == HumlaService.ConnectionState.CONNECTING
                || state == HumlaService.ConnectionState.CONNECTION_LOST;
        boolean transmitting = false;
        try {
            transmitting = service.isConnected() && service.HumlaSession().isTalking();
        } catch (IllegalStateException ignored) {
            return false;
        }
        return RadioConfigActivationPolicy.canTrial(true, transitioning, transmitting,
                service.isRadioReceiving());
    }

    private static boolean isAudibleTalkState(TalkState state) {
        return state == TalkState.TALKING || state == TalkState.SHOUTING
                || state == TalkState.WHISPERING;
    }

    private void beginPendingConfigurationTrial(RadioConnectionConfig candidate) {
        pendingConfigTrial = true;
        pendingConfigConnectionFailures = 0;
        applyConfigurationToUi(candidate);
        setStatus(COLOR_BUSY, getString(R.string.radio_config_testing));

        if (!candidate.isAutoConnect()) {
            commitPendingConfiguration();
            return;
        }
        if (service != null && service.isConnected()) {
            reconnectAfterDisconnect = true;
            service.disconnect();
        } else {
            maybeConnect();
        }
    }

    private void commitPendingConfiguration() {
        if (!pendingConfigTrial || pendingConfigIoInFlight) {
            return;
        }
        pendingConfigIoInFlight = true;
        RadioConfigRepository repository = new RadioConfigRepository(this);
        new Thread(() -> {
            try {
                repository.commitPending();
                runOnUiThread(() -> {
                    pendingConfigIoInFlight = false;
                    pendingConfigTrial = false;
                    pendingConfigConnectionFailures = 0;
                    if (service != null) {
                        service.reloadTrackingConfig();
                    }
                    if (destroyed) {
                        return;
                    }
                    persistSelectedChannel();
                    if (!config.isAutoConnect() && service != null && service.isConnected()) {
                        reconnectAfterDisconnect = false;
                        service.disconnect();
                        setStatus(COLOR_OFFLINE, getString(R.string.radio_not_provisioned));
                    } else {
                        showTrafficOrReady();
                    }
                });
            } catch (IOException | JSONException | RuntimeException error) {
                runOnUiThread(() -> {
                    pendingConfigIoInFlight = false;
                    failPendingConfiguration();
                });
            }
        }, "minimum-radio-config-commit").start();
    }

    private void failPendingConfiguration() {
        if (!pendingConfigTrial || pendingConfigIoInFlight) {
            return;
        }
        pendingConfigIoInFlight = true;
        RadioConfigRepository repository = new RadioConfigRepository(this);
        new Thread(() -> {
            RadioConnectionConfig restored = null;
            try {
                repository.discardPending();
                restored = RadioConnectionConfig.fromJson(repository.loadActiveOrDefault());
            } catch (IOException | JSONException | RuntimeException ignored) {
                // The current in-memory LKG remains safer than accepting the failed candidate.
            }
            RadioConnectionConfig finalRestored = restored;
            runOnUiThread(() -> {
                pendingConfigIoInFlight = false;
                pendingConfigTrial = false;
                pendingConfigConnectionFailures = 0;
                if (destroyed) {
                    return;
                }
                if (finalRestored == null) {
                    setStatus(COLOR_ERROR, getString(R.string.radio_config_invalid));
                    return;
                }
                applyConfigurationToUi(finalRestored);
                setStatus(COLOR_ERROR, getString(R.string.radio_config_rolled_back));
                if (service != null) {
                    HumlaService.ConnectionState state = service.getConnectionState();
                    if (service.isConnected()
                            || state == HumlaService.ConnectionState.CONNECTING) {
                        reconnectAfterDisconnect = finalRestored.isAutoConnect();
                        service.disconnect();
                        return;
                    }
                    if (service.isReconnecting()
                            || state == HumlaService.ConnectionState.CONNECTION_LOST) {
                        reconnectAfterDisconnect = false;
                        service.cancelReconnect();
                    }
                }
                maybeConnect();
            });
        }, "minimum-radio-config-rollback").start();
    }

    private void maybeConnect() {
        boolean explicitlyRequested = connectOnPttRequest;
        if (destroyed || config == null || service == null || connectRequested
                || pendingConfigIoInFlight || (!config.isAutoConnect() && !explicitlyRequested)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
            return;
        }

        HumlaService.ConnectionState state = service.getConnectionState();
        if (state == HumlaService.ConnectionState.CONNECTING
                || (state == HumlaService.ConnectionState.CONNECTION_LOST
                && service.isReconnecting())) {
            connectOnPttRequest = false;
            updateFromService();
            return;
        }
        if (service.isConnected()) {
            connectOnPttRequest = false;
            Server target = service.getTargetServer();
            RadioConnectionConfig.Connection connection = getSelectedChannel().getConnection();
            if (connection.matches(target)) {
                joinSelectedRoom();
                updateFromService();
                return;
            }
            IHumlaSession session = service.HumlaSession();
            if (session.isTalking()) {
                setStatus(COLOR_BUSY, getString(R.string.radio_waiting_for_idle));
                return;
            }
            reconnectAfterDisconnect = true;
            service.disconnect();
            return;
        }

        connectRequested = true;
        connectOnPttRequest = false;
        setStatus(COLOR_BUSY, getString(R.string.radio_preparing_identity));
        RadioCertificateManager.ensureCertificate(this, available -> {
            if (!available || destroyed) {
                connectRequested = false;
                setStatus(COLOR_ERROR, getString(R.string.radio_certificate_failed));
                return;
            }
            RadioConnectionConfig.Channel channel = getSelectedChannel();
            RadioConnectionConfig.Connection connection = channel.getConnection();
            Server server = new Server(-1, connection.getName(), connection.getHost(),
                    connection.getPort(), connection.getUsername(), connection.getPassword());
            new ServerConnectTask(this, database, channel.getAccessTokens(),
                    config.isAutoReconnect()).execute(server);
        });
    }

    private void updateFromService() {
        if (service == null) {
            return;
        }
        switch (service.getConnectionState()) {
            case CONNECTED:
                IChannel channel = getSessionChannelSafely();
                if (channel != null) {
                    updateChannelAliasView();
                }
                showTrafficOrReady();
                break;
            case CONNECTING:
                setStatus(COLOR_BUSY, getString(R.string.radio_connecting));
                break;
            case CONNECTION_LOST:
                setStatus(service.isReconnecting() ? COLOR_BUSY : COLOR_ERROR,
                        getString(service.isReconnecting()
                                ? R.string.radio_reconnecting : R.string.radio_offline));
                break;
            case DISCONNECTED:
            default:
                if (config == null || config.isAutoConnect()) {
                    setStatus(COLOR_OFFLINE, getString(R.string.radio_offline));
                }
                break;
        }
    }

    private void trustConfiguredCertificate(X509Certificate[] chain) {
        if (config == null || chain == null || chain.length == 0) {
            connectionRetrySuspended = true;
            setStatus(COLOR_ERROR, getString(R.string.radio_certificate_untrusted));
            if (pendingConfigTrial) {
                failPendingConfiguration();
            }
            return;
        }
        try {
            RadioConnectionConfig.Connection connection = getSelectedChannel().getConnection();
            String actual = toHex(MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].getEncoded()));
            if (!connection.acceptsServerCertificate(actual)) {
                if (connection.getServerCertificateSha256() == null) {
                    connectionRetrySuspended = true;
                    setStatus(COLOR_ERROR, getString(R.string.radio_certificate_untrusted));
                    if (pendingConfigTrial) {
                        failPendingConfiguration();
                    }
                    return;
                }
                connectionRetrySuspended = true;
                setStatus(COLOR_ERROR, getString(R.string.radio_certificate_changed));
                if (pendingConfigTrial) {
                    failPendingConfiguration();
                }
                return;
            }
            KeyStore trustStore = MumlaTrustStore.getTrustStore(this);
            String alias = "minimum-" + connection.getHost() + ":" + connection.getPort();
            trustStore.setCertificateEntry(alias, chain[0]);
            MumlaTrustStore.saveTrustStore(this, trustStore);
            connectionRetrySuspended = false;
            retryAfterConfiguredCertificateTrust = true;
            setStatus(COLOR_BUSY, getString(R.string.radio_certificate_trusted));
        } catch (Exception error) {
            connectionRetrySuspended = true;
            setStatus(COLOR_ERROR, getString(R.string.radio_certificate_failed));
            if (pendingConfigTrial) {
                failPendingConfiguration();
            }
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(java.util.Locale.ROOT, "%02X", item & 0xFF));
        }
        return result.toString();
    }

    private void selectRelativeRoom(int direction) {
        if (config == null || config.getChannels().isEmpty()) {
            return;
        }
        if (service != null && service.isConnected()) {
            try {
                if (service.HumlaSession().isTalking()) {
                    setStatus(COLOR_BUSY, getString(R.string.radio_waiting_for_idle));
                    return;
                }
            } catch (IllegalStateException ignored) {
                return;
            }
        }
        RadioConnectionConfig.Channel previous = getSelectedChannel();
        int count = config.getChannels().size();
        selectedRoomIndex = (selectedRoomIndex + direction + count) % count;
        RadioConnectionConfig.Channel channel = getSelectedChannel();
        persistSelectedChannel();
        updateChannelAliasView();
        setStatus(COLOR_BUSY, getString(R.string.radio_joining_room));
        activateSelectedChannel(previous);
    }

    private void activateSelectedChannel(RadioConnectionConfig.Channel previous) {
        if (config == null || service == null) {
            return;
        }
        RadioConnectionConfig.Channel selected = getSelectedChannel();
        boolean sessionCredentialsChanged = previous != null
                && previous.requiresReconnectTo(selected);
        HumlaService.ConnectionState state = service.getConnectionState();
        if (sessionCredentialsChanged
                && state == HumlaService.ConnectionState.CONNECTING) {
            reconnectAfterDisconnect = true;
            service.cancelReconnect();
            service.disconnect();
            return;
        }
        if (sessionCredentialsChanged
                && state == HumlaService.ConnectionState.CONNECTION_LOST) {
            reconnectAfterDisconnect = false;
            service.cancelReconnect();
            maybeConnect();
            return;
        }
        if (service.isConnected()) {
            IHumlaSession session = service.HumlaSession();
            if (session.isTalking()) {
                setStatus(COLOR_BUSY, getString(R.string.radio_waiting_for_idle));
                return;
            }
            if (sessionCredentialsChanged
                    || !selected.getConnection().matches(service.getTargetServer())) {
                joinedConfiguredRoom = false;
                updateServiceRoomReady(false);
                reconnectAfterDisconnect = true;
                service.disconnect();
                return;
            }
            joinSelectedRoom();
            return;
        }
        maybeConnect();
    }

    private void joinSelectedRoom() {
        if (config == null || service == null || !service.isConnected()) {
            return;
        }
        IHumlaSession session = service.HumlaSession();
        if (session.isTalking()) {
            setStatus(COLOR_BUSY, getString(R.string.radio_waiting_for_idle));
            return;
        }
        RadioConnectionConfig.Channel channel = getSelectedChannel();
        IChannel target = RoomPathResolver.resolve(session.getRootChannel(), channel.getPath());
        if (target == null) {
            setStatus(COLOR_ERROR, getString(R.string.radio_room_missing));
            if (pendingConfigTrial) {
                failPendingConfiguration();
            }
            return;
        }
        updateChannelAliasView();
        if (!target.equals(session.getSessionChannel())) {
            joinedConfiguredRoom = false;
            updateServiceRoomReady(false);
            setStatus(COLOR_BUSY, getString(R.string.radio_joining_room));
            session.joinChannel(target.getId());
        } else {
            joinedConfiguredRoom = true;
            updateServiceRoomReady(true);
            scheduleRadioTrafficRefresh();
            if (pendingConfigTrial) {
                commitPendingConfiguration();
            }
        }
    }

    private boolean isSelectedRoom(IChannel channel) {
        if (config == null || channel == null) {
            return false;
        }
        return getSelectedChannel().getPath()
                .equals(RoomPathResolver.fullPath(channel));
    }

    private RadioConnectionConfig.Channel getSelectedChannel() {
        return config.getChannels().get(selectedRoomIndex);
    }

    private void updateChannelAliasView() {
        if (roomView == null || config == null || config.getChannels().isEmpty()
                || selectedRoomIndex < 0 || selectedRoomIndex >= config.getChannels().size()) {
            return;
        }
        String alias = getSelectedChannel().getAlias();
        roomView.setText(getString(R.string.radio_channel_alias, alias));
        roomView.setContentDescription(
                getString(R.string.radio_channel_alias_accessibility, alias));
    }

    private void persistSelectedChannel() {
        if (config == null || selectedRoomIndex < 0
                || selectedRoomIndex >= config.getChannels().size()) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PREF_SELECTED_CHANNEL_ID, getSelectedChannel().getId())
                .apply();
    }

    private void showTrafficOrReady() {
        // A screen-off OEM DOWN can start TX before Android resumes this Activity. In that case
        // the Activity was not observing the initial self-talk edge, so recover the service-owned
        // state before rendering Ready/RX.
        if (isLocalTalkingSafely()) {
            startTxTimer();
            return;
        }
        List<String> activeTalkers = service == null
                ? java.util.Collections.emptyList() : service.getRadioTalkers();
        RadioTrafficUiState traffic = RadioTrafficUiState.from(activeTalkers);
        switch (traffic.getKind()) {
            case SINGLE_TALKER:
                String talker = traffic.getTalker();
                setStatus(COLOR_RX,
                        talker.isEmpty() ? getString(R.string.radio_receiving) : talker);
                statusView.setContentDescription(talker.isEmpty()
                        ? getString(R.string.radio_receiving) : talker);
                detailView.setText(R.string.radio_receiving);
                break;
            case MULTIPLE_TALKERS:
                int maxLines = compactLayout ? 2 : 4;
                int hiddenCount = Math.max(0, traffic.getTalkers().size() - maxLines);
                RadioTalkerDisplay.Display display = RadioTalkerDisplay.format(
                        traffic.getTalkers(), maxLines,
                        getString(R.string.radio_more_talkers, hiddenCount));
                setStatus(COLOR_RX, display.getText());
                statusView.setContentDescription(display.getAccessibilityText());
                detailView.setText(R.string.radio_receiving);
                break;
            case READY:
            default:
                showReadyState();
                break;
        }
    }

    private boolean isLocalTalkingSafely() {
        if (service == null || !service.isConnected()) {
            return false;
        }
        try {
            return service.HumlaSession().isTalking();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private void scheduleRadioTrafficRefresh() {
        // Humla fans observers out through an unordered concurrent set. Deferring until the
        // current fan-out completes guarantees MumlaService's tracker has applied this same event,
        // regardless of whether the Activity observer happened to run first or last.
        uiHandler.removeCallbacks(radioTrafficRefresh);
        uiHandler.post(radioTrafficRefresh);
    }

    private void showReadyState() {
        if (!joinedConfiguredRoom) {
            setStatus(COLOR_BUSY, getString(R.string.radio_joining_room));
            return;
        }
        setStatus(COLOR_READY, getString(R.string.radio_ready));
        statusView.setContentDescription(AUTOMATION_STATE_READY);
        detailView.setText(R.string.radio_hardware_ptt_hint);
    }

    private boolean isSelf(IUser user) {
        if (service == null || user == null || !service.isConnected()) {
            return false;
        }
        try {
            return user.getSession() == service.HumlaSession().getSessionId();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private IChannel getSessionChannelSafely() {
        if (service == null || !service.isConnected()) {
            return null;
        }
        try {
            return service.HumlaSession().getSessionChannel();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void startTxTimer() {
        if (txStartedElapsedRealtime <= 0L) {
            txStartedElapsedRealtime = SystemClock.elapsedRealtime();
            txTimerView.setText("00:00.0");
            txTimerView.setVisibility(View.VISIBLE);
            uiHandler.removeCallbacks(txTimerTick);
            uiHandler.post(txTimerTick);
        }
        setStatus(COLOR_TX, getString(R.string.radio_transmitting));
        detailView.setText(R.string.radio_tx_timer_label);
    }

    private void stopTxTimer() {
        txStartedElapsedRealtime = 0L;
        uiHandler.removeCallbacks(txTimerTick);
        if (txTimerView != null) {
            txTimerView.setVisibility(View.GONE);
        }
    }

    private void releasePtt() {
        if (service != null) {
            service.onTalkKeyUp();
        }
    }

    private void setStatus(int color, String text) {
        currentStatusColor = color;
        rootView.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        statusView.setBackgroundColor(Color.TRANSPARENT);
        statusView.setContentDescription(null);
        statusView.setText(text);
        detailView.setText("");
        connectionProgress.setVisibility(color == COLOR_BUSY ? View.VISIBLE : View.GONE);
        if (identityOverlayView != null) {
            identityOverlayView.setBackgroundColor(color);
        }
    }

    private boolean isConfirmKey(int keyCode) {
        if (RadioDeviceProfile.T99.equals(RadioDeviceProfile.detectCurrent())) {
            return keyCode == KeyEvent.KEYCODE_MENU;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                || keyCode == KeyEvent.KEYCODE_CALL;
    }

    private boolean isNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_ENDCALL
                || isProtectedExitKey(keyCode)
                || isIdentityToggleKey(keyCode)
                || isConfirmKey(keyCode);
    }

    private boolean isIdentityToggleKey(int keyCode) {
        return RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.detectCurrent(), keyCode);
    }

    private boolean isIdentityToggleEvent(KeyEvent event) {
        return RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.detectCurrent(), event);
    }

    private boolean isProtectedExitKey(int keyCode) {
        return RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.detectCurrent(), keyCode);
    }

    private void acceptPttRecoveryIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_CONNECT_ON_PTT, false)) {
            connectOnPttRequest = true;
            intent.removeExtra(EXTRA_CONNECT_ON_PTT);
            setStatus(COLOR_BUSY, getString(R.string.radio_ptt_recovery));
            detailView.setText(R.string.radio_ptt_recovery_detail);
        }
    }

    private void requestConnectionFromPtt() {
        connectOnPttRequest = true;
        setStatus(COLOR_BUSY, getString(R.string.radio_ptt_recovery));
        detailView.setText(R.string.radio_ptt_recovery_detail);
        maybeConnect();
    }

    private void beginProtectedExit(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() != 0 || pendingExitKey == keyCode) {
            return;
        }
        cancelPendingHardwareActions(false);
        pendingExitKey = keyCode;
        pendingExitStartedAt = event.getEventTime();
        setStatus(COLOR_BUSY, getString(R.string.radio_hold_to_exit));
        uiHandler.removeCallbacks(protectedExitPromptTick);
        uiHandler.postDelayed(protectedExitPromptTick, 250L);
        uiHandler.postDelayed(protectedExitAction, RadioKeyActionPolicy.EXIT_HOLD_MS);
    }

    private void finishProtectedExit(int keyCode, KeyEvent event) {
        if (pendingExitKey != keyCode) {
            return;
        }
        boolean completed = RadioKeyActionPolicy.heldLongEnough(pendingExitStartedAt,
                event.getEventTime(), RadioKeyActionPolicy.EXIT_HOLD_MS);
        pendingExitKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingExitStartedAt = -1L;
        uiHandler.removeCallbacks(protectedExitAction);
        uiHandler.removeCallbacks(protectedExitPromptTick);
        if (completed) {
            openRecoveryDashboard();
        } else {
            updateFromService();
        }
    }

    private void beginRoomChange(int keyCode, int direction, KeyEvent event) {
        if (event.getRepeatCount() != 0 || pendingRoomKey == keyCode) {
            return;
        }
        cancelPendingHardwareActions(false);
        pendingRoomKey = keyCode;
        pendingRoomDirection = direction;
        pendingRoomStartedAt = event.getEventTime();
        setStatus(COLOR_BUSY, getString(R.string.radio_hold_to_change_room));
        uiHandler.postDelayed(roomChangeAction, RadioKeyActionPolicy.ROOM_CHANGE_HOLD_MS);
    }

    private void finishRoomChange(int keyCode, int direction, KeyEvent event) {
        if (pendingRoomKey != keyCode || pendingRoomDirection != direction) {
            return;
        }
        boolean completed = RadioKeyActionPolicy.heldLongEnough(pendingRoomStartedAt,
                event.getEventTime(), RadioKeyActionPolicy.ROOM_CHANGE_HOLD_MS);
        pendingRoomKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingRoomDirection = 0;
        pendingRoomStartedAt = -1L;
        uiHandler.removeCallbacks(roomChangeAction);
        if (completed && direction != 0) {
            selectRelativeRoom(direction);
            joinSelectedRoom();
        } else {
            updateFromService();
        }
    }

    private void beginIdentityToggle(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() != 0 || pendingIdentityKey == keyCode) {
            return;
        }
        cancelPendingHardwareActions(false);
        pendingIdentityKey = keyCode;
        pendingIdentityStartedAt = event.getEventTime();
        identityHoldCompleted = false;
        uiHandler.postDelayed(identityToggleAction, RadioKeyActionPolicy.IDENTITY_HOLD_MS);
    }

    private void finishIdentityToggle(int keyCode, KeyEvent event) {
        if (pendingIdentityKey != keyCode) {
            return;
        }
        boolean completed = identityHoldCompleted;
        if (!completed && RadioKeyActionPolicy.heldLongEnough(pendingIdentityStartedAt,
                event.getEventTime(), RadioKeyActionPolicy.IDENTITY_HOLD_MS)) {
            toggleIdentityOverlay();
            completed = true;
        }
        pendingIdentityKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingIdentityStartedAt = -1L;
        identityHoldCompleted = false;
        uiHandler.removeCallbacks(identityToggleAction);
        if (!completed && RadioDeviceProfile.T99.equals(RadioDeviceProfile.detectCurrent())) {
            joinSelectedRoom();
        }
    }

    private void setIdentityOverlayVisible(boolean visible) {
        identityOverlayVisible = visible;
        if (identityOverlayView != null) {
            identityOverlayView.setBackgroundColor(currentStatusColor);
            identityOverlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void toggleIdentityOverlay() {
        lastIdentityToggleElapsedRealtime = SystemClock.elapsedRealtime();
        setIdentityOverlayVisible(!identityOverlayVisible);
    }

    private void acceptIdentityToggleIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_TOGGLE_IDENTITY, false)) {
            return;
        }
        intent.removeExtra(EXTRA_TOGGLE_IDENTITY);
        long now = SystemClock.elapsedRealtime();
        if (now - lastIdentityToggleElapsedRealtime > 750L) {
            toggleIdentityOverlay();
        }
    }

    private void cancelPendingHardwareActions(boolean restoreStatus) {
        boolean hadPendingAction = pendingExitKey != KeyEvent.KEYCODE_UNKNOWN
                || pendingRoomKey != KeyEvent.KEYCODE_UNKNOWN
                || pendingIdentityKey != KeyEvent.KEYCODE_UNKNOWN;
        pendingExitKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingRoomKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingRoomDirection = 0;
        pendingExitStartedAt = -1L;
        pendingRoomStartedAt = -1L;
        pendingIdentityKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingIdentityStartedAt = -1L;
        identityHoldCompleted = false;
        uiHandler.removeCallbacks(protectedExitAction);
        uiHandler.removeCallbacks(protectedExitPromptTick);
        uiHandler.removeCallbacks(roomChangeAction);
        uiHandler.removeCallbacks(identityToggleAction);
        if (restoreStatus && hadPendingAction) {
            updateFromService();
        }
    }

    private void updateServiceRoomReady(boolean ready) {
        if (service != null) {
            service.setRadioRoomReady(ready);
        }
    }

    private void applyServicePttPolicy() {
        if (service != null && config != null) {
            service.setMaximumPttSeconds(config.getMaximumTxSeconds());
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
