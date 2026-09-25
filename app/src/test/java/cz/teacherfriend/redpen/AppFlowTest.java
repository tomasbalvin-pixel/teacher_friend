package cz.teacherfriend.redpen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Průchod aplikací: vložení souboru, fotoaparát, zobrazení opravené práce. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppFlowTest {

    private static byte[] jpeg(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 90, bos);
        return bos.toByteArray();
    }

    @Before
    public void reset() {
        Session.get().pages.clear();
        Session.get().result = null;
    }

    private static void waitForPages(int n) throws InterruptedException {
        for (int i = 0; i < 400 && Session.get().pages.size() < n; i++) {
            Thread.sleep(25);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Thread.sleep(100);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String state(MainActivity a) {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        return "pages=" + Session.get().pages.size()
                + " label=" + ((TextView) a.findViewById(R.id.pages_label)).getText()
                + " toast=" + ShadowToast.getTextOfLatestToast()
                + " dialog=" + (d == null ? null : shadowOf(d).getMessage());
    }

    private static void assertIdle(MainActivity a) {
        assertTrue("tlačítko Vyfotit zůstalo neaktivní", a.findViewById(R.id.btn_camera).isEnabled());
        assertTrue("tlačítko Vybrat soubor zůstalo neaktivní", a.findViewById(R.id.btn_pick).isEnabled());
        assertEquals(View.GONE, a.findViewById(R.id.progress_box).getVisibility());
    }

    @Test
    public void pickImageFile() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.findViewById(R.id.btn_pick).performClick();
        ShadowActivity sa = shadowOf(a);
        ShadowActivity.IntentForResult started = sa.getNextStartedActivityForResult();
        assertNotNull("výběr souborů se neotevřel: " + state(a), started);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());

        Uri uri = Uri.parse("content://com.android.providers.media.documents/document/image%3A42");
        // Obrázek se čte třikrát (rozměry, dekódování, EXIF) – každé čtení potřebuje nový proud.
        shadowOf(a.getContentResolver()).registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(jpeg(1200, 1600)));
        sa.receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        waitForPages(1);

        System.out.println("PICK: " + state(a));
        assertEquals(state(a), 1, Session.get().pages.size());
        assertEquals(1, ((LinearLayout) a.findViewById(R.id.thumbs)).getChildCount());
        assertTrue(a.findViewById(R.id.btn_grade).isEnabled());
        assertIdle(a);
    }

    @Test
    public void takePhoto() throws Exception {
        Robolectric.setupContentProvider(FilesProvider.class, FilesProvider.AUTHORITY);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.findViewById(R.id.btn_camera).performClick();
        ShadowActivity sa = shadowOf(a);
        ShadowActivity.IntentForResult started = sa.getNextStartedActivityForResult();
        assertNotNull("fotoaparát se nespustil: " + state(a), started);
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.getAction());
        Uri out = started.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        assertNotNull(out);
        assertTrue((started.intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);

        // Fotoaparát zapisuje přes náš ContentProvider.
        try (OutputStream os = a.getContentResolver().openOutputStream(out)) {
            os.write(jpeg(1600, 1200));
        }
        sa.receiveResult(started.intent, Activity.RESULT_OK, null);
        waitForPages(1);

        System.out.println("CAMERA: " + state(a));
        assertEquals(state(a), 1, Session.get().pages.size());
        assertIdle(a);
    }

    @Test
    public void cameraThumbnailFallback() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.findViewById(R.id.btn_camera).performClick();
        ShadowActivity sa = shadowOf(a);
        ShadowActivity.IntentForResult started = sa.getNextStartedActivityForResult();
        Bitmap thumb = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888);
        thumb.eraseColor(Color.WHITE);
        sa.receiveResult(started.intent, Activity.RESULT_OK, new Intent().putExtra("data", thumb));
        waitForPages(1);
        assertEquals(state(a), 1, Session.get().pages.size());
        assertIdle(a);
    }

    @Test
    public void brokenFileShowsErrorAndKeepsButtonsUsable() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.findViewById(R.id.btn_pick).performClick();
        ShadowActivity sa = shadowOf(a);
        ShadowActivity.IntentForResult started = sa.getNextStartedActivityForResult();
        Uri uri = Uri.parse("content://com.example/broken.jpg");
        shadowOf(a.getContentResolver()).registerInputStreamSupplier(uri,
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        sa.receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        waitForPages(1);
        Thread.sleep(300);
        shadowOf(Looper.getMainLooper()).idle();

        System.out.println("BROKEN: " + state(a));
        assertEquals(0, Session.get().pages.size());
        assertNotNull("chyba se nezobrazila", ShadowAlertDialog.getLatestAlertDialog());
        assertIdle(a);
    }

    @Test
    public void resultScreenRendersAllMarks() throws Exception {
        File dir = PageLoader.pagesDir(org.robolectric.RuntimeEnvironment.getApplication());
        File page = new File(dir, "test_page.jpg");
        try (FileOutputStream os = new FileOutputStream(page)) {
            os.write(jpeg(1400, 2000));
        }
        Session.get().pages.add(page);
        String json = "{\"grade\":\"2-\",\"points\":\"17/20\",\"summary\":\"Dobrá práce.\","
                + "\"strengths\":[\"čitelnost\"],\"improvements\":[\"i/y\"],\"marks\":["
                + "{\"page\":1,\"kind\":\"wrong\",\"x0\":100,\"y0\":200,\"x1\":300,\"y1\":240,\"original\":\"bily\",\"correction\":\"bílý\",\"explanation\":\"Tvrdé y.\",\"penalty\":\"-1\"},"
                + "{\"page\":1,\"kind\":\"missing\",\"x0\":400,\"y0\":200,\"x1\":420,\"y1\":240,\"original\":\"\",\"correction\":\",\",\"explanation\":\"Čárka před že.\",\"penalty\":\"\"},"
                + "{\"page\":1,\"kind\":\"circle\",\"x0\":100,\"y0\":400,\"x1\":600,\"y1\":480,\"original\":\"\",\"correction\":\"špatný postup\",\"explanation\":\"Nejdřív násobení.\",\"penalty\":\"-2\"},"
                + "{\"page\":1,\"kind\":\"correct\",\"x0\":100,\"y0\":600,\"x1\":300,\"y1\":640,\"original\":\"\",\"correction\":\"\",\"explanation\":\"\",\"penalty\":\"\"},"
                + "{\"page\":1,\"kind\":\"note\",\"x0\":100,\"y0\":800,\"x1\":400,\"y1\":840,\"original\":\"\",\"correction\":\"Pěkně napsáno!\",\"explanation\":\"\",\"penalty\":\"\"},"
                + "{\"page\":1,\"kind\":\"wrong\",\"x0\":900,\"y0\":990,\"x1\":1000,\"y1\":1000,\"original\":\"x\",\"correction\":\"\",\"explanation\":\"Okraj.\",\"penalty\":\"\"},"
                + "{\"page\":7,\"kind\":\"wrong\",\"x0\":0,\"y0\":0,\"x1\":1,\"y1\":1,\"original\":\"mimo\",\"correction\":\"x\",\"explanation\":\"\",\"penalty\":\"\"}"
                + "]}";
        GradingResult r = ClaudeGrader.parseResult("Tady je hodnocení:\n" + json, 1);
        assertEquals("2-", r.grade);
        assertEquals(7, r.marks.size()); // 6 značek ze stránky 1 + známka; značka ze stránky 7 zahozena
        assertNotNull(r.gradeMark());
        Session.get().result = r;

        ResultActivityHolder.launchAndDraw();
    }

    /** Spustí ResultActivity a každou stránku skutečně vykreslí do bitmapy (MarkRenderer). */
    static final class ResultActivityHolder {
        static void launchAndDraw() {
            ResultActivity a = Robolectric.buildActivity(ResultActivity.class).setup().get();
            assertFalse("ResultActivity se hned ukončila", a.isFinishing());
            ViewGroup pages = a.findViewById(R.id.pages);
            assertEquals(1, pages.getChildCount());
            View pv = pages.getChildAt(0);
            pv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            pv.layout(0, 0, pv.getMeasuredWidth(), pv.getMeasuredHeight());
            Bitmap out = Bitmap.createBitmap(pv.getWidth(), pv.getHeight(), Bitmap.Config.ARGB_8888);
            pv.draw(new Canvas(out));
            String details = ((TextView) a.findViewById(R.id.details)).getText().toString();
            System.out.println("DETAILS:\n" + details);
            assertTrue(details.contains("bílý"));
        }
    }
}
