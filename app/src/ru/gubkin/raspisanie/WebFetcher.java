package ru.gubkin.raspisanie;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Работает с кабинетом через невидимый встроенный браузер: открывает страницу
 * входа, входит, переходит на страницу расписания и выполняет там задачи из
 * logic.js. Сервер расписания тормозит прямые запросы из программ, а запросы
 * со страницы для него выглядят как обычный браузер.
 */
final class WebFetcher {
    static final String BASE = "https://lk.gubkin.ru";
    private static final String TAG = "GubkinFetch";

    /** Даёт следующую задачу по результату предыдущей (null на первом шаге). Вернуть null — закончить. */
    interface Steps {
        JSONObject next(JSONObject previous) throws Exception;
    }

    interface Done {
        /** result — последний результат; error — текст ошибки; authError — неверный логин или пароль */
        void onDone(JSONObject result, String error, boolean authError);
    }

    /** Сколько недель уже скачано — для надписи на экране */
    interface Progress {
        void onProgress(int done, int total);
    }

    private enum Stage { LOGIN_PAGE, LOGGING_IN, SCHEDULE_PAGE, TASKS, FINISHED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context ctx;
    private final String login, password;
    private final long timeoutMs;
    private final Steps steps;
    private final Done done;
    private final Progress progress;
    private WebView wv;
    private Stage stage = Stage.LOGIN_PAGE;
    private String logic;

    private WebFetcher(Context ctx, String login, String password, long timeoutMs, Steps steps, Done done, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.login = login;
        this.password = password;
        this.timeoutMs = timeoutMs;
        this.steps = steps;
        this.done = done;
        this.progress = progress;
    }

    /** Запускать с главного потока. progress может быть null */
    static void run(Context ctx, String login, String password, long timeoutMs, Steps steps, Done done, Progress progress) {
        new WebFetcher(ctx, login, password, timeoutMs, steps, done, progress).start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void start() {
        try {
            logic = readAsset("logic.js");
        } catch (Exception e) {
            finish(null, "Не удалось загрузить логику приложения.", false);
            return;
        }
        CookieManager.getInstance().setAcceptCookie(true);
        wv = new WebView(ctx);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBlockNetworkImage(true); // картинки сайта не нужны — быстрее
        wv.addJavascriptInterface(new Bridge(), "GubkinBridge");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "страница загружена: " + url + " (этап " + stage + ")");
                onPage();
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "консоль страницы: " + m.message());
                return true;
            }
        });
        main.postDelayed(() -> finish(null, "timeout", false), timeoutMs);
        wv.loadUrl(BASE + "/login/");
    }

    private void onPage() {
        if (stage == Stage.LOGIN_PAGE) {
            stage = Stage.LOGGING_IN;
            try {
                JSONObject t = new JSONObject().put("kind", "login").put("login", login).put("password", password)
                        .put("timeoutMs", Math.min(30000, timeoutMs));
                eval(t);
            } catch (Exception e) {
                finish(null, e.getMessage(), false);
            }
        } else if (stage == Stage.SCHEDULE_PAGE) {
            stage = Stage.TASKS;
            nextTask(null);
        }
    }

    private void onResult(String json) {
        Log.d(TAG, "ответ на этапе " + stage + ": " + (json.length() > 200 ? json.substring(0, 200) + "…" : json));
        JSONObject r;
        try {
            r = new JSONObject(json);
        } catch (Exception e) {
            finish(null, "Непонятный ответ страницы кабинета.", false);
            return;
        }
        if (r.has("error")) {
            finish(r, "timeout".equals(r.optString("error")) ? "timeout" : "Нет связи: " + r.optString("error"), false);
            return;
        }
        if (stage == Stage.LOGGING_IN) {
            if (r.optBoolean("blocked") || r.optBoolean("badFormat")) {
                finish(r, "Кабинет не ответил на вход. Попробуй позже.", false);
            } else if (!r.optBoolean("success")) {
                String reason = r.optString("reason", "");
                finish(r, reason.isEmpty() ? "Не удалось войти. Проверь логин и пароль." : reason, true);
            } else {
                stage = Stage.SCHEDULE_PAGE;
                wv.loadUrl(BASE + "/schedule/");
            }
        } else if (stage == Stage.TASKS) {
            nextTask(r);
        }
    }

    private void nextTask(JSONObject previous) {
        try {
            JSONObject t = steps.next(previous);
            if (t == null) {
                finish(previous, null, false);
            } else {
                t.put("timeoutMs", Math.min(30000, timeoutMs));
                eval(t);
            }
        } catch (Exception e) {
            finish(previous, e.getMessage(), false);
        }
    }

    private void eval(JSONObject task) {
        Log.d(TAG, "выполняю задачу: " + task.optString("kind"));
        String code = logic + "\n;GubkinLogic.run(" + task.toString()
                + ", function (r) { GubkinBridge.done(JSON.stringify(r)); }"
                + ", function (d, t) { GubkinBridge.progress(d, t); });\nnull;";
        wv.evaluateJavascript(code, null);
    }

    private void finish(JSONObject result, String error, boolean authError) {
        Log.d(TAG, "завершение на этапе " + stage + ", ошибка: " + error);
        if (stage == Stage.FINISHED) return;
        stage = Stage.FINISHED;
        main.removeCallbacksAndMessages(null);
        if (wv != null) {
            WebView w = wv;
            wv = null;
            main.post(() -> {
                w.stopLoading();
                w.destroy();
            });
        }
        try {
            done.onDone(result, error, authError);
        } catch (Exception e) {
            // Ошибка в обработчике не должна оставить экран в вечном ожидании
            Log.e(TAG, "ошибка в обработчике результата", e);
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void done(String json) {
            main.post(() -> {
                if (stage != Stage.FINISHED) onResult(json);
            });
        }

        @JavascriptInterface
        public void progress(int doneCount, int total) {
            if (progress == null) return;
            main.post(() -> {
                if (stage != Stage.FINISHED) progress.onProgress(doneCount, total);
            });
        }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Текст ошибки для человека */
    static String humanError(String error) {
        if (error == null) return null;
        if ("timeout".equals(error)) return "Сервер расписания не ответил вовремя. Возможно, он временно ограничил доступ — попробуй позже.";
        return error;
    }
}
