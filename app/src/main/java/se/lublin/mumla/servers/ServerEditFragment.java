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

package se.lublin.mumla.servers;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class ServerEditFragment extends DialogFragment {
    private static final String ARGUMENT_ACTION = "action";
    private static final String ARGUMENT_IGNORE_TITLE = "ignore_title";
    private static final String ARGUMENT_SERVER = "server";

    private EditText mHostEdit;
    private ServerEditListener mListener;
    private EditText mNameEdit;
    private EditText mPasswordEdit;
    private EditText mPortEdit;
    private EditText mUsernameEdit;

    public enum Action {
        CONNECT_ACTION,
        EDIT_ACTION,
        ADD_ACTION
    }

    public interface ServerEditListener {
        void onServerEdited(Action action, Server server);
    }

    public static ServerEditFragment createServerEditDialog(Context context, Server server,
                                                            Action action, boolean ignoreTitle) {
        ServerEditFragment frag = new ServerEditFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARGUMENT_SERVER, server);
        args.putInt(ARGUMENT_ACTION, action.ordinal());
        args.putBoolean(ARGUMENT_IGNORE_TITLE, ignoreTitle);
        frag.setArguments(args);
        return frag;
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (ServerEditListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity + " harus mengimplementasikan ServerEditListener!");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d == null) return;

        // Tombol konfirmasi — ikuti alur asli: validasi dulu → kirim → tutup
        d.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!validate()) return; // ❌ salah, berhenti di sini

            Server server = buatServerDariInput(); // ✅ susun data
            mListener.onServerEdited(getTindakan(), server); // 📤 kirim ke aktivitas
            dismiss(); // ✅ tutup
        });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Settings set = Settings.getInstance(requireActivity());
        Action tindakan = getTindakan();
        String teksTombol;

        // Sesuaikan teks tombol sesuai tindakan — PERSIS aslinya
        switch (tindakan) {
            case CONNECT_ACTION: teksTombol = getString(R.string.connect); break;
            case EDIT_ACTION:    teksTombol = getString(android.R.string.ok); break;
            case ADD_ACTION:     teksTombol = getString(R.string.add); break;
            default: throw new IllegalStateException("Tindakan tidak dikenal: " + tindakan);
        }

        View tampilan = LayoutInflater.from(getActivity())
                .inflate(R.layout.dialog_server_edit, null, false);

        TextView labelNama = tampilan.findViewById(R.id.server_edit_name_title);
        mNameEdit     = tampilan.findViewById(R.id.server_edit_name);
        mHostEdit     = tampilan.findViewById(R.id.server_edit_host);
        mPortEdit     = tampilan.findViewById(R.id.server_edit_port);
        mUsernameEdit = tampilan.findViewById(R.id.server_edit_username);
        mPasswordEdit = tampilan.findViewById(R.id.server_edit_password);

        // Petunjuk nama pengguna dari pengaturan — seperti aslinya
        mUsernameEdit.setHint(set.getDefaultUsername());

        // Isi data lama jika ada
        Server serverLama = getServerTersimpan();
        if (serverLama != null) {
            mNameEdit.setText(serverLama.getName());
            mHostEdit.setText(serverLama.getHost());
            if (serverLama.getPort() != 0) {
                mPortEdit.setText(String.valueOf(serverLama.getPort()));
            }
            mUsernameEdit.setText(serverLama.getUsername());
            mPasswordEdit.setText(serverLama.getPassword());
        }

        // Sembunyikan kolom nama jika diminta
        if (abaikanJudul()) {
            labelNama.setVisibility(View.GONE);
            mNameEdit.setVisibility(View.GONE);
        }

        // Fokus ke kolom pertama
        View kolomPertama = abaikanJudul() ? mHostEdit : mNameEdit;
        tampilan.post(() -> kolomPertama.requestFocus());

        return new MaterialAlertDialogBuilder(requireActivity())
                .setView(tampilan)
                .setPositiveButton(teksTombol, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    // ✅ Ikuti urutan asli: baca → perbaiki kosong → susun objek
    @NonNull
    private Server buatServerDariInput() {
        long id;
        String nama = mNameEdit.getText().toString().trim();
        String host = mHostEdit.getText().toString().trim();
        int port = bacaPort();
        String namaPengguna = bacaNamaPengguna();
        String sandi = mPasswordEdit.getText().toString();

        Server lama = getServerTersimpan();
        id = (lama != null) ? lama.getId() : -1;

        return new Server(id, nama, host, port, namaPengguna, sandi);
    }

    private int bacaPort() {
        try {
            return Integer.parseInt(mPortEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0; // kosong/salah = pakai baku
        }
    }

    @NonNull
    private String bacaNamaPengguna() {
        String ketik = mUsernameEdit.getText().toString().trim();
        return ketik.isEmpty() ? mUsernameEdit.getHint().toString() : ketik;
    }

    // ✅ Validasi PERSIS seperti aslinya
    private boolean validate() {
        // Host wajib diisi
        if (mHostEdit.getText().length() == 0) {
            mHostEdit.setError(getString(R.string.invalid_host));
            return false;
        }

        // Port janggal?
        String teksPort = mPortEdit.getText().toString().trim();
        if (!teksPort.isEmpty()) {
            try {
                int p = Integer.parseInt(teksPort);
                if (p < 1 || p > 65535) {
                    mPortEdit.setError(getString(R.string.invalid_port_range));
                    return false;
                }
            } catch (NumberFormatException e) {
                mPortEdit.setError(getString(R.string.invalid_port_range));
                return false;
            }
        }
        return true; // ✅ semua oke
    }

    private Action getTindakan() {
        Bundle b = getArguments();
        return Action.values()[b.getInt(ARGUMENT_ACTION)];
    }

    private Server getServerTersimpan() {
        Bundle b = getArguments();
        return (b != null) ? b.getParcelable(ARGUMENT_SERVER) : null;
    }

    private boolean abaikanJudul() {
        Bundle b = getArguments();
        return (b != null) && b.getBoolean(ARGUMENT_IGNORE_TITLE);
    }
}
