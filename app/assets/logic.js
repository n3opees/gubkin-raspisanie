// Логика работы с сайтом кабинета. Выполняется ВНУТРИ страницы lk.gubkin.ru
// во встроенном браузере приложения: так запросы для сервера ничем не отличаются
// от обычного браузера (прямые запросы из программ сервер расписания тормозит).
// Этот же файл проверяется тестами на компьютере (tools/test-logic.js).
(function (root) {
  // ───── Поиск группы по коду ─────
  // «гм 24-11», «GM-24-11» и «ГМ-24-11» приводим к «ГМ2411». Латиницу меняем
  // на русские буквы по похожему виду (M→М, H→Н) или по звучанию (G→Г, H→Х).
  var LAT_LOOK = { A: "А", B: "В", C: "С", E: "Е", H: "Н", K: "К", M: "М", O: "О", P: "Р", T: "Т", X: "Х", Y: "У" };
  var LAT_SOUND = { A: "А", B: "Б", C: "Ц", D: "Д", E: "Е", F: "Ф", G: "Г", H: "Х", I: "И", J: "Й", K: "К", L: "Л", M: "М", N: "Н", O: "О", P: "П", Q: "К", R: "Р", S: "С", T: "Т", U: "У", V: "В", W: "В", X: "Х", Y: "Ы", Z: "З" };

  function normGroup(s, map) {
    map = map || LAT_LOOK;
    return String(s || "")
      .toUpperCase()
      .replace(/[A-Z]/g, function (ch) { return map[ch] || ch; })
      .replace(/Ё/g, "Е")
      .replace(/[^0-9А-Я]/g, "");
  }

  // Ищет группу; если не нашёл — подбирает похожие коды для подсказки
  function matchGroup(groups, input) {
    var target = normGroup(input);
    var sound = normGroup(input, LAT_SOUND);
    var found = groups.filter(function (g) { return normGroup(g.code) === target; })[0] ||
      groups.filter(function (g) { return normGroup(g.code) === sound; })[0];
    if (found) return { found: { id: found.id, code: found.code } };
    var letters = target.replace(/[0-9]/g, "");
    var year = target.replace(/\D/g, "").slice(0, 2);
    var sameLetters = groups.filter(function (g) { return letters && normGroup(g.code).indexOf(letters) === 0; });
    var sameYear = sameLetters.filter(function (g) { return year && normGroup(g.code).indexOf(letters + year) === 0; });
    var pool = sameYear.length ? sameYear : sameLetters;
    var codes = [];
    pool.forEach(function (g) { if (codes.indexOf(g.code) < 0) codes.push(g.code); });
    codes.sort();
    return { found: null, similar: codes.slice(0, 6) };
  }

  // ───── Разбор недели расписания ─────
  function toMinutes(hhmm) {
    var p = hhmm.split(":");
    return Number(p[0]) * 60 + Number(p[1]);
  }

  // Неделя с сервера → { "ДД-ММ-ГГГГ": { weekType, lessons: [...] } } для всех 7 дней
  function parseWeek(rows) {
    var out = {};
    var wr = rows && rows.week && rows.week.weekRussia;
    if (!wr || !wr.days) return out;
    var weekType = wr.type === "upper" ? "верхняя" : wr.type === "lower" ? "нижняя" : "";
    var org = (rows.organizations || [])[0] || {};
    var TC = org.lessonsTimeChunks || [];
    wr.days.forEach(function (day, i) {
      var wdn = typeof day.weekDayNumber === "number" ? day.weekDayNumber : i;
      var lessons = (org.lessons || [])
        .filter(function (l) { return l.weekDayNumber === wdn && l.timeChunks && l.timeChunks.length; })
        .map(function (l) {
          var start = TC[l.timeChunks[0]].split("-")[0];
          var end = TC[l.timeChunks[l.timeChunks.length - 1]].split("-")[1];
          var teacher = (l.teachers || []).map(function (t) {
            return t.lastName + (t.firstName ? " " + t.firstName[0] + "." : "") + (t.patronymic ? t.patronymic[0] + "." : "");
          }).join(", ");
          return {
            start: start,
            end: end,
            startMin: toMinutes(start),
            endMin: toMinutes(end),
            type: l.type || "",
            name: l.course ? l.course.name : "—",
            room: (l.rooms || []).map(function (r) { return r.number; }).join(", "),
            teacher: teacher,
            subgroup: l.subgroup || 0,
          };
        })
        .sort(function (a, b) { return a.startMin - b.startMin; });
      out[day.date] = { weekType: weekType, lessons: lessons };
    });
    return out;
  }

  // ───── Даты недель семестра ─────
  function parseDate(s) {
    var p = String(s).split("-");
    return new Date(Number(p[2]), Number(p[1]) - 1, Number(p[0]));
  }
  function fmtDate(d) {
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return p(d.getDate()) + "-" + p(d.getMonth() + 1) + "-" + d.getFullYear();
  }
  function mondayOf(d) {
    var m = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    m.setDate(m.getDate() - ((m.getDay() + 6) % 7));
    return m;
  }
  // Понедельники всех недель семестра: сначала текущая и будущие по порядку,
  // потом прошедшие от недавних к старым — если сервер прервёт, важное уже скачано
  function semesterWeeks(begin, end, today) {
    var e = parseDate(end), t = mondayOf(today), fut = [], past = [];
    for (var m = mondayOf(parseDate(begin)); m <= e; m.setDate(m.getDate() + 7)) {
      (m >= t ? fut : past).push(fmtDate(m));
    }
    return fut.concat(past.reverse());
  }

  // ───── Запросы со страницы ─────
  var API = "/schedule/api/api.php?";

  function withTimeout(p, ms) {
    return Promise.race([p, new Promise(function (_, rej) { setTimeout(function () { rej(new Error("timeout")); }, ms); })]);
  }

  function getJSON(url, opts, ms) {
    return withTimeout(fetch(url, Object.assign({ credentials: "include" }, opts || {})), ms).then(function (r) {
      return r.text().then(function (text) {
        if (r.status === 418 || r.status === 429 || !text.trim()) return { blocked: true, status: r.status };
        try { return JSON.parse(text); } catch (e) { return { badFormat: true, status: r.status }; }
      });
    });
  }

  // Выполняет задачу и отдаёт результат в done(объект)
  function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
  }

  // Выполняет задачу и отдаёт результат в done(объект); progress(сделано, всего) — по желанию
  function run(task, done, progress) {
    var ms = task.timeoutMs || 30000;
    var safe = function (p) { return p.catch(function () { return null; }); };
    var job;
    if (task.kind === "login") {
      job = getJSON("/api/api.php?module=auth&method=login", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ login: task.login, password: task.password, rememberMe: 1 }),
      }, ms);
    } else if (task.kind === "groups") {
      // Все группы всех факультетов → ищем нужную по коду
      job = safe(getJSON(API + "act=meta", null, ms))
        .then(function () { return getJSON(API + "act=list&method=getFaculties", null, ms); })
        .then(function (fac) {
          if (!fac || !Array.isArray(fac.rows)) return { blocked: true };
          var groups = [];
          // Перебираем факультеты по одному и останавливаемся, как только группа нашлась:
          // меньше запросов — быстрее и спокойнее для сервера
          return fac.rows.reduce(function (chain, f) {
            return chain.then(function (hit) {
              if (hit) return hit;
              return safe(getJSON(API + "act=list&method=getFacultyGroups&facultyId=" + encodeURIComponent(f.id), null, ms)).then(function (g) {
                ((g && g.rows) || []).forEach(function (row) { groups.push({ id: row.id, code: row.code }); });
                var m = matchGroup(groups, task.groupText);
                return m.found ? m : null;
              });
            });
          }, Promise.resolve(null)).then(function (hit) {
            if (hit) return hit;
            if (!groups.length) return { blocked: true };
            return matchGroup(groups, task.groupText); // не нашли — с подсказками похожих
          });
        });
    } else if (task.kind === "semester") {
      // Весь текущий семестр по неделям, с паузами — чтобы не злить сервер
      var sdays = {}, loaded = [];
      job = safe(getJSON(API + "act=meta", null, ms))
        .then(function () { return getJSON(API + "act=list&method=getStudies", null, ms); })
        .then(function (st) {
          if (!st || st.blocked) return { blocked: true };
          var rows = st.rows || [];
          var study = rows.filter(function (r) { return r.isCurrent; })[0] || rows.filter(function (r) { return r.isActive; })[0];
          if (!study) return { noStudy: true };
          var weeks = semesterWeeks(study.beginDate, study.endDate, task.today ? parseDate(task.today) : new Date());
          var always = weeks.slice(0, 2); // текущую и следующую качаем всегда
          // индекс первой прошедшей недели (в списке они идут после текущей и будущих)
          var fut0 = semesterWeeks(study.beginDate, study.endDate, task.today ? parseDate(task.today) : new Date())
            .filter(function (m) { return parseDate(m) >= mondayOf(task.today ? parseDate(task.today) : new Date()); }).length;
          var skip = task.skip || [];
          var total = weeks.length, doneCount = 0, fetched = 0;
          var info = { name: study.name, beginDate: study.beginDate, endDate: study.endDate };
          return weeks.reduce(function (chain, mon) {
            return chain.then(function (stop) {
              if (stop) return stop;
              if (skip.indexOf(mon) >= 0 && always.indexOf(mon) < 0) {
                doneCount++;
                if (progress) progress(doneCount, total);
                return null;
              }
              return (fetched++ > 0 ? sleep(task.pauseMs == null ? 800 : task.pauseMs) : Promise.resolve())
                .then(function () { return getJSON(API + "act=schedule&groupId=" + task.groupId + "&date=" + mon, null, ms); })
                .then(function (w) {
                  // «Расписание на указанную дату недоступно»: студентам сайт не отдаёт прошедшие
                  // недели (и ещё не опубликованные). Это не ошибка — неделю просто пропускаем,
                  // а раз недоступна прошедшая, то и более ранние спрашивать незачем.
                  if (w && w.state === false && !w.rows && !w.blocked) {
                    doneCount++;
                    if (progress) progress(doneCount, total);
                    if (weeks.indexOf(mon) >= fut0) {
                      doneCount = total;
                      if (progress) progress(doneCount, total);
                      return { pastUnavailable: true };
                    }
                    return null;
                  }
                  if (!w || w.blocked || w.badFormat || !w.rows) {
                    // Почему остановились — для журнала и решения, что делать дальше
                    var why = !w ? "пусто" : w.blocked ? "отказ " + w.status : w.badFormat ? "не JSON" : JSON.stringify(w).slice(0, 160);
                    return { stopped: true, mon: mon, why: why, past: weeks.indexOf(mon) >= fut0 };
                  }
                  var parsed = parseWeek(w.rows);
                  Object.keys(parsed).forEach(function (k) { sdays[k] = parsed[k]; });
                  loaded.push(mon);
                  doneCount++;
                  if (progress) progress(doneCount, total);
                  return null;
                });
            });
          }, Promise.resolve(null)).then(function (stop) {
            var partial = !!(stop && stop.stopped);
            return { stoppedAt: partial ? stop : null, days: sdays, loaded: loaded, total: total, done: doneCount, partial: partial, study: info };
          });
        });
    } else {
      // Как сайт: сначала служебные запросы, потом расписание по неделям
      var days = {};
      job = safe(getJSON(API + "act=meta", null, ms))
        .then(function () { return safe(getJSON(API + "act=list&method=getStudies", null, ms)); })
        .then(function () {
          return task.dates.reduce(function (chain, d) {
            return chain.then(function (stop) {
              if (stop) return stop;
              return getJSON(API + "act=schedule&groupId=" + task.groupId + "&date=" + d, null, ms).then(function (w) {
                if (!w || w.blocked) return { blocked: true };
                if (w.badFormat || !w.rows) return { badFormat: true };
                var parsed = parseWeek(w.rows);
                Object.keys(parsed).forEach(function (k) { days[k] = parsed[k]; });
                return null;
              });
            });
          }, Promise.resolve(null));
        })
        .then(function (stop) { return stop || { days: days }; });
    }
    job.then(done, function (e) { done({ error: String((e && e.message) || e) }); });
  }

  var api = { normGroup: normGroup, matchGroup: matchGroup, parseWeek: parseWeek, semesterWeeks: semesterWeeks, run: run };
  root.GubkinLogic = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
