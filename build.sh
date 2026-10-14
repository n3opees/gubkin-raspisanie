#!/bin/sh
# Собирает gubkin-raspisanie.apk напрямую инструментами Android SDK (без Android Studio и Gradle).
# Нужны: Java 17 и Android SDK (platform 34, build-tools 34) в папке TOOLS.
# Запуск из Git Bash:  sh build.sh
set -e
cd "$(dirname "$0")"

TOOLS="${GUBKIN_TOOLS:-$USERPROFILE/.gubkin-android-tools}"
export JAVA_HOME="$TOOLS/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$TOOLS/sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
VERSION_CODE=6
VERSION_NAME=1.5

rm -rf build
mkdir -p build/res build/gen build/classes build/dex

echo "1/6 Разметка виджета"
node tools/gen-widget-layout.js > /dev/null

echo "2/6 Ресурсы"
"$BT/aapt2" compile --dir app/res -o build/res/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJAR" \
  --manifest app/AndroidManifest.xml -A app/assets --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  ${DEBUG:+--debug-mode} build/res/res.zip

echo "3/6 Компиляция Java"
find app/src build/gen -name "*.java" > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR;$BT/core-lambda-stubs.jar" -Xlint:-options \
  -d build/classes @build/sources.txt

echo "4/6 Перевод в формат Android (dex)"
"$BT/d8.bat" --release --min-api 26 --lib "$AJAR" --output build/dex $(find build/classes -name "*.class")

echo "5/6 Упаковка"
cp build/base.apk build/unsigned.apk
(cd build/dex && "$BT/aapt" add ../unsigned.apk classes.dex > /dev/null)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

echo "6/6 Подпись"
KS=signing/gubkin.keystore
if [ ! -f "$KS" ]; then
  mkdir -p signing
  keytool -genkeypair -keystore "$KS" -alias gubkin -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass gubkin123 -keypass gubkin123 -dname "CN=Gubkin Raspisanie, O=Student, C=RU" > /dev/null 2>&1
  echo "   создан ключ подписи $KS — не удаляй его, иначе обновления не встанут поверх старой версии"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:gubkin123 --key-pass pass:gubkin123 \
  --v4-signing-enabled false --out gubkin-raspisanie.apk build/aligned.apk
"$BT/apksigner.bat" verify gubkin-raspisanie.apk

echo "Готово: $(pwd)/gubkin-raspisanie.apk ($(du -k gubkin-raspisanie.apk | cut -f1) КБ)"
