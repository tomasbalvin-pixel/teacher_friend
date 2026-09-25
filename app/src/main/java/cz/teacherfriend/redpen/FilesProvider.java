package cz.teacherfriend.redpen;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimální náhrada FileProvideru: zpřístupní soubory z vybraných podadresářů cache
 * (výstup fotoaparátu a soubory ke sdílení) přes content:// URI s dočasným oprávněním.
 */
public final class FilesProvider extends ContentProvider {

    public static final String AUTHORITY = "cz.teacherfriend.redpen.files";
    public static final String DIR_CAMERA = "camera";
    public static final String DIR_SHARED = "shared";

    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(f.getParentFile().getName()).appendPath(f.getName()).build();
    }

    public static File cameraFile(Context ctx) {
        File d = new File(ctx.getCacheDir(), DIR_CAMERA);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, "photo_" + System.currentTimeMillis() + ".jpg");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 2) throw new FileNotFoundException(uri.toString());
        String dir = uri.getPathSegments().get(0);
        if (!DIR_CAMERA.equals(dir) && !DIR_SHARED.equals(dir)) throw new FileNotFoundException(uri.toString());
        try {
            File base = new File(getContext().getCacheDir(), dir).getCanonicalFile();
            File f = new File(base, uri.getPathSegments().get(1)).getCanonicalFile();
            if (!base.equals(f.getParentFile())) throw new FileNotFoundException(uri.toString());
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
