import studio.scopic.sanpa.*;
import java.util.*;
import kotlin.random.Random;
import kotlin.random.RandomKt;

/** 질문 은행 뽑기 확인. scripts/bank-check.sh 가 돌린다. */
public class BankCheck {
    static int fails = 0;
    static void ok(boolean c, String what) { System.out.println((c ? "✅ " : "❌ ") + what); if (!c) fails++; }
    static Bank B = Bank.INSTANCE;
    static List<Topic> T = QuestionBank.INSTANCE.getTopics();
    static Topic t(String id) { for (Topic x : T) if (x.getId().equals(id)) return x; throw new RuntimeException(id); }
    static Set<Integer> s(Integer... a) { return new HashSet<>(Arrays.asList(a)); }
    static List<String> ids(List<Topic> p) { List<String> r = new ArrayList<>(); for (Topic x : p) r.add(x.getId()); return r; }

    public static void main(String[] a) {
        Random rnd = RandomKt.Random(42);
        ok(T.size() == 30, "주제 30개");
        for (Topic x : T) if (x.getQuestions().size() != 4) ok(false, x.getId() + " 질문 넷");

        Memory m = new Memory(0, new HashMap<>(), 0, null);
        List<Topic> p = B.pool(T, Choice.Companion.getDEFAULT(), m, null);
        boolean noHeavy = true; for (Topic x : p) if (x.getLevel() == Level.HARD || x.getCategory() == 5) noHeavy = false;
        ok(p.size() == 8 && noHeavy, "고르지 않으면 쉬운 질문 8개, 무거운 것 없음 " + ids(p));

        p = B.pool(T, new Choice(s(5), Level.EASY, false), m, null);
        ok(ids(p).equals(Arrays.asList("s-home", "s-memory")), "삶과 죽음 + 쉬운 질문(0개) → 적당한 질문으로 넓힘, 어려운 건 안 감 " + ids(p));
        p = B.pool(T, new Choice(s(1), Level.HARD, false), m, null);
        ok(p.size() == 6, "관계 + 어려운 질문(1개) → 셋 미만이라 적당한 질문까지 " + ids(p));
        p = B.pool(T, new Choice(s(5), Level.HARD, false), m, null);
        ok(p.size() == 4, "삶과 죽음 + 어려운 질문 = 4개 그대로 " + ids(p));

        // 모든 고르기 조합: 빈손 없음, 고른 범주 밖 없음, 어려운 질문은 고른 사람에게만
        boolean all = true;
        for (int mask = 1; mask < 64; mask++) for (Level lv : Level.values()) {
            Set<Integer> cats = new HashSet<>(); for (int i = 0; i < 6; i++) if ((mask & (1 << i)) != 0) cats.add(i);
            List<Topic> q = B.pool(T, new Choice(cats, lv, false), m, null);
            if (q.isEmpty()) all = false;
            for (Topic x : q) if (!cats.contains(x.getCategory()) || (lv != Level.HARD && x.getLevel() == Level.HARD)) all = false;
        }
        ok(all, "63 범주 조합 × 난이도 3: 빈손 없음 · 범주 밖 없음 · 어려운 질문은 고른 사람에게만");

        // 이어 묻기
        Choice c = new Choice(s(0), Level.EASY, false);
        Memory mem = new Memory(0, new HashMap<>(), 0, null);
        B.close(mem, t("d-road"), 1, Bank.HOT_MS, false);
        kotlin.Pair<Topic, Integer> f = B.first(T, c, mem, rnd);
        ok(f.getFirst().getId().equals("d-road") && f.getSecond() == 2, "말이 많이 오간 주제는 다음에 탈 때 3번째 질문부터");
        B.close(mem, t("d-road"), 2, Bank.HOT_MS - 1, false);
        ok(mem.getMarks().get("d-road").getNext() == 0, "이어 물었는데 조용했으면 접는다");
        B.close(mem, t("d-road"), 1, Bank.HOT_MS, false);
        f = B.first(T, new Choice(s(4), Level.EASY, false), mem, rnd);
        ok(!f.getFirst().getId().equals("d-road") && f.getSecond() == 0, "불붙은 주제라도 지금 고른 범주 밖이면 안 잇는다");

        // 넷을 다 물으면 쉬게
        mem = new Memory(0, new HashMap<>(), 0, null);
        B.first(T, c, mem, rnd); // ride 1
        B.close(mem, t("d-rest"), 3, 0, false);
        boolean rested = true;
        for (int r = 0; r < Bank.COOL_RIDES - 1; r++) { mem.setRide(mem.getRide() + 1); if (ids(B.pool(T, c, mem, null)).contains("d-rest")) rested = false; }
        mem.setRide(mem.getRide() + 1);
        ok(rested && ids(B.pool(T, c, mem, null)).contains("d-rest"), "넷을 다 물은 주제는 " + Bank.COOL_RIDES + "번 탈 동안 쉬고 다시 나온다");

        // 다른 질문
        mem = new Memory(0, new HashMap<>(), 0, null);
        boolean diff = true;
        for (int i = 0; i < 200; i++) if (B.other(T, c, mem, "d-rest", rnd).getId().equals("d-rest")) diff = false;
        ok(diff, "다른 질문은 지금 주제를 다시 안 낸다(200번)");
        Choice one = new Choice(s(2), Level.HARD, false); // 사랑 + 어려운 질문: 0 → 적당한 질문 2개
        ok(B.other(T, one, mem, "l-together", rnd).getId().equals("l-say"), "칸에 둘뿐이면 다른 하나");
        for (int i = 0; i < 5; i++) B.close(mem, t("d-time"), 0, 0, true);
        int hits = 0; for (int i = 0; i < 2000; i++) if (B.other(T, c, mem, null, rnd).getId().equals("d-time")) hits++;
        ok(hits < 2000 / 5 / 3, "다섯 번 넘긴 주제는 덜 나온다 (2000번에 " + hits + ")");

        // 쉬운 질문만 골랐고 관계의 쉬운·적당한 질문이 다 쉬는 중이어도 어려운 질문(r-sorry)은 안 나온다
        mem = new Memory(1, new HashMap<>(), 0, null);
        for (Topic x : T) if (x.getCategory() == 1 && x.getLevel() != Level.HARD) B.close(mem, x, 3, 0, false);
        boolean noHard = true;
        for (int i = 0; i < 50; i++) for (String ex : new String[]{null, "r-nag"}) for (Topic x : B.pool(T, new Choice(s(1), Level.EASY, false), mem, ex)) if (x.getLevel() == Level.HARD) noHard = false;
        ok(noHard, "쉬는 주제를 다시 꺼낼지언정 고르지 않은 어려운 질문은 안 낸다");

        // 쉬는 주제뿐이어도 빈손은 없다
        mem = new Memory(1, new HashMap<>(), 0, null);
        for (Topic x : T) B.close(mem, x, 3, 0, false);
        ok(!B.pool(T, c, mem, null).isEmpty(), "모두 쉬는 중이어도 뽑는다");

        // 저장
        mem = new Memory(7, new HashMap<>(), 0, null);
        B.close(mem, t("w-job"), 1, 30000, false);
        B.close(mem, t("n-ai"), 0, 0, true);
        Memory back = B.decode(B.encode(mem));
        ok(back.getRide() == 7 && back.getMarks().equals(mem.getMarks()), "기억 저장·읽기 " + B.encode(mem));
        ok(B.decode("x|a,b;;w-job,9,1,2").getMarks().isEmpty() && B.decode(null).getRide() == 0, "깨진 기억은 버린다");
        ok(B.decode("3|w-job,9,1,2,3").getMarks().get("w-job").getNext() == 3, "다음 질문 번호는 0~3 안으로");
        Choice cc = new Choice(s(5, 0), Level.HARD, false);
        ok(cc.equals(B.decodeChoice(B.encodeChoice(cc))) && B.decodeChoice("|EASY") == null && B.decodeChoice("1|NOPE") == null, "고른 것 저장·읽기");

        // 차가 알아서
        mem = new Memory(0, new HashMap<>(), 0, null);
        Choice au = Choice.Companion.getDEFAULT();
        ok(au.getAuto(), "아직 안 골랐으면 차가 알아서");
        f = B.first(T, au, mem, rnd);
        ok(f.getFirst().getLevel() == Level.EASY && f.getFirst().getCategory() != 5, "처음 탈 때는 쉬운 질문, 삶과 죽음 없음 (" + f.getFirst().getId() + ")");
        B.close(mem, t("l-like"), 1, Bank.HOT_MS, false);
        B.close(mem, t("l-first"), 1, Bank.HOT_MS, false);
        mem.setRide(3);
        Choice e = B.auto(mem, null);
        ok(mem.getWarm() == 2 && e.getLevel() == Level.MID && !e.getCategories().contains(5), "이야기가 두 번 붙으면 적당한 질문으로");
        B.close(mem, t("l-say"), 1, Bank.HOT_MS, false); B.close(mem, t("l-together"), 1, Bank.HOT_MS, false);
        e = B.auto(mem, null);
        ok(e.getLevel() == Level.HARD && e.getCategories().contains(5), "넷 붙으면 어려운 질문 · 삶과 죽음도 연다");
        ok(B.auto(mem, 10).getLevel() == Level.EASY, "남은 시간이 짧으면 쉬운 질문(남은 시간을 읽게 되면)");
        Memory cold = new Memory(5, new HashMap<>(), 3, null);
        B.close(cold, t("d-rest"), 0, 0, false); B.close(cold, t("d-road"), 0, 0, false);
        ok(cold.getWarm() == 1 && B.auto(cold, null).getLevel() == Level.EASY, "첫 질문에서 조용히 접히면 다시 가볍게");
        // 가까운 범주: 사랑이 붙었으면 관계·삶과죽음 쪽이 더 나온다
        Memory near = new Memory(9, new HashMap<>(), 2, "l-like");
        int rel = 0, n = 3000;
        Choice mid = new Choice(s(0, 1, 2, 3, 4), Level.MID, true);
        for (int i = 0; i < n; i++) { int cat = B.other(T, mid, near, null, rnd).getCategory(); if (cat == 1 || cat == 2) rel++; }
        ok(rel > n * 0.6, "사랑 이야기가 붙었으면 다음엔 관계·사랑 쪽이 더 (" + rel + "/" + n + ", 가까움 없으면 약 0.5)");
        Memory rt = B.decode(B.encode(near));
        ok(rt.getWarm() == 2 && "l-like".equals(rt.getLast()) && rt.getRide() == 9, "알아서 기록 저장·읽기 " + B.encode(near));
        ok(B.decode("7|w-job,2,30000,0,0").getRide() == 7, "옛 저장 꼴도 읽는다");
        ok(B.decodeChoice(B.encodeChoice(au)).getAuto() && !B.decodeChoice("0|EASY").getAuto(), "알아서 저장·읽기");

        System.out.println(fails == 0 ? "모두 통과" : fails + "개 실패");
        System.exit(fails == 0 ? 0 : 1);
    }
}
