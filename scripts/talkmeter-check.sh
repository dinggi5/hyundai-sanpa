#!/bin/bash
# TalkMeter 를 합성한 소리 크기로 확인한다. 먼저 ./gradlew :app:assembleDebug.
set -e
cd "$(dirname "$0")/.."
J="${JAVA_HOME:?JDK 17 을 JAVA_HOME 에}/bin"
CLS=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
STD=$(find ~/.gradle/caches/modules-2 -name 'kotlin-stdlib-2*.jar' ! -name '*sources*' | sort | tail -1)
OUT=$(mktemp -d)
"$J/javac" -encoding UTF-8 -cp "$CLS:$STD" -d "$OUT" scripts/TalkMeterCheck.java
"$J/java" -cp "$OUT:$CLS:$STD" TalkMeterCheck
