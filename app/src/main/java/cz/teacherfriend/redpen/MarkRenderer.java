package cz.teacherfriend.redpen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;

/**
 * Kreslí značky „červenou tužkou“. Pracuje v pixelových souřadnicích obrázku stránky (w × h);
 * pro zobrazení i export stačí plátno předem zvětšit/zmenšit.
 */
public final class MarkRenderer {

    public static final int RED = Color.rgb(215, 38, 30);

    private static Typeface handFont;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint select = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean numbers;

    public MarkRenderer(Context ctx, boolean numbers) {
        this.numbers = numbers;
        synchronized (MarkRenderer.class) {
            if (handFont == null) {
                try {
                    handFont = Typeface.createFromAsset(ctx.getAssets(), "fonts/caveat.ttf");
                } catch (RuntimeException e) {
                    handFont = Typeface.create("casual", Typeface.NORMAL);
                }
            }
        }
        stroke.setColor(RED);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(RED);
        text.setTypeface(handFont);
        select.setColor(Color.rgb(25, 118, 210));
        select.setStyle(Paint.Style.STROKE);
    }

    public void drawAll(Canvas c, List<Mark> marks, float w, float h, Mark selected) {
        for (Mark m : marks) draw(c, m, w, h);
        if (selected != null) {
            RectF r = hitBounds(selected, w, h);
            select.setStrokeWidth(Math.max(2f, w / 500f));
            select.setPathEffect(new DashPathEffect(new float[]{w / 80f, w / 120f}, 0));
            c.drawRect(r, select);
        }
    }

    private float penWidth(float w, float h) {
        return Math.max(2f, Math.min(w, h) / 260f);
    }

    /** Velikost „rukopisu“ odvozená od výšky označeného textu, omezená rozumnými mezemi. */
    private float textSize(Mark m, float w, float h) {
        float boxH = (m.y1 - m.y0) * h;
        float min = w / 42f, max = w / 20f;
        return Math.max(min, Math.min(max, boxH * 1.15f));
    }

    public void draw(Canvas c, Mark m, float w, float h) {
        RectF b = m.box(w, h);
        float pen = penWidth(w, h);
        stroke.setStrokeWidth(pen);
        float ts = textSize(m, w, h);
        text.setTextSize(ts);

        switch (m.kind) {
            case WRONG: drawWrong(c, m, b, w, h, ts, pen); break;
            case MISSING: drawMissing(c, m, b, w, h, ts); break;
            case CIRCLE: drawCircle(c, m, b, w, h, ts, pen); break;
            case CORRECT: drawTick(c, b, w, ts, pen); break;
            case NOTE: drawNote(c, m, b, w, h, ts); break;
            case GRADE: drawGrade(c, m, b, w, h, pen); break;
        }
        if (numbers && m.number > 0) drawNumber(c, m.number, b, w, ts);
    }

    private void drawWrong(Canvas c, Mark m, RectF b, float w, float h, float ts, float pen) {
        if (m.correction.isEmpty()) {
            // Bez opravy jen vlnovka pod chybou.
            Path p = new Path();
            float amp = Math.max(pen * 1.2f, ts * 0.08f), step = Math.max(pen * 3f, ts * 0.25f);
            float y = b.bottom + pen;
            p.moveTo(b.left, y);
            boolean up = true;
            for (float x = b.left + step; x <= b.right + step / 2; x += step) {
                p.quadTo(x - step / 2, y + (up ? -amp : amp), x, y);
                up = !up;
            }
            c.drawPath(p, stroke);
            return;
        }
        // Přeškrtnutí: mírně stoupající čára přes střed, s přesahem jako od ruky.
        float cy = b.centerY();
        float over = Math.min(b.width() * 0.06f, ts * 0.3f);
        c.drawLine(b.left - over, cy + b.height() * 0.12f, b.right + over, cy - b.height() * 0.12f, stroke);
        drawTextAbove(c, m.correction, b.left, b, w, h, ts, false);
    }

    private void drawMissing(Canvas c, Mark m, RectF b, float w, float h, float ts) {
        float cx = b.centerX();
        float s = ts * 0.45f;
        Path p = new Path();
        p.moveTo(cx - s, b.bottom + s * 0.5f);
        p.lineTo(cx, b.bottom - s * 0.9f);
        p.lineTo(cx + s, b.bottom + s * 0.5f);
        c.drawPath(p, stroke);
        if (!m.correction.isEmpty()) drawTextAbove(c, m.correction, cx, b, w, h, ts, true);
    }

    private void drawCircle(Canvas c, Mark m, RectF b, float w, float h, float ts, float pen) {
        float padX = Math.max(pen * 3, b.width() * 0.08f);
        float padY = Math.max(pen * 3, b.height() * 0.18f);
        RectF o = new RectF(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY);
        // Ovál s překrytím konců jako při kroužkování rukou.
        Path p = new Path();
        p.addArc(o, 200, 340);
        RectF o2 = new RectF(o);
        o2.inset(-pen * 1.2f, pen * 0.8f);
        p.addArc(o2, 170, 45);
        c.drawPath(p, stroke);
        if (!m.correction.isEmpty()) drawSideText(c, m.correction, o, w, h, ts);
    }

    private void drawTick(Canvas c, RectF b, float w, float ts, float pen) {
        float s = Math.max(ts * 1.1f, b.height() * 0.9f);
        float x = b.right + s * 0.25f;
        if (x + s > w) x = Math.max(0, b.right - s);
        float y = b.centerY() - s * 0.5f;
        Path p = new Path();
        p.moveTo(x, y + s * 0.55f);
        p.lineTo(x + s * 0.35f, y + s * 0.95f);
        p.lineTo(x + s, y);
        float old = stroke.getStrokeWidth();
        stroke.setStrokeWidth(pen * 1.3f);
        c.drawPath(p, stroke);
        stroke.setStrokeWidth(old);
    }

    private void drawNote(Canvas c, Mark m, RectF b, float w, float h, float ts) {
        // Krátké podtržení místa, ke kterému se poznámka vztahuje.
        c.drawLine(b.left, b.bottom + stroke.getStrokeWidth(), b.right, b.bottom + stroke.getStrokeWidth(), stroke);
        if (!m.correction.isEmpty()) drawSideText(c, m.correction, b, w, h, ts);
    }

    private void drawGrade(Canvas c, Mark m, RectF b, float w, float h, float pen) {
        String grade = m.correction;
        if (grade.isEmpty()) return;
        float size = Math.min(b.height() * 0.8f, w / 9f);
        text.setTextSize(size);
        float tw = text.measureText(grade);
        float cx = b.centerX(), cy = b.centerY();
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2;
        c.drawText(grade, cx - tw / 2, baseline, text);
        float rx = Math.max(tw * 0.75f, size * 0.6f), ry = size * 0.62f;
        float old = stroke.getStrokeWidth();
        stroke.setStrokeWidth(pen * 1.3f);
        Path p = new Path();
        p.addArc(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), 250, 345);
        c.drawPath(p, stroke);
        stroke.setStrokeWidth(old);
        if (!m.original.isEmpty()) {
            text.setTextSize(size * 0.45f);
            float pw = text.measureText(m.original);
            float px = Math.max(0, Math.min(w - pw, cx - pw / 2));
            c.drawText(m.original, px, cy + ry + size * 0.45f, text);
        }
    }

    private void drawNumber(Canvas c, int n, RectF b, float w, float ts) {
        float r = ts * 0.32f;
        float cx = Math.max(r, b.left - r * 1.3f), cy = Math.max(r, b.top - r * 0.2f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(RED);
        c.drawCircle(cx, cy, r, fill);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        String s = String.valueOf(n);
        tp.setTextSize(r * (s.length() > 1 ? 1.05f : 1.3f));
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(s, cx - tp.measureText(s) / 2, cy - (fm.ascent + fm.descent) / 2, tp);
    }

    /** Text nad místem (případně pod ním, když nahoře není místo); x je levý okraj nebo střed. */
    private void drawTextAbove(Canvas c, String s, float x, RectF b, float w, float h, float ts, boolean centered) {
        text.setTextSize(ts);
        float tw = text.measureText(s);
        if (tw > w * 0.9f) {
            text.setTextSize(ts * (w * 0.9f) / tw);
            tw = text.measureText(s);
        }
        float left = centered ? x - tw / 2 : x;
        left = Math.max(2, Math.min(w - tw - 2, left));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = b.top - fm.descent * 0.6f;
        if (baseline + fm.ascent < 0) baseline = b.bottom - fm.ascent + fm.descent * 0.2f;
        c.drawText(s, left, baseline, text);
    }

    /** Delší text vpravo od oblasti, pokud je tam místo, jinak pod ní. */
    private void drawSideText(Canvas c, String s, RectF area, float w, float h, float ts) {
        text.setTextSize(ts);
        float gap = ts * 0.3f;
        float spaceRight = w - area.right - gap;
        float maxWidth;
        float left, top;
        StaticLayout layout;
        if (spaceRight >= w * 0.22f) {
            maxWidth = Math.min(spaceRight - 4, w * 0.4f);
            layout = layout(s, maxWidth);
            left = area.right + gap;
            top = area.centerY() - layout.getHeight() / 2f;
        } else {
            maxWidth = Math.min(w * 0.6f, w - 8);
            layout = layout(s, maxWidth);
            left = Math.max(4, Math.min(w - layout.getWidth() - 4, area.left));
            top = area.bottom + gap * 0.5f;
            if (top + layout.getHeight() > h) top = area.top - layout.getHeight() - gap * 0.5f;
        }
        top = Math.max(2, Math.min(h - layout.getHeight() - 2, top));
        c.save();
        c.translate(left, top);
        layout.draw(c);
        c.restore();
    }

    private StaticLayout layout(String s, float maxWidth) {
        int width = (int) Math.max(1, Math.min(maxWidth, Math.ceil(text.measureText(s)) + 2));
        return StaticLayout.Builder.obtain(s, 0, s.length(), text, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 0.9f)
                .setIncludePad(false)
                .build();
    }

    /** Oblast, kterou lze prstem chytit – box rozšířený o okolí, kde bývá text opravy. */
    public RectF hitBounds(Mark m, float w, float h) {
        RectF b = m.box(w, h);
        float ts = m.kind == Mark.Kind.GRADE ? 0 : textSize(m, w, h);
        float pad = Math.max(w / 60f, ts * 0.4f);
        b.inset(-pad, -pad);
        if (m.kind == Mark.Kind.WRONG || m.kind == Mark.Kind.MISSING) b.top -= ts;
        if (m.kind == Mark.Kind.CORRECT) b.right += ts * 1.4f;
        return b;
    }
}
