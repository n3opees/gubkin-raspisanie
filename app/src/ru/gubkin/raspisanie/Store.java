package ru.gubkin.raspisanie;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Хранит логин, группу и сохранённое расписание в личной папке приложения.
 * Пароль дополнительно шифруется ключом из защищённого хранилища Android (Keystore).
 */
final class Store {
    static final long FRESH_MS = 3L * 60 * 60 * 1000; // в сеть — не чаще раза в 3 часа

    private static final String KEY_ALIAS = "gubkin_password_key";
    private final SharedPreferences sp;
    private final File filesDir;

    Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("gubkin", Context.MODE_PRIVATE);
        filesDir = c.getApplicationContext().getFilesDir();
    }

    // ───── Логин и пароль ─────
    boolean hasCreds() {
        return !login().isEmpty() && password() != null;
    }

    String login() {
        return sp.getString("login", "");
    }

    String password() {
        String enc = sp.getString("pass", null);
        if (enc == null) return null;
        try {
            String[] parts = enc.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // ключ потерян (например, после восстановления) — попросим войти заново
        }
    }

    void saveCreds(String login, String password) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        String enc = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(c.doFinal(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        sp.edit().putString("login", login).putString("pass", enc).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    // ───── Группа ─────
    int groupId() {
        return sp.getInt("groupId", 0);
    }

    String groupCode() {
        return sp.getString("groupCode", "");
    }

    /** Новая группа — старое расписание больше не подходит */
    void saveGroup(int id, String code) {
        if (id != groupId()) clearDays();
        sp.edit().putInt("groupId", id).putString("groupCode", code).apply();
    }

    // ───── Сохранённое расписание ─────
    long fetchedAt() {
        return sp.getLong("fetchedAt", 0);
    }

    boolean isStale() {
        return System.currentTimeMillis() - fetchedAt() > FRESH_MS;
    }

    long lastAttempt() {
        return sp.getLong("lastAttempt", 0);
    }

    void markAttempt() {
        sp.edit().putLong("lastAttempt", System.currentTimeMillis()).apply();
    }

    /** Добавляет дни к сохранённым; совсем старые (больше полугода назад) выбрасывает */
    void saveDays(JSONObject fresh) throws Exception {
        JSONObject all = new JSONObject(readDays());
        Iterator<String> it = fresh.keys();
        while (it.hasNext()) {
            String k = it.next();
            all.put(k, fresh.get(k));
        }
        JSONObject keep = new JSONObject();
        long now = System.currentTimeMillis();
        Iterator<String> it2 = all.keys();
        while (it2.hasNext()) {
            String k = it2.next();
            if (dayMillis(k) > now - 210L * 24 * 3600 * 1000) keep.put(k, all.get(k));
        }
        writeDays(keep.toString());
        sp.edit().putLong("fetchedAt", now).apply();
    }

    // Расписание семестра (~170 дней) хранится отдельным файлом: для настроек оно великовато
    private File daysFile() {
        return new File(filesDir, "days.json");
    }

    private String readDays() {
        File f = daysFile();
        if (!f.exists()) return sp.getString("days", "{}"); // старая версия хранила в настройках
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void writeDays(String json) throws Exception {
        File tmp = new File(filesDir, "days.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(daysFile())) {
            daysFile().delete();
            if (!tmp.renameTo(daysFile())) throw new Exception("не удалось сохранить расписание");
        }
        sp.edit().remove("days").apply();
    }

    private void clearDays() {
        daysFile().delete();
        sp.edit().remove("days").remove("fetchedAt").remove("fullAt").remove("weeksLoaded").apply();
    }

    // ───── Загрузка всего семестра ─────
    static final long FULL_EVERY_MS = 7L * 24 * 3600 * 1000; // весь семестр — раз в неделю
    static final long WEEK_FRESH_MS = 3L * 24 * 3600 * 1000; // неделю, скачанную за 3 дня, не перекачиваем

    boolean needsFull() {
        return System.currentTimeMillis() - sp.getLong("fullAt", 0) > FULL_EVERY_MS;
    }

    void markFull() {
        sp.edit().putLong("fullAt", System.currentTimeMillis()).apply();
    }

    /** Отмечает, когда скачаны недели (по датам понедельников) */
    void markWeeksLoaded(JSONArray mondays) {
        try {
            JSONObject w = new JSONObject(sp.getString("weeksLoaded", "{}"));
            long now = System.currentTimeMillis();
            for (int i = 0; mondays != null && i < mondays.length(); i++) w.put(mondays.getString(i), now);
            sp.edit().putString("weeksLoaded", w.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Недели, скачанные недавно, — при докачке семестра их пропускаем */
    JSONArray recentWeeks() {
        JSONArray out = new JSONArray();
        try {
            JSONObject w = new JSONObject(sp.getString("weeksLoaded", "{}"));
            long now = System.currentTimeMillis();
            Iterator<String> it = w.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (now - w.getLong(k) < WEEK_FRESH_MS) out.put(k);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static long dayMillis(String k) {
        try {
            String[] p = k.split("-");
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[0]));
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    Map<String, DayPicker.Day> days() {
        Map<String, DayPicker.Day> out = new HashMap<>();
        try {
            JSONObject all = new JSONObject(readDays());
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject d = all.getJSONObject(k);
                DayPicker.Day day = new DayPicker.Day();
                day.weekType = d.optString("weekType", "");
                JSONArray ls = d.optJSONArray("lessons");
                for (int i = 0; ls != null && i < ls.length(); i++) {
                    JSONObject o = ls.getJSONObject(i);
                    DayPicker.Lesson l = new DayPicker.Lesson();
                    l.start = o.optString("start");
                    l.end = o.optString("end");
                    l.startMin = o.optInt("startMin");
                    l.endMin = o.optInt("endMin");
                    l.type = o.optString("type");
                    l.name = o.optString("name");
                    l.room = o.optString("room");
                    l.teacher = o.optString("teacher");
                    l.subgroup = o.optInt("subgroup");
                    day.lessons.add(l);
                }
                out.put(k, day);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    boolean hasDays() {
        return daysFile().exists() || !"{}".equals(sp.getString("days", "{}"));
    }
}
