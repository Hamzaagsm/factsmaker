#!/bin/bash
# Meer Facts Maker — manual APK build (Gradle daemon is broken in this VM sandbox;
# this pipeline uses the same SDK tools AGP would call: aapt2, kotlinc, javac, d8,
# zipalign, apksigner). Output: signed release APK.
set -e
TC=~/workspace/android-toolchain
export JAVA_HOME=$TC/jdk
export PATH=$JAVA_HOME/bin:$PATH
SDK=$TC/sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
PROJ=~/workspace/facts-app/project
B=~/workspace/facts-app/manual-build
rm -rf $B/work; mkdir -p $B/work

cd $B/work
# --- 1) extract AARs ---
mkdir -p sherpa core lifecycle vp interp
unzip -oq ../$PROJ/app/libs/sherpa-onnx-2.0.1.aar -d sherpa 2>/dev/null || unzip -oq $PROJ/app/libs/sherpa-onnx-2.0.1.aar -d sherpa
unzip -oq $B/deps/core.aar -d core
unzip -oq $B/deps/lifecycle.aar -d lifecycle
unzip -oq $B/deps/versionedparcelable.aar -d vp
unzip -oq $B/deps/interpolator.aar -d interp
STDLIB=$TC/gradle/lib/kotlin-stdlib-1.9.22.jar
CP_DEPS="sherpa/classes.jar:core/classes.jar:lifecycle/classes.jar:vp/classes.jar:interp/classes.jar:$B/deps/lifecycle-common.jar:$B/deps/annotation.jar:$B/deps/concurrent-futures.jar:$B/deps/collection.jar:$B/deps/commons-compress.jar:$STDLIB"
echo "[1] AARs extracted"

# --- 2) resources + manifest (placeholder -> literal package) ---
sed -e 's/\${applicationId}/com.hamza.factsmaker/' -e 's/<manifest xmlns:android="http:\/\/schemas.android.com\/apk\/res\/android">/<manifest xmlns:android="http:\/\/schemas.android.com\/apk\/res\/android" package="com.hamza.factsmaker">/' $PROJ/app/src/main/AndroidManifest.xml > AndroidManifest.xml
$BT/aapt2 compile --dir $PROJ/app/src/main/res -o res.zip
$BT/aapt2 link -o base.apk -I $AJAR --manifest AndroidManifest.xml \
  --rename-manifest-package com.hamza.factsmaker \
  --version-code 1 --version-name 1.0 --min-sdk-version 26 --target-sdk-version 34 \
  --java gen -A $PROJ/app/src/main/assets res.zip
echo "[2] aapt2 link OK"

# --- 3) Kotlin compile (NeuralTts.kt) ---
mkdir -p kout
java -cp $TC/gradle/lib/kotlin-compiler-embeddable-1.9.22.jar:$TC/gradle/lib/kotlin-stdlib-1.9.22.jar:$TC/gradle/lib/trove4j-1.0.20200330.jar:$TC/gradle/lib/annotations-24.0.1.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$AJAR:$CP_DEPS" -no-stdlib -no-reflect -jvm-target 1.8 \
  -d kout $PROJ/app/src/main/java/com/hamza/factsmaker/NeuralTts.kt
echo "[3] kotlinc OK"

# --- 4) Java compile ---
mkdir -p jout
RJAVA=$(find gen -name "R.java" | head -1)
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJAR:kout:$CP_DEPS" -d jout \
  $PROJ/app/src/main/java/com/hamza/factsmaker/MainActivity.java \
  $PROJ/app/src/main/java/com/hamza/factsmaker/TranscodeEngine.java \
  $RJAVA
echo "[4] javac OK"

# --- 5) dex (d8 ko jar inputs chahien, directory nahi) ---
mkdir -p dex
jar cf kout.jar -C kout .
jar cf jout.jar -C jout .
$BT/d8 --min-api 26 --lib $AJAR --output dex \
  kout.jar jout.jar \
  sherpa/classes.jar core/classes.jar lifecycle/classes.jar vp/classes.jar interp/classes.jar \
  $B/deps/lifecycle-common.jar $B/deps/annotation.jar $B/deps/concurrent-futures.jar \
  $B/deps/collection.jar $B/deps/commons-compress.jar $STDLIB
echo "[5] d8 OK"

# --- 6) package: dex + native libs (arm64 + v7a only, like abiFilters) ---
cp base.apk app-unsigned.apk
mkdir -p lib/arm64-v8a lib/armeabi-v7a
cp sherpa/jni/arm64-v8a/*.so lib/arm64-v8a/
cp sherpa/jni/armeabi-v7a/*.so lib/armeabi-v7a/
zip -q app-unsigned.apk -j dex/classes.dex
zip -q -r app-unsigned.apk lib
$BT/zipalign -f -p 4 app-unsigned.apk app-aligned.apk
echo "[6] packaged + aligned"

# --- 7) sign with the permanent MEER keystore (password read in-command, never printed) ---
PW=$(cat ~/workspace/meer-secrets/meer-signing-password.txt)
$BT/apksigner sign --ks ~/workspace/meer-secrets/meer-signing.p12 --ks-type PKCS12 \
  --ks-key-alias meer --ks-pass "pass:$PW" --key-pass "pass:$PW" \
  --out $B/MeerFactsMaker-v1.0.apk app-aligned.apk
$BT/apksigner verify --print-certs $B/MeerFactsMaker-v1.0.apk | head -4
ls -la $B/MeerFactsMaker-v1.0.apk
echo "BUILD DONE"
