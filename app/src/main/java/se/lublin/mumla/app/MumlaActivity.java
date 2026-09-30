/*
 * Copyright (C) 2014 Andrew Comminos
 * Modif By Ofaid/Ahmad 2026 — Langsung Masuk Server + Sertifikat Otomatis
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

import static java.util.Objects.requireNonNull;
package se.lublin.mumla.app;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HumlaCertificateGenerator;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.MumbleURLParser;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.db.PublicServer;
import se.lublin.mumla.preference.MumlaCertificateGenerateTask;
import se.lublin.mumla.preference.SettingsActivity;
import se.lublin.mumla.servers.FavouriteServerListFragment;
import se.lublin.mumla.servers.PublicServerListFragment;
import se.lublin.mumla.servers.ServerEditFragment;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;
import se.lublin.mumla.util.HumlaServiceProvider;
import se.lublin.mumla.util.MumlaTrustStore;
import se.lublin.mumla.channel.AccessTokenFragment;
import se.lublin.mumla.channel.ChannelFragment;
import se.lublin.mumla.channel.ServerInfoFragment;

public class MumlaActivity extends AppCompatActivity
        implements ListView.OnItemClickListener,
                   FavouriteServerListFragment.ServerConnectHandler,
                   HumlaServiceProvider,
                   DatabaseProvider,
                   SharedPreferences.OnSharedPreferenceChangeListener,
                   DrawerAdapter.DrawerDataProvider,
                   ServerEditFragment.ServerEditListener {

    private static final String TAG = MumlaActivity.class.getName();
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";

    private IMumlaService mService;
    private MumlaDatabase mDatabase;
    private Settings mSettings;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private DrawerAdapter mDrawerAdapter;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;
    private Server mServerPendingPerm = null;
    private boolean mPermPostNotificationsAsked = false;

    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;

    private final List<HumlaServiceFragment> mServiceFragments = new ArrayList<>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((MumlaService.MumlaBinder) service).getService();
            mService.setSuppressNotifications(true);
            mService.registerObserver(mObserver);
            mService.clearChatNotifications();
            mDrawerAdapter.notifyDataSetChanged();

            for (HumlaServiceFragment fragment : mServiceFragments) {
                fragment.setServiceBound(true);
            }

            if (getSupportFragmentManager().findFragmentById(R.id.content_frame)
                    instanceof HumlaServiceFragment && !mService.isConnected()) {
                // === OFAID: Sambung Otomatis ke Server Tetap ===
                if (!"mumble.samto.my.id".isEmpty()) {
                    Server embedded = findOrCreateEmbeddedServer();
                    if (embedded != null) {
                        connectToServer(embedded);
                    } else {
                        loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                    }
                } else {
                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                }
            }
            updateConnectionState(getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {
            if (mSettings.shouldStartUpInPinnedMode()) {
                loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS);
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_SERVER);
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();
            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame)
                    instanceof HumlaServiceFragment) {
                // === OFAID: Keluar ke Daftar Server / Tutup ===
                if (!"mumble.samto.my.id".isEmpty()) {
                    finishAndRemoveTask();
                } else {
                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                }
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();
            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            if (chain.length == 0) return;
            final Server lastServer = getService().getTargetServer();
            try {
                final X509Certificate x509 = chain[0];
                View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
                TextView textView = layout.findViewById(R.id.certificate_info_text);

                try {
                    MessageDigest digest1 = MessageDigest.getInstance("SHA-1");
                    MessageDigest digest2 = MessageDigest.getInstance("SHA-256");
                    String hexDigest1 = new String(Hex.encode(digest1.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");
                    String hexDigest2 = new String(Hex.encode(digest2.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");
                    textView.setText(getString(R.string.certificate_info,
                            x509.getSubjectDN().getName(),
                            x509.getNotBefore().toString(),
                            x509.getNotAfter().toString(),
                            hexDigest1.substring(0, hexDigest1.length() - 1),
                            hexDigest2.substring(0, hexDigest2.length() - 1)));
                } catch (NoSuchAlgorithmException nsae) {
                    textView.setText(x509.toString());
                }

                new MaterialAlertDialogBuilder(MumlaActivity.this)
                        .setTitle(R.string.untrusted_certificate)
                        .setView(layout)
                        .setPositiveButton(R.string.allow, (dialog, which) -> {
                            try {
                                String alias = lastServer.getHost();
                                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                                trustStore.setCertificateEntry(alias, x509);
                                MumlaTrustStore.saveTrustStore(MumlaActivity.this, trustStore);
                                Toast.makeText(MumlaActivity.this, R.string.trust_added, Toast.LENGTH_SHORT).show();
                                connectToServer(lastServer);
                            } catch (Exception ex) {
                                Toast.makeText(MumlaActivity.this, R.string.trust_add_failed, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } catch (CertificateException ce) {
                ce.printStackTrace();
            }
        }

        @Override
        public void onPermissionDenied(String reason) {
            new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.perm_denied)
                    .setMessage(reason)
                    .show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSettings = Settings.getInstance(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mService != null && mService.isConnected()) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.disconnectSure,
                                    mService.getTargetServer().getName()))
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                mService.disconnect();
                                if (!"mumble.samto.my.id".isEmpty()) {
                                    finishAndRemoveTask();
                                } else {
                                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new MumlaSQLiteDatabase(this);
        mDatabase.open();

        mDrawerLayout = findViewById(R.id.drawer_layout);
        ListView mDrawerList = findViewById(R.id.left_drawer);

        View headerView = getLayoutInflater().inflate(R.layout.list_drawer_headerlogo, mDrawerList, false);
        mDrawerList.addHeaderView(headerView, null, false);

        if (BuildConfig.FLAVOR.equals("foss")) {
            int layoutResId = getResources().getIdentifier("list_drawer_headerdonate_foss",
                    "xml", getPackageName());
            int stringResId = getResources().getIdentifier("donate_link_foss",
                    "string", getPackageName());
            if (layoutResId != 0 && stringResId != 0) {
                View footerView = getLayoutInflater().inflate(layoutResId, mDrawerList, false);
                mDrawerList.addHeaderView(footerView, null, true);
                footerView.setOnClickListener(v -> {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(getString(stringResId))));
                    mDrawerLayout.closeDrawers();
                });
            }
        }

        mDrawerList.setOnItemClickListener(this);
        mDrawerAdapter = new DrawerAdapter(this, this);
        mDrawerList.setAdapter(mDrawerAdapter);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerClosed(View drawerView) {
                supportInvalidateOptionsMenu();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                super.onDrawerStateChanged(newState);
                if (getService() != null && getService().isConnected()) {
                    IHumlaSession session = getService().HumlaSession();
                    if (session.isTalking() && !mSettings.isPushToTalkToggle()) {
                        session.setTalkingState(false);
                    }
                }
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                supportInvalidateOptionsMenu();
            }
        };

        mDrawerLayout.setDrawerListener(mDrawerToggle);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(getIntent().getIntExtra(EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES));
            } else if (!"mumble.samto.my.id".isEmpty()) {
                // === OFAID: Tampilkan Dialog Nama Pengguna Lalu Langsung Sambung ===
                showEmbeddedServerCredentialsDialog();
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
        }

        if (getIntent() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String url = getIntent().getDataString();
            try {
                Server server = MumbleURLParser.parseURL(url);
                DialogFragment frag = ServerEditFragment.createServerEditDialog(
                        this, server, ServerEditFragment.Action.CONNECT_ACTION, true);
                frag.show(getSupportFragmentManager(), "url_edit");
            } catch (MalformedURLException e) {
                Toast.makeText(this, R.string.mumble_url_parse_failed, Toast.LENGTH_LONG).show();
            }
        }

        setVolumeControlStream(mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);

        if (savedInstanceState == null && "mumble.samto.my.id".isEmpty()) {
            if (mSettings.isFirstRun()) {
                ensureDefaultCertificate();
            } else {
                new StartupAction().execute(this);
            }
        }
    }

    // === OFAID: Dialog Nama Pengguna — Langsung Sambung ===
    private void showEmbeddedServerCredentialsDialog() {
        int pad = (int) (getResources().getDisplayMetrics().density * 16f);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView userLabel = new TextView(this);
        userLabel.setText(R.string.server_username);
        layout.addView(userLabel);

        final EditText userEdit = new EditText(this);
        userEdit.setHint(mSettings.getDefaultUsername());
        userEdit.setText(mSettings.getDefaultUsername()); // Isi Otomatis
        userEdit.setSingleLine(true);
        layout.addView(userEdit);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String username = userEdit.getText().toString().trim();
                    if (username.isEmpty()) {
                        username = mSettings.getDefaultUsername();
                    }
                    Server embedded = findOrCreateEmbeddedServer();
                    if (embedded != null) {
                        embedded.setUsername(username);
                        mDatabase.updateServer(embedded);
                        mSettings.setFirstRun(false);
                        maybeRequestIgnoreBatteryOptimizations();
                        if (!mSettings.isUsingCertificate()) {
                            generateEmbeddedCertificateSilently(embedded);
                        } else {
                            connectToServer(embedded);
                        }
                    }
                })
                .show();
    }

    private Server findOrCreateEmbeddedServer() {
        if ("mumble.samto.my.id".isEmpty()) return null;

        for (Server s : mDatabase.getServers()) {
            if ("mumble.samto.my.id".equalsIgnoreCase(s.getHost()) && s.getPort() == 22222) {
                return s;
            }
        }

        Server server = new Server(-1L, "Blambangan Online",
                "mumble.samto.my.id", 22222, mSettings.getDefaultUsername(), "");
        mDatabase.addServer(server);
        return server;
    }

    private void generateEmbeddedCertificateSilently(final Server embedded) {
        final Context ctx = getApplicationContext();
        new android.os.AsyncTask<Void, Void, se.lublin.mumla.db.DatabaseCertificate>() {
            @Override
            protected se.lublin.mumla.db.DatabaseCertificate doInBackground(Void... params) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    se.lublin.humla.net.HumlaCertificateGenerator.generateCertificate(baos);
                    String fileName = getString(R.string.certificate_export_format,
                            new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                                    .format(new java.util.Date()));
                    MumlaSQLiteDatabase db = new MumlaSQLiteDatabase(ctx);
                    se.lublin.mumla.db.DatabaseCertificate cert = db.addCertificate(fileName, baos.toByteArray());
                    db.close();
                    return cert;
                } catch (Exception e) {
                    Log.e(TAG, "silent cert gen failed", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(se.lublin.mumla.db.DatabaseCertificate result) {
                if (result != null) {
                    mSettings.setDefaultCertificateId(result.getId());
                }
                connectToServer(embedded);
            }
        }.execute();
    }

    private void maybeRequestIgnoreBatteryOptimizations() {
        if ("mumble.samto.my.id".isEmpty()) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent intent = new Intent(
                    "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot request battery exemption", e);
        }
    }

    private void ensureDefaultCertificate() {
        if (mSettings.isUsingCertificate()) {
            mSettings.setFirstRun(false);
            new StartupAction().execute(this);
            return;
        }
        MumlaCertificateGenerateTask task =
                new MumlaCertificateGenerateTask(this, false) {
                    @Override
                    protected void onPostExecute(se.lublin.mumla.db.DatabaseCertificate result) {
                        super.onPostExecute(result);
                        if (result != null) {
                            mSettings.setDefaultCertificateId(result.getId());
                            mSettings.setFirstRun(false);
                        } else {
                            Toast.makeText(MumlaActivity.this, R.string.generateCertFailure,
                                    Toast.LENGTH_SHORT).show();
                        }
                        new StartupAction().execute(MumlaActivity.this);
                    }
                };
        task.execute();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent serviceIntent = new Intent(this, MumlaService.class);
        bindService(serviceIntent, mConnection, 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mErrorDialog != null) mErrorDialog.dismiss();
        if (mConnectingDialog != null) mConnectingDialog.dismiss();
        if (mService != null) {
            for (HumlaServiceFragment f : mServiceFragments) {
                f.setServiceBound(false);
            }
            mService.unregisterObserver(mObserver);
            mService.setSuppressNotifications(false);
        }
        unbindService(mConnection);
    }

    @Override
    protected void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        mDatabase.close();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.mumla, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem disconnect = menu.findItem(R.id.action_disconnect);
        disconnect.setVisible(mService != null && mService.isConnected());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NotNull MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) return true;
        if (item.getItemId() == R.id.action_disconnect) {
            getService().disconnect();
            if (!"mumble.samto.my.id".isEmpty()) {
                finishAndRemoveTask();
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(@NotNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mService != null && RadioPttKeyManager.isConfiguredPttEvent(event, mSettings)
                && !isMediaPttKey(keyCode)) {
            mService.onTalkKeyDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mService != null && RadioPttKeyManager.isConfiguredPttEvent(event, mSettings)
                && !isMediaPttKey(keyCode)) {
            mService.onTalkKeyUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isMediaPttKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mDrawerLayout.closeDrawers();
        loadDrawerFragment((int) id);
    }

    private void loadDrawerFragment(int fragmentId) {
        Class<? extends Fragment> cls;
        Bundle args = new Bundle();

        switch (fragmentId) {
            case DrawerAdapter.ITEM_SERVER:
                cls = ChannelFragment.class;
                break;
            case DrawerAdapter.ITEM_PINNED_CHANNELS:
                cls = ChannelFragment.class;
                args.putBoolean("pinned", true);
                break;
            case DrawerAdapter.ITEM_INFO:
                cls = ServerInfoFragment.class;
                break;
            case DrawerAdapter.ITEM_ACCESS_TOKENS:
                cls = AccessTokenFragment.class;
                Server connected = getService().getTargetServer();
                args.putLong("server", connected.getId());
                args.putStringArrayList("access_tokens",
                        (ArrayList<String>) mDatabase.getAccessTokens(connected.getId()));
                break;
            case DrawerAdapter.ITEM_FAVOURITES:
                cls = FavouriteServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_PUBLIC:
                cls = PublicServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                return;
            default:
                return;
        }

        Fragment frag = Fragment.instantiate(this, cls.getName(), args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, frag, cls.getName())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();

        requireNonNull(getSupportActionBar()).setTitle(mDrawerAdapter.getItemWithId(fragmentId).title);
    }

    public void connectToServer(final Server server) {
        mServerPendingPerm = server;
        connectToServerWithPerm();
    }

    public void connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !mPermPostNotificationsAsked
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
            return;
        }

        if (mServerPendingPerm == null) {
            Log.w(TAG, "No pending server after permissions");
            return;
        }

        final Server server = mServerPendingPerm;
        mServerPendingPerm = null;

        if (mService != null && mService.isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.reconnect_dialog_message)
                    .setPositiveButton(R.string.connect, (dialog, which) -> {
                        mService.registerObserver(new HumlaObserver() {
                            @Override
                            public void onDisconnected(HumlaException e) {
                                connectToServer(server);
                                mService.unregisterObserver(this);
                            }
                        });
                        mService.disconnect();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        if (mSettings.isTorEnabled()) {
            if (!OrbotHelper.isOrbotInstalled(this)) {
                mSettings.disableTor();
                new MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.orbot_not_installed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            if (!isPortOpen(HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT, 2000)) {
                new MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.orbot_tor_failed, HumlaConnection.TOR_PORT))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
        }

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0) return;

        switch (requestCode) {
            case PERMISSIONS_REQUEST_RECORD_AUDIO:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm();
                } else {
                    Toast.makeText(this, R.string.grant_perm_microphone, Toast.LENGTH_LONG).show();
                }
                break;
            case PERMISSIONS_REQUEST_POST_NOTIFICATIONS:
                mPermPostNotificationsAsked = true;
                if (grantResults[0] == PackageManager.PERMISSION_DENIED
                        && ActivityCompat.shouldShowRequestPermissionRationale(this,
                                Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, R.string.grant_perm_notifications, Toast.LENGTH_LONG).show();
                }
                connectToServerWithPerm();
                break;
        }
    }

    private boolean isPortOpen(final String host, final int port, final int timeoutMs) {
        final AtomicBoolean open = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), timeoutMs);
                open.set(true);
            } catch (Exception ignored) {}
        });
        t.start();
        try { t.join(); } catch (InterruptedException ie) {}
        return open.get();
    }

    public void connectToPublicServer(final PublicServer server) {
        final EditText userInput = new EditText(this);
        userInput.setHint(mSettings.getDefaultUsername());
        FrameLayout container = new FrameLayout(this);
        int pad = getResources().getDimensionPixelSize(R.dimen.padding_medium);
        container.setPadding(pad, 0, pad, 0);
        container.addView(userInput);

        new MaterialAlertDialogBuilder(this)
                .setView(container)
                .setTitle(R.string.connectToServer)
                .setPositiveButton(R.string.connect, (dialog, which) -> {
                    String user = userInput.getText().toString().trim();
                    if (user.isEmpty()) {
                        user = mSettings.getDefaultUsername();
                    }
                    server.setUsername(user);
                    connectToServer(server);
                })
                .show();
    }

    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateConnectionState(IHumlaService service) {
        if (mConnectingDialog != null) mConnectingDialog.dismiss();
        if (mErrorDialog != null) mErrorDialog.dismiss();
        if (service == null) return;

        switch (mService.getConnectionState()) {
            case CONNECTING:
                Server svr = service.getTargetServer();
                String extra = mSettings.isTorEnabled() ? " (Tor)" : "";
                mConnectingDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.connecting_to_server, svr.getHost()) + extra)
                        .setView(R.layout.dialog_progress)
                        .setCancelable(true)
                        .setOnCancelListener(dialog -> {
                            mService.disconnect();
                            Toast.makeText(this, R.string.cancelled, Toast.LENGTH_SHORT).show();
                        })
                        .create();
                mConnectingDialog.show();
                break;

            case CONNECTION_LOST:
                if (getService() == null || getService().isErrorShown()) break;

                MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this);
                b.setTitle(getString(R.string.connectionRefused)
                        + (mSettings.isTorEnabled() ? " (Tor)" : ""));
                HumlaException err = getService().getConnectionError();

                if (err != null && mService.isReconnecting()) {
                    b.setMessage(err.getMessage() + "\n\n"
                            + getString(R.string.attempting_reconnect,
                                    err.getCause() != null ? err.getCause().getMessage() : "unknown"));
                    b.setPositiveButton(R.string.cancel_reconnect, (dialog, which) -> {
                        if (getService() != null) {
                            getService().cancelReconnect();
                            getService().markErrorShown();
                        }
                    });
                } else if (err != null && err.getReason() == HumlaException.HumlaDisconnectReason.REJECT
                        && (err.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW
                            || err.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                    final EditText passIn = new EditText(this);
                    passIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    passIn.setHint(R.string.password);
                    b.setTitle(R.string.invalid_password);
                    b.setMessage(err.getMessage());
                    b.setView(passIn);
                    b.setPositiveButton(R.string.reconnect, (dialog, which) -> {
                        Server s = getService().getTargetServer();
                        if (s == null) return;
                        s.setPassword(passIn.getText().toString());
                        if (s.isSaved()) mDatabase.updateServer(s);
                        connectToServer(s);
                    });
                    b.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        if (getService() != null) getService().markErrorShown();
                    });
                } else {
                    String msg = err != null ? err.getMessage() : getString(R.string.unknown);
                    b.setMessage(msg);
                    b.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (getService() != null) getService().markErrorShown();
                    });
                }
                b.setCancelable(false);
                mErrorDialog = b.show();
                break;
        }
    }

    @Override
    public IMumlaService getService() { return mService; }

    @Override
    public MumlaDatabase getDatabase() { return mDatabase; }

    @Override
    public void addServiceFragment(HumlaServiceFragment f) { mServiceFragments.add(f); }

    @Override
    public void removeServiceFragment(HumlaServiceFragment f) { mServiceFragments.remove(f); }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (Settings.PREF_STAY_AWAKE.equals(key)) {
            setStayAwake(mSettings.shouldStayAwake());
        } else if (Settings.PREF_HANDSET_MODE.equals(key)) {
            setVolumeControlStream(mSettings.isHandsetMode() ?
                    AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
        }
    }

    @Override
    public boolean isConnected() { return mService != null && mService.isConnected(); }

    @Override
    public String getConnectedServerName() {
        if (isConnected()) {
            Server s = mService.getTargetServer();
            return s.getName().isEmpty() ? s.getHost() : s.getName();
        }
        if (BuildConfig.DEBUG) {
            throw new RuntimeException("getConnectedServerName should only be called if connected!");
        }
        return "";
    }

    @Override
    public void onServerEdited(ServerEditFragment.Action action, Server server) {
        switch (action) {
            case ADD_ACTION:
                mDatabase.addServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case EDIT_ACTION:
                mDatabase.updateServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case CONNECT_ACTION:
                connectToServer(server);
                break;
        }
    }

    private static class StartupAction extends android.os.AsyncTask<Void, Void, Void> {
        private final MumlaActivity mActivity;
        StartupAction(MumlaActivity activity) { mActivity = activity; }

        @Override
        protected Void doInBackground(Void... voids) { return null; }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (!"mumble.samto.my.id".isEmpty()) {
                mActivity.showEmbeddedServerCredentialsDialog();
            } else {
                mActivity.loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
        }
    }
}
