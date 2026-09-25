package cz.teacherfriend.redpen;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Převádí vstupy (PDF, fotky, obrázky) na stránky uložené jako JPEG v cache. */
public final class PageLoader {

    /** Delší strana uložené stránky v pixelech. */
    public static final int MAX_EDGE = 2000;
    public static final int MAX_PAGES = 20;

    private PageLoader() {
    }

    public static File pagesDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "pages");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Načte jeden vstup a vrátí nově vytvořené soubory stránek. */
    public static List<File> load(Context ctx, Uri uri, int alreadyLoaded) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String type = cr.getType(uri);
        String path = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        boolean pdf = "application/pdf".equals(type) || (type == null && path.endsWith(".pdf"));
        List<File> out = new ArrayList<>();
        if (pdf) {
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd == null) throw new IOException("Soubor nelze otevřít.");
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    int n = Math.min(renderer.getPageCount(), MAX_PAGES - alreadyLoaded);
                    for (int i = 0; i < n; i++) {
                        try (PdfRenderer.Page page = renderer.openPage(i)) {
                            float scale = (float) MAX_EDGE / Math.max(page.getWidth(), page.getHeight());
                            int w = Math.max(1, Math.round(page.getWidth() * scale));
                            int h = Math.max(1, Math.round(page.getHeight() * scale));
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            out.add(save(ctx, bmp));
                            bmp.recycle();
                        }
                    }
                }
            } catch (SecurityException e) {
                throw new IOException("PDF je chráněné heslem nebo poškozené.", e);
            }
        } else {
            Bitmap bmp = decodeImage(cr, uri);
            out.add(save(ctx, bmp));
            bmp.recycle();
        }
        return out;
    }

    private static Bitmap decodeImage(ContentResolver cr, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Soubor není podporovaný obrázek ani PDF.");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = cr.openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) throw new IOException("Obrázek se nepodařilo načíst.");

        int rotation = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                    default: break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Bez EXIF necháme orientaci, jak je.
        }

        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
        if (rotation == 0 && scale == 1f) return decoded;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        if (out != decoded) decoded.recycle();
        return out;
    }

    private static File save(Context ctx, Bitmap bmp) throws IOException {
        // JPEG nemá průhlednost – podložíme bílou, aby průhledné PNG nezčernaly.
        Bitmap flat = bmp;
        if (bmp.hasAlpha()) {
            flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            flat.eraseColor(Color.WHITE);
            new Canvas(flat).drawBitmap(bmp, 0, 0, null);
        }
        File f = new File(pagesDir(ctx), "page_" + System.nanoTime() + ".jpg");
        try (FileOutputStream os = new FileOutputStream(f)) {
            if (!flat.compress(Bitmap.CompressFormat.JPEG, 92, os)) throw new IOException("Uložení stránky selhalo.");
        } finally {
            if (flat != bmp) flat.recycle();
        }
        return f;
    }

    /** Načte stránku zmenšenou tak, aby delší strana nepřesáhla maxEdge. */
    public static Bitmap decodePage(File f, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        if (b == null) return null;
        int edge = Math.max(b.getWidth(), b.getHeight());
        if (edge <= maxEdge) return b;
        float s = (float) maxEdge / edge;
        Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
        if (scaled != b) b.recycle();
        return scaled;
    }
}
