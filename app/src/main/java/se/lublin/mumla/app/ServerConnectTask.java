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

package se.lublin.mumla.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.AsyncTask;

import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.HumlaService;
import se.lublin.humla.model.Server;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.MumlaTrustStore;

/**
 * Constructs an intent for connection to a MumlaService and executes it.
 * Created by andrew on 20/08/14.
 */
public class ServerConnectTask extends AsyncTask<Server, Void, Intent> {
    private static final long MANAGED_RADIO_CONNECTION_INTERVAL_MS = 15000L;

    private Context mContext;
    private MumlaDatabase mDatabase;
    private Settings mSettings;
    private final List<String> mAccessTokens;
    private final Boolean mAutoReconnect;

    public ServerConnectTask(Context context, MumlaDatabase database) {
        this(context, database, null, null);
    }

    /**
     * Creates a connection task with optional radio-config overrides. Tokens are copied and never
     * persisted to the Mumla database by this task.
     */
    public ServerConnectTask(Context context, MumlaDatabase database, List<String> accessTokens,
                             Boolean autoReconnect) {
        mContext = context;
        mDatabase = database;
        mSettings = Settings.getInstance(context);
        mAccessTokens = accessTokens == null ? null : new ArrayList<>(accessTokens);
        mAutoReconnect = autoReconnect;
    }

    @Override
    protected Intent doInBackground(Server... params) {
        Server server = params[0];

        /* Convert input method defined in settings to an integer format used by Humla. */
        int inputMethod = mSettings.getHumlaInputMethod();

        int audioSource = mSettings.isHandsetMode() ?
                MediaRecorder.AudioSource.DEFAULT : MediaRecorder.AudioSource.MIC;
        int audioStream = mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;

        Intent connectIntent = new Intent(mContext, MumlaService.class);
        boolean managedRadioConnection = mAutoReconnect != null;
        connectIntent.putExtra(HumlaService.EXTRAS_SERVER, server);
        connectIntent.putExtra(HumlaService.EXTRAS_CLIENT_NAME, mContext.getString(R.string.app_name)+" "+ BuildConfig.VERSION_NAME);
        connectIntent.putExtra(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
        connectIntent.putExtra(HumlaService.EXTRAS_DETECTION_THRESHOLD, mSettings.getDetectionThreshold());
        connectIntent.putExtra(HumlaService.EXTRAS_AMPLITUDE_BOOST, mSettings.getAmplitudeBoostMultiplier());
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT,
                mAutoReconnect == null ? mSettings.isAutoReconnectEnabled() : mAutoReconnect);
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT_DELAY,
                mAutoReconnect == null ? MumlaService.RECONNECT_DELAY
                        : (int) MANAGED_RADIO_CONNECTION_INTERVAL_MS);
        connectIntent.putExtra(HumlaService.EXTRAS_RECONNECT_ON_ALL_ERRORS,
                false);
        connectIntent.putExtra(HumlaService.EXTRAS_REDELIVER_CONNECT_INTENT,
                managedRadioConnection && Boolean.TRUE.equals(mAutoReconnect));
        connectIntent.putExtra(HumlaService.EXTRAS_MIN_CONNECTION_ATTEMPT_INTERVAL,
                managedRadioConnection ? MANAGED_RADIO_CONNECTION_INTERVAL_MS : 0L);
        connectIntent.putExtra(HumlaService.EXTRAS_USE_OPUS, !mSettings.isOpusDisabled());
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
        connectIntent.putExtra(HumlaService.EXTRAS_FORCE_TCP, mSettings.isTcpForced());
        connectIntent.putExtra(HumlaService.EXTRAS_USE_TOR, mSettings.isTorEnabled());
        ArrayList<String> accessTokens = mAccessTokens == null
                ? new ArrayList<>(mDatabase.getAccessTokens(server.getId()))
                : new ArrayList<>(mAccessTokens);
        connectIntent.putStringArrayListExtra(HumlaService.EXTRAS_ACCESS_TOKENS, accessTokens);
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_SOURCE, audioSource);
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_STREAM, audioStream);
        connectIntent.putExtra(HumlaService.EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE, MumlaTrustStore.getTrustStorePath(mContext));
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE_PASSWORD, MumlaTrustStore.getTrustStorePassword());
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE_FORMAT, MumlaTrustStore.getTrustStoreFormat());
        connectIntent.putExtra(HumlaService.EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
        connectIntent.putExtra(HumlaService.EXTRAS_ENABLE_PREPROCESSOR, mSettings.isPreprocessorEnabled());
        connectIntent.putExtra(HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD, mSettings.getEchoCancellationMethod());
        if (server.isSaved()) {
            ArrayList<Integer> muteHistory = (ArrayList<Integer>) mDatabase.getLocalMutedUsers(server.getId());
            ArrayList<Integer> ignoreHistory = (ArrayList<Integer>) mDatabase.getLocalIgnoredUsers(server.getId());
            connectIntent.putExtra(HumlaService.EXTRAS_LOCAL_MUTE_HISTORY, muteHistory);
            connectIntent.putExtra(HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY, ignoreHistory);
        }

        if (mSettings.isUsingCertificate()) {
            long certificateId = mSettings.getDefaultCertificate();
            byte[] certificate = mDatabase.getCertificateData(certificateId);
            if (certificate != null)
                connectIntent.putExtra(HumlaService.EXTRAS_CERTIFICATE, certificate);
            // TODO(acomminos): handle the case where a certificate's data is unavailable.
        }

        connectIntent.setAction(HumlaService.ACTION_CONNECT);
        return connectIntent;
    }

    @Override
    protected void onPostExecute(Intent intent) {
        super.onPostExecute(intent);
        mContext.startService(intent);
    }
}
