package ru.gubkin.raspisanie;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/** Проверка выбора дня и времени перерисовки виджета. Запуск: sh tools/test-daypicker.sh */
public class DayPickerTest {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) failures++;
        System.out.println((ok ? "ок   " : "ОШИБКА ") + what + ": " + got + (ok ? "" : " (ожидалось " + want + ")"));
    }

    static DayPicker.Lesson l(String start, String end) {
        DayPicker.Lesson x = new DayPicker.Lesson();
        x.start = start;
        x.end = end;
        String[] s = start.split(":"), e = end.split(":");
        x.startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
        x.endMin = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
        return x;
    }

    static Calendar at(int d, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, d, h, m);
        return c;
    }

    static String fmt(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format("%02d:%02d %s", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DayPicker.key(c));
    }

    public static void main(String[] args) {
        Map<String, DayPicker.Day> days = new HashMap<>();
        DayPicker.Day wed = new DayPicker.Day();
        wed.lessons.add(l("8:30", "10:00"));
        wed.lessons.add(l("14:00", "15:30"));
        wed.lessons.add(l("14:00", "15:30")); // параллельная пара другой подгруппы
        wed.lessons.add(l("17:20", "18:50"));
        days.put("14-10-2026", wed);
        DayPicker.Day thu = new DayPicker.Day();
        thu.lessons.add(l("8:30", "10:00"));
        days.put("15-10-2026", thu);
        DayPicker.Day fri = new DayPicker.Day();
        fri.lessons.add(l("10:15", "11:45"));
        days.put("16-10-2026", fri);
        days.put("17-10-2026", new DayPicker.Day()); // суббота без пар
        DayPicker.Day mon = new DayPicker.Day();
        mon.lessons.add(l("8:30", "10:00"));
        days.put("19-10-2026", mon);

        DayPicker.Pick p;
        p = DayPicker.pick(days, at(14, 0, 32));
        check("ср 00:32 — какой день", DayPicker.key(p.date) + " offset " + p.offset, "14-10-2026 offset 0");
        check("ср 00:32 — перерисовать в", fmt(DayPicker.nextRefresh(p, at(14, 0, 32))), "18:50 14-10-2026");
        p = DayPicker.pick(days, at(14, 12, 10));
        check("ср 12:10 — всё ещё сегодня", p.offset, 0);
        p = DayPicker.pick(days, at(14, 18, 51));
        check("ср 18:51 — следующий день", DayPicker.key(p.date), "15-10-2026");
        check("ср 18:51 — перерисовать в", fmt(DayPicker.nextRefresh(p, at(14, 18, 51))), "00:00 15-10-2026");
        p = DayPicker.pick(days, at(16, 12, 0));
        check("пт 12:00 — пропускаем выходные", DayPicker.key(p.date), "19-10-2026");
        p = DayPicker.pick(days, at(18, 9, 0));
        check("вс 09:00 — понедельник", DayPicker.key(p.date) + " offset " + p.offset, "19-10-2026 offset 1");
        p = DayPicker.pick(days, at(28, 9, 0));
        check("нет данных — пусто", p.day == null, true);
        check("параллельная пара", DayPicker.isParallel(wed.lessons, 2) + "/" + DayPicker.isParallel(wed.lessons, 1), "true/false");

        check("понедельник недели для вс 18.10", DayPicker.key(DayPicker.mondayOf(at(18, 9, 0))), "12-10-2026");
        check("понедельник недели для пн 12.10", DayPicker.key(DayPicker.mondayOf(at(12, 9, 0))), "12-10-2026");
        check("понедельник недели для ср 14.10", DayPicker.key(DayPicker.mondayOf(at(14, 9, 0))), "12-10-2026");

        System.out.println(failures == 0 ? "\nВСЕ ПРОВЕРКИ ПРОШЛИ" : "\nПРОВАЛОВ: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
