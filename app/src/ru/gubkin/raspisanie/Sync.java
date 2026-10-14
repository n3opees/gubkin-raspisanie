package ru.gubkin.raspisanie;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Вход с поиском группы и обновление расписания. Общий код для приложения и фонового обновления.
 * Весь семестр скачивается при входе и потом раз в неделю; в остальное время — только текущая
 * и следующая неделя (2 запроса), чтобы не злить сервер.
 */
final class Sync {
    private Sync() {}

    /** Обновление уже идёт — второе параллельно не запускаем (сервер не любит лишних запросов) */
    private static boolean refreshing;

    static final String BLOCKED = "Сервер расписания временно ограничил доступ. Попробуй через пару часов.";
    /** Сколько ждать загрузки всего семестра (~24 недели с паузами между запросами) */
    static final long FULL_TIMEOUT_MS = 5 * 60 * 1000L;

    interface Result {
        /** error == null — всё получилось; authError — неверный логин или пароль */
        void onResult(String error, boolean authError);
    }

    interface SetupResult {
        /** saved — логин и группа подошли и сохранены (расписание при этом могло загрузиться не всё, тогда error != null) */
        void onResult(boolean saved, String error, boolean authError);
    }

    /** Текущая и следующая неделя */
    private static JSONArray dates() {
        Calendar c = Calendar.getInstance();
        JSONArray a = new JSONArray();
        a.put(DayPicker.key(c));
        c.add(Calendar.DAY_OF_MONTH, 7);
        a.put(DayPicker.key(c));
        return a;
    }

    private static JSONObject scheduleTask(int groupId) throws Exception {
        return new JSONObject().put("kind", "schedule").put("groupId", groupId).put("dates", dates());
    }

    /** Весь текущий семестр; недели, скачанные за последние дни, пропускаются */
    private static JSONObject semesterTask(int groupId, JSONArray skip) throws Exception {
        return new JSONObject().put("kind", "semester").put("groupId", groupId).put("skip", skip);
    }

    /** Сохраняет дни из ответа; возвращает текст для человека (ошибку или предупреждение) или null */
    private static String saveResult(Store store, JSONObject r) {
        if (r == null) return "Пустой ответ сервера.";
        if (r.optBoolean("blocked")) return BLOCKED;
        if (r.optBoolean("noStudy")) return "В кабинете не найден текущий семестр.";
        if (r.optBoolean("badFormat") || !r.has("days")) return "Непонятный ответ сервера расписания.";
        JSONArray loaded = r.optJSONArray("loaded");
        if (r.optBoolean("partial") && (loaded == null || loaded.length() == 0)) return BLOCKED;
        try {
            store.saveDays(r.getJSONObject("days"));
        } catch (Exception e) {
            return "Не удалось сохранить расписание.";
        }
        if (loaded != null) store.markWeeksLoaded(loaded);
        if (r.has("total")) {
            if (!r.optBoolean("partial")) {
                store.markFull();
            } else {
                return "Скачано " + r.optInt("done") + " из " + r.optInt("total")
                        + " недель семестра. Остальные догрузятся сами позже — сервер попросил подождать.";
            }
        }
        return null;
    }

    /** Первая настройка или смена группы/логина: вход → поиск группы → весь семестр */
    static void setup(Context ctx, String login, String password, String groupText, SetupResult cb, WebFetcher.Progress progress) {
        Store store = new Store(ctx);
        final int[] found = {0};
        final String[] foundCode = {""};
        WebFetcher.run(ctx, login, password, FULL_TIMEOUT_MS, prev -> {
            if (prev == null) return new JSONObject().put("kind", "groups").put("groupText", groupText);
            if (prev.has("found") || prev.has("similar")) {
                JSONObject f = prev.optJSONObject("found");
                if (f == null) return null; // группа не найдена — заканчиваем с подсказкой
                found[0] = f.getInt("id");
                foundCode[0] = f.getString("code");
                // Та же группа, что была: свежие недели не перекачиваем
                JSONArray skip = found[0] == store.groupId() ? store.recentWeeks() : new JSONArray();
                return semesterTask(found[0], skip);
            }
            return null; // пришло расписание
        }, (r, error, authError) -> {
            if (authError) {
                cb.onResult(false, error, true);
                return;
            }
            if (found[0] == 0) {
                // До расписания не дошли: ошибка связи или группа не найдена
                if (error != null) {
                    cb.onResult(false, WebFetcher.humanError(error), false);
                } else if (r != null && r.optBoolean("blocked")) {
                    cb.onResult(false, BLOCKED, false);
                } else {
                    StringBuilder msg = new StringBuilder("Группа «" + groupText + "» не найдена.");
                    JSONArray sim = r == null ? null : r.optJSONArray("similar");
                    if (sim != null && sim.length() > 0) {
                        msg.append(" Похожие: ");
                        for (int i = 0; i < sim.length(); i++) msg.append(i > 0 ? ", " : "").append(sim.optString(i));
                        msg.append(".");
                    } else {
                        msg.append(" Проверь, как она пишется в ЛК.");
                    }
                    cb.onResult(false, msg.toString(), false);
                }
                return;
            }
            // Вход и группа подошли — сохраняем, даже если расписание сейчас загрузилось не всё
            try {
                store.saveCreds(login, password);
            } catch (Exception e) {
                cb.onResult(false, "Не удалось сохранить пароль на телефоне.", false);
                return;
            }
            store.saveGroup(found[0], foundCode[0]); // до сохранения дней: смена группы стирает старые
            String msg = error != null ? WebFetcher.humanError(error) : saveResult(store, r);
            ScheduleWidget.updateAll(ctx, false);
            RefreshJob.schedulePeriodic(ctx);
            cb.onResult(true, msg, false);
        }, progress);
    }

    /** Обновление с сохранёнными логином и группой: раз в неделю — весь семестр, иначе две недели */
    static void refresh(Context ctx, long timeoutMs, Result cb, WebFetcher.Progress progress) {
        Store store = new Store(ctx);
        if (!store.hasCreds() || store.groupId() == 0) {
            cb.onResult("Сначала войди в приложении.", false);
            return;
        }
        if (refreshing) {
            cb.onResult(null, false); // результат придёт от уже идущего обновления
            return;
        }
        refreshing = true;
        store.markAttempt();
        int gid = store.groupId();
        boolean full = store.needsFull();
        JSONArray skip = full ? store.recentWeeks() : null;
        WebFetcher.run(ctx, store.login(), store.password(), full ? Math.max(timeoutMs, FULL_TIMEOUT_MS) : timeoutMs,
                prev -> prev == null ? (full ? semesterTask(gid, skip) : scheduleTask(gid)) : null,
                (r, error, authError) -> {
                    refreshing = false;
                    String msg = error != null ? WebFetcher.humanError(error) : saveResult(store, r);
                    ScheduleWidget.updateAll(ctx, false);
                    cb.onResult(msg, authError);
                }, full ? progress : null);
    }
}
