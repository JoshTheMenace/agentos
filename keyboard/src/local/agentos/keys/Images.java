package local.agentos.keys;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the keyboard's generated images to the app being typed in, as content://local.agentos.keys.images/<name>.png.
 * Not exported: the app only gets a one-time read grant from commitContent / the clipboard (grantUriPermissions).
 */
public final class Images extends ContentProvider {
    static final String AUTHORITY = "local.agentos.keys.images";
    static Uri uri(String name) { return Uri.parse("content://" + AUTHORITY + "/" + name); }
    static File dir(android.content.Context c) { File d = new File(c.getFilesDir(), "images"); d.mkdirs(); return d; }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri u) { return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        String name = u.getLastPathSegment();
        if (name == null || !name.matches("[\\w-]+\\.png")) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(dir(getContext()), name), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
