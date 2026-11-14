# 산파

차가 묻습니다. 같이 탔으면 차 안에서, 혼자면 떨어진 가족과 이야기가 시작됩니다.

- **같이 탔을 때.** 옆자리에 누가 있으면, 달리는 중에 차가 물음 하나를 소리 내어 던지고 빠집니다. 차는 말이 오가는지 소리 크기만 듣습니다(담지도 보내지도 않습니다). 이야기가 잦아들면 같은 주제의 다음 물음을 냅니다 — 묻기 → 흔들기 → 넓히기 → 돌려주기, 주제 하나에 넷까지. 질문은 주제 30개 × 넷이고, 세워 둔 동안 주제와 단계를 고르거나 차에 맡깁니다.
- **혼자일 때.** 주차하면 차가 물음 하나를 묻고, 차에 대고 한 한마디를 연결한 가족의 휴대전화로 보냅니다. 가족이 남긴 답은 다음에 차를 켤 때 차에서 나옵니다.

어느 자리인지는 동승석 착석으로 차가 고릅니다.

Pleos Connect(Android Automotive OS)용 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 차가 고르는 때

| 때 | 차가 하는 일 | 쓰는 것 |
|---|---|---|
| 같이 출발하고 20초쯤 | 고른 칸에서 주제 하나를 뽑아 첫 물음을 읽습니다. 지난번에 이야기가 이어진 주제면 멈춘 다음 물음부터 | Pleos Vehicle SDK(기어·동승석 착석), Pleos TextToSpeech SDK |
| 같이 달리는 동안 | 말이 오가는지 소리 크기만 듣고, 잦아들면 다음 물음을 냅니다. 물은 뒤 내내 조용하면 접습니다 | 차 마이크(`AudioRecord`, 크기만 재고 버림) |
| 같이 탄 채 주차하거나 옆자리가 빌 때 | 그만둡니다 | Pleos Vehicle SDK |
| 혼자 주차했을 때(기어가 P 로 들어올 때) | 물음 하나를 읽고, 한마디를 담아 가족에게 보냅니다 | Pleos Vehicle SDK(기어), Pleos TextToSpeech SDK, 차 마이크 |
| 혼자 차를 켤 때 | 가족의 답이 새로 와 있으면 틉니다 | 서버 조회, `MediaPlayer`(오디오 포커스) |
| 음악이 나올 때 | 끼어들지 않고 기다립니다. 같이 이야기하던 중에 누가 음악을 켜면 접습니다 | `AudioManager` |
| 달리는 동안 | 화면에는 물음 글자와 「다른 질문」 버튼 하나만 둡니다(옆자리 분이 누릅니다). 고르기·가족 연결은 세워 둔 때만 | AAOS `CarUxRestrictionsManager`, 기어 |

시연에서 한 바퀴가 보이도록 때를 짧게 잡았습니다(잦아듦 20초, 앞 물음에서 45초). 실제로는 더 길게 둘 값입니다.

혼자일 때 담기는 손을 쓰지 않아도 끝납니다. 말한 뒤 4초쯤 조용하면 다 말한 것으로 보고 보냅니다. 처음부터 말이 없으면 보내지 않고 넘어갑니다.

## 구성

- `app/` — 차량용 앱(Kotlin, 화면 하나).
  - `MainActivity` 무엇을 할 때인지 정함 · `CarGate` 기어·착석·주행 제한·음악 · `Voice` 묻기·담기·틀기·듣기만(`Hearer`·`TalkMeter`) · `Server` 서버 왕복 · `Screen` 화면 · `Bank` 질문 뽑기·이어 묻기·차가 알아서 · `QuestionBank` 같이 탔을 때 질문 120(`질문_초벌.md` 에서 만든다) · `Questions` 혼자일 때 물음
- `server/` — 서버(Vercel 함수 둘 + 비공개 Blob)와 가족용 웹(`server/public/index.html`).
- `scripts/vc.sh` — 에뮬레이터의 Virtual Console 에 기어·착석 값을 넣는 스크립트.
- `scripts/gen-questions.py` — `질문_초벌.md` 에서 `QuestionBank.kt` 를 만듭니다. `scripts/bank-check.sh` — 질문 뽑기를 확인합니다.
- `scripts/talkmeter-check.sh` — 「말이 오갔나 / 잦아들었나」 판정을 합성한 소리 크기로 확인합니다(디버그 빌드 뒤).
- `apk/스코픽스튜디오_산파_release.apk` — 실행 가능한 release APK.

## 테스트 환경

- Pleos Connect Emulator **v2.1.0** (시스템 이미지 「Pleos Connect v2.1.0 APIs arm64」, API 34)
- 화면 규격 **2560×1440, 160dpi**. 앱은 그 가운데 오른쪽 창(1682×1210)에 뜹니다. 치수는 화면 짧은 변에 대한 비율로 잡았습니다.
- Pleos SDK: `TextToSpeech 3.3.1`(ON_DEVICE, 한국어) · `Vehicle 2.3.2`
- 빌드: JDK 17 · Gradle 9.3.1 · AGP 9.0.1 · compileSdk 36 · minSdk 28 · targetSdk 34

## 실행 방법

### APK 로

```bash
adb install apk/스코픽스튜디오_산파_release.apk
adb shell pm grant --user 10 studio.scopic.sanpa android.permission.RECORD_AUDIO
adb shell am start --user 10 -n studio.scopic.sanpa/.MainActivity
```

마이크 권한은 앱을 처음 켤 때 화면에서 허락해도 됩니다. 에뮬레이터는 `-allow-host-audio` 로 띄우고, 부팅 뒤 `adb emu avd hostmicon` 을 한 번 부릅니다(호스트 마이크가 차 마이크가 됩니다).

### 같이 탔을 때 돌려 보기

1. 동승석을 **Occupied** 로 둡니다(`scripts/vc.sh passenger true`).
2. 주차한 채로 **질문 고르기**를 누릅니다. 주제(여러 개)와 단계를 고르거나 **차가 알아서**를 고릅니다. 고르지 않으면 차가 알아서 고릅니다.
3. 기어를 D 로 올립니다(`scripts/vc.sh gear Drive`). 20초쯤 지나면 차가 고른 칸 안에서 주제 하나를 뽑아 첫 질문을 읽습니다.
4. 이야기를 하다가 20초쯤 조용히 두면 그 주제의 다음 질문이 나옵니다(주제마다 넷까지). 물은 뒤 아무 말이 없으면 45초 뒤 접고 「오늘 물음은 여기까지예요」가 뜹니다.
5. 달리는 중에도 **다른 질문**은 누를 수 있습니다(옆자리 분이). 다른 주제의 첫 질문을 바로 읽습니다.
6. 기어를 P 로 내리거나 동승석을 비우면 그만둡니다. 이야기가 오래 이어진 주제는 다음에 같이 탈 때 멈춘 질문 다음부터 묻습니다.

질문 문장은 `질문_초벌.md` 에 있습니다. 고친 뒤 `python3 scripts/gen-questions.py` 를 돌리면 앱의 `QuestionBank.kt` 가 다시 만들어집니다.

### 혼자일 때 돌려 보기

1. 앱을 켜고 오른쪽 위 **가족 연결**을 누릅니다. 나온 그림(QR)을 휴대전화 카메라로 비추면 가족용 웹이 열립니다. 짝 코드는 차가 처음 켤 때 만듭니다.
2. Virtual Console(또는 `scripts/vc.sh passenger false`)에서 동승석을 **Empty** 로 둡니다. Occupied 로 두면 같이 탔을 때 자리가 됩니다.
3. 기어를 D 로 올렸다가 P 로 내립니다(`scripts/vc.sh gear Drive` → `scripts/vc.sh gear Park`). 차가 물음을 읽고 듣기 시작합니다. 한마디 하고 잠깐 기다리면 보냅니다. 주차한 채로 **물음 듣기**를 눌러도 됩니다.
4. 휴대전화에서 차에서 온 한마디를 듣고 답을 녹음해 보냅니다.
5. 앱을 껐다 켭니다(차를 다시 켠 것). 가족의 답이 나옵니다.

`scripts/vc.sh` 는 에뮬레이터 안 Virtual Console(8088 포트)에 값을 넣습니다. 콘솔은 브라우저가 열려 있을 때만 값을 받아서, 스크립트가 브라우저 신호를 흉내 냅니다.

### 소스에서 빌드

저장소 루트에 `local.properties` 를 만들고 서버 주소를 적습니다. 주소와 서명 열쇠는 저장소에 두지 않습니다.

```properties
sanpa.server=https://<서버 주소>
# 디버그 빌드에서 짝 코드를 고정하려면(영소문자·숫자 6~32자)
sanpa.pair=<짝 코드>
# release 서명(없으면 서명 없이 나옵니다)
sanpa.store=<keystore 경로>
sanpa.storePass=<암호>
sanpa.keyAlias=sanpa
```

```bash
export JAVA_HOME=<JDK 17> ANDROID_HOME=<Android SDK>
./gradlew :app:assembleDebug      # 또는 :app:assembleRelease
```

서버는 `server/` 를 Vercel 프로젝트로 올리고 비공개 Blob 저장소를 하나 붙이면 됩니다.

## 알아 둘 것

- 에뮬레이터 v2.1.0 과 Vehicle SDK 2.3.2 에서는 동승석 착석 값이 뒤집혀 옵니다(콘솔에서 Occupied 로 두면 SDK 는 `false`). 앱은 이를 뒤집어 읽습니다(`CarGate.SEAT_VALUE_INVERTED`).
- 시동 상태를 주는 공개 API 가 없어 「차를 켤 때」는 앱이 화면에 뜰 때로 대신했습니다.
- 짝 코드 하나가 읽기·쓰기 열쇠입니다. 가족별 권한 나누기와 요청량 제한은 아직 없습니다.

## 오픈소스

- [ZXing](https://github.com/zxing/zxing) core 3.5.3 — Apache License 2.0 (가족 연결 QR)
- [@vercel/blob](https://github.com/vercel/storage) — Apache License 2.0 (서버)
