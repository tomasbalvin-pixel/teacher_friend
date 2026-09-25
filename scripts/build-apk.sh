#!/usr/bin/env bash
# Sestaví podepsané APK bez Gradle – jen z nástrojů Android SDK (aapt2, d8/dx, zipalign, apksigner) a JDK.
# Použití: [RES_JAR=/cesta/android-29.jar] ANDROID_JAR=/cesta/android.jar BUILD_TOOLS=/cesta/build-tools/XX scripts/build-apk.sh [keystore]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
ANDROID_JAR="${ANDROID_JAR:?nastavte ANDROID_JAR na platforms/android-35/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:?nastavte BUILD_TOOLS na adresář build-tools}"
# Starší aapt2 neumí načíst resources.arsc novějších platforem – pak lze zdroje linkovat proti starší.
RES_JAR="${RES_JAR:-$ANDROID_JAR}"
KEYSTORE="${1:-$OUT/debug.keystore}"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.0.1}"

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT"/*.apk "$OUT/res.zip"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# Manifest nemá atribut package (v Gradle ho určuje namespace) – doplníme ho do kopie.
APP_ID="cz.teacherfriend.redpen"
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "» zdroje (aapt2)"
"$BUILD_TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -I "$RES_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    -A "$SRC/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/app.unaligned.apk" "$OUT/res.zip"

echo "» java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# java.* z JDK (API úrovně Java 8), android.* a org.json z android.jar.
javac -nowarn -Xlint:-options -encoding UTF-8 --release 8 \
    -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "» dex"
# Starý dx lambdy neumí převést: nechal by v DEX invoke-custom/LambdaMetafactory, na kterém
# aplikace na telefonu okamžitě spadne. Bez d8 proto nesmí kód obsahovat invokedynamic.
if [ ! -x "$BUILD_TOOLS/d8" ]; then
    if find "$OUT/classes" -name '*.class' -print0 | xargs -0 javap -c -p 2>/dev/null | grep -q invokedynamic; then
        echo "✗ kód obsahuje lambdy/invokedynamic a d8 není k dispozici – dx by vytvořil nefunkční APK" >&2
        exit 1
    fi
fi
if [ -x "$BUILD_TOOLS/d8" ]; then
    "$BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
elif command -v dalvik-exchange >/dev/null; then
    dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
else
    "$BUILD_TOOLS/dx" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
fi
if command -v dexdump >/dev/null && dexdump -d "$OUT/dex/classes.dex" 2>/dev/null | grep -q invoke-custom; then
    echo "✗ DEX obsahuje invoke-custom – na Androidu by aplikace spadla" >&2
    exit 1
fi
(cd "$OUT/dex" && zip -q -j "$OUT/app.unaligned.apk" classes.dex)

echo "» zarovnání a podpis"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias redpen \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Cervena tuzka" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias redpen --min-sdk-version 26 --out "$OUT/cervena-tuzka.apk" "$OUT/app.aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$OUT/cervena-tuzka.apk"
rm -f "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"
echo "✓ $OUT/cervena-tuzka.apk ($(du -h "$OUT/cervena-tuzka.apk" | cut -f1))"
