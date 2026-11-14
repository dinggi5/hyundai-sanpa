#!/bin/bash
# Pleos Virtual Console 에 값을 넣는다(에뮬레이터 안 8088 포트).
# 콘솔은 브라우저가 열려 있을 때만 값을 받으므로, 넣는 동안 브라우저 하트비트를 흉내 낸다.
#   scripts/vc.sh gear Drive|Park|Reverse|Neutral
#   scripts/vc.sh passenger true|false        동승석 착석
set -e
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
B=http://localhost:8088
case "$1" in
  gear) path="/vehicle/gear?gear=$2" ;;
  passenger|driver) path="/vehicle/seat?area=$1&kind=occupied&value=$2" ;;
  *) echo "usage: $0 gear <Park|Drive|…> | passenger <true|false>"; exit 1 ;;
esac
beat() { curl -s -m 3 -X POST "$B/browser/session?client=sanpa-vc" >/dev/null; }
for i in 1 2 3; do beat; sleep 1; done
curl -sf -m 5 -X POST "$B$path" || { echo "값을 넣지 못했다: $path" >&2; exit 1; }; echo
for i in 1 2 3; do beat; sleep 1; done
