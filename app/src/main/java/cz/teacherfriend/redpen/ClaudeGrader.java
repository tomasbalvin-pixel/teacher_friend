package cz.teacherfriend.redpen;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Hodnocení práce přes Claude Messages API (streamované SSE, strukturovaný JSON výstup).
 * Záměrně bez externích knihoven – jen HttpURLConnection a org.json z Android frameworku.
 */
public final class ClaudeGrader {

    public interface Progress {
        void onProgress(String message);
    }

    public static final class GradingException extends Exception {
        GradingException(String msg) { super(msg); }
        GradingException(String msg, Throwable cause) { super(msg, cause); }
    }

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /** Delší strana obrázku posílaného modelu. */
    private static final int SEND_EDGE = 1900;
    private static final int MAX_ATTEMPTS = 3;

    private final Prefs prefs;

    public ClaudeGrader(Prefs prefs) {
        this.prefs = prefs;
    }

    public GradingResult grade(List<File> pages, String instructions, Progress progress) throws GradingException {
        if (pages.isEmpty()) throw new GradingException("Nejsou žádné stránky k opravě.");
        progress.onProgress("Připravuji " + pages.size() + " " + plural(pages.size(), "stránku", "stránky", "stránek") + "…");
        JSONObject body;
        try {
            body = buildRequest(pages, instructions);
        } catch (JSONException e) {
            throw new GradingException("Chyba při sestavení požadavku.", e);
        }

        boolean useFallbacks = supportsFallbacks(prefs.model());
        GradingException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (useFallbacks) body.put("fallbacks", "default"); else body.remove("fallbacks");
                String text = send(body, useFallbacks, progress);
                return parseResult(text, pages.size());
            } catch (HttpError e) {
                if (e.code == 400 && useFallbacks && e.getMessage() != null
                        && e.getMessage().toLowerCase().contains("fallback")) {
                    // Účet/model záložní modely nepodporuje – zkusíme znovu bez nich.
                    useFallbacks = false;
                    attempt--;
                    continue;
                }
                if (!e.retryable || attempt == MAX_ATTEMPTS) throw new GradingException(e.userMessage());
                last = new GradingException(e.userMessage());
                sleepBackoff(attempt, e.retryAfterSec, progress);
            } catch (SocketTimeoutException e) {
                last = new GradingException("Vypršel časový limit spojení.", e);
                if (attempt == MAX_ATTEMPTS) throw last;
                sleepBackoff(attempt, 0, progress);
            } catch (IOException e) {
                last = new GradingException("Chyba sítě: " + e.getMessage(), e);
                if (attempt == MAX_ATTEMPTS) throw last;
                sleepBackoff(attempt, 0, progress);
            } catch (JSONException e) {
                throw new GradingException("Odpověď modelu se nepodařilo přečíst.", e);
            }
        }
        throw last != null ? last : new GradingException("Hodnocení se nezdařilo.");
    }

    private static boolean supportsFallbacks(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable");
    }

    private static void sleepBackoff(int attempt, int retryAfterSec, Progress progress) {
        int sec = retryAfterSec > 0 ? Math.min(retryAfterSec, 60) : (attempt == 1 ? 3 : 10);
        progress.onProgress("Služba je přetížená, zkouším znovu za " + sec + " s…");
        try {
            Thread.sleep(sec * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- požadavek

    private JSONObject buildRequest(List<File> pages, String instructions) throws JSONException, GradingException {
        JSONArray content = new JSONArray();
        for (int i = 0; i < pages.size(); i++) {
            Bitmap bmp = PageLoader.decodePage(pages.get(i), SEND_EDGE);
            if (bmp == null) throw new GradingException("Stránku " + (i + 1) + " nelze načíst.");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            int w = bmp.getWidth(), h = bmp.getHeight();
            bmp.recycle();
            content.put(new JSONObject().put("type", "text")
                    .put("text", "Page " + (i + 1) + " of " + pages.size() + " (" + w + "×" + h + " px):"));
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))));
        }
        content.put(new JSONObject().put("type", "text").put("text", userPrompt(instructions)));

        return new JSONObject()
                .put("model", prefs.model())
                .put("max_tokens", 32000)
                .put("stream", true)
                // "summarized": během přemýšlení proudí události, takže spojení nezůstane dlouho tiché.
                .put("thinking", new JSONObject().put("type", "adaptive").put("display", "summarized"))
                .put("output_config", new JSONObject()
                        .put("effort", "high")
                        .put("format", new JSONObject()
                                .put("type", "json_schema")
                                .put("schema", schema())))
                .put("system", systemPrompt())
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", content)));
    }

    private String systemPrompt() {
        String scale;
        switch (prefs.scale()) {
            case "percent":
                scale = "Express the overall result as a percentage in `grade` (e.g. \"85 %\"). Fill `points` only if the teacher gave a point scheme.";
                break;
            case "points":
                scale = "Score the work in points. Put the result into `points` as \"earned/max\" (e.g. \"17/20\"); use the teacher's point scheme if given, otherwise define a sensible one per task. Set `grade` to the same string.";
                break;
            case "none":
                scale = "Do not assign a grade: leave `grade` and `points` as empty strings. Focus on corrections and feedback.";
                break;
            default:
                scale = "Use the Czech school grading scale 1 (výborně) – 5 (nedostatečně) in `grade`; you may use \"-\" (e.g. \"2-\") or \"1*\" for exceptional work. Fill `points` as \"earned/max\" only if the teacher gave a point scheme.";
        }
        String strict;
        switch (prefs.strictness()) {
            case "lenient": strict = "Grade leniently and encouragingly: ignore minor slips, focus on substantive errors."; break;
            case "strict": strict = "Grade strictly: every error counts, including spelling, punctuation, missing units and sloppy notation."; break;
            default: strict = "Grade like a fair, experienced teacher: mark all real errors, weigh them by importance.";
        }
        String checks = prefs.checks()
                ? "Add one `correct` mark (a tick) next to each task, answer or result that is fully correct. Do not tick individual words."
                : "Do not produce `correct` marks.";

        return "You are an experienced, fair teacher correcting a student's work with a red pen. "
                + "The work arrives as photos or scans (one image per page). Read everything carefully, including handwriting, "
                + "work out the correct answers yourself, then return your corrections as marks that the app draws directly onto the page images.\n\n"
                + "COORDINATES: every mark has a box x0,y0 (top-left) and x1,y1 (bottom-right) as integers 0–1000, normalised to that page image: "
                + "(0,0) is the top-left corner and (1000,1000) the bottom-right corner of the image, x horizontal, y vertical. "
                + "Boxes must be tight and precise – they enclose exactly the characters the mark refers to (for a misspelled word the word itself, "
                + "for a wrong result just that number), never a whole line or paragraph unless the mark really concerns it. "
                + "`page` is the 1-based page number.\n\n"
                + "MARK KINDS:\n"
                + "- wrong: the student wrote something incorrect. Box = the incorrect word/number/expression. `original` = what the student wrote, "
                + "`correction` = the corrected form, written as a teacher would above it (short – a word, number or brief expression). "
                + "If only one letter is wrong, still box the whole word and give the whole corrected word.\n"
                + "- missing: something is missing (letter, comma, diacritic, word, unit, step). Box = small area exactly where it belongs. "
                + "`correction` = what to insert.\n"
                + "- circle: a problem that cannot be fixed by a short replacement (wrong method, illogical sentence, unreadable part, wrong answer to a whole task). "
                + "Box = the affected region. `correction` = very short note written next to it (max ~6 words).\n"
                + "- correct: a tick for a correct task/answer. Box = the answer. `correction` empty.\n"
                + "- note: a short margin comment (praise or advice) tied to a place, max ~12 words in `correction`.\n\n"
                + "For every mark except `correct`, `explanation` is one short sentence explaining the error or rule (it is listed below the work). "
                + "`penalty` is the points deducted (e.g. \"-1\") or an empty string when no point scheme is used. "
                + "Mark every real error exactly once. Never mark correct text as an error; if handwriting is ambiguous, give the student the benefit of the doubt. "
                + "Corrections themselves are written in the language of the student's work. "
                + "`explanation`, `summary`, `strengths`, `improvements` and notes are written in: " + prefs.language() + ".\n\n"
                + "GRADING: " + scale + " " + strict + " " + checks + "\n"
                + "`summary` is 2–4 sentences of overall feedback to the student. `strengths` and `improvements` have 1–4 short items each. "
                + "If the images contain no student work to grade, return no marks and explain that in `summary`.";
    }

    private static String userPrompt(String instructions) {
        String base = "Correct and grade this student's work.";
        if (instructions == null || instructions.trim().isEmpty()) {
            return base + " No extra instructions from the teacher – infer the task, subject and level from the work itself.";
        }
        return base + " The teacher's instructions (assignment, answer key, point scheme or level) are authoritative:\n<teacher_instructions>\n"
                + instructions.trim() + "\n</teacher_instructions>";
    }

    private static JSONObject schema() throws JSONException {
        JSONObject str = new JSONObject().put("type", "string");
        JSONObject integer = new JSONObject().put("type", "integer");
        JSONObject mark = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("page", integer)
                        .put("kind", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("wrong").put("missing").put("circle").put("correct").put("note")))
                        .put("x0", integer).put("y0", integer).put("x1", integer).put("y1", integer)
                        .put("original", str)
                        .put("correction", str)
                        .put("explanation", str)
                        .put("penalty", str))
                .put("required", new JSONArray().put("page").put("kind").put("x0").put("y0").put("x1").put("y1")
                        .put("original").put("correction").put("explanation").put("penalty"))
                .put("additionalProperties", false);
        JSONObject strArray = new JSONObject().put("type", "array").put("items", str);
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("marks", new JSONObject().put("type", "array").put("items", mark))
                        .put("grade", str)
                        .put("points", str)
                        .put("summary", str)
                        .put("strengths", strArray)
                        .put("improvements", strArray))
                .put("required", new JSONArray().put("marks").put("grade").put("points").put("summary")
                        .put("strengths").put("improvements"))
                .put("additionalProperties", false);
    }

    // ---------------------------------------------------------------- přenos

    private static final class HttpError extends IOException {
        final int code;
        final boolean retryable;
        final int retryAfterSec;

        HttpError(int code, String message, int retryAfterSec) {
            super(message);
            this.code = code;
            this.retryable = code == 408 || code == 409 || code == 429 || code >= 500;
            this.retryAfterSec = retryAfterSec;
        }

        String userMessage() {
            switch (code) {
                case 401: return "Neplatný API klíč. Zkontrolujte ho v Nastavení.";
                case 403: return "API klíč nemá k tomuto modelu přístup. " + getMessage();
                case 413: return "Práce je příliš velká. Zkuste méně stránek.";
                case 429: return "Byl překročen limit požadavků API. Zkuste to za chvíli.";
                case 529: return "Služba je momentálně přetížená. Zkuste to za chvíli.";
                default: return "Chyba API (" + code + "): " + getMessage();
            }
        }
    }

    private String send(JSONObject body, boolean withFallbacks, Progress progress) throws IOException, JSONException, GradingException {
        progress.onProgress("Odesílám práci k opravě…");
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "text/event-stream");
            conn.setRequestProperty("x-api-key", prefs.apiKey());
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            if (withFallbacks) conn.setRequestProperty("anthropic-beta", FALLBACK_BETA);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String msg = readError(conn.getErrorStream());
                int retryAfter = 0;
                try {
                    retryAfter = Integer.parseInt(conn.getHeaderField("retry-after"));
                } catch (RuntimeException ignored) {
                    // hlavička chybí nebo není číslo
                }
                throw new HttpError(code, msg, retryAfter);
            }
            progress.onProgress("Opravuji práci – čtu, počítám a kontroluji…");
            return readStream(conn.getInputStream(), progress);
        } finally {
            conn.disconnect();
        }
    }

    private static String readError(InputStream es) {
        if (es == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            try {
                return new JSONObject(sb.toString()).getJSONObject("error").optString("message", sb.toString());
            } catch (JSONException e) {
                return sb.toString();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Čte SSE stream a skládá text odpovědi. Při záložním modelu (blok typu "fallback")
     * si pamatujeme hranici, protože pokračování může navazovat na částečný výstup.
     */
    private static String readStream(InputStream in, Progress progress) throws IOException, JSONException, GradingException {
        StringBuilder text = new StringBuilder();
        String stopReason = null;
        int fallbackAt = -1;
        long lastReport = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                JSONObject ev = new JSONObject(data);
                switch (ev.optString("type")) {
                    case "content_block_start": {
                        JSONObject block = ev.optJSONObject("content_block");
                        if (block != null && "fallback".equals(block.optString("type"))) {
                            fallbackAt = text.length();
                            progress.onProgress("Pokračuji záložním modelem…");
                        }
                        break;
                    }
                    case "content_block_delta": {
                        JSONObject delta = ev.optJSONObject("delta");
                        if (delta != null && "thinking_delta".equals(delta.optString("type")) && text.length() == 0) {
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 1500) {
                                lastReport = now;
                                progress.onProgress("Čtu práci a promýšlím opravy…");
                            }
                        } else if (delta != null && "text_delta".equals(delta.optString("type"))) {
                            text.append(delta.optString("text"));
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 700) {
                                lastReport = now;
                                progress.onProgress("Zapisuji opravy… (" + text.length() + " znaků)");
                            }
                        }
                        break;
                    }
                    case "message_delta": {
                        JSONObject delta = ev.optJSONObject("delta");
                        if (delta != null && !delta.isNull("stop_reason")) stopReason = delta.optString("stop_reason");
                        break;
                    }
                    case "error": {
                        JSONObject err = ev.optJSONObject("error");
                        String type = err == null ? "" : err.optString("type");
                        String msg = err == null ? data : err.optString("message", data);
                        if ("overloaded_error".equals(type)) throw new HttpError(529, msg, 0);
                        if ("rate_limit_error".equals(type)) throw new HttpError(429, msg, 0);
                        throw new HttpError(500, msg, 0);
                    }
                    default:
                        break;
                }
            }
        }
        if ("refusal".equals(stopReason)) {
            throw new GradingException("Model odmítl tuto práci vyhodnotit. Zkuste jiné fotky nebo upravte pokyny.");
        }
        if ("max_tokens".equals(stopReason)) {
            throw new GradingException("Odpověď byla příliš dlouhá. Zkuste opravit méně stránek najednou.");
        }
        if (fallbackAt >= 0) {
            // Pokud celek není platný JSON, zkusíme jen část od záložního modelu.
            String all = text.toString();
            if (extractJson(all) == null && extractJson(all.substring(fallbackAt)) != null) {
                return all.substring(fallbackAt);
            }
        }
        return text.toString();
    }

    private static JSONObject extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- odpověď

    static GradingResult parseResult(String text, int pageCount) throws GradingException {
        JSONObject o = extractJson(text);
        if (o == null) throw new GradingException("Model nevrátil čitelné hodnocení. Zkuste to prosím znovu.");
        GradingResult r = new GradingResult();
        r.grade = o.optString("grade", "").trim();
        r.points = o.optString("points", "").trim();
        r.summary = o.optString("summary", "").trim();
        addStrings(o.optJSONArray("strengths"), r.strengths);
        addStrings(o.optJSONArray("improvements"), r.improvements);
        JSONArray marks = o.optJSONArray("marks");
        if (marks != null) {
            for (int i = 0; i < marks.length(); i++) {
                JSONObject m = marks.optJSONObject(i);
                if (m == null) continue;
                int page = m.optInt("page", 1) - 1;
                if (page < 0 || page >= pageCount) continue;
                Mark mark = new Mark(page, Mark.Kind.parse(m.optString("kind")),
                        (float) m.optDouble("x0", 0) / 1000f, (float) m.optDouble("y0", 0) / 1000f,
                        (float) m.optDouble("x1", 0) / 1000f, (float) m.optDouble("y1", 0) / 1000f);
                mark.original = m.optString("original", "").trim();
                mark.correction = m.optString("correction", "").trim();
                mark.explanation = m.optString("explanation", "").trim();
                mark.penalty = m.optString("penalty", "").trim();
                r.marks.add(mark);
            }
        }
        if (!r.grade.isEmpty() || !r.points.isEmpty()) {
            Mark g = new Mark(0, Mark.Kind.GRADE, 0.78f, 0.02f, 0.97f, 0.11f);
            g.correction = r.grade.isEmpty() ? r.points : r.grade;
            g.original = r.grade.isEmpty() || r.points.equals(r.grade) ? "" : r.points;
            r.marks.add(g);
        }
        r.renumber();
        return r;
    }

    private static void addStrings(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
    }

    private static String plural(int n, String one, String few, String many) {
        if (n == 1) return one;
        if (n >= 2 && n <= 4) return few;
        return many;
    }
}
