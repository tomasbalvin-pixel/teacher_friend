package cz.teacherfriend.redpen;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Export opravené práce do PDF a obrázků. */
public final class Exporter {

    /** Šířka stránky PDF v bodech (A4). */
    private static final float PDF_WIDTH = 595f;
    private static final float A4_HEIGHT = 842f;
    /** Rozlišení obrázku v PDF – kompromis mezi čitelností a velikostí souboru. */
    private static final int PDF_IMAGE_EDGE = 1700;

    private Exporter() {
    }

    public static void writePdf(Context ctx, OutputStream os, Session session, Prefs prefs) throws IOException {
        GradingResult r = session.result;
        MarkRenderer renderer = new MarkRenderer(ctx, prefs.numbers());
        PdfDocument doc = new PdfDocument();
        try {
            int pageNo = 1;
            for (int i = 0; i < session.pages.size(); i++) {
                Bitmap bmp = PageLoader.decodePage(session.pages.get(i), PDF_IMAGE_EDGE);
                if (bmp == null) continue;
                float s = PDF_WIDTH / bmp.getWidth();
                int ph = Math.round(bmp.getHeight() * s);
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) PDF_WIDTH, ph, pageNo++).create());
                Canvas c = page.getCanvas();
                c.scale(s, s);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(bmp, 0, 0, p);
                renderer.drawAll(c, r.marksOnPage(i), bmp.getWidth(), bmp.getHeight(), null);
                doc.finishPage(page);
                bmp.recycle();
            }
            if (prefs.summaryPage()) pageNo = writeSummaryPages(doc, r, pageNo);
            doc.writeTo(os);
        } finally {
            doc.close();
        }
    }

    private static int writeSummaryPages(PdfDocument doc, GradingResult r, int pageNo) {
        float margin = 48f;
        int width = (int) (PDF_WIDTH - 2 * margin);

        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20f);
        title.setColor(MarkRenderer.RED);

        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setTextSize(11f);
        body.setColor(Color.rgb(33, 33, 33));

        StringBuilder sb = new StringBuilder();
        String headline = r.headline();
        if (!headline.isEmpty()) sb.append(headline).append("\n\n");
        if (!r.summary.isEmpty()) sb.append(r.summary).append("\n\n");
        sb.append(r.detailsText());
        String text = sb.toString().trim();

        StaticLayout titleLayout = StaticLayout.Builder.obtain("Slovní hodnocení", 0, 16, title, width).build();
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), body, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build();

        // Stránkování po celých řádcích.
        int line = 0;
        boolean first = true;
        while (line < layout.getLineCount() || first) {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) PDF_WIDTH, (int) A4_HEIGHT, pageNo++).create());
            Canvas c = page.getCanvas();
            float y = margin;
            if (first) {
                c.save();
                c.translate(margin, y);
                titleLayout.draw(c);
                c.restore();
                y += titleLayout.getHeight() + 16f;
            }
            float available = A4_HEIGHT - margin - y;
            int startLine = line;
            float startTop = layout.getLineTop(startLine < layout.getLineCount() ? startLine : 0);
            while (line < layout.getLineCount() && layout.getLineBottom(line) - startTop <= available) line++;
            if (line == startLine && line < layout.getLineCount()) line++; // extrémně vysoký řádek
            if (line > startLine) {
                float endBottom = layout.getLineBottom(line - 1);
                c.save();
                c.translate(margin, y - startTop);
                c.clipRect(0, startTop, width, endBottom);
                layout.draw(c);
                c.restore();
            }
            doc.finishPage(page);
            first = false;
        }
        return pageNo;
    }

    /** Vykreslí stránku s opravami v plném uloženém rozlišení. */
    public static Bitmap renderPage(Context ctx, Session session, int index, Prefs prefs) {
        Bitmap src = PageLoader.decodePage(session.pages.get(index), PageLoader.MAX_EDGE);
        if (src == null) return null;
        Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (out != src) src.recycle();
        Canvas c = new Canvas(out);
        new MarkRenderer(ctx, prefs.numbers())
                .drawAll(c, session.result.marksOnPage(index), out.getWidth(), out.getHeight(), null);
        return out;
    }

    public static File sharedDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), FilesProvider.DIR_SHARED);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File clearSharedDir(Context ctx) {
        File dir = sharedDir(ctx);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return dir;
    }

    /** Připraví soubory ke sdílení (PDF, nebo JPEG pro každou stránku). */
    public static File sharePdfFile(Context ctx, Session session, Prefs prefs, String baseName) throws IOException {
        File dir = clearSharedDir(ctx);
        File f = new File(dir, baseName + ".pdf");
        try (FileOutputStream os = new FileOutputStream(f)) {
            writePdf(ctx, os, session, prefs);
        }
        return f;
    }

    public static List<File> shareImageFiles(Context ctx, Session session, Prefs prefs, String baseName) throws IOException {
        File dir = clearSharedDir(ctx);
        List<File> out = new ArrayList<>();
        for (int i = 0; i < session.pages.size(); i++) {
            Bitmap b = renderPage(ctx, session, i, prefs);
            if (b == null) continue;
            File f = new File(dir, baseName + "_" + (i + 1) + ".jpg");
            try (FileOutputStream os = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, os);
            } finally {
                b.recycle();
            }
            out.add(f);
        }
        return out;
    }

    /** Uloží opravené stránky do Galerie (Android 10+). Vrací počet uložených stránek. */
    public static int saveToGallery(Context ctx, Session session, Prefs prefs, String baseName) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Ukládání do galerie vyžaduje Android 10+.");
        }
        ContentResolver cr = ctx.getContentResolver();
        int saved = 0;
        for (int i = 0; i < session.pages.size(); i++) {
            Bitmap b = renderPage(ctx, session, i, prefs);
            if (b == null) continue;
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, baseName + "_" + (i + 1) + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Červená tužka");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                b.recycle();
                throw new IOException("Galerie odmítla uložení.");
            }
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("Galerie odmítla uložení.");
                b.compress(Bitmap.CompressFormat.JPEG, 92, os);
            } finally {
                b.recycle();
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            saved++;
        }
        return saved;
    }
}
