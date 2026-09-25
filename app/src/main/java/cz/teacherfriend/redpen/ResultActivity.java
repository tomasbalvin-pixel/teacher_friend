package cz.teacherfriend.redpen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ResultActivity extends Activity implements PageView.Listener {

    private static final int REQ_SAVE_PDF = 10;
    /** Rozlišení stránek na displeji (značky jsou normalizované, takže na něm nezáleží). */
    private static final int DISPLAY_EDGE = 1400;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<PageView> pageViews = new ArrayList<>();

    private Session session;
    private Prefs prefs;
    private MarkRenderer renderer;
    private TextView details;
    private View selectionBar;
    private TextView selectionText;
    private PageView selectedView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        session = Session.get();
        if (session.result == null || session.pages.isEmpty()) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        renderer = new MarkRenderer(this, prefs.numbers());
        setContentView(R.layout.activity_result);
        setTitle("Opravená práce");

        GradingResult r = session.result;
        TextView gradeBig = findViewById(R.id.grade_big);
        String headline = r.headline();
        gradeBig.setText(headline);
        gradeBig.setVisibility(headline.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.summary)).setText(r.summary);
        details = findViewById(R.id.details);
        selectionBar = findViewById(R.id.selection_bar);
        selectionText = findViewById(R.id.selection_text);

        LinearLayout pages = findViewById(R.id.pages);
        for (int i = 0; i < session.pages.size(); i++) {
            Bitmap b = PageLoader.decodePage(session.pages.get(i), DISPLAY_EDGE);
            if (b == null) continue;
            PageView pv = new PageView(this, i, b, r, renderer, this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            pv.setLayoutParams(lp);
            pv.setElevation(dp(2));
            pages.addView(pv);
            pageViews.add(pv);
        }
        refreshDetails();

        findViewById(R.id.btn_edit_mark).setOnClickListener(v -> editSelected());
        findViewById(R.id.btn_delete_mark).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.btn_save_pdf).setOnClickListener(v -> savePdf());
        findViewById(R.id.btn_share_pdf).setOnClickListener(v -> sharePdf());
        findViewById(R.id.btn_save_images).setOnClickListener(v -> saveImages());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (selectedView != null) {
            onSelectionChanged(selectedView, null);
            return;
        }
        super.onBackPressed();
    }

    private void refreshDetails() {
        session.result.renumber();
        String text = session.result.detailsText();
        details.setText(text);
        details.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        for (PageView pv : pageViews) pv.invalidate();
    }

    // ------------------------------------------------------------ úpravy značek

    @Override
    public void onSelectionChanged(PageView view, Mark selected) {
        if (selectedView != null && selectedView != view) selectedView.setSelected(null);
        if (selected == null) {
            view.setSelected(null);
            selectedView = null;
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectedView = view;
        selectionText.setText(describe(selected));
        selectionBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onMarkMoved(PageView view, Mark mark) {
        // Pořadí čísel se může posunem změnit.
        refreshDetails();
    }

    @Override
    public void onLongPressEmpty(PageView view, int page, float nx, float ny) {
        String[] labels = {"Fajfka ✓", "Přeškrtnout a opravit", "Doplnit (stříška)", "Zakroužkovat s poznámkou", "Poznámka"};
        Mark.Kind[] kinds = {Mark.Kind.CORRECT, Mark.Kind.WRONG, Mark.Kind.MISSING, Mark.Kind.CIRCLE, Mark.Kind.NOTE};
        new AlertDialog.Builder(this)
                .setTitle("Přidat značku")
                .setItems(labels, (d, which) -> {
                    float w = kinds[which] == Mark.Kind.CORRECT ? 0.05f : 0.14f;
                    float h = 0.035f;
                    Mark m = new Mark(page, kinds[which], nx - w / 2, ny - h / 2, nx + w / 2, ny + h / 2);
                    if (m.kind == Mark.Kind.CORRECT) {
                        addMark(view, m);
                    } else {
                        editMark(m, () -> addMark(view, m));
                    }
                })
                .show();
    }

    private void addMark(PageView view, Mark m) {
        session.result.marks.add(m);
        refreshDetails();
        view.setSelected(m);
        onSelectionChanged(view, m);
    }

    private void editSelected() {
        if (selectedView == null || selectedView.selected() == null) return;
        Mark m = selectedView.selected();
        editMark(m, () -> {
            if (m.kind == Mark.Kind.GRADE) syncGradeFromMark(m);
            selectionText.setText(describe(m));
            refreshDetails();
        });
    }

    private void editMark(Mark m, Runnable onSave) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        boolean grade = m.kind == Mark.Kind.GRADE;
        EditText correction = field(box, grade ? "Známka" : "Text, který se napíše na práci", m.correction);
        EditText second = grade
                ? field(box, "Body (nepovinné)", m.original)
                : field(box, "Vysvětlení pod prací (nepovinné)", m.explanation);
        new AlertDialog.Builder(this)
                .setTitle(grade ? "Známka" : "Upravit značku")
                .setView(box)
                .setPositiveButton("Uložit", (d, w) -> {
                    m.correction = correction.getText().toString().trim();
                    if (grade) m.original = second.getText().toString().trim();
                    else m.explanation = second.getText().toString().trim();
                    onSave.run();
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setPadding(0, dp(8), 0, 0);
        parent.addView(label);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(value);
        parent.addView(e);
        return e;
    }

    private void syncGradeFromMark(Mark m) {
        GradingResult r = session.result;
        r.grade = m.correction;
        r.points = m.original;
        TextView gradeBig = findViewById(R.id.grade_big);
        gradeBig.setText(r.headline());
        gradeBig.setVisibility(r.headline().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void deleteSelected() {
        if (selectedView == null || selectedView.selected() == null) return;
        Mark m = selectedView.selected();
        session.result.marks.remove(m);
        onSelectionChanged(selectedView, null);
        refreshDetails();
    }

    private static String describe(Mark m) {
        switch (m.kind) {
            case GRADE: return "Známka: " + m.correction + (m.original.isEmpty() ? "" : " (" + m.original + ")");
            case CORRECT: return "Fajfka – správně";
            default:
                StringBuilder sb = new StringBuilder();
                if (m.number > 0) sb.append(m.number).append(". ");
                if (!m.original.isEmpty() && !m.correction.isEmpty()) sb.append(m.original).append(" → ").append(m.correction);
                else sb.append(m.correction);
                if (!m.explanation.isEmpty()) sb.append(" — ").append(m.explanation);
                return sb.toString();
        }
    }

    // ------------------------------------------------------------ export

    private String baseName() {
        return "opraveno_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
    }

    private void savePdf() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, baseName() + ".pdf");
        try {
            startActivityForResult(i, REQ_SAVE_PDF);
        } catch (ActivityNotFoundException e) {
            sharePdf();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE_PDF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        runExport("Ukládám PDF…", () -> {
            try (OutputStream os = getContentResolver().openOutputStream(target)) {
                if (os == null) throw new IOException("Soubor nelze zapsat.");
                Exporter.writePdf(this, os, session, prefs);
            }
            return "PDF uloženo.";
        });
    }

    private void sharePdf() {
        String name = baseName();
        runExport("Připravuji PDF…", () -> {
            File f = Exporter.sharePdfFile(this, session, prefs, name);
            main.post(() -> share(Collections.singletonList(f), "application/pdf"));
            return null;
        });
    }

    private void saveImages() {
        String name = baseName();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runExport("Připravuji obrázky…", () -> {
                List<File> files = Exporter.shareImageFiles(this, session, prefs, name);
                main.post(() -> share(files, "image/jpeg"));
                return null;
            });
            return;
        }
        runExport("Ukládám do galerie…", () -> {
            int n = Exporter.saveToGallery(this, session, prefs, name);
            return "Uloženo " + n + " obr. do Galerie › Pictures/Červená tužka.";
        });
    }

    private void share(List<File> files, String mime) {
        if (files.isEmpty()) return;
        Intent i;
        if (files.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            Uri u = FilesProvider.uriFor(files.get(0));
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData cd = null;
            for (File f : files) {
                Uri u = FilesProvider.uriFor(f);
                uris.add(u);
                if (cd == null) cd = ClipData.newRawUri("", u);
                else cd.addItem(new ClipData.Item(u));
            }
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.setClipData(cd);
        }
        i.setType(mime);
        String headline = session.result.headline();
        if (!headline.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, headline);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Sdílet opravenou práci"));
    }

    private interface Export {
        String run() throws IOException;
    }

    private void runExport(String progress, Export job) {
        Toast.makeText(this, progress, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String msg;
            try {
                msg = job.run();
            } catch (IOException e) {
                msg = "Export selhal: " + e.getMessage();
            } catch (OutOfMemoryError e) {
                msg = "Export selhal: nedostatek paměti.";
            }
            String m = msg;
            if (m != null) main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
