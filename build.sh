#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$SCRIPT_DIR/bin"
SRC_DIR="$SCRIPT_DIR/src"
RES_DIR="$SCRIPT_DIR/res"
MANIFEST="$SCRIPT_DIR/AndroidManifest.xml"
OUTPUT_APK="$SCRIPT_DIR/art-auto-optimizer-shizuku.apk"

SDK_JAR="$SCRIPT_DIR/libs/android_sdk/android.jar"
R8_JAR="$SCRIPT_DIR/libs/android_sdk/r8.jar"
SHIZUKU_DIR="$SCRIPT_DIR/libs/shizuku_libs"

# Descarga automática de dependencias si no existen
if [ ! -f "$SDK_JAR" ] || [ ! -f "$R8_JAR" ] || [ ! -d "$SHIZUKU_DIR/api_aar" ]; then
    echo "[0/5] Descargando dependencias de construcción (SDK 33, R8, Shizuku)..."
    mkdir -p "$SCRIPT_DIR/libs/android_sdk" "$SHIZUKU_DIR"
    
    if [ ! -f "$SDK_JAR" ]; then
        curl -s -L https://dl.google.com/android/repository/platform-33_r01.zip -o /tmp/platform-33.zip
        unzip -q -j /tmp/platform-33.zip "android-13/android.jar" -d "$SCRIPT_DIR/libs/android_sdk"
        rm -f /tmp/platform-33.zip
    fi
    if [ ! -f "$R8_JAR" ]; then
        curl -s -L https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar -o "$R8_JAR"
    fi
    if [ ! -d "$SHIZUKU_DIR/api_aar" ]; then
        cd "$SHIZUKU_DIR"
        curl -sO https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar
        curl -sO https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar
        curl -sO https://repo1.maven.org/maven2/dev/rikka/shizuku/shared/13.1.5/shared-13.1.5.aar
        curl -sO https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar
        unzip -q -o api-13.1.5.aar -d api_aar
        unzip -q -o provider-13.1.5.aar -d provider_aar
        unzip -q -o shared-13.1.5.aar -d shared_aar
        unzip -q -o aidl-13.1.5.aar -d aidl_aar
        rm -f *.aar
        cd "$SCRIPT_DIR"
    fi
fi

# Generar keystore de depuración si no existe
if [ ! -f ~/.android/debug.keystore ]; then
    mkdir -p ~/.android
    keytool -genkey -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

mkdir -p "$BIN_DIR/classes"

echo "[1/5] Generando R.java con aapt..."
aapt package -f -m -J "$SRC_DIR" \
    -M "$MANIFEST" \
    -S "$RES_DIR" \
    -I "$SDK_JAR"

echo "[2/5] Compilando fuentes Java con javac..."
javac --release 8 \
    -cp "$SDK_JAR:$SHIZUKU_DIR/api_aar/classes.jar:$SHIZUKU_DIR/provider_aar/classes.jar:$SHIZUKU_DIR/shared_aar/classes.jar:$SHIZUKU_DIR/aidl_aar/classes.jar" \
    -d "$BIN_DIR/classes" \
    "$SRC_DIR"/dev/art/optimizer/*.java

echo "[3/5] Generando dex con D8..."
java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --output "$BIN_DIR" \
    "$BIN_DIR"/classes/dev/art/optimizer/*.class \
    "$SHIZUKU_DIR"/api_aar/classes.jar \
    "$SHIZUKU_DIR"/provider_aar/classes.jar \
    "$SHIZUKU_DIR"/shared_aar/classes.jar \
    "$SHIZUKU_DIR"/aidl_aar/classes.jar \
    --lib "$SDK_JAR"

echo "[4/5] Empaquetando recursos y dex..."
cd "$BIN_DIR"
aapt package -f -M "$MANIFEST" -S "$RES_DIR" -I "$SDK_JAR" -F app.apk
aapt add app.apk classes.dex

echo "[5/5] Zipalign y firmado..."
zipalign -f -p 4 app.apk "$OUTPUT_APK"
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android "$OUTPUT_APK"

if [ -d "/home/nuc/Downloads" ]; then
    cp "$OUTPUT_APK" "/home/nuc/Downloads/art-auto-optimizer-shizuku.apk"
fi

echo "¡APK compilado y firmado con éxito en $OUTPUT_APK!"
