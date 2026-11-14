// 같이 탔을 때의 「말이 오갔나 / 잦아들었나」(TalkMeter)를 합성한 소리 크기로 확인한다. 실차 소음은 아니다.
// 컴파일된 앱 클래스를 그대로 불러 쓴다(한글 경로에서 Gradle 단위 시험이 본 코드 클래스를 못 봐서 이렇게 한다 — 개발 4).
//   ./gradlew :app:assembleDebug && scripts/talkmeter-check.sh
import studio.scopic.sanpa.TalkMeter;
import java.util.*;

public class TalkMeterCheck {
    static Random rnd = new Random(4);
    static TalkMeter m;
    static int fails = 0;

    static void road(int seconds, double level) { for (int i = 0; i < seconds * 10; i++) m.feed(level * (0.8 + 0.4 * rnd.nextDouble()), 100); }
    static void talk(int seconds, int syllables) {
        for (int s = 0; s < seconds; s++) {
            List<Integer> idx = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)); Collections.shuffle(idx, rnd);
            Set<Integer> on = new HashSet<>(idx.subList(0, syllables));
            for (int i = 0; i < 10; i++) m.feed(on.contains(i) ? 2000 * (0.7 + 0.6 * rnd.nextDouble()) : 300 * (0.8 + 0.4 * rnd.nextDouble()), 100);
        }
    }
    static void check(String name, boolean ok) { System.out.println((ok ? "통과 " : "실패 ") + name + "  (말 " + m.getVoicedMs() + " · 조용 " + m.getQuietMs() + " · 바탕 " + (int) m.getFloor() + ")"); if (!ok) fails++; }

    public static void main(String[] a) {
        m = new TalkMeter(); road(60, 300);
        check("주행 소음만이면 말이 없다", m.getVoicedMs() == 0 && m.getQuietMs() == 58_000);
        m = new TalkMeter(); road(10, 300); talk(15, 5); road(25, 300);
        check("말이 오가다 잦아든다", m.getVoicedMs() >= 14_000 && m.getQuietMs() == 25_000);
        m = new TalkMeter(); road(10, 300); talk(10, 3);
        check("숨 쉬며 끊기는 말(1초에 0.3초)도 센다", m.getVoicedMs() >= 9_000);
        m = new TalkMeter(); for (int k = 0; k < 8; k++) { road(6, 300); m.feed(6000, 100); m.feed(4000, 100); for (int i = 0; i < 8; i++) m.feed(300, 100); }
        check("문소리·기침은 말이 아니다", m.getVoicedMs() == 0);
        m = new TalkMeter(); for (int i = 0; i < 20; i++) m.feed(3000, 100); road(10, 300);
        check("물음의 끝자락(처음 2초)은 세지 않는다", m.getVoicedMs() == 0);
        m = new TalkMeter(); road(40, 300); road(60, 1200);
        check("소음이 커지면 반 분 안에 따라간다", m.getQuietMs() >= 25_000);
        m = new TalkMeter(); road(10, 300); talk(180, 5);
        check("오래 이야기해도 말을 계속 센다", m.getVoicedMs() >= 175_000 && m.getQuietMs() == 0);
        System.out.println(fails == 0 ? "전부 통과" : fails + "개 실패");
        System.exit(fails == 0 ? 0 : 1);
    }
}
