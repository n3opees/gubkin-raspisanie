package ru.gubkin.raspisanie;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Выбор дня для показа и времени следующего обновления. Без зависимостей от Android — проверяется тестом. */
final class DayPicker {
    private DayPicker() {}

    static final class Lesson {
        String start, end, type, name, room, teacher;
        int startMin, endMin, subgroup;
    }

    static final class Day {
        String weekType = "";
        List<Lesson> lessons = new ArrayList<>();
    }

    static final class Pick {
        Calendar date;
        int offset;
        Day day; // null — на ближайшие две недели пар нет или нет данных
    }

    /** Ключ дня в формате сервера: ДД-ММ-ГГГГ */
    static String key(Calendar c) {
        return String.format(Locale.ROOT, "%02d-%02d-%04d",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    /** Понедельник той же недели (неделя начинается с понедельника) */
    static Calendar mondayOf(Calendar c) {
        Calendar m = (Calendar) c.clone();
        int dow = m.get(Calendar.DAY_OF_WEEK); // вс=1 … сб=7
        m.add(Calendar.DAY_OF_MONTH, -((dow + 5) % 7));
        return m;
    }

    static int maxEnd(Day d) {
        int m = 0;
        for (Lesson l : d.lessons) m = Math.max(m, l.endMin);
        return m;
    }

    /**
     * Сегодня показываем весь день, пока не кончится последняя пара;
     * потом — ближайший следующий день, где есть пары.
     */
    static Pick pick(Map<String, Day> days, Calendar now) {
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int offset = 0; offset < 14; offset++) {
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_MONTH, offset);
            Day day = days.get(key(d));
            if (day == null || day.lessons.isEmpty()) continue;
            if (offset == 0 && maxEnd(day) <= nowMin) continue; // все пары прошли
            Pick p = new Pick();
            p.date = d;
            p.offset = offset;
            p.day = day;
            return p;
        }
        Pick p = new Pick();
        p.date = (Calendar) now.clone();
        return p;
    }

    /**
     * Когда перерисовать виджет: через 30 секунд после последней пары сегодня,
     * а если показан другой день — сразу после полуночи (чтобы появилось «Сегодня»).
     */
    static long nextRefresh(Pick p, Calendar now) {
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.SECOND, 30);
        t.set(Calendar.MILLISECOND, 0);
        if (p.day != null && p.offset == 0) {
            int end = maxEnd(p.day);
            t.set(Calendar.HOUR_OF_DAY, end / 60);
            t.set(Calendar.MINUTE, end % 60);
        } else {
            t.set(Calendar.HOUR_OF_DAY, 0);
            t.set(Calendar.MINUTE, 0);
            t.add(Calendar.DAY_OF_MONTH, 1);
        }
        return t.getTimeInMillis();
    }

    /** Время у второй из параллельных пар не повторяем */
    static boolean isParallel(List<Lesson> lessons, int i) {
        return i > 0 && lessons.get(i - 1).start.equals(lessons.get(i).start);
    }
}
