package cz.teacherfriend.redpen;

import android.graphics.RectF;

/** Jedna značka „červenou tužkou“ na stránce. Souřadnice jsou normalizované 0..1 vůči obrázku stránky. */
public final class Mark {

    public enum Kind {
        /** Chybný text: přeškrtnout a nad něj napsat opravu. */
        WRONG,
        /** Něco chybí: stříška a doplněný text. */
        MISSING,
        /** Zakroužkovat problémové místo s krátkou poznámkou. */
        CIRCLE,
        /** Fajfka u správné odpovědi. */
        CORRECT,
        /** Volný komentář na okraji. */
        NOTE,
        /** Výsledná známka (correction = známka, original = body). */
        GRADE;

        static Kind parse(String s) {
            if (s == null) return NOTE;
            switch (s.trim().toLowerCase()) {
                case "wrong": return WRONG;
                case "missing": return MISSING;
                case "circle": return CIRCLE;
                case "correct": return CORRECT;
                case "grade": return GRADE;
                default: return NOTE;
            }
        }
    }

    public int page;
    public Kind kind;
    public float x0, y0, x1, y1;
    public String original = "";
    public String correction = "";
    public String explanation = "";
    public String penalty = "";
    /** Pořadové číslo v seznamu vysvětlení; 0 = nečíslovat. */
    public int number;

    public Mark(int page, Kind kind, float x0, float y0, float x1, float y1) {
        this.page = page;
        this.kind = kind;
        setBox(x0, y0, x1, y1);
    }

    public void setBox(float ax, float ay, float bx, float by) {
        x0 = clamp01(Math.min(ax, bx));
        x1 = clamp01(Math.max(ax, bx));
        y0 = clamp01(Math.min(ay, by));
        y1 = clamp01(Math.max(ay, by));
        // Degenerované boxy roztáhneme na minimální velikost, aby šly kreslit i chytit prstem.
        if (x1 - x0 < 0.01f) { float c = (x0 + x1) / 2; x0 = clamp01(c - 0.005f); x1 = clamp01(c + 0.005f); }
        if (y1 - y0 < 0.01f) { float c = (y0 + y1) / 2; y0 = clamp01(c - 0.005f); y1 = clamp01(c + 0.005f); }
    }

    public void moveBy(float dx, float dy) {
        float w = x1 - x0, h = y1 - y0;
        float nx = Math.max(0, Math.min(1 - w, x0 + dx));
        float ny = Math.max(0, Math.min(1 - h, y0 + dy));
        x0 = nx; y0 = ny; x1 = nx + w; y1 = ny + h;
    }

    public RectF box(float w, float h) {
        return new RectF(x0 * w, y0 * h, x1 * w, y1 * h);
    }

    /** Zda značka patří do číslovaného seznamu vysvětlení. */
    public boolean isListed() {
        return kind != Kind.CORRECT && kind != Kind.GRADE
                && (!explanation.isEmpty() || !correction.isEmpty());
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
