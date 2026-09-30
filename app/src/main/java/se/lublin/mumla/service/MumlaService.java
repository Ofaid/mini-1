package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;
import androidx.preference.PreferenceManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.IChatMessage;
import se.lublin.mumla.service.LocationCommentReporter;
import se.lublin.mumla.service.MumlaConnectionNotification;
import se.lublin.mumla.service.MumlaHotCorner;
import se.lublin.mumla.service.MumlaReconnectNotification;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.AvatarUtils;
import se.lublin.mumla.util.HtmlUtils;

public class MumlaService extends HumlaService implements SharedPreferences.OnSharedPreferenceChangeListener, MumlaConnectionNotification.OnActionListener, MumlaReconnectNotification.OnActionListener, IMumlaService {
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int RECONNECT_DELAY = 10000;
    private static final String TAG = MumlaService.class.getName();
    public static final int TTS_THRESHOLD = 250;
    
    // --- MODIFIKASI: Konstanta Audio Stream ---
    private static final int STREAM_VOICE_CALL = 0;
    private static final int STREAM_MUSIC = 3;
    // ------------------------------------------

    private MumlaOverlay mChannelOverlay;
    private boolean mErrorShown;
    private MumlaHotCorner mHotCorner;
    private List<IChatMessage> mMessageLog;
    private MumlaMessageNotification mMessageNotification;
    private MumlaConnectionNotification mNotification;
    private boolean mPTTSoundEnabled;
    private PowerManager.WakeLock mProximityLock;
    private MumlaReconnectNotification mReconnectNotification;
    private int mRogerBeepSoundId;
    private String mRogerBeepTone;
    private boolean mSelfWasTalkingPtt;
    private Settings mSettings;
    private boolean mShortTtsMessagesEnabled;
    private SoundPool mSoundPool;
    private boolean mSuppressNotifications;
    private TextToSpeech mTTS;
    private BroadcastReceiver mTalkReceiver;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if (status == -1) {
                logWarning(getString(R.string.tts_failed));
            }
        }
    };
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
    private HumlaObserver mObserver = new HumlaObserver() {
        public void onConnecting() {
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }
            String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this, getString(R.string.mumlaConnecting) + tor, MumlaService.this);
            mNotification.show();
            mErrorShown = false;
        }

        public void onConnected() {
            if (mNotification != null) {
                String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }
            reportLocationComment();
            reportCachedAvatar();
        }

        public void onDisconnected(HumlaException e) {
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification = MumlaReconnectNotification.show(MumlaService.this, e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""), isReconnecting(), MumlaService.this);
            }
        }

        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null && user.getTexture() == null) {
                requestAvatar(user.getSession());
            }
        }

        public void onUserStateUpdated(IUser user) {
            String contentText;
            if (user == null) return;
            try {
                int selfSession = getSessionId();
                if (user.getSession() == selfSession) {
                    mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened());
                    if (mNotification != null) {
                        if (user.isSelfMuted() && user.isSelfDeafened()) {
                            contentText = getString(R.string.status_notify_muted_and_deafened);
                        } else if (user.isSelfMuted()) {
                            contentText = getString(R.string.status_notify_muted);
                        } else {
                            contentText = getString(R.string.connected);
                        }
                        mNotification.setCustomContentText(contentText);
                        mNotification.show();
                    }
                }
                if (user.getTextureHash() != null && user.getTexture() == null) {
                    requestAvatar(user.getSession());
                }
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
            }
        }

        public void onMessageLogged(IMessage message) {
            String ttsMessage;
            String urlHostname;
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();
            if (mShortTtsMessagesEnabled) {
                Iterator it = parsedMessage.getElementsByTag("A").iterator();
                while (it.hasNext()) {
                    Element anchor = (Element) it.next();
                    String href = anchor.attr("href");
                    if (href != null && href.equals(anchor.text()) && (urlHostname = HtmlUtils.getHostnameFromLink(href)) != null) {
                        anchor.text(getString(R.string.chat_message_tts_short_link, new Object[]{urlHostname}));
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }
            String formattedTtsMessage = getString(R.string.notification_message, new Object[]{message.getActorName(), ttsMessage});
            if (mSettings.isTextToSpeechEnabled() && mTTS != null && formattedTtsMessage.length() <= 250 && getSessionUser() != null && !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, 1, null);
            }
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }
            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        public void onPermissionDenied(String reason) {
            if (mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }
            if (isConnectionEstablished() && user.getSession() == selfSession) {
                if (getTransmitMode() == 1) {
                    boolean isTalkingNow = user.getTalkState() == TalkState.TALKING;
                    if (isTalkingNow && mPTTSoundEnabled) {
                        AudioManager audioManager = (AudioManager) getSystemService("audio");
                        audioManager.playSoundEffect(5, -1.0f);
                    }
                    if (!isTalkingNow && mSelfWasTalkingPtt) {
                        playRogerBeep();
                    }
                    mSelfWasTalkingPtt = isTalkingNow;
                }
            }
        }
    };

    private void loadRogerBeepSound() {
        this.mRogerBeepSoundId = 0;
        if (this.mRogerBeepTone == null || "off".equals(this.mRogerBeepTone) || this.mSoundPool == null) {
            return;
        }
        int resId = getResources().getIdentifier("roger_beep_" + this.mRogerBeepTone, "raw", getPackageName());
        if (resId != 0) {
            this.mRogerBeepSoundId = this.mSoundPool.load(this, resId, 1);
        } else {
            Log.w(TAG, "roger beep tone '" + this.mRogerBeepTone + "' has no matching res/raw file");
        }
    }

    private void playRogerBeep() {
        if (this.mSoundPool != null && this.mRogerBeepSoundId != 0) {
            this.mSoundPool.play(this.mRogerBeepSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private short[] loadRogerBeepPcm(String tone) {
        int resId;
        if (tone == null || "off".equals(tone) || (resId = getResources().getIdentifier("roger_beep_" + tone, "raw", getPackageName())) == 0) {
            return null;
        }
        try {
            InputStream is = getResources().openRawResource(resId);
            byte[] wavBytes = readAllBytes(is);
            short[] pcm = parseWavPcm16Mono(wavBytes);
            if (pcm == null) {
                Log.w(TAG, "roger beep tone '" + tone + "': couldn't parse .wav data chunk");
            }
            if (is != null) is.close();
            return pcm;
        } catch (IOException e) {
            Log.w(TAG, "roger beep tone '" + tone + "': failed to read .wav resource", e);
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        while (true) {
            int n = is.read(data);
            if (n != -1) buffer.write(data, 0, n);
            else return buffer.toByteArray();
        }
    }

    private static short[] parseWavPcm16Mono(byte[] wav) {
        if (wav.length >= 12 && wav[0] == 82 && wav[1] == 73 && wav[2] == 70 && wav[3] == 70) {
            int offset = 12;
            while (offset + 8 <= wav.length) {
                String chunkId = new String(wav, offset, 4, StandardCharsets.US_ASCII);
                int chunkSize = (wav[offset + 4] & 255) | ((wav[offset + 5] & 255) << 8) | ((wav[offset + 6] & 255) << 16) | ((wav[offset + 7] & 255) << 24);
                int dataStart = offset + 8;
                if ("data".equals(chunkId)) {
                    if (dataStart + chunkSize > wav.length) chunkSize = wav.length - dataStart;
                    int sampleCount = chunkSize / 2;
                    short[] samples = new short[sampleCount];
                    for (int i = 0; i < sampleCount; i++) {
                        int lo = wav[(i * 2) + dataStart] & 255;
                        int hi = wav[(i * 2) + dataStart + 1];
                        samples[i] = (short) ((hi << 8) | lo);
                    }
                    return samples;
                }
                offset = dataStart + chunkSize + (chunkSize % 2);
            }
            return null;
        }
        return null;
    }

    private void reportLocationComment() {
        LocationCommentReporter.fetchLocationComment(this, new LocationCommentReporter.Callback() {
            @Override
            public void onCommentReady(String comment) {
                try {
                    if (!isConnected()) return;
                    IHumlaSession session = HumlaSession();
                    session.setUserComment(session.getSessionId(), comment);
                } catch (IllegalStateException | HumlaDisconnectedException e) {
                    Log.w(TAG, "failed to set location comment", e);
                }
            }
            @Override
            public void onFailed(String reason) {
                Log.d(TAG, "location comment not sent: " + reason);
            }
        });
    }

    private void reportCachedAvatar() {
        byte[] cached = AvatarUtils.loadCachedAvatar(this);
        if (cached == null || cached.length == 0) return;
        try {
            if (!isConnected()) return;
            IHumlaSession session = HumlaSession();
            session.setUserTexture(session.getSessionId(), cached);
        } catch (HumlaDisconnectedException | IllegalStateException e) {
            Log.w(TAG, "failed to resend cached avatar", e);
        }
    }

    public void onCreate() {
        super.onCreate();
        registerObserver(this.mObserver);
        this.mSettings = Settings.getInstance(this);
        this.mPTTSoundEnabled = this.mSettings.isPttSoundEnabled();
        this.mShortTtsMessagesEnabled = this.mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);
        this.mSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder().setUsage(13).setContentType(4).build()).build();
        this.mRogerBeepTone = this.mSettings.getRogerBeepTone();
        loadRogerBeepSound();
        setRogerBeepSamples(loadRogerBeepPcm(this.mRogerBeepTone));
        setTheme(R.style.Theme_Mumla);
        this.mMessageLog = new ArrayList();
        this.mMessageNotification = new MumlaMessageNotification(this);
        this.mChannelOverlay = new MumlaOverlay(this);
        this.mHotCorner = new MumlaHotCorner(this, this.mSettings.getHotCornerGravity(), this.mHotCornerListener);
        if (this.mSettings.isTextToSpeechEnabled()) {
            this.mTTS = new TextToSpeech(this, this.mTTSInitListener);
        }
        this.mTalkReceiver = new TalkBroadcastReceiver(this);
    }

    public IBinder onBind(Intent intent) {
        return new MumlaBinder();
    }

    public void onDestroy() {
        if (this.mNotification != null) {
            this.mNotification.hide();
            this.mNotification = null;
        }
        if (this.mReconnectNotification != null) {
            this.mReconnectNotification.hide();
            this.mReconnectNotification = null;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try { unregisterReceiver(this.mTalkReceiver); } catch (IllegalArgumentException e) { e.printStackTrace(); }
        unregisterObserver(this.mObserver);
        if (this.mTTS != null) this.mTTS.shutdown();
        if (this.mSoundPool != null) {
            this.mSoundPool.release();
            this.mSoundPool = null;
        }
        this.mMessageLog = null;
        this.mMessageNotification.dismiss();
        super.onDestroy();
    }

    // --- MODIFIKASI UTAMA: FORCE WAKE UP AUDIO DI SINI ---
    public void onConnectionSynchronized() {
        try {
            super.onConnectionSynchronized();
            
            if (this.mSettings.isMuted() || this.mSettings.isDeafened()) {
                setSelfMuteDeafState(this.mSettings.isMuted(), this.mSettings.isDeafened());
            }
            if (Build.VERSION.SDK_INT >= 34) {
                registerReceiver(this.mTalkReceiver, new IntentFilter("se.lublin.mumla.action.TALK"), 2);
            } else {
                registerReceiver(this.mTalkReceiver, new IntentFilter("se.lublin.mumla.action.TALK"));
            }
            if (this.mSettings.isHotCornerEnabled()) {
                this.mHotCorner.setShown(true);
            }
            if (this.mSettings.isHandsetMode()) {
                setProximitySensorOn(true);
            }

            // PAKSA INISIALISASI AUDIO STREAM AGAR LANGSUNG NYALA
            Bundle audioExtras = new Bundle();
            // Set stream type sesuai mode handset (0=Voice Call, 3=Music)
            audioExtras.putInt("audio_stream", 
                this.mSettings.isHandsetMode() ? STREAM_VOICE_CALL : STREAM_MUSIC);
            
            // Kirim parameter audio dasar untuk memicu alloc buffer di底层 engine
            audioExtras.putInt("frames_per_packet", this.mSettings.getFramesPerPacket());
            audioExtras.putInt("input_quality", this.mSettings.getInputQuality());
            
            try {
                configureExtras(audioExtras);
                Log.d(TAG, "Audio stream force-initialized on sync");
            } catch (AudioException e) {
                Log.e(TAG, "Failed to init audio stream", e);
            }
            
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
        }
    }
    // -----------------------------------------------------

    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);
        try { unregisterReceiver(this.mTalkReceiver); } catch (IllegalArgumentException e2) {}
        this.mChannelOverlay.hide();
        this.mHotCorner.setShown(false);
        setProximitySensorOn(false);
        clearMessageLog();
        this.mMessageNotification.dismiss();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        char c;
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key.hashCode()) {
            case -2067778698: if (key.equals("roger_beep")) c = 11; else c = 65535; break;
            case -2029305277: if (key.equals("shortTtsMessages")) c = 5; else c = 65535; break;
            case -1863259392: if (key.equals("ptt_sound")) c = '\n'; else c = 65535; break;
            case -1591013384: if (key.equals("input_bitrate")) c = '\f'; else c = 65535; break;
            case -1217870590: if (key.equals("hotCorner")) c = 3; else c = 65535; break;
            case -1049000753: if (key.equals("handset_mode")) c = 1; else c = 65535; break;
            case -837528182: if (key.equals("input_quality")) c = '\r'; else c = 65535; break;
            case -836058544: if (key.equals("useTor")) c = 17; else c = 65535; break;
            case -836058388: if (key.equals("useTts")) c = 4; else c = 65535; break;
            case -644529902: if (key.equals("certificateId")) c = 15; else c = 65535; break;
            case -224875644: if (key.equals("inputVolume")) c = 6; else c = 65535; break;
            case 378885201: if (key.equals("preprocessor_enabled")) c = '\b'; else c = 65535; break;
            case 464933110: if (key.equals("forceTcp")) c = 16; else c = 65535; break;
            case 873529237: if (key.equals("audioInputMethod")) c = 0; else c = 65535; break;
            case 911058419: if (key.equals("audio_per_packet")) c = 14; else c = 65535; break;
            case 1343034700: if (key.equals("half_duplex")) c = 7; else c = 65535; break;
            case 1353190215: if (key.equals("disableOpus")) c = 18; else c = 65535; break;
            case 1445797507: if (key.equals("echo_cancellation_method")) c = '\t'; else c = 65535; break;
            case 2123245906: if (key.equals("vadThreshold")) c = 2; else c = 65535; break;
            default: c = 65535; break;
        }
        switch (c) {
            case 0:
                int inputMethod = this.mSettings.getHumlaInputMethod();
                changedExtras.putInt("transmit_mode", inputMethod);
                this.mChannelOverlay.setPushToTalkShown(inputMethod == 1);
                break;
            case 1:
                setProximitySensorOn(isConnectionEstablished() && this.mSettings.isHandsetMode());
                changedExtras.putInt("audio_stream", this.mSettings.isHandsetMode() ? STREAM_VOICE_CALL : STREAM_MUSIC);
                break;
            case 2:
                changedExtras.putFloat("detection_threshold", this.mSettings.getDetectionThreshold());
                break;
            case 3:
                this.mHotCorner.setGravity(this.mSettings.getHotCornerGravity());
                MumlaHotCorner mumlaHotCorner = this.mHotCorner;
                boolean r7 = isConnectionEstablished() && this.mSettings.isHotCornerEnabled();
                mumlaHotCorner.setShown(r7);
                break;
            case 4:
                if (this.mTTS == null && this.mSettings.isTextToSpeechEnabled()) {
                    this.mTTS = new TextToSpeech(this, this.mTTSInitListener);
                } else if (this.mTTS != null && !this.mSettings.isTextToSpeechEnabled()) {
                    this.mTTS.shutdown();
                    this.mTTS = null;
                }
                break;
            case 5:
                this.mShortTtsMessagesEnabled = this.mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case 6:
                changedExtras.putFloat("amplitude_boost", this.mSettings.getAmplitudeBoostMultiplier());
                break;
            case 7:
                changedExtras.putBoolean("half_duplex", this.mSettings.isHalfDuplex());
                break;
            case '\b':
                changedExtras.putBoolean("enable_preprocessor", this.mSettings.isPreprocessorEnabled());
                break;
            case '\t':
                changedExtras.putString("echo_cancellation_method", this.mSettings.getEchoCancellationMethod());
                break;
            case '\n':
                this.mPTTSoundEnabled = this.mSettings.isPttSoundEnabled();
                break;
            case 11:
                this.mRogerBeepTone = this.mSettings.getRogerBeepTone();
                loadRogerBeepSound();
                setRogerBeepSamples(loadRogerBeepPcm(this.mRogerBeepTone));
                break;
            case '\f':
                changedExtras.putInt("input_quality", this.mSettings.getInputQuality());
                break;
            case '\r':
                changedExtras.putInt("input_frequency", this.mSettings.getInputSampleRate());
                break;
            case 14:
                changedExtras.putInt("frames_per_packet", this.mSettings.getFramesPerPacket());
                break;
            case 15: case 16: case 17: case 18:
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) { e.printStackTrace(); }
        }
        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText((Context) this, R.string.change_requires_reconnect, 1).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if (on) {
            PowerManager pm = (PowerManager) getSystemService("power");
            this.mProximityLock = pm.newWakeLock(32, "Mumla:Proximity");
            this.mProximityLock.acquire();
            return;
        }
        if (this.mProximityLock != null) {
            this.mProximityLock.release();
        }
        this.mProximityLock = null;
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean deafened = true;
            boolean muted = !user.isSelfMuted();
            deafened = (user.isSelfDeafened() && muted) ? false : false;
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
        if (Build.VERSION.SDK_INT < 31) {
            Intent close = new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS");
            getApplicationContext().sendBroadcast(close);
        }
        if (!this.mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                Intent showSetting = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + getPackageName()));
                showSetting.setFlags(268435456);
                startActivity(showSetting);
                Toast.makeText((Context) this, R.string.grant_perm_draw_over_apps, 1).show();
                return;
            }
            this.mChannelOverlay.show();
            return;
        }
        this.mChannelOverlay.hide();
    }

    @Override
    public void onReconnectNotificationDismissed() {
        this.mErrorShown = true;
    }

    @Override
    public void reconnect() { connect(); }

    @Override
    public void cancelReconnect() {
        if (this.mReconnectNotification != null) {
            this.mReconnectNotification.hide();
            this.mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if (!this.mChannelOverlay.isShown()) this.mChannelOverlay.show();
        else this.mChannelOverlay.hide();
    }

    @Override
    public boolean isOverlayShown() { return this.mChannelOverlay.isShown(); }

    @Override
    public void clearChatNotifications() { this.mMessageNotification.dismiss(); }

    @Override
    public void markErrorShown() {
        this.mErrorShown = true;
        if (this.mReconnectNotification != null && !isReconnecting()) {
            this.mReconnectNotification.hide();
            this.mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() { return this.mErrorShown; }

    @Override
    public void onTalkKeyDown() {
        if (isConnectionEstablished() && "ptt".equals(this.mSettings.getInputMethod()) && !this.mSettings.isPushToTalkToggle() && !isTalking()) {
            setTalkingState(true);
        }
    }

    @Override
    public void onTalkKeyUp() {
        if (isConnectionEstablished() && "ptt".equals(this.mSettings.getInputMethod())) {
            if (this.mSettings.isPushToTalkToggle()) setTalkingState(!isTalking());
            else if (isTalking()) setTalkingState(false);
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() { return Collections.unmodifiableList(this.mMessageLog); }

    @Override
    public void clearMessageLog() { if (this.mMessageLog != null) this.mMessageLog.clear(); }

    @Override
    public void setSuppressNotifications(boolean suppressNotifications) { this.mSuppressNotifications = suppressNotifications; }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;
        private MumlaBinder(MumlaService service) { this.mService = service; }
        public IMumlaService getService() { return this.mService; }
    }

    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);
        this.mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);
        this.mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}