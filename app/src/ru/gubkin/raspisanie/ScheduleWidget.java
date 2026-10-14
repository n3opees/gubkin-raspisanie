package ru.gubkin.raspisanie;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.List;

/** Виджет на рабочем столе. Рисует только из сохранённого расписания — быстро и без интернета. */
public class ScheduleWidget extends AppWidgetProvider {
    static final String ACTION_TICK = "ru.gubkin.raspisanie.TICK";
    static final int MAX_ROWS = 9;

    static final int C_TEXT = 0xFFFFFFFF;
    static final int C_MUTED = 0xFF8A8F98;
    static final int C_WARN = 0xFFE0A43B;
    static final int C_SG1 = 0xFF56C271;
    static final int C_SG2 = 0xFFE0A43B;

    static final String[] WEEKDAYS = {"Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"};
    static final String[] MONTHS = {"янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};

    static int typeColor(String type) {
        if ("Лекция".equals(type)) return 0xFF4F9DDE;
        if ("Семинар".equals(type)) return 0xFF56C271;
        if ("Лабораторная работа".equals(type)) return 0xFFE0A43B;
        if ("Мероприятие".equals(type)) return 0xFFB07CD6;
        return C_MUTED;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        updateAll(ctx, true);
    }

    @Override
    public void onEnabled(Context ctx) {
        RefreshJob.schedulePeriodic(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(tickIntent(ctx));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_TICK.equals(intent.getAction())) {
            updateAll(ctx, true);
        } else {
            super.onReceive(ctx, intent);
        }
    }

    private static PendingIntent tickIntent(Context ctx) {
        Intent i = new Intent(ctx, ScheduleWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Перерисовывает все виджеты и ставит будильник на следующую перерисовку
     * (после последней пары или в полночь). kickRefresh — если данные устарели,
     * попросить фоновое обновление из интернета.
     */
    static void updateAll(Context ctx, boolean kickRefresh) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ScheduleWidget.class));
        if (ids == null || ids.length == 0) return;

        Store store = new Store(ctx);
        Calendar now = Calendar.getInstance();
        DayPicker.Pick pick = DayPicker.pick(store.days(), now);
        mgr.updateAppWidget(ids, build(ctx, store, pick));

        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) {
            // Неточный будильник: Android может сдвинуть его на несколько минут, зато без особых разрешений
            am.setWindow(AlarmManager.RTC, DayPicker.nextRefresh(pick, now), 5 * 60 * 1000L, tickIntent(ctx));
        }
        // Задачи могли стереться (принудительная остановка, «очистка» телефона) — ставим заново
        if (store.hasCreds() && store.groupId() != 0) RefreshJob.schedulePeriodic(ctx);
        if (kickRefresh && store.hasCreds() && store.groupId() != 0 && store.isStale()
                && System.currentTimeMillis() - store.lastAttempt() > 30 * 60 * 1000L) {
            RefreshJob.scheduleNow(ctx);
        }
    }

    private static RemoteViews build(Context ctx, Store store, DayPicker.Pick pick) {
        String pkg = ctx.getPackageName();
        RemoteViews v = new RemoteViews(pkg, R.layout.widget);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        for (int i = 0; i < MAX_ROWS; i++) v.setViewVisibility(id(ctx, "w_row" + i), View.GONE);
        v.setViewVisibility(R.id.w_note, View.GONE);

        String empty = null;
        if (!store.hasCreds() || store.groupId() == 0) empty = "Открой приложение и войди в кабинет.";
        else if (!store.hasDays()) empty = "Открой приложение, чтобы загрузить расписание.";
        else if (pick.day == null) empty = "На ближайшие дни пар нет 🎉";

        Calendar d = pick.date;
        v.setTextViewText(R.id.w_title, pick.day != null && pick.offset != 0 ? WEEKDAYS[d.get(Calendar.DAY_OF_WEEK) - 1] : "Сегодня");
        String date = d.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[d.get(Calendar.MONTH)];
        if (pick.day != null && !pick.day.weekType.isEmpty()) date += " · " + pick.day.weekType;
        v.setTextViewText(R.id.w_date, date);

        if (empty != null) {
            v.setViewVisibility(R.id.w_empty, View.VISIBLE);
            v.setTextViewText(R.id.w_empty, empty);
            return v;
        }
        v.setViewVisibility(R.id.w_empty, View.GONE);

        // Давно не обновлялось (например, сервер не пускал) — мягкая пометка
        long age = System.currentTimeMillis() - store.fetchedAt();
        if (age > 24L * 3600 * 1000) {
            v.setViewVisibility(R.id.w_note, View.VISIBLE);
            v.setTextViewText(R.id.w_note, "Обновлено " + ago(age));
        }

        List<DayPicker.Lesson> ls = pick.day.lessons;
        for (int i = 0; i < ls.size() && i < MAX_ROWS; i++) {
            DayPicker.Lesson l = ls.get(i);
            boolean parallel = DayPicker.isParallel(ls, i);
            v.setViewVisibility(id(ctx, "w_row" + i), View.VISIBLE);
            v.setInt(id(ctx, "w_bar" + i), "setBackgroundColor", typeColor(l.type));
            v.setTextViewText(id(ctx, "w_start" + i), parallel ? "┊" : l.start);
            v.setTextColor(id(ctx, "w_start" + i), parallel ? C_MUTED : C_TEXT);
            v.setViewVisibility(id(ctx, "w_end" + i), parallel ? View.GONE : View.VISIBLE);
            v.setTextViewText(id(ctx, "w_end" + i), l.end);
            v.setTextViewText(id(ctx, "w_name" + i), nameWithSubgroup(l));
            v.setTextViewText(id(ctx, "w_sub" + i), details(l));
        }
        return v;
    }

    static CharSequence nameWithSubgroup(DayPicker.Lesson l) {
        SpannableStringBuilder b = new SpannableStringBuilder(l.name);
        if (l.subgroup != 0) {
            int s = b.length();
            // Неразрывные символы: «п/г 1» не должна переноситься на две строки
            b.append("  п/⁠г ").append(String.valueOf(l.subgroup));
            b.setSpan(new ForegroundColorSpan(l.subgroup == 1 ? C_SG1 : C_SG2), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    static String details(DayPicker.Lesson l) {
        String s = l.room.isEmpty() ? "" : "ауд. " + l.room;
        if (!l.teacher.isEmpty()) s += (s.isEmpty() ? "" : " · ") + l.teacher;
        return s;
    }

    static String ago(long ms) {
        long min = ms / 60000;
        if (min < 60) return min + " мин назад";
        long h = min / 60;
        if (h < 24) return h + " ч назад";
        return (h / 24) + " дн назад";
    }

    private static int id(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }
}
