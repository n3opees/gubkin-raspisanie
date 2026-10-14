package ru.gubkin.raspisanie;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** Экран приложения: вход (логин, пароль, группа) и расписание ближайшего дня. */
public class MainActivity extends Activity {
    private static final int C_BG = 0xFF0F1214;
    private static final int C_CARD = 0xFF15181D;
    private static final int C_LINE = 0xFF262B33;
    private static final int C_ACCENT = 0xFF3FB59E;

    private Store store;
    private LinearLayout root;
    private boolean busy;
    private boolean editing; // открыт экран смены группы/логина
    private Calendar selected; // день, выбранный в ряду Пн–Вс

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        selected = null; // при каждом открытии — актуальный день, а не выбранный когда-то раньше
        render();
        // Открыли приложение, а данные устарели — обновим сами
        if (!editing && store.hasCreds() && store.groupId() != 0 && store.isStale() && !busy) refresh(null);
    }

    private void render() {
        root.removeAllViews();
        if (editing || !store.hasCreds() || store.groupId() == 0) renderSetup(null);
        else renderSchedule(null);
    }

    // ───── Экран входа ─────
    private void renderSetup(String message) {
        root.removeAllViews();
        root.addView(text("Расписание Губкина", 24, 0xFFFFFFFF, true));
        root.addView(space(6));
        root.addView(text("Войди с теми же логином и паролем, что на lk.gubkin.ru, и укажи свою группу.", 15, ScheduleWidget.C_MUTED, false));
        root.addView(space(20));

        EditText login = input("Логин от ЛК", InputType.TYPE_CLASS_NUMBER);
        login.setText(store.login());
        EditText pass = input(store.hasCreds() ? "Пароль (оставь пустым, чтобы не менять)" : "Пароль от ЛК",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText group = input("Группа, например ГМ-24-11", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        group.setText(store.groupCode());
        root.addView(login);
        root.addView(space(10));
        root.addView(pass);
        root.addView(space(10));
        root.addView(group);
        root.addView(space(16));

        TextView status = text(message == null ? "" : message, 14, ScheduleWidget.C_WARN, false);
        Button go = button("Войти и загрузить расписание", true);
        go.setOnClickListener(v -> {
            if (busy) return;
            String l = login.getText().toString().trim();
            String p = pass.getText().toString();
            if (p.isEmpty() && store.hasCreds() && l.equals(store.login())) p = store.password();
            String g = group.getText().toString().trim();
            if (l.isEmpty() || p == null || p.isEmpty()) { status.setText("Нужны и логин, и пароль."); return; }
            if (g.isEmpty()) { status.setText("Впиши код группы, например ГМ-24-11."); return; }
            hideKeyboard(v);
            busy = true;
            go.setEnabled(false);
            status.setTextColor(ScheduleWidget.C_MUTED);
            status.setText("Вхожу в кабинет и ищу группу… Это может занять до минуты.");
            final String fl = l, fp = p;
            Sync.setup(this, fl, fp, g, (saved, error, authError) -> {
                busy = false;
                if (isFinishing()) return;
                if (saved) {
                    editing = false;
                    renderSchedule(error);
                } else {
                    go.setEnabled(true);
                    status.setTextColor(ScheduleWidget.C_WARN);
                    status.setText(error);
                }
            }, (doneCount, total) -> status.setText("Вошёл. Загружаю расписание семестра: " + doneCount + " из " + total + " недель…"));
        });
        root.addView(go);
        root.addView(space(12));
        root.addView(status);
        if (editing) {
            root.addView(space(12));
            Button cancel = button("Отмена", false);
            cancel.setOnClickListener(v -> { editing = false; render(); });
            root.addView(cancel);
        }
        root.addView(space(24));
        root.addView(text("Логин хранится только на этом телефоне, пароль — в зашифрованном виде. Приложение отправляет их только на сайт личного кабинета.", 13, ScheduleWidget.C_MUTED, false));
    }

    // ───── Экран расписания ─────
    private void renderSchedule(String message) {
        root.removeAllViews();
        animating = false;
        Calendar now = Calendar.getInstance();
        Map<String, DayPicker.Day> days = store.days();
        DayPicker.Pick pick = DayPicker.pick(days, now);
        // Неделя — та, где лежит день, который показывает виджет (в пятницу вечером — уже следующая)
        if (selected == null || !store.hasDays()) selected = pick.date;
        // Строка с группой; справа — кнопка «Сегодня», появляется, когда ушёл с сегодняшнего дня
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Группа " + store.groupCode(), 14, ScheduleWidget.C_MUTED, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        todayBtn = text("Сегодня", 14, C_ACCENT, true);
        todayBtn.setGravity(Gravity.CENTER);
        todayBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable pill = rounded(0x1A3FB59E, 18);
        pill.setStroke(dp(1), C_ACCENT);
        todayBtn.setBackground(pill);
        todayBtn.setContentDescription("Перейти к сегодняшнему дню");
        top.addView(todayBtn);
        root.addView(top);
        root.addView(space(10));

        // Неделя целиком (подпись, ряд Пн–Вс и пары дня) перерисовывается на месте
        LinearLayout week = new LinearLayout(this);
        week.setOrientation(LinearLayout.VERTICAL);
        root.addView(week);
        fillWeek(week, days, now);
        todayBtn.setOnClickListener(v -> goToday(week, days, now));
        updateTodayBtn(now, false);
        root.addView(space(10));

        String updated = store.fetchedAt() == 0 ? "Ещё не обновлялось"
                : "Обновлено " + ScheduleWidget.ago(System.currentTimeMillis() - store.fetchedAt());
        TextView status = text(message != null ? message : updated, 13, message != null ? ScheduleWidget.C_WARN : ScheduleWidget.C_MUTED, false);
        root.addView(status);
        root.addView(space(18));

        Button refresh = button("Обновить сейчас", true);
        refresh.setOnClickListener(v -> refresh(status));
        root.addView(refresh);
        root.addView(space(10));

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        boolean hasWidget = mgr.getAppWidgetIds(new ComponentName(this, ScheduleWidget.class)).length > 0;
        if (!hasWidget && mgr.isRequestPinAppWidgetSupported()) {
            Button pin = button("Добавить виджет на рабочий стол", false);
            pin.setOnClickListener(v -> mgr.requestPinAppWidget(new ComponentName(this, ScheduleWidget.class), null, null));
            root.addView(pin);
            root.addView(space(10));
        }
        Button change = button("Сменить группу или логин", false);
        change.setOnClickListener(v -> { editing = true; renderSetup(null); });
        root.addView(change);
        root.addView(space(20));
        root.addView(text(hasWidget
                ? "Виджет на рабочем столе обновляется сам: после последней пары он покажет следующий учебный день."
                : "Виджет можно добавить и вручную: зажми пустое место на рабочем столе → «Виджеты» → «Расписание Губкина».",
                13, ScheduleWidget.C_MUTED, false));
    }

    private void refresh(TextView status) {
        if (busy) return;
        busy = true;
        if (status != null) {
            status.setTextColor(ScheduleWidget.C_MUTED);
            status.setText("Обновляю…");
        }
        Sync.refresh(this, 90000, (error, authError) -> {
            busy = false;
            if (isFinishing()) return;
            if (authError) {
                editing = true;
                renderSetup("Кабинет не принял пароль. Возможно, его сменили — войди заново.");
            } else if (!editing) {
                renderSchedule(error);
            }
        }, (doneCount, total) -> {
            if (status != null) status.setText("Обновляю расписание семестра: " + doneCount + " из " + total + " недель…");
        });
    }

    /** Есть ли в памяти хоть один день этой недели */
    private static boolean weekHasData(Calendar monday, Map<String, DayPicker.Day> days) {
        for (int i = 0; i < 7; i++) {
            Calendar d = (Calendar) monday.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            if (days.containsKey(DayPicker.key(d))) return true;
        }
        return false;
    }

    /** «Эта неделя · 12–18 окт» */
    private static String weekLabel(Calendar monday, Calendar now) {
        Calendar sunday = (Calendar) monday.clone();
        sunday.add(Calendar.DAY_OF_MONTH, 6);
        long diff = DayPicker.mondayOf(now).getTimeInMillis();
        int weeks = Math.round((monday.getTimeInMillis() - diff) / (7f * 24 * 3600 * 1000));
        String rel = weeks == 0 ? "Эта неделя" : weeks == 1 ? "Следующая неделя" : weeks == -1 ? "Прошлая неделя" : "Неделя";
        String m1 = ScheduleWidget.MONTHS[monday.get(Calendar.MONTH)], m2 = ScheduleWidget.MONTHS[sunday.get(Calendar.MONTH)];
        String range = monday.get(Calendar.DAY_OF_MONTH) + (m1.equals(m2) ? "" : " " + m1) + "–" + sunday.get(Calendar.DAY_OF_MONTH) + " " + m2;
        return rel + " · " + range;
    }

    /**
     * Рисует неделю выбранного дня: подпись со стрелками, ряд Пн–Вс (его можно
     * смахнуть влево-вправо) и пары выбранного дня. dir — откуда въезжает новая неделя.
     */
    private void fillWeek(LinearLayout week, Map<String, DayPicker.Day> days, Calendar now) {
        week.removeAllViews();
        Calendar monday = DayPicker.mondayOf(selected);
        Calendar prevMon = (Calendar) monday.clone();
        prevMon.add(Calendar.DAY_OF_MONTH, -7);
        Calendar nextMon = (Calendar) monday.clone();
        nextMon.add(Calendar.DAY_OF_MONTH, 7);
        boolean canPrev = weekHasData(prevMon, days), canNext = weekHasData(nextMon, days);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = arrow("‹", canPrev, "Предыдущая неделя");
        prev.setOnClickListener(v -> {
            if (canPrev) slideWeek(week, days, now, -1, 0);
            else bump(week, -1);
        });
        TextView label = text(weekLabel(monday, now), 14, ScheduleWidget.C_MUTED, false);
        label.setGravity(Gravity.CENTER);
        TextView next = arrow("›", canNext, "Следующая неделя");
        next.setOnClickListener(v -> {
            if (canNext) slideWeek(week, days, now, 1, 0);
            else bump(week, 1);
        });
        head.addView(prev);
        head.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(next);
        week.addView(head);
        week.addView(space(6));

        SwipeRow tabs = new SwipeRow(this);
        tabs.setListener(new SwipeRow.Listener() {
            @Override
            public void onDrag(float dx) {
                if (animating) return;
                boolean allowed = dx < 0 ? canNext : canPrev;
                // Неделя едет за пальцем; если дальше листать некуда — тянется туго, как резинка
                float t = allowed ? dx : rubber(dx);
                week.setTranslationX(t);
                week.setAlpha(1f - Math.min(0.3f, Math.abs(t) / Math.max(1, week.getWidth()) * 0.6f));
            }

            @Override
            public void onRelease(float dx, float vx) {
                if (animating) return;
                int dir = dx < 0 ? 1 : -1;
                boolean allowed = dir > 0 ? canNext : canPrev;
                float w = Math.max(1, week.getWidth());
                boolean fling = Math.abs(vx) > dp(500) && Math.signum(vx) == Math.signum(dx) && Math.abs(dx) > dp(12);
                if (dx != 0 && allowed && (Math.abs(dx) > w * 0.3f || fling)) {
                    slideWeek(week, days, now, dir, vx);
                } else {
                    if (!allowed && Math.abs(dx) > dp(40)) edgeHint(dir);
                    week.animate().translationX(0).alpha(1f).setDuration(240)
                            .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
                }
            }
        });
        TextView[] tabViews = new TextView[7];
        LinearLayout dayBox = new LinearLayout(this);
        dayBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 7; i++) {
            Calendar d = (Calendar) monday.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setLineSpacing(0, 1.1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(5);
            tabs.addView(t, lp);
            tabViews[i] = t;
            t.setOnClickListener(v -> {
                selected = d;
                styleTabs(tabViews, monday, days, now);
                showDay(dayBox, days, now);
                updateTodayBtn(now, true);
            });
        }
        week.addView(tabs);
        week.addView(space(16));
        week.addView(dayBox);
        styleTabs(tabViews, monday, days, now);
        showDay(dayBox, days, now);
    }

    private boolean animating;

    /** Резинка у края: чем дальше тянешь, тем меньше неделя поддаётся */
    private float rubber(float dx) {
        float max = dp(70);
        return Math.signum(dx) * max * (1f - (float) Math.exp(-Math.abs(dx) / (max * 2f)));
    }

    /**
     * Перелистывает неделю, как страницу: текущая уезжает в сторону жеста (с той скоростью,
     * с которой её отпустили), новая выезжает с другой стороны и мягко останавливается.
     * dir = +1 — следующая неделя, -1 — предыдущая; выбранный день недели сохраняется.
     */
    private void slideWeek(LinearLayout week, Map<String, DayPicker.Day> days, Calendar now, int dir, float vx) {
        Calendar s = (Calendar) selected.clone();
        s.add(Calendar.DAY_OF_MONTH, 7 * dir);
        slideTo(week, days, now, s, dir, vx);
    }

    private TextView todayBtn;

    /** Кнопка «Сегодня» видна, только когда выбран не сегодняшний день */
    private void updateTodayBtn(Calendar now, boolean animate) {
        if (todayBtn == null) return;
        boolean show = !sameDay(selected, now);
        boolean shown = todayBtn.getVisibility() == View.VISIBLE && todayBtn.getAlpha() > 0.5f;
        if (show == shown && animate) return;
        todayBtn.animate().cancel();
        if (!animate) {
            todayBtn.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            todayBtn.setAlpha(show ? 1f : 0f);
            return;
        }
        if (show) {
            todayBtn.setVisibility(View.VISIBLE);
            todayBtn.setScaleX(0.85f);
            todayBtn.setScaleY(0.85f);
            todayBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            todayBtn.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150)
                    .withEndAction(() -> todayBtn.setVisibility(View.INVISIBLE)).start();
        }
    }

    /** Возврат к сегодняшнему дню: одним перелистыванием, сколько бы недель ни пролистали */
    private void goToday(LinearLayout week, Map<String, DayPicker.Day> days, Calendar now) {
        if (animating) return;
        Calendar today = (Calendar) now.clone();
        long cur = DayPicker.mondayOf(selected).getTimeInMillis(), dst = DayPicker.mondayOf(today).getTimeInMillis();
        if (Math.abs(cur - dst) < 24L * 3600 * 1000) {
            // Та же неделя — просто выбираем сегодняшний день
            selected = today;
            fillWeek(week, days, now);
            updateTodayBtn(now, true);
            settleHeight(week);
        } else {
            slideTo(week, days, now, today, dst > cur ? 1 : -1, 0);
        }
    }

    /** Перелистывание к неделе с днём target; dir — откуда въезжает новая неделя */
    private void slideTo(LinearLayout week, Map<String, DayPicker.Day> days, Calendar now, Calendar target, int dir, float vx) {
        if (animating) return;
        animating = true;
        float w = Math.max(1, week.getWidth());
        float outX = -dir * w;
        float remaining = Math.abs(outX - week.getTranslationX());
        float speed = Math.max(Math.abs(vx), w * 3.5f); // не медленнее, чем за ~0.3 с на всю ширину
        long outMs = Math.max(90, Math.min(220, Math.round(remaining / speed * 1000)));
        week.animate().translationX(outX).alpha(0f).setDuration(outMs)
                .setInterpolator(new AccelerateInterpolator(1.1f)).withLayer()
                .withEndAction(() -> {
                    // Высота страницы не прыгает, пока листаешь недели
                    week.setMinimumHeight(Math.max(week.getMinimumHeight(), week.getHeight()));
                    selected = target;
                    fillWeek(week, days, now);
                    updateTodayBtn(now, true);
                    week.setTranslationX(dir * w * 0.45f);
                    week.animate().translationX(0).alpha(1f).setDuration(300)
                            .setInterpolator(new DecelerateInterpolator(2.2f)).withLayer()
                            .withEndAction(() -> {
                                animating = false;
                                settleHeight(week);
                            }).start();
                }).start();
    }

    /**
     * Во время листания высота недели держится, чтобы экран не прыгал. Когда новая неделя
     * встала на место, плавно подгоняем высоту под её содержимое (без пустоты внизу).
     */
    private void settleHeight(LinearLayout week) {
        int from = week.getHeight();
        week.measure(View.MeasureSpec.makeMeasureSpec(week.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int saveMin = week.getMinimumHeight();
        week.setMinimumHeight(0);
        week.measure(View.MeasureSpec.makeMeasureSpec(week.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = week.getMeasuredHeight();
        if (to >= from) {
            week.requestLayout();
            return;
        }
        week.setMinimumHeight(saveMin);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, to);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(an -> week.setMinimumHeight((int) an.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                week.setMinimumHeight(0); // дальше высота снова по содержимому
            }
        });
        a.start();
    }

    /** Дальше листать некуда: неделя слегка дёргается в сторону и возвращается */
    private void bump(LinearLayout week, int dir) {
        if (animating) return;
        edgeHint(dir);
        week.animate().translationX(-dir * dp(16)).setDuration(90).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> week.animate().translationX(0).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start())
                .start();
    }

    private void edgeHint(int dir) {
        android.widget.Toast.makeText(this, dir > 0 ? "Дальше расписания нет — это конец семестра" : "Раньше расписания нет: прошедшие недели сайт не показывает",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView arrow(String s, boolean enabled, String description) {
        TextView t = text(s, 26, enabled ? 0xFFFFFFFF : 0xFF3A4049, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), dp(2));
        t.setContentDescription(description);
        return t;
    }

    private static final String[] SHORT_DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    private static boolean sameDay(Calendar a, Calendar b) {
        return DayPicker.key(a).equals(DayPicker.key(b));
    }

    /** Подсвечивает выбранный день; сегодня — цветной рамкой; дни без пар — бледнее */
    private void styleTabs(TextView[] tabs, Calendar monday, Map<String, DayPicker.Day> days, Calendar now) {
        for (int i = 0; i < 7; i++) {
            Calendar d = (Calendar) monday.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            DayPicker.Day day = days.get(DayPicker.key(d));
            boolean hasLessons = day != null && !day.lessons.isEmpty();
            boolean isSelected = sameDay(d, selected);
            boolean isToday = sameDay(d, now);
            TextView t = tabs[i];
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(SHORT_DAYS[i]);
            b.setSpan(new android.text.style.RelativeSizeSpan(0.8f), 0, b.length(), 0);
            b.append('\n').append(String.valueOf(d.get(Calendar.DAY_OF_MONTH)));
            t.setText(b);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int fg = isSelected ? 0xFF06201B : hasLessons ? 0xFFFFFFFF : 0xFF5C636E;
            t.setTextColor(fg);
            GradientDrawable g = rounded(isSelected ? C_ACCENT : C_CARD, 12);
            if (isToday && !isSelected) g.setStroke(dp(2), C_ACCENT);
            t.setBackground(g);
            t.setContentDescription(ScheduleWidget.WEEKDAYS[d.get(Calendar.DAY_OF_WEEK) - 1] + ", " + d.get(Calendar.DAY_OF_MONTH)
                    + (hasLessons ? ", пар: " + day.lessons.size() : ", пар нет"));
        }
    }

    /** Заголовок и пары выбранного дня — перерисовывается без смены экрана */
    private void showDay(LinearLayout box, Map<String, DayPicker.Day> days, Calendar now) {
        box.removeAllViews();
        DayPicker.Day day = days.get(DayPicker.key(selected));
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        String weekday = ScheduleWidget.WEEKDAYS[selected.get(Calendar.DAY_OF_WEEK) - 1];
        String title = sameDay(selected, now) ? "Сегодня" : sameDay(selected, tomorrow) ? "Завтра" : weekday;
        box.addView(text(title, 26, 0xFFFFFFFF, true));
        String date = (title.equals(weekday) ? "" : weekday.toLowerCase() + ", ")
                + selected.get(Calendar.DAY_OF_MONTH) + " " + ScheduleWidget.MONTHS[selected.get(Calendar.MONTH)];
        if (day != null && !day.weekType.isEmpty()) date += " · " + day.weekType + " неделя";
        box.addView(text(date, 14, ScheduleWidget.C_MUTED, false));
        box.addView(space(14));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(rounded(C_CARD, 16));
        if (day == null) {
            card.addView(text(store.hasDays() ? "На этот день сайт расписание не показывает."
                    : "Расписание ещё не загружено.", 15, ScheduleWidget.C_MUTED, false));
        } else if (day.lessons.isEmpty()) {
            card.addView(text("Пар нет", 15, ScheduleWidget.C_MUTED, false));
        } else {
            List<DayPicker.Lesson> ls = day.lessons;
            for (int i = 0; i < ls.size(); i++) card.addView(lessonRow(ls.get(i), DayPicker.isParallel(ls, i), i > 0 && !DayPicker.isParallel(ls, i)));
        }
        box.addView(card);
    }

    private View lessonRow(DayPicker.Lesson l, boolean parallel, boolean divider) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (divider) {
            View line = new View(this);
            line.setBackgroundColor(C_LINE);
            wrap.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(9), 0, dp(9));

        View bar = new View(this);
        bar.setBackground(rounded(ScheduleWidget.typeColor(l.type), 2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT);
        bp.rightMargin = dp(12);
        row.addView(bar, bp);

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.addView(text(parallel ? "┊" : l.start, 15, parallel ? ScheduleWidget.C_MUTED : 0xFFFFFFFF, true));
        if (!parallel) time.addView(text(l.end, 12, ScheduleWidget.C_MUTED, false));
        row.addView(time, new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text("", 15, 0xFFFFFFFF, true);
        name.setText(ScheduleWidget.nameWithSubgroup(l));
        info.addView(name);
        String det = ScheduleWidget.details(l);
        String type = l.type.isEmpty() ? "" : l.type;
        info.addView(text(type + (type.isEmpty() || det.isEmpty() ? "" : " · ") + det, 13, ScheduleWidget.C_MUTED, false));
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(row);
        return wrap;
    }

    // ───── Мелкие помощники для интерфейса ─────
    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        // Сначала однострочность, потом тип ввода: setSingleLine сбрасывает скрытие пароля
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTypeface(Typeface.DEFAULT); // у поля пароля по умолчанию моноширинный шрифт
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF6B7380);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(rounded(C_CARD, 12));
        return e;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primary ? 0xFF06201B : 0xFFFFFFFF);
        b.setBackground(rounded(primary ? C_ACCENT : C_CARD, 12));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
