package cz.teacherfriend.redpen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Zachycení chyb bez adb: pád aplikace se zapíše do souboru a při dalším spuštění se ukáže
 * v dialogu, odkud jde text zkopírovat a poslat vývojáři.
 */
public final class CrashLog {

    private static boolean installed;

    private CrashLog() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "crash.txt");
    }

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try (FileOutputStream os = new FileOutputStream(file(app))) {
                    os.write(report(app, e).getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    // zápis se nepovedl – nic dalšího nezmůžeme
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    public static String report(Context ctx, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String version = "?";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // verze není nutná
        }
        pw.println("Červená tužka " + version + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
        e.printStackTrace(pw);
        pw.flush();
        String s = sw.toString();
        return s.length() > 6000 ? s.substring(0, 6000) : s;
    }

    /** Pokud aplikace minule spadla, zobrazí záznam o pádu. */
    public static void showPreviousCrash(Activity a) {
        File f = file(a);
        if (!f.exists()) return;
        String text;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 16000)];
            int n = in.read(buf);
            text = new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!text.isEmpty()) showDetails(a, "Aplikace minule spadla", "Pošlete prosím tento text vývojáři:", text);
    }

    /** Dialog s chybou; technické detaily lze zkopírovat do schránky. */
    public static void showError(Activity a, String title, Throwable e) {
        showDetails(a, title, userMessage(e), report(a, e));
    }

    static String userMessage(Throwable e) {
        String m = e.getMessage();
        if (e instanceof IOException && m != null && !m.isEmpty()) return m;
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void showDetails(final Activity a, String title, String message, final String details) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message + "\n\n" + details)
                .setPositiveButton("OK", null)
                .setNeutralButton("Kopírovat", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("chyba", details));
                            Toast.makeText(a, "Zkopírováno", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }
}
