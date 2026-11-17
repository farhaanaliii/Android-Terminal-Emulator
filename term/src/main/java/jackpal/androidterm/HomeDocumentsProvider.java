package jackpal.androidterm;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import jackpal.androidterm.util.Bootstrap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Exposes the terminal's HOME directory (files/home) through the Storage
 * Access Framework, so the system Files app and other apps can browse,
 * open and edit the files the shell works with, the way Termux does.
 * Document ids are "home" for HOME itself and "home/<relative path>"
 * for everything under it.
 */
public class HomeDocumentsProvider extends DocumentsProvider {
    private static final String ROOT_ID = "home";

    private static final String[] ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    @Override
    public boolean onCreate() {
        Bootstrap.init(getContext());
        return true;
    }

    private File getHomeDir() {
        File home = Bootstrap.getHomeDir();
        if (!home.exists()) home.mkdirs();
        return home;
    }

    private File getFile(String document_id) throws FileNotFoundException {
        File home = getHomeDir();
        File file;
        if (document_id.equals(ROOT_ID)) {
            file = home;
        } else if (document_id.startsWith(ROOT_ID + "/")) {
            file = new File(home, document_id.substring(ROOT_ID.length() + 1));
        } else {
            throw new FileNotFoundException("Unknown document: " + document_id);
        }
        try {
            String home_path = home.getCanonicalPath();
            String file_path = file.getCanonicalPath();
            if (!file_path.equals(home_path) && !file_path.startsWith(home_path + File.separator)) throw new FileNotFoundException("Document escapes HOME: " + document_id);
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot resolve document: " + document_id);
        }
        if (!file.exists()) throw new FileNotFoundException("No such document: " + document_id);
        return file;
    }

    private String getDocumentId(File file) throws IOException {
        String home_path = getHomeDir().getCanonicalPath();
        String file_path = file.getCanonicalPath();
        return file_path.equals(home_path) ? ROOT_ID : ROOT_ID + "/" + file_path.substring(home_path.length() + 1);
    }

    private String getMimeType(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String mime_type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime_type != null ? mime_type : "application/octet-stream";
    }

    private void includeFile(MatrixCursor cursor, File file) throws IOException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        int flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        if (file.isDirectory()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        row.add(Document.COLUMN_DOCUMENT_ID, getDocumentId(file));
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(file));
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isDirectory() ? null : file.length());
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.application_terminal));
        row.add(Root.COLUMN_SUMMARY, getContext().getString(R.string.title_home_path_preference));
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        row.add(Root.COLUMN_AVAILABLE_BYTES, getHomeDir().getFreeSpace());
        return cursor;
    }

    @Override
    public Cursor queryDocument(String document_id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        try {
            includeFile(cursor, getFile(document_id));
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot read document: " + document_id);
        }
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parent_document_id, String[] projection, String sort_order) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        File[] children = getFile(parent_document_id).listFiles();
        if (children != null) {
            for (File child : children) {
                try {
                    includeFile(cursor, child);
                } catch (IOException e) {
                    throw new FileNotFoundException("Cannot read child: " + child);
                }
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String document_id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(getFile(document_id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parent_document_id, String mime_type, String display_name) throws FileNotFoundException {
        File parent = getFile(parent_document_id);
        File file = new File(parent, display_name);
        int suffix = 1;
        while (file.exists()) {
            file = new File(parent, display_name + " (" + suffix + ")");
            suffix++;
        }
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mime_type) ? file.mkdir() : file.createNewFile();
            if (!created) throw new FileNotFoundException("Cannot create document: " + display_name);
            return getDocumentId(file);
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot create document: " + display_name);
        }
    }

    @Override
    public void deleteDocument(String document_id) throws FileNotFoundException {
        if (document_id.equals(ROOT_ID)) throw new FileNotFoundException("Cannot delete HOME itself");
        deleteRecursively(getFile(document_id));
    }

    private void deleteRecursively(File file) throws FileNotFoundException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new FileNotFoundException("Cannot delete: " + file);
    }

    @Override
    public String renameDocument(String document_id, String display_name) throws FileNotFoundException {
        File file = getFile(document_id);
        File renamed = new File(file.getParentFile(), display_name);
        if (!file.renameTo(renamed)) throw new FileNotFoundException("Cannot rename: " + document_id);
        try {
            return getDocumentId(renamed);
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot resolve renamed document: " + document_id);
        }
    }

    @Override
    public boolean isChildDocument(String parent_document_id, String document_id) {
        return document_id.equals(parent_document_id) || document_id.startsWith(parent_document_id + "/");
    }
}
