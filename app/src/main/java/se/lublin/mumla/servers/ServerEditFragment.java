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

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

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
        Bundle args = new Bundle();
        args.putParcelable(ARGUMENT_SERVER, server);
        args.putInt(ARGUMENT_ACTION, action.ordinal());
        args.putBoolean(ARGUMENT_IGNORE_TITLE, ignoreTitle);
        return (ServerEditFragment) Fragment.instantiate(
                context, ServerEditFragment.class.getName(), args);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            this.mListener = (ServerEditListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() +
                    " must implement ServerEditListener!");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ServerEditFragment.this.validate()) {
                    Server server = ServerEditFragment.this.createServer();
                    ServerEditFragment.this.mListener.onServerEdited(
                            ServerEditFragment.this.getAction(), server);
                    ServerEditFragment.this.dismiss();
                }
            }
        });
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        String actionName;
        Settings settings = Settings.getInstance(getActivity());

        switch (getAction().ordinal()) {
            case 0: // CONNECT_ACTION
                actionName = getString(R.string.connect);
                break;
            case 1: // EDIT_ACTION
                actionName = getString(android.R.string.ok);
                break;
            case 2: // ADD_ACTION
                actionName = getString(R.string.add);
                break;
            default:
                throw new RuntimeException("Unknown action " + getAction());
        }

        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.dialog_server_edit, (ViewGroup) null, false);

        TextView titleLabel = view.findViewById(R.id.server_edit_name_title);
        mNameEdit = view.findViewById(R.id.server_edit_name);
        mHostEdit = view.findViewById(R.id.server_edit_host);
        mPortEdit = view.findViewById(R.id.server_edit_port);
        mUsernameEdit = view.findViewById(R.id.server_edit_username);
        mUsernameEdit.setHint(settings.getDefaultUsername());
        mPasswordEdit = view.findViewById(R.id.server_edit_password);

        Server oldServer = getServer();
        if (oldServer != null) {
            mNameEdit.setText(oldServer.getName());
            mHostEdit.setText(oldServer.getHost());
            if (oldServer.getPort() != 0) {
                mPortEdit.setText(String.valueOf(oldServer.getPort()));
            }
            mUsernameEdit.setText(oldServer.getUsername());
            mPasswordEdit.setText(oldServer.getPassword());
        }

        if (shouldIgnoreTitle()) {
            titleLabel.setVisibility(View.GONE);
            mNameEdit.setVisibility(View.GONE);
        }

        final EditText firstField = shouldIgnoreTitle() ? mHostEdit : mNameEdit;
        view.post(new Runnable() {
            @Override
            public void run() {
                firstField.requestFocus();
            }
        });

        return new MaterialAlertDialogBuilder(requireActivity())
                .setPositiveButton(actionName, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(view)
                .create();
    }

    public Server createServer() {
        int port;
        String username;
        long id;

        String name = mNameEdit.getText().toString().trim();
        String host = mHostEdit.getText().toString().trim();

        try {
            port = Integer.parseInt(mPortEdit.getText().toString());
        } catch (NumberFormatException e) {
            port = 0;
        }

        String username2 = mUsernameEdit.getText().toString().trim();
        String password = mPasswordEdit.getText().toString();

        if (!username2.equals("")) {
            username = username2;
        } else {
            username = mUsernameEdit.getHint().toString();
        }

        if (getServer() != null) {
            id = getServer().getId();
        } else {
            id = -1;
        }

        return new Server(id, name, host, port, username, password);
    }

    public boolean validate() {
        if (mHostEdit.getText().length() == 0) {
            mHostEdit.setError(getString(R.string.invalid_host));
            return false;
        }

        if (mPortEdit.getText().length() > 0) {
            try {
                int port = Integer.parseInt(mPortEdit.getText().toString());
                if (port >= 1 && port <= 65535) {
                    // port ok — lanjut
                } else {
                    mPortEdit.setError(getString(R.string.invalid_port_range));
                    return false;
                }
            } catch (NumberFormatException e) {
                mPortEdit.setError(getString(R.string.invalid_port_range));
                return false;
            }
        }
        return true;
    }

    private Server getServer() {
        return getArguments().getParcelable(ARGUMENT_SERVER);
    }

    private Action getAction() {
        return Action.values()[getArguments().getInt(ARGUMENT_ACTION)];
    }

    private boolean shouldIgnoreTitle() {
        return getArguments().getBoolean(ARGUMENT_IGNORE_TITLE);
    }
}
