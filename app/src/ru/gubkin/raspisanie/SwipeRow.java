package ru.gubkin.raspisanie;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * Ряд Пн–Вс, который ловит горизонтальный жест. Нажатия на квадратики внутри работают
 * как обычно; если палец явно ведут вбок — ряд забирает жест себе и сообщает, на сколько
 * сдвинули и с какой скоростью отпустили. Двигает неделю тот, кто слушает.
 */
final class SwipeRow extends LinearLayout {
    interface Listener {
        /** Палец ведут: dx — смещение от точки касания в пикселях */
        void onDrag(float dx);

        /** Палец отпустили: dx — итоговое смещение, vx — скорость в пикселях в секунду */
        void onRelease(float dx, float vx);
    }

    private final int slop;
    private float downX, downY;
    private boolean swiping;
    private VelocityTracker tracker;
    private Listener listener;

    SwipeRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void setListener(Listener l) {
        listener = l;
    }

    private void track(MotionEvent e) {
        if (tracker == null) tracker = VelocityTracker.obtain();
        // Скорость считаем по экранным координатам: ряд сам едет вместе с пальцем
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setLocation(e.getRawX(), e.getRawY());
        tracker.addMovement(copy);
        copy.recycle();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                swiping = false;
                if (tracker != null) tracker.clear();
                track(e);
                break;
            case MotionEvent.ACTION_MOVE:
                track(e);
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    swiping = true;
                    // Не даём прокрутке экрана перехватить горизонтальный жест
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            default:
                break;
        }
        return swiping;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                if (tracker != null) tracker.clear();
                track(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (listener != null) listener.onDrag(e.getRawX() - downX);
                return true;
            case MotionEvent.ACTION_UP: {
                track(e);
                float vx = 0;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    vx = tracker.getXVelocity();
                }
                swiping = false;
                if (listener != null) listener.onRelease(e.getRawX() - downX, vx);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                if (listener != null) listener.onRelease(0, 0);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}
