#!/bin/sh
# Ставит свежий APK на запущенный эмулятор, очищает данные приложения и вводит
# логин, пароль и группу из ../login.txt (файл с данными для проверки).
# Группу вводим латиницей: эмулятор не печатает кириллицу через adb.
# Запуск: sh tools/emu-login.sh [группа]
cd "$(dirname "$0")/.."
ADB="$USERPROFILE/.gubkin-android-tools/sdk/platform-tools/adb.exe"
F=../login.txt
GROUP="${1:-GM-24-11}"
L=$(sed -n 's/^LOGIN=//p' "$F" | tr -d '\r')
P=$(sed -n 's/^PASSWORD=//p' "$F" | tr -d '\r')

"$ADB" install -r gubkin-raspisanie.apk > /dev/null
"$ADB" shell pm clear ru.gubkin.raspisanie > /dev/null
"$ADB" logcat -c
"$ADB" shell am start -W -n ru.gubkin.raspisanie/.MainActivity > /dev/null
"$ADB" shell input tap 540 514; "$ADB" shell input text "$L"
"$ADB" shell input tap 540 662
# adb не печатает «!» через input text — добираем сочетанием Shift+1
BASEP="${P%!}"
"$ADB" shell input text "$BASEP"
[ "$BASEP" != "$P" ] && "$ADB" shell input keycombination 59 8
"$ADB" shell input tap 540 810; "$ADB" shell input text "$GROUP"
"$ADB" shell input keyevent 111
"$ADB" shell input tap 540 978
echo "Кнопка «Войти» нажата"
