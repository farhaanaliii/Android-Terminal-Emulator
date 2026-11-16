/*
 * Copyright (C) 2011 Steven Luo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;
import jackpal.androidterm.util.SessionList;

public class WindowList extends ComponentActivity {
    private SessionList sessions;
    private WindowRowsAdapter mRowsAdapter;
    private TermService mTermService;
    private TextView mEmptyView;

    private final ServiceConnection mTSConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            TermService.TSBinder binder = (TermService.TSBinder) service;
            mTermService = binder.getService();
            populateList();
        }

        public void onServiceDisconnected(ComponentName arg0) {
            mTermService = null;
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.window_list_activity);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        mEmptyView = findViewById(R.id.window_list_empty);
        RecyclerView recycler = findViewById(R.id.window_list_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        mRowsAdapter = new WindowRowsAdapter();
        recycler.setAdapter(mRowsAdapter);

        setResult(RESULT_CANCELED);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent TSIntent = new Intent(this, TermService.class);
        if (!bindService(TSIntent, mTSConnection, BIND_AUTO_CREATE)) {
            Log.w(TermDebug.LOG_TAG, "bind to service failed!");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (sessions != null) {
            sessions.removeCallback(mRowsAdapter);
            sessions.removeTitleChangedListener(mRowsAdapter);
        }
        mRowsAdapter.setSessions(null);
        unbindService(mTSConnection);
    }

    private void populateList() {
        sessions = mTermService.getSessions();
        mRowsAdapter.setSessions(sessions);
        sessions.addCallback(mRowsAdapter);
        sessions.addTitleChangedListener(mRowsAdapter);
    }

    private void selectWindow(int windowId) {
        Intent data = new Intent();
        data.putExtra(Term.EXTRA_WINDOW_ID, windowId);
        setResult(RESULT_OK, data);
        finish();
    }

    private void updateEmptyVisibility() {
        boolean empty = sessions == null || sessions.size() == 0;
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private class WindowRowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements UpdateCallback {
        private static final int TYPE_NEW_WINDOW = 0;
        private static final int TYPE_WINDOW = 1;

        private SessionList mSessions;

        void setSessions(SessionList sessions) {
            mSessions = sessions;
            notifyDataSetChanged();
            updateEmptyVisibility();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_NEW_WINDOW : TYPE_WINDOW;
        }

        @Override
        public int getItemCount() {
            return 1 + (mSessions == null ? 0 : mSessions.size());
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_NEW_WINDOW) {
                return new NewWindowHolder(inflater.inflate(R.layout.window_list_new_window, parent, false));
            }
            return new WindowHolder(inflater.inflate(R.layout.window_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof WindowHolder) {
                ((WindowHolder) holder).bind(position - 1);
            }
        }

        @Override
        public void onUpdate() {
            notifyDataSetChanged();
            updateEmptyVisibility();
        }

        private String getSessionTitle(int position, String defaultTitle) {
            TermSession session = mSessions.get(position);
            if (session instanceof GenericTermSession) {
                return ((GenericTermSession) session).getTitle(defaultTitle);
            }
            return defaultTitle;
        }

        private int getSessionTypeString(TermSession session) {
            if (session instanceof ShellTermSession) {
                return R.string.session_type_shell;
            } else if (session instanceof BoundSession) {
                return R.string.session_type_app;
            }
            return R.string.session_type_generic;
        }

        private class NewWindowHolder extends RecyclerView.ViewHolder {
            NewWindowHolder(View itemView) {
                super(itemView);
                itemView.setOnClickListener(v -> selectWindow(-1));
            }
        }

        private class WindowHolder extends RecyclerView.ViewHolder {
            private final TextView label;
            private final TextView subtitle;
            private final View close;

            WindowHolder(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.window_list_label);
                subtitle = itemView.findViewById(R.id.window_list_subtitle);
                close = itemView.findViewById(R.id.window_list_close);
                itemView.setOnClickListener(v -> selectWindow(getBindingAdapterPosition() - 1));
                close.setOnClickListener(v -> {
                    int position = getBindingAdapterPosition() - 1;
                    TermSession session = mSessions.remove(position);
                    if (session != null) {
                        session.finish();
                        notifyDataSetChanged();
                        updateEmptyVisibility();
                    }
                });
            }

            void bind(int position) {
                String defaultTitle = getString(R.string.window_title, position + 1);
                label.setText(getSessionTitle(position, defaultTitle));
                subtitle.setText(getSessionTypeString(mSessions.get(position)));
            }
        }
    }
}
