package cz.teacherfriend.redpen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final int REQ_PICK = 2;
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_LAST_RESULT = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout thumbs;
    private TextView pagesLabel;
    private TextView pagesEmpty;
    private EditText instructions;
    private Button gradeButton;
    private View progressBox;
    private TextView progressText;
    private File pendingPhoto;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);
        setContentView(R.layout.activity_main);
        thumbs = findViewById(R.id.thumbs);
        pagesLabel = findViewById(R.id.pages_label);
        pagesEmpty = findViewById(R.id.pages_empty);
        instructions = findViewById(R.id.instructions);
        gradeButton = findViewById(R.id.btn_grade);
        progressBox = findViewById(R.id.progress_box);
        progressText = findViewById(R.id.progress_text);

        // Záměrně bez lambd: APK musí fungovat i při sestavení nástrojem dx bez desugaringu.
        View.OnClickListener clicks = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                try {
                    if (id == R.id.btn_camera) takePhoto();
                    else if (id == R.id.btn_pick) pickFiles();
                    else if (id == R.id.btn_clear) confirmClear();
                    else if (id == R.id.btn_grade) startGrading();
                } catch (Throwable e) {
                    CrashLog.showError(MainActivity.this, "Akce se nepodařila", e);
                }
            }
        };
        findViewById(R.id.btn_camera).setOnClickListener(clicks);
        findViewById(R.id.btn_pick).setOnClickListener(clicks);
        findViewById(R.id.btn_clear).setOnClickListener(clicks);
        gradeButton.setOnClickListener(clicks);

        instructions.setText(Session.get().instructions);
        instructions.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { Session.get().instructions = s.toString(); }
        });

        if (state != null && state.getString("pendingPhoto") != null) {
            pendingPhoto = new File(state.getString("pendingPhoto"));
        }
        refreshPages();
        if (state == null) handleIncoming(getIntent());
        CrashLog.showPreviousCrash(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingPhoto != null) out.putString("pendingPhoto", pendingPhoto.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_LAST_RESULT, 0, "Poslední výsledek")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_SETTINGS, 1, R.string.settings)
                .setIcon(android.R.drawable.ic_menu_preferences)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(MENU_LAST_RESULT).setVisible(Session.get().result != null && !busy);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_LAST_RESULT) {
            startActivity(new Intent(this, ResultActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------ vstupy

    private void takePhoto() {
        if (!canAddPages()) return;
        pendingPhoto = FilesProvider.cameraFile(this);
        Uri out = FilesProvider.uriFor(pendingPhoto);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(ClipData.newRawUri("", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            toast("V telefonu není aplikace fotoaparátu.");
        }
    }

    private void pickFiles() {
        if (!canAddPages()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast("Výběr souborů není v tomto zařízení dostupný.");
        }
    }

    private boolean canAddPages() {
        if (busy) return false;
        if (Session.get().pages.size() >= PageLoader.MAX_PAGES) {
            toast("Najednou lze opravit nejvýše " + PageLoader.MAX_PAGES + " stránek.");
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            handleResult(requestCode, resultCode, data);
        } catch (Throwable e) {
            setBusy(false, null);
            CrashLog.showError(this, "Stránku se nepodařilo přidat", e);
        }
    }

    private void handleResult(int requestCode, int resultCode, Intent data) throws IOException {
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA && pendingPhoto != null) {
                //noinspection ResultOfMethodCallIgnored
                pendingPhoto.delete();
                pendingPhoto = null;
            }
            return;
        }
        if (requestCode == REQ_CAMERA && pendingPhoto != null) {
            File photo = pendingPhoto;
            pendingPhoto = null;
            if (photo.length() == 0) {
                // Některé fotoaparáty EXTRA_OUTPUT ignorují a vrátí jen náhled v "data".
                Object thumb = data != null && data.getExtras() != null ? data.getExtras().get("data") : null;
                if (thumb instanceof Bitmap) {
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(photo)) {
                        ((Bitmap) thumb).compress(Bitmap.CompressFormat.JPEG, 95, os);
                    }
                    toast("Fotoaparát vrátil jen malý náhled – kvalita může být nižší.");
                } else {
                    toast("Fotoaparát nevrátil žádný snímek.");
                    return;
                }
            }
            List<Uri> uris = new ArrayList<>();
            uris.add(Uri.fromFile(photo));
            importUris(uris, photo);
        } else if (requestCode == REQ_PICK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris, null);
        }
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        if (!uris.isEmpty()) importUris(uris, null);
    }

    private void importUris(final List<Uri> uris, final File deleteAfter) {
        setBusy(true, "Načítám stránky…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<File> added = new ArrayList<>();
                String note = null;
                Throwable failure = null;
                try {
                    int already = Session.get().pages.size();
                    for (Uri u : uris) {
                        if (already + added.size() >= PageLoader.MAX_PAGES) {
                            note = "Načteno jen prvních " + PageLoader.MAX_PAGES + " stránek.";
                            break;
                        }
                        try {
                            added.addAll(PageLoader.load(MainActivity.this, u, already + added.size()));
                        } catch (OutOfMemoryError e) {
                            note = "Soubor je příliš velký.";
                        } catch (Throwable e) {
                            // Chybu ukážeme, ale ostatní vybrané soubory ještě zkusíme načíst.
                            failure = e;
                        }
                    }
                    if (deleteAfter != null) //noinspection ResultOfMethodCallIgnored
                        deleteAfter.delete();
                } catch (Throwable e) {
                    failure = e;
                }
                final String msg = note;
                final Throwable fail = failure;
                // Vždy vrátit obrazovku do použitelného stavu, i když načítání selhalo.
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Session.get().pages.addAll(added);
                        if (!added.isEmpty()) Session.get().result = null;
                        setBusy(false, null);
                        refreshPages();
                        if (fail != null) CrashLog.showError(MainActivity.this, "Soubor se nepodařilo načíst", fail);
                        else if (msg != null) toast(msg);
                    }
                });
            }
        });
    }

    private void confirmClear() {
        if (busy || Session.get().pages.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage("Odebrat všechny stránky?")
                .setPositiveButton("Odebrat", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        for (File f : Session.get().pages) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        Session.get().pages.clear();
                        Session.get().result = null;
                        refreshPages();
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    private void refreshPages() {
        List<File> pages = Session.get().pages;
        thumbs.removeAllViews();
        int h = dp(120);
        for (int i = 0; i < pages.size(); i++) {
            File f = pages.get(i);
            ImageView iv = new ImageView(this);
            Bitmap b = PageLoader.decodePage(f, h * 2);
            if (b != null) iv.setImageBitmap(b);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(0xFFE0E0E0);
            iv.setPadding(dp(1), dp(1), dp(1), dp(1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h);
            lp.setMarginEnd(dp(8));
            lp.topMargin = dp(8);
            iv.setLayoutParams(lp);
            iv.setContentDescription("Stránka " + (i + 1));
            final int index = i;
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRemove(index);
                }
            });
            thumbs.addView(iv);
        }
        pagesLabel.setText(pages.isEmpty() ? "Stránky" : "Stránky: " + pages.size() + "  (klepnutím odeberete)");
        pagesEmpty.setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
        gradeButton.setEnabled(!pages.isEmpty() && !busy);
    }

    private void confirmRemove(final int index) {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setMessage("Odebrat stránku " + (index + 1) + "?")
                .setPositiveButton("Odebrat", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<File> pages = Session.get().pages;
                        if (index < pages.size()) {
                            //noinspection ResultOfMethodCallIgnored
                            pages.remove(index).delete();
                            Session.get().result = null;
                            refreshPages();
                        }
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    // ------------------------------------------------------------ hodnocení

    private void startGrading() {
        if (busy) return;
        final Prefs prefs = new Prefs(this);
        if (prefs.apiKey().isEmpty()) {
            toast("Nejdřív zadejte API klíč.");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        final List<File> pages = new ArrayList<>(Session.get().pages);
        final String instr = instructions.getText().toString();
        setBusy(true, "Připravuji…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        io.execute(new Runnable() {
            @Override
            public void run() {
                GradingResult result = null;
                String error = null;
                Throwable failure = null;
                try {
                    result = new ClaudeGrader(prefs).grade(pages, instr, new ClaudeGrader.Progress() {
                        @Override
                        public void onProgress(final String msg) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    progressText.setText(msg);
                                }
                            });
                        }
                    });
                } catch (ClaudeGrader.GradingException e) {
                    error = e.getMessage();
                } catch (OutOfMemoryError e) {
                    error = "Nedostatek paměti. Zkuste méně stránek.";
                } catch (Throwable e) {
                    failure = e;
                }
                final GradingResult r = result;
                final String err = error;
                final Throwable fail = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        setBusy(false, null);
                        if (isFinishing() || isDestroyed()) return;
                        if (r != null) {
                            Session.get().result = r;
                            startActivity(new Intent(MainActivity.this, ResultActivity.class));
                        } else if (fail != null) {
                            CrashLog.showError(MainActivity.this, "Hodnocení se nepodařilo", fail);
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Hodnocení se nepodařilo")
                                    .setMessage(err)
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                });
            }
        });
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        progressBox.setVisibility(b ? View.VISIBLE : View.GONE);
        if (msg != null) progressText.setText(msg);
        gradeButton.setEnabled(!b && !Session.get().pages.isEmpty());
        findViewById(R.id.btn_camera).setEnabled(!b);
        findViewById(R.id.btn_pick).setEnabled(!b);
        invalidateOptionsMenu();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
