package cz.teacherfriend.redpen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * Zobrazí stránku s opravami a umožní značky vybrat, posunout a přidat.
 * Tah po již vybrané značce ji posouvá; jinde se stránka normálně roluje.
 */
public final class PageView extends View {

    public interface Listener {
        void onSelectionChanged(PageView view, Mark selected);
        void onLongPressEmpty(PageView view, int page, float nx, float ny);
        void onMarkMoved(PageView view, Mark mark);
    }

    private final int page;
    private final Bitmap bitmap;
    private final GradingResult result;
    private final MarkRenderer renderer;
    private final Listener listener;
    private final GestureDetector gestures;
    private Mark selected;
    private boolean dragging;
    private float lastX, lastY;

    public PageView(Context ctx, int page, Bitmap bitmap, GradingResult result, MarkRenderer renderer, Listener listener) {
        super(ctx);
        this.page = page;
        this.bitmap = bitmap;
        this.result = result;
        this.renderer = renderer;
        this.listener = listener;
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Mark hit = hitTest(e.getX(), e.getY());
                setSelected(hit);
                listener.onSelectionChanged(PageView.this, hit);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (dragging) return;
                if (hitTest(e.getX(), e.getY()) == null) {
                    float s = scale();
                    listener.onLongPressEmpty(PageView.this, PageView.this.page,
                            e.getX() / s / bitmap.getWidth(), e.getY() / s / bitmap.getHeight());
                }
            }
        });
    }

    public int page() {
        return page;
    }

    public Mark selected() {
        return selected;
    }

    public void setSelected(Mark m) {
        selected = m;
        invalidate();
    }

    private float scale() {
        return getWidth() > 0 ? (float) getWidth() / bitmap.getWidth() : 1f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round((float) w * bitmap.getHeight() / bitmap.getWidth());
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = scale();
        canvas.save();
        canvas.scale(s, s);
        canvas.drawBitmap(bitmap, 0, 0, null);
        List<Mark> marks = result.marksOnPage(page);
        renderer.drawAll(canvas, marks, bitmap.getWidth(), bitmap.getHeight(), selected);
        canvas.restore();
    }

    private Mark hitTest(float vx, float vy) {
        float s = scale();
        float x = vx / s, y = vy / s;
        List<Mark> marks = result.marksOnPage(page);
        Mark best = null;
        float bestArea = Float.MAX_VALUE;
        for (Mark m : marks) {
            RectF r = renderer.hitBounds(m, bitmap.getWidth(), bitmap.getHeight());
            if (r.contains(x, y)) {
                float area = r.width() * r.height();
                if (area < bestArea) { // menší (konkrétnější) značka má přednost
                    bestArea = area;
                    best = m;
                }
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = selected != null && hitTest(e.getX(), e.getY()) == selected;
                if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float s = scale();
                    selected.moveBy((e.getX() - lastX) / s / bitmap.getWidth(),
                            (e.getY() - lastY) / s / bitmap.getHeight());
                    lastX = e.getX();
                    lastY = e.getY();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    listener.onMarkMoved(this, selected);
                }
                break;
            default:
                break;
        }
        gestures.onTouchEvent(e);
        return true;
    }
}
