//From the desk of Frank P. Westlake; public domain.
package jackpal.androidterm.shortcuts;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import jackpal.androidterm.R;

public class FSNavigator extends androidx.appcompat.app.AppCompatActivity {
    private SharedPreferences shared_prefs;
    private File current_dir;
    private File external_dir;
    private MaterialToolbar toolbar;
    private FileListAdapter adapter;

    private final ActivityResultLauncher<Uri> open_directory_launcher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
        if (uri == null) {
            return;
        }
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        File dir = toFile(uri);
        if (dir != null && dir.isDirectory()) {
            chdir(dir);
            refresh();
        } else {
            Toast.makeText(this, R.string.fsnavigator_no_external_storage, Toast.LENGTH_LONG).show();
        }
    });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        jackpal.androidterm.util.ThemeMode.apply(this);
        setContentView(R.layout.fsnavigator_activity);
        shared_prefs = PreferenceManager.getDefaultSharedPreferences(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        toolbar = findViewById(R.id.toolbar);
        String title = getIntent().hasExtra("title") ? getIntent().getStringExtra("title") : getString(R.string.fsnavigator_title);
        toolbar.setTitle(title);
        toolbar.setNavigationOnClickListener(v -> goUpOrFinish());
        toolbar.inflateMenu(R.menu.fsnavigator);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.fsnav_open_folder) {
                open_directory_launcher.launch(null);
                return true;
            }
            return false;
        });

        RecyclerView list = findViewById(R.id.fsnav_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter();
        list.setAdapter(adapter);

        EditText path_entry = findViewById(R.id.fsnav_path_entry);
        path_entry.setOnEditorActionListener((v, action_id, event) -> {
            if (action_id == EditorInfo.IME_ACTION_GO) {
                submitPath(path_entry.getText().toString());
                return true;
            }
            return false;
        });
        TextInputLayout path_layout = findViewById(R.id.fsnav_path_layout);
        path_layout.setEndIconOnClickListener(v -> submitPath(path_entry.getText().toString()));

        external_dir = getExternalFilesDir(null);
        Uri uri = getIntent().getData();
        String path = uri == null ? null : uri.getPath();
        if (path == null || !chdir(path)) {
            chdir(external_dir);
        }
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        shared_prefs.edit().putString("lastDirectory", getCanonicalPath(current_dir)).commit();
    }

    private void goUpOrFinish() {
        File parent = current_dir == null ? null : current_dir.getParentFile();
        if (parent == null) {
            finish();
        } else {
            chdir(parent);
            refresh();
        }
    }

    private void submitPath(String path) {
        if (path.isEmpty()) {
            return;
        }
        File file = new File(getCanonicalPath(path));
        if (file.isFile()) {
            pickFile(file);
        } else {
            chdir(file);
            refresh();
        }
    }

    private void pickFile(File file) {
        setResult(RESULT_OK, getIntent().setData(Uri.fromFile(file)));
        finish();
    }

    private File toFile(Uri tree_uri) {
        String document_id = DocumentsContract.getTreeDocumentId(tree_uri);
        int split = document_id.indexOf(':');
        if (split < 0) {
            return null;
        }
        String volume = document_id.substring(0, split);
        String relative = document_id.substring(split + 1);
        File root = volume.equals("primary") ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
        return relative.isEmpty() ? root : new File(root, relative);
    }

    private boolean chdir(String path) {
        return path != null && chdir(new File(path));
    }

    private boolean chdir(File file) {
        if (file == null) {
            return false;
        }
        String path = ifAvailable(getCanonicalPath(file));
        System.setProperty("user.dir", path);
        current_dir = new File(path);
        return true;
    }

    private String ifAvailable(String go_to) {
        if (external_dir != null && go_to.startsWith(getCanonicalPath(external_dir))) {
            String state = Environment.getExternalStorageState(external_dir);
            if (state.equals(Environment.MEDIA_MOUNTED) || state.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
                return go_to;
            }
            Toast.makeText(this, R.string.fsnavigator_no_external_storage, Toast.LENGTH_LONG).show();
            return getCanonicalPath(external_dir);
        }
        return go_to;
    }

    private void refresh() {
        toolbar.setSubtitle(current_dir.getPath());
        adapter.setEntries(buildEntries());
    }

    private List<File> buildEntries() {
        List<File> entries = new ArrayList<>();
        String[] names = current_dir.list();
        if (names == null) {
            return entries;
        }
        Arrays.sort(names, (a, b) -> a.toLowerCase().compareTo(b.toLowerCase()));
        for (String name : names) {
            File entry = new File(current_dir, name);
            if (entry.isDirectory()) {
                entries.add(entry);
            }
        }
        for (String name : names) {
            File entry = new File(current_dir, name);
            if (!entry.isDirectory()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private String getCanonicalPath(String path) {
        return getCanonicalPath(new File(path));
    }

    private String getCanonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getPath();
        }
    }

    private class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.EntryHolder> {
        private List<File> entries = new ArrayList<>();

        void setEntries(List<File> new_entries) {
            entries = new_entries;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return entries.size() + (current_dir.getParentFile() == null ? 0 : 1);
        }

        @NonNull
        @Override
        public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int view_type) {
            return new EntryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.fsnav_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
            boolean up_row = current_dir.getParentFile() != null && position == 0;
            if (up_row) {
                holder.bindUp();
            } else {
                int index = current_dir.getParentFile() == null ? position : position - 1;
                holder.bind(entries.get(index));
            }
        }

        private class EntryHolder extends RecyclerView.ViewHolder {
            private final ImageView icon;
            private final TextView name;
            private final TextView detail;

            EntryHolder(View item_view) {
                super(item_view);
                icon = item_view.findViewById(R.id.fsnav_icon);
                name = item_view.findViewById(R.id.fsnav_name);
                detail = item_view.findViewById(R.id.fsnav_detail);
            }

            void bindUp() {
                icon.setImageResource(R.drawable.ic_folderup);
                name.setText("..");
                detail.setText(current_dir.getParent());
                itemView.setOnClickListener(v -> goUpOrFinish());
            }

            void bind(File entry) {
                boolean is_dir = entry.isDirectory();
                icon.setImageResource(is_dir ? R.drawable.ic_folder : R.drawable.ic_file);
                name.setText(entry.getName());
                String date = DateFormat.format("yyyy-MM-dd HH:mm", new Date(entry.lastModified())).toString();
                detail.setText(is_dir ? getString(R.string.fsnavigator_folder) + " · " + date : Formatter.formatShortFileSize(FSNavigator.this, entry.length()) + " · " + date);
                itemView.setOnClickListener(v -> {
                    if (entry.isDirectory()) {
                        chdir(entry);
                        refresh();
                    } else {
                        pickFile(entry);
                    }
                });
            }
        }
    }
}
