/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.CleanupRunner;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;
import se.lublin.mumla.radio.RadioPttKeyManager;
import se.lublin.mumla.radio.RadioPttRecoveryGuard;
import se.lublin.mumla.radio.RadioPttSafetyPolicy;
import se.lublin.mumla.radio.RadioReceiveTracker;
import se.lublin.mumla.radio.RadioDeviceProfile;
import se.lublin.mumla.radio.RadioHardwareKeyReceiver;
import se.lublin.mumla.radio.RadioKeyDiagnostics;
import se.lublin.mumla.radio.RadioNotificationPolicy;
import se.lublin.mumla.radio.RadioProcessWatchdog;
import se.lublin.mumla.radio.RadioShellActivity;
import se.lublin.mumla.radio.RadioConfigRepository;
import se.lublin.mumla.radio.tracking.AprsTrackingManager;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();
    public static final String ACTION_RADIO_PTT_FAILURE =
            "se.lublin.mumla.action.RADIO_PTT_FAILURE";
    public static final String ACTION_RADIO_REQUIRE_PTT_RELEASE =
            "se.lublin.mumla.action.RADIO_REQUIRE_PTT_RELEASE";
    public static final String ACTION_RADIO_PTT_RELEASED =
            "se.lublin.mumla.action.RADIO_PTT_RELEASED";
    public static final String ACTION_RADIO_PTT_DOWN =
            "se.lublin.mumla.action.RADIO_PTT_DOWN";
    public static final String ACTION_RADIO_PTT_UP =
            "se.lublin.mumla.action.RADIO_PTT_UP";
    public static final String ACTION_RADIO_TRACKING_POLL =
            "se.lublin.mumla.action.RADIO_TRACKING_POLL";

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;
    static final int MAX_MESSAGE_LOG_ENTRIES = 256;
    private static final long PTT_DELIVERY_CONFIRM_MS = 1500L;
    private static final long RADIO_WAKE_COOLDOWN_MS = 1000L;
    private static final long RADIO_WAKE_DURATION_MS = 5000L;
    private static final Locale THAI_LOCALE = new Locale("th", "TH");
    private static volatile MumlaService sRunningService;

    /**
     * Delivers an OEM hardware PTT edge directly when this process already owns the service.
     *
     * <p>Android 8+ may reject a receiver's background {@code startService()} even though the
     * radio service is already alive. The direct path avoids that race and does not retain an edge
     * for later: a false return means no service was running and the caller may attempt a normal
     * service start, still subject to the service-owned readiness gate.</p>
     */
    public static boolean dispatchRadioPttAction(String action) {
        MumlaService service = sRunningService;
        if (service == null) {
            return false;
        }
        if (ACTION_RADIO_PTT_DOWN.equals(action)) {
            service.wakeRadioDisplay(false);
            service.onTalkKeyDown();
            return true;
        }
        if (ACTION_RADIO_PTT_UP.equals(action)) {
            service.onTalkKeyUp();
            return true;
        }
        return false;
    }

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Set before app-owned fields are cleared so late Humla callbacks cannot touch them. */
    private volatile boolean mDestroying;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /**
     * Media-session bridge for hardware/media PTT keys while the Activity is not focused.
     *
     * T99 exposes a Button Jack with KEY_MEDIA. A MediaSession is the app-level Android API
     * that can receive media-button events while the screen is off. F1/F2 and raw GPIO keys
     * still require an OEM broadcast or privileged input path.
     */
    private MediaSession mPttMediaSession;
    private RadioHardwareKeyReceiver mRadioHardwareKeyReceiver;
    private boolean mRadioRoomReady;
    private boolean mPttMediaKeyDown;
    private final Handler mPttWatchdogHandler = new Handler(Looper.getMainLooper());
    private boolean mPttInputDown;
    private boolean mPttWatchdogLockout;
    private boolean mPttWatchdogArmed;
    private int mMaximumPttSeconds = RadioPttWatchdogPolicy.DEFAULT_MAXIMUM_TX_SECONDS;
    private int mArmedPttMaximumSeconds = RadioPttWatchdogPolicy.DEFAULT_MAXIMUM_TX_SECONDS;
    private final RadioReceiveTracker mRadioReceiveTracker = new RadioReceiveTracker();
    private final Runnable mRadioProcessWatchdogHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isManagedRadioDevice()) {
                return;
            }
            RadioProcessWatchdog.arm(MumlaService.this);
            mPttWatchdogHandler.postDelayed(this,
                    RadioProcessWatchdog.HEARTBEAT_INTERVAL_MS);
        }
    };
    private long mPttPressStartedElapsedRealtime;
    private boolean mPttFailureAlerted;
    private long mLastRadioWakeElapsedRealtime;
    private ToneGenerator mRadioAlertTone;
    private PowerManager.WakeLock mRadioScreenWakeLock;
    private final Runnable mPttDeliveryFailureCheck = new Runnable() {
        @Override
        public void run() {
            if (mPttWatchdogArmed && isTalking() && mPttPressStartedElapsedRealtime > 0L
                    && getLastAudioPacketSentElapsedRealtime()
                    < mPttPressStartedElapsedRealtime) {
                mPttFailureAlerted = true;
                playPttFailureAlert();
            }
        }
    };
    private final Runnable mPttWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!mPttWatchdogArmed || !isTalking()) {
                return;
            }

            // Fail safe: stop transmitting and require a real release before another TX.
            mPttWatchdogArmed = false;
            mPttWatchdogHandler.removeCallbacks(mPttDeliveryFailureCheck);
            mPttWatchdogLockout = true;
            mPttInputDown = false;
            setPttTalkingState(false);
            playPttFailureAlert();
            Log.w(TAG, "PTT watchdog stopped transmission after "
                    + mArmedPttMaximumSeconds + " seconds");
        }
    };
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;
    private AprsTrackingManager mAprsTrackingManager;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR) {
                logWarning(getString(R.string.tts_failed));
                return;
            }
            if (mTTS != null) {
                int thai = mTTS.isLanguageAvailable(THAI_LOCALE);
                if (thai >= TextToSpeech.LANG_AVAILABLE) {
                    mTTS.setLanguage(THAI_LOCALE);
                } else {
                    Log.w(TAG, "Thai TTS voice is not installed; retaining engine default");
                }
            }
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            mRadioReceiveTracker.clear();
            wakeRadioDisplay();
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                int result = mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
                if (result == TextToSpeech.ERROR) {
                    Log.w(TAG, "TTS engine rejected a queued message");
                }
            }

            // TODO: create a customizable notification sieve
            if (RadioNotificationPolicy.shouldShowChatNotification(
                    mSettings.isChatNotifyEnabled(), isManagedRadioDevice())) {
                mMessageNotification.show(message);
            }

            appendMessageLog(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO,
                    message));
        }

        @Override
        public void onLogWarning(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING,
                    message));
        }

        @Override
        public void onLogError(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR,
                    message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            boolean wasReceiving = mRadioReceiveTracker.isReceiving();
            mRadioReceiveTracker.update(user.getSession(), user.getName(),
                    user.getSession() == selfSession, user.getTalkState());
            if ((!wasReceiving && mRadioReceiveTracker.isReceiving())
                    || (user.getSession() == selfSession
                    && user.getTalkState() != TalkState.PASSIVE)) {
                wakeRadioDisplay();
            }

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                    user.getTalkState() == TalkState.TALKING &&
                    mPTTSoundEnabled) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
            }
        }

        @Override
        public void onUserRemoved(IUser user, String reason) {
            mRadioReceiveTracker.remove(user.getSession());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sRunningService = this;
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.detectCurrent(), mSettings.isPttSoundEnabled());
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>(MAX_MESSAGE_LOG_ENTRIES);
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        initializePttMediaSession();
        updatePttMediaSessionState();
        if (isManagedRadioDevice()) {
            mMessageNotification.dismiss();
            RadioProcessWatchdog.arm(this);
            mPttWatchdogHandler.postDelayed(mRadioProcessWatchdogHeartbeat,
                    RadioProcessWatchdog.HEARTBEAT_INTERVAL_MS);
        }
        if (RadioDeviceProfile.T56.equals(RadioDeviceProfile.detectCurrent())) {
            mAprsTrackingManager = new AprsTrackingManager(this);
            reloadTrackingConfig();
        }

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);
        registerRadioHardwarePttReceiver();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_RADIO_REQUIRE_PTT_RELEASE.equals(intent.getAction())) {
                requirePttRelease();
            } else if (ACTION_RADIO_PTT_RELEASED.equals(intent.getAction())) {
                onTalkKeyUp();
            } else if (ACTION_RADIO_PTT_DOWN.equals(intent.getAction())) {
                wakeRadioDisplay(false);
                onTalkKeyDown();
            } else if (ACTION_RADIO_PTT_UP.equals(intent.getAction())) {
                onTalkKeyUp();
            } else if (ACTION_RADIO_TRACKING_POLL.equals(intent.getAction())
                    && mAprsTrackingManager != null) {
                mAprsTrackingManager.onPoll();
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        mDestroying = true;
        try {
            destroyMumlaResources();
        } finally {
            try {
                setProximitySensorOn(false);
            } finally {
                super.onDestroy();
            }
        }
    }

    private void destroyMumlaResources() {
        if (sRunningService == this) {
            sRunningService = null;
        }
        AprsTrackingManager trackingManager = mAprsTrackingManager;
        mAprsTrackingManager = null;
        MediaSession pttMediaSession = mPttMediaSession;
        ToneGenerator radioAlertTone = mRadioAlertTone;
        PowerManager.WakeLock radioScreenWakeLock = mRadioScreenWakeLock;
        MumlaConnectionNotification notification = mNotification;
        MumlaReconnectNotification reconnectNotification = mReconnectNotification;
        RadioHardwareKeyReceiver hardwareKeyReceiver = mRadioHardwareKeyReceiver;
        TextToSpeech textToSpeech = mTTS;
        MumlaMessageNotification messageNotification = mMessageNotification;
        mPttMediaSession = null;
        mRadioAlertTone = null;
        mRadioScreenWakeLock = null;
        mNotification = null;
        mReconnectNotification = null;
        mRadioHardwareKeyReceiver = null;
        mTTS = null;
        mMessageNotification = null;
        mPttMediaKeyDown = false;
        mMessageLog = null;

        RuntimeException failure = CleanupRunner.runAll(
                () -> {
                    if (trackingManager != null) {
                        trackingManager.stop();
                    }
                },
                () -> mPttWatchdogHandler.removeCallbacks(mRadioProcessWatchdogHeartbeat),
                mRadioReceiveTracker::clear,
                () -> releasePttForSafety(true),
                () -> {
                    if (pttMediaSession != null) {
                        pttMediaSession.setActive(false);
                    }
                },
                () -> {
                    if (pttMediaSession != null) {
                        pttMediaSession.release();
                    }
                },
                () -> {
                    if (radioAlertTone != null) {
                        radioAlertTone.release();
                    }
                },
                () -> {
                    if (radioScreenWakeLock != null && radioScreenWakeLock.isHeld()) {
                        radioScreenWakeLock.release();
                    }
                },
                () -> {
                    if (notification != null) {
                        notification.hide();
                    }
                },
                () -> {
                    if (reconnectNotification != null) {
                        reconnectNotification.hide();
                    }
                },
                () -> PreferenceManager.getDefaultSharedPreferences(this)
                        .unregisterOnSharedPreferenceChangeListener(this),
                () -> unregisterReceiverIfRegistered(mTalkReceiver),
                () -> unregisterReceiverIfRegistered(hardwareKeyReceiver),
                () -> unregisterObserver(mObserver),
                () -> {
                    if (textToSpeech != null) {
                        textToSpeech.shutdown();
                    }
                },
                () -> {
                    if (messageNotification != null) {
                        messageNotification.dismiss();
                    }
                });
        if (failure != null) {
            Log.e(TAG, "Mumla teardown completed with a resource failure", failure);
        }
    }

    private void unregisterReceiverIfRegistered(BroadcastReceiver receiver) {
        if (receiver == null) {
            return;
        }
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // A partial service startup may not have completed receiver registration.
        }
    }

    @Override
    public void onConnectionSynchronized() {
        if (mDestroying) {
            return;
        }
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        // Intentional legacy external TALK control on controlled dedicated deployments.
        ContextCompat.registerReceiver(this, mTalkReceiver,
                new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK),
                ContextCompat.RECEIVER_EXPORTED);

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }

        updatePttMediaSessionState();
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        mRadioReceiveTracker.clear();
        mRadioRoomReady = false;
        releasePttForSafety(true);
        super.onConnectionDisconnected(e);
        // Humla disconnects its connection from super.onDestroy(). Dynamic dispatch can therefore
        // arrive here after destroyMumlaResources() has already cleared app-owned notification and
        // UI fields. The Humla half still receives the disconnect above; skip only the cleared
        // Mumla resources so teardown remains idempotent and cannot throw a late NPE.
        if (mDestroying) {
            return;
        }
        updatePttMediaSessionState();
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {
        }

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                updatePttMediaSessionState();
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = RadioPttKeyManager.shouldEnablePttConfirmationSound(
                        RadioDeviceProfile.detectCurrent(), mSettings.isPttSoundEnabled());
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("WakelockTimeout")
    private void setProximitySensorOn(boolean on) {
        if(on) {
            if (mProximityLock != null && mProximityLock.isHeld()) {
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            // This lifecycle-owned lock must remain held while handset audio is connected.
            mProximityLock.setReferenceCounted(false);
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null && mProximityLock.isHeld()) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    /**
     * Creates the media-session bridge used for media-style hardware PTT keys.
     *
     * This deliberately does not claim arbitrary F1/F2 keys. Android only routes media-button
     * keys through MediaSession for an ordinary application; T99's GPIO/F-key path needs a
     * device-specific broadcast or privileged input integration discovered separately.
     */
    private boolean isManagedRadioDevice() {
        return RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.detectCurrent());
    }

    @SuppressWarnings("deprecation")
    private void wakeRadioDisplay() {
        wakeRadioDisplay(false);
    }

    private void wakeRadioDisplay(boolean connectOnPtt) {
        if (!isManagedRadioDevice()) {
            return;
        }
        if (connectOnPtt) {
            RadioPttRecoveryGuard.requireRelease();
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastRadioWakeElapsedRealtime < RADIO_WAKE_COOLDOWN_MS) {
            return;
        }
        mLastRadioWakeElapsedRealtime = now;

        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager != null) {
            if (mRadioScreenWakeLock == null) {
                mRadioScreenWakeLock = manager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "Mumla:RadioDisplay");
            }
            if (!mRadioScreenWakeLock.isHeld()) {
                mRadioScreenWakeLock.acquire(RADIO_WAKE_DURATION_MS);
            }
        }

        Intent radio = new Intent(this, RadioShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (connectOnPtt) {
            radio.putExtra(RadioShellActivity.EXTRA_CONNECT_ON_PTT, true);
        }
        try {
            startActivity(radio);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to bring radio status to foreground", error);
        }
    }

    private void playPttFailureAlert() {
        try {
            if (mRadioAlertTone == null) {
                mRadioAlertTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            }
            mRadioAlertTone.startTone(ToneGenerator.TONE_SUP_ERROR, 700);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to play PTT failure tone", error);
        }
        sendBroadcast(new Intent(ACTION_RADIO_PTT_FAILURE).setPackage(getPackageName()));
    }

    private void initializePttMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        mPttMediaSession = new MediaSession(this, TAG + ".PttMediaSession");
        mPttMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mPttMediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent keyEvent = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                RadioKeyDiagnostics.record(MumlaService.this, "media-session", keyEvent);
                if (keyEvent == null || !isConfiguredMediaPttKey(keyEvent.getKeyCode())) {
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }

                if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                    if (!mPttMediaKeyDown && keyEvent.getRepeatCount() == 0) {
                        mPttMediaKeyDown = true;
                        onTalkKeyDown();
                    }
                    return true;
                }

                if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    if (mPttMediaKeyDown) {
                        mPttMediaKeyDown = false;
                        onTalkKeyUp();
                    }
                    return true;
                }

                return true;
            }
        });
    }

    /**
     * Only media-style keys can reach this callback. The configured key still controls whether
     * the event is treated as PTT, so ordinary media buttons are not hijacked by default.
     */
    private boolean isConfiguredMediaPttKey(int keyCode) {
        if (mSettings == null || !RadioPttKeyManager.isConfiguredPttKey(keyCode, mSettings)) {
            return false;
        }

        return RadioPttKeyManager.isMediaStyleKey(keyCode);
    }

    private void updatePttMediaSessionState() {
        boolean pttMode = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
        boolean shouldBeActive = pttMode
                && (isConnectionEstablished() || isManagedRadioDevice());
        setPttMediaSessionActive(shouldBeActive);
    }

    private void setPttMediaSessionActive(boolean active) {
        if (mPttMediaSession == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        if (active == mPttMediaSession.isActive()) {
            return;
        }

        if (!active && mPttMediaKeyDown) {
            mPttMediaKeyDown = false;
            onTalkKeyUp();
        }
        mPttMediaSession.setActive(active);
    }

    /** True while at least one remote user is delivering audible voice. */
    public boolean isRadioReceiving() {
        return mRadioReceiveTracker.isReceiving();
    }

    @Override
    public List<String> getRadioTalkers() {
        return mRadioReceiveTracker.getActiveTalkers();
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyDown() {
        if (mPttInputDown || mPttWatchdogLockout) {
            return;
        }
        if (mAprsTrackingManager != null) {
            mAprsTrackingManager.onPttPressed();
        }
        boolean synchronizedSession = isSynchronized();
        boolean managedRadio = isManagedRadioDevice();
        boolean pttMode = Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
        boolean readyToTransmit = RadioPttSafetyPolicy.canStartTransmission(
                synchronizedSession, pttMode, managedRadio, mRadioRoomReady);
        wakeRadioDisplay(!readyToTransmit);
        if (!readyToTransmit) {
            playPttFailureAlert();
            return;
        }

        mPttInputDown = true;
        mPttFailureAlerted = false;
        if (!mSettings.isPushToTalkToggle() && !isTalking()) {
            setPttTalkingState(true); // Start talking
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyUp() {
        RadioPttRecoveryGuard.noteRelease();
        boolean wasLockedOut = mPttWatchdogLockout;
        boolean wasTalking = isTalking();
        boolean hadNoAudioPacket = wasTalking && mPttPressStartedElapsedRealtime > 0L
                && getLastAudioPacketSentElapsedRealtime() < mPttPressStartedElapsedRealtime;
        mPttInputDown = false;
        mPttWatchdogLockout = false;
        if (wasLockedOut) {
            disarmPttTransmissionWatchdog();
            mPttFailureAlerted = false;
            return;
        }
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setPttTalkingState(!wasTalking); // Toggle talk state
            } else if (isTalking()) {
                setPttTalkingState(false); // Stop talking
            }
        } else {
            disarmPttTransmissionWatchdog();
        }
        if (hadNoAudioPacket && !mPttFailureAlerted) {
            playPttFailureAlert();
        }
        mPttFailureAlerted = false;
    }

    @Override
    public void onExternalTalkCommand(String status) {
        if (mDestroying) {
            return;
        }
        boolean readyToTransmit = RadioPttSafetyPolicy.canStartTransmission(
                isSynchronized(),
                Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod()),
                isManagedRadioDevice(),
                mRadioRoomReady);
        RadioExternalTalkPolicy.Decision decision = RadioExternalTalkPolicy.decide(
                status,
                isTalking(),
                readyToTransmit,
                mPttWatchdogLockout || RadioPttRecoveryGuard.isReleaseRequired());
        if (decision == RadioExternalTalkPolicy.Decision.START) {
            setPttTalkingState(true);
        } else if (decision == RadioExternalTalkPolicy.Decision.STOP) {
            // An explicit OFF (or toggle from ON) is the release edge for legacy control.
            RadioPttRecoveryGuard.noteRelease();
            releasePttForSafety(false);
        } else if (decision == RadioExternalTalkPolicy.Decision.REJECT) {
            Log.w(TAG, "External TALK start rejected by radio readiness or release gate");
        } else if (decision == RadioExternalTalkPolicy.Decision.KEEP) {
            // Do not extend an existing deadline, but adopt any legacy unarmed TX safely.
            ensurePttTransmissionWatchdogArmed();
        }
    }

    private void setPttTalkingState(boolean talking) {
        boolean wasTalking = isTalking();
        if (wasTalking == talking) {
            if (talking) {
                ensurePttTransmissionWatchdogArmed();
            }
            return;
        }
        setTalkingState(talking);
        boolean isNowTalking = isTalking();
        if (RadioPttWatchdogPolicy.shouldArm(wasTalking, isNowTalking)) {
            armPttTransmissionWatchdog();
        } else if (RadioPttWatchdogPolicy.shouldDisarm(wasTalking, isNowTalking)) {
            disarmPttTransmissionWatchdog();
        }
    }

    private void ensurePttTransmissionWatchdogArmed() {
        if (isTalking() && !mPttWatchdogArmed) {
            armPttTransmissionWatchdog();
        }
    }

    private void armPttTransmissionWatchdog() {
        mPttWatchdogHandler.removeCallbacks(mPttWatchdog);
        mPttWatchdogHandler.removeCallbacks(mPttDeliveryFailureCheck);
        mPttWatchdogArmed = true;
        mPttFailureAlerted = false;
        mPttPressStartedElapsedRealtime = SystemClock.elapsedRealtime();
        mArmedPttMaximumSeconds = mMaximumPttSeconds;
        mPttWatchdogHandler.postDelayed(mPttWatchdog,
                RadioPttWatchdogPolicy.delayMillis(mArmedPttMaximumSeconds));
        mPttWatchdogHandler.postDelayed(mPttDeliveryFailureCheck,
                PTT_DELIVERY_CONFIRM_MS);
    }

    private void disarmPttTransmissionWatchdog() {
        mPttWatchdogArmed = false;
        mPttWatchdogHandler.removeCallbacks(mPttWatchdog);
        mPttWatchdogHandler.removeCallbacks(mPttDeliveryFailureCheck);
        mPttPressStartedElapsedRealtime = 0L;
    }

    @Override
    public void requirePttRelease() {
        RadioPttRecoveryGuard.requireRelease();
        releasePttForSafety(true);
    }

    @Override
    public void setRadioRoomReady(boolean ready) {
        mRadioRoomReady = ready;
    }

    @Override
    public void setMaximumPttSeconds(int maximumTxSeconds) {
        int validated = RadioPttWatchdogPolicy.sanitizeMaximumSeconds(maximumTxSeconds);
        if (mMaximumPttSeconds == validated) {
            return;
        }
        boolean transmissionActive = mPttInputDown || isTalking();
        if (transmissionActive) {
            releasePttForSafety(true);
        }
        mMaximumPttSeconds = validated;
    }

    /** Stops TX and clears pending watchdog work during lifecycle or connection failures. */
    private void releasePttForSafety(boolean requireRelease) {
        disarmPttTransmissionWatchdog();
        mPttInputDown = false;
        mPttFailureAlerted = false;
        mPttWatchdogLockout = requireRelease;
        if (isTalking()) {
            setPttTalkingState(false);
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    private void appendMessageLog(IChatMessage message) {
        appendMessageLog(mMessageLog, message);
    }

    static void appendMessageLog(List<IChatMessage> messageLog, IChatMessage message) {
        if (messageLog == null || message == null) {
            return;
        }
        int overflow = messageLog.size() - MAX_MESSAGE_LOG_ENTRIES + 1;
        if (overflow > 0) {
            messageLog.subList(0, overflow).clear();
        }
        messageLog.add(message);
    }

    @Override
    public void reloadTrackingConfig() {
        if (mAprsTrackingManager == null
                || !RadioDeviceProfile.T56.equals(RadioDeviceProfile.detectCurrent())) {
            return;
        }
        final AprsTrackingManager manager = mAprsTrackingManager;
        new Thread(() -> {
            try {
                manager.reloadConfig(new RadioConfigRepository(MumlaService.this)
                        .loadActiveOrDefault());
            } catch (Exception exception) {
                Log.w(TAG, "T56 tracking config reload skipped: "
                        + exception.getClass().getSimpleName());
            }
        }, "minimum-t56-tracking-config").start();
    }

    /** Requests a reload only when the service is already running. */
    public static void reloadTrackingConfigIfRunning() {
        MumlaService service = sRunningService;
        if (service != null) {
            service.reloadTrackingConfig();
        }
    }

    /**
     * Manifest receivers for these OEM implicit broadcasts are suppressed by Android 8 while the
     * display is off. The foreground service remains alive, so a context receiver is the reliable
     * lifecycle owner for the physical PTT edges.
     */
    private void registerRadioHardwarePttReceiver() {
        String profile = RadioDeviceProfile.detectCurrent();
        IntentFilter filter = new IntentFilter();
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            filter.addAction(RadioHardwareKeyReceiver.ACTION_RYKS_PTT_DOWN);
            filter.addAction(RadioHardwareKeyReceiver.ACTION_RYKS_PTT_UP);
        } else if (RadioDeviceProfile.T56.equals(profile)) {
            filter.addAction(RadioHardwareKeyReceiver.ACTION_T56_PTT_DOWN);
            filter.addAction(RadioHardwareKeyReceiver.ACTION_T56_PTT_UP);
        } else {
            return;
        }
        mRadioHardwareKeyReceiver = new RadioHardwareKeyReceiver();
        ContextCompat.registerReceiver(this, mRadioHardwareKeyReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        appendMessageLog(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        appendMessageLog(new IChatMessage.TextMessage(msg));
        return msg;
    }
}
