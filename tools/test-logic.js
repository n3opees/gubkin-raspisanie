// Проверка app/assets/logic.js в Node с поддельным сервером.
// Запуск: node tools/test-logic.js
const path = require("path");
const assert = require("assert");

const TC = ["8:30-9:15", "9:15-10:00", "10:15-11:00", "11:00-11:45", "12:00-12:45", "12:45-13:30"];
const GROUPS = { 0: [{ id: 9735, code: "ГМ-24-11" }, { id: 9800, code: "ГМ-24-12" }], 4: [{ id: 5001, code: "ХТ-23-01" }] };
let mode = "ok";
let blockAfter = Infinity; // после скольких недель сервер начнёт отказывать
let weekCalls = 0;
let pastMode = true; // прошедшие недели отдаются отказом, как на настоящем сервере
const calls = [];

function weekFor(dateStr) {
  const [d, m, y] = dateStr.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  const mon = new Date(dt);
  mon.setDate(dt.getDate() - ((dt.getDay() + 6) % 7));
  const p = (n) => String(n).padStart(2, "0");
  const days = [...Array(7)].map((_, i) => {
    const x = new Date(mon);
    x.setDate(mon.getDate() + i);
    return { date: p(x.getDate()) + "-" + p(x.getMonth() + 1) + "-" + x.getFullYear(), weekDayNumber: i };
  });
  return {
    week: { weekRussia: { type: "upper", days } },
    organizations: [{
      lessonsTimeChunks: TC,
      lessons: [
        { weekDayNumber: 2, timeChunks: [4, 5], type: "Семинар", subgroup: 0, course: { name: "Экономика" }, rooms: [{ number: "1609" }], teachers: [{ lastName: "Иванова", firstName: "Анна", patronymic: "Петровна" }] },
        { weekDayNumber: 2, timeChunks: [0, 1], type: "Лабораторная работа", subgroup: 2, course: { name: "Физика земли" }, rooms: [{ number: "124" }], teachers: [] },
        { weekDayNumber: 2, timeChunks: [0, 1], type: "Семинар", subgroup: 1, course: { name: "Английский" }, rooms: [], teachers: [] },
      ],
    }],
  };
}

global.fetch = async (url, opts) => {
  calls.push(url);
  const r = (status, body) => ({ status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) });
  if (url.includes("method=login")) return r(200, JSON.parse(opts.body).password === "верный" ? { success: true } : { success: false, reason: "Неверный логин/пароль." });
  if (url.includes("getFaculties")) return r(200, { rows: [{ id: 0 }, { id: 4 }, { id: -5 }] });
  const g = url.match(/getFacultyGroups&facultyId=([-\d]+)/);
  if (g) return r(200, { rows: GROUPS[decodeURIComponent(g[1])] || [] });
  const s = url.match(/act=schedule&groupId=(\d+)&date=([\d-]+)/);
  if (url.includes("getStudies")) return r(200, { rows: [
    { id: 62, name: "2025/2026 весенний", beginDate: "09-02-2026", endDate: "30-06-2026", isActive: false, isCurrent: false },
    { id: 63, name: "2026/2027 осенний", beginDate: "01-09-2026", endDate: "08-02-2027", isActive: true, isCurrent: true },
  ] });
  if (s) {
    weekCalls++;
    // Как настоящий сервер: прошедшие недели (раньше 12.10.2026) студентам недоступны
    const [dd, mm, yy] = s[2].split("-").map(Number);
    if (pastMode && new Date(yy, mm - 1, dd) < new Date(2026, 9, 12)) return r(200, { state: false, reason: "Расписание на указанную дату недоступно" });
    if (weekCalls > blockAfter) return r(418, "");
    if (mode === "blocked") return r(418, "");
    if (mode === "hang") return new Promise(() => {});
    return r(200, { rows: weekFor(s[2]) });
  }
  return r(200, { rows: [] });
};

const L = require(path.join(__dirname, "..", "app", "assets", "logic.js"));
const run = (task, progress) => new Promise((res) => L.run(task, res, progress));

(async () => {
  // Коды групп
  const all = [...GROUPS[0], ...GROUPS[4]];
  for (const [inp, want] of [["ГМ-24-11", "ГМ-24-11"], ["гм 24 11", "ГМ-24-11"], ["GM-24-11", "ГМ-24-11"], ["XT-23-01", "ХТ-23-01"], ["ГМ-24-13", null]]) {
    const m = L.matchGroup(all, inp);
    assert.strictEqual(m.found ? m.found.code : null, want, inp);
  }
  assert.deepStrictEqual(L.matchGroup(all, "ГМ-24-13").similar, ["ГМ-24-11", "ГМ-24-12"]);
  console.log("коды групп: ок");

  // Вход
  assert.strictEqual((await run({ kind: "login", login: "1", password: "верный" })).success, true);
  assert.strictEqual((await run({ kind: "login", login: "1", password: "x" })).reason, "Неверный логин/пароль.");
  console.log("вход: ок");

  // Поиск группы со страницы
  calls.length = 0;
  const gr = await run({ kind: "groups", groupText: "гм-24-12" });
  assert.deepStrictEqual(gr.found, { id: 9800, code: "ГМ-24-12" });
  assert.strictEqual(calls.filter((u) => u.includes("getFacultyGroups")).length, 1, "нашли на первом факультете — дальше не спрашиваем");
  const miss = await run({ kind: "groups", groupText: "ГМ-24-13" });
  assert.deepStrictEqual(miss.similar, ["ГМ-24-11", "ГМ-24-12"], "не нашли — подсказки похожих");
  console.log("поиск группы: ок");

  // Расписание двух недель
  calls.length = 0;
  const sc = await run({ kind: "schedule", groupId: 9735, dates: ["14-10-2026", "21-10-2026"] });
  const keys = Object.keys(sc.days);
  assert.strictEqual(keys.length, 14, "14 дней");
  const wed = sc.days["14-10-2026"];
  assert.strictEqual(wed.weekType, "верхняя");
  assert.deepStrictEqual(wed.lessons.map((l) => l.start + " " + l.name + " п/г" + l.subgroup), ["8:30 Физика земли п/г2", "8:30 Английский п/г1", "12:00 Экономика п/г0"]);
  assert.strictEqual(wed.lessons[2].teacher, "Иванова А.П.");
  assert.strictEqual(wed.lessons[2].endMin, 13 * 60 + 30);
  assert.strictEqual(sc.days["15-10-2026"].lessons.length, 0);
  assert.ok(calls[0].includes("act=meta") && calls[1].includes("getStudies"), "порядок как у сайта");
  console.log("расписание: ок (" + keys[0] + " … " + keys[13] + ")");

  mode = "blocked";
  assert.deepStrictEqual(await run({ kind: "schedule", groupId: 9735, dates: ["14-10-2026"] }), { blocked: true });
  mode = "hang";
  assert.deepStrictEqual(await run({ kind: "schedule", groupId: 9735, dates: ["14-10-2026"], timeoutMs: 200 }), { error: "timeout" });
  console.log("блокировка и зависание сервера: ок");
  mode = "ok";

  // Недели семестра: сначала текущая и будущие, потом прошедшие
  const weeks = L.semesterWeeks("01-09-2026", "08-02-2027", new Date(2026, 9, 14));
  assert.strictEqual(weeks[0], "12-10-2026", "первой идёт текущая неделя");
  assert.strictEqual(weeks[1], "19-10-2026");
  assert.ok(weeks.includes("31-08-2026") && weeks.includes("08-02-2027"), "границы семестра");
  assert.strictEqual(weeks[weeks.length - 1], "31-08-2026", "последней — самая старая");
  assert.strictEqual(new Set(weeks).size, weeks.length, "без повторов");
  console.log("недели семестра: ок (" + weeks.length + " недель)");

  // Весь семестр
  weekCalls = 0;
  const prog = [];
  const full = await run({ kind: "semester", groupId: 9735, today: "14-10-2026", pauseMs: 0 }, (d, t) => prog.push(d + "/" + t));
  assert.strictEqual(full.total, weeks.length);
  const future = weeks.filter((m) => { const [d, mo, y] = m.split("-").map(Number); return new Date(y, mo - 1, d) >= new Date(2026, 9, 12); });
  assert.strictEqual(future.length, 18, "18 недель от текущей до конца семестра");
  assert.strictEqual(full.loaded.length, future.length, "скачаны все доступные недели");
  assert.strictEqual(weekCalls, future.length + 1, "на прошедшие недели ушёл всего один запрос");
  assert.strictEqual(full.partial, false);
  assert.strictEqual(Object.keys(full.days).length, future.length * 7);
  assert.strictEqual(full.stoppedAt, null, "недоступные прошедшие недели — не ошибка");
  assert.strictEqual(full.study.name, "2026/2027 осенний");
  assert.strictEqual(prog[prog.length - 1], weeks.length + "/" + weeks.length);
  console.log("весь семестр: ок (" + Object.keys(full.days).length + " дней, прогресс " + prog[0] + " … " + prog[prog.length - 1] + ")");

  // Пропуск недавно скачанных недель (текущая и следующая — всегда заново)
  weekCalls = 0;
  const sk = await run({ kind: "semester", groupId: 9735, today: "14-10-2026", pauseMs: 0, skip: weeks.slice(0, 10) });
  assert.strictEqual(weekCalls, future.length - 8 + 1, "пропущено 8 недель, текущая и следующая перекачаны, +1 на прошедшую");
  assert.strictEqual(sk.done, weeks.length);
  console.log("пропуск свежих недель: ок (запросов " + weekCalls + " вместо " + weeks.length + ")");

  // Сервер начал отказывать на середине — сохраняем то, что успели
  weekCalls = 0;
  blockAfter = 5;
  const part = await run({ kind: "semester", groupId: 9735, today: "14-10-2026", pauseMs: 0 });
  blockAfter = Infinity;
  assert.strictEqual(part.partial, true);
  assert.deepStrictEqual(part.loaded, weeks.slice(0, 5));
  assert.strictEqual(Object.keys(part.days).length, 35);
  console.log("обрыв на середине: ок (сохранено " + part.loaded.length + " недель из " + part.total + ")");

  // Пауза между запросами действительно есть
  weekCalls = 0;
  const t0 = Date.now();
  await run({ kind: "semester", groupId: 9735, today: "14-10-2026", pauseMs: 30, skip: weeks.slice(2) });
  assert.ok(Date.now() - t0 >= 30, "пауза между неделями");
  console.log("пауза между запросами: ок");
  console.log("\nВСЕ ПРОВЕРКИ ПРОШЛИ");
})().catch((e) => { console.error("ПРОВАЛ:", e.message); process.exit(1); });
