#!/bin/sh
# Запускает DayPickerTest на компьютере (обычной Java, без телефона)
cd "$(dirname "$0")/.."
J="${GUBKIN_TOOLS:-$USERPROFILE/.gubkin-android-tools}/jdk17/bin"
OUT=build/daypicker-test
rm -rf "$OUT" && mkdir -p "$OUT"
"$J/javac" -encoding UTF-8 -d "$OUT" app/src/ru/gubkin/raspisanie/DayPicker.java tools/DayPickerTest.java
"$J/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" ru.gubkin.raspisanie.DayPickerTest
