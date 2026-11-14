package studio.scopic.sanpa

import kotlin.random.Random

/** 난이도. 화면에서는 「단계」라 부르고, 단계 이름(쉬운 질문·적당한 질문·어려운 질문)은 이 말 그대로 쓴다. */
enum class Level(val label: String) { EASY("쉬운 질문"), MID("적당한 질문"), HARD("어려운 질문") }

/**
 * 같이 탔을 때의 주제 하나. 질문 넷은 순서대로 나온다: 묻기 → 흔들기 → 넓히기 → 돌려주기.
 * 말이 오가다 잦아들 때만 다음 질문을 낸다. 문장은 질문_초벌.md 에서 만든다([QuestionBank]).
 */
data class Topic(val id: String, val name: String, val category: Int, val level: Level, val star: Boolean, val questions: List<String>)

/**
 * 탄 사람이 고른 것. 범주(화면에서는 「주제」)는 여러 개(하나 이상), 난이도는 하나.
 * auto = 「차가 알아서」 — 범주·난이도를 지난 기록으로 차가 정한다([Bank.auto]). 그때 categories·level 은 화면에 남겨 둔 값일 뿐이다.
 */
data class Choice(val categories: Set<Int>, val level: Level, val auto: Boolean = false) {
    companion object {
        /** 아직 안 골랐을 때 — 차가 알아서. 손으로 고르기로 바꾸면 이 범주·난이도에서 시작한다. */
        val DEFAULT = Choice(setOf(0, 1, 2, 3, 4), Level.EASY, auto = true)
    }
}

/**
 * 주제마다의 기억. 무슨 말을 했는지는 모른다 — 「말이 오간 초」만 안다.
 * next: 다음에 이어 물을 질문(0 = 처음부터) · heat: 그때 말이 오간 ms · done: 넷을 다 물은 탈것 번호 · skips: 「다른 질문」으로 넘긴 횟수.
 */
data class Mark(var next: Int = 0, var heat: Int = 0, var done: Int = 0, var skips: Int = 0)

/**
 * ride: 같이 달린 횟수 · warm: 이야기가 붙은 정도(불붙은 주제 +1, 조용히 접힌 주제 -1) · last: 마지막으로 불붙은 주제.
 * warm·last 는 「차가 알아서」 고를 때만 쓴다.
 */
class Memory(var ride: Int = 0, val marks: MutableMap<String, Mark> = mutableMapOf(), var warm: Int = 0, var last: String? = null) {
    fun mark(id: String) = marks.getOrPut(id) { Mark() }
}

/** 질문 은행에서 뽑기. 안드로이드 없이 돈다 — scripts/bank-check.sh 가 부른다. */
object Bank {
    const val HOT_MS = 20_000   // 한 주제에서 말이 이만큼 오갔으면 「불붙은 주제」 — 다음에 탈 때 이어 묻는다(시연값)
    const val COOL_RIDES = 5    // 넷을 다 물은 주제는 이만큼 탈 동안 안 낸다(시연값)
    const val NEAR_WEIGHT = 3.0 // 차가 알아서 고를 때, 지난번에 불붙은 주제와 가까운 범주를 이만큼 더 자주

    /** 범주끼리 가까운 쪽. 사랑 이야기가 붙었으면 다음엔 관계(가족)로, 일상이 붙었으면 일과 꿈·요즘 세상으로. */
    private val NEAR = mapOf(
        0 to setOf(0, 3, 4), 1 to setOf(1, 2, 5), 2 to setOf(2, 1),
        3 to setOf(3, 0, 4), 4 to setOf(4, 0, 3), 5 to setOf(5, 1),
    )

    /**
     * 「차가 알아서」. 처음엔 쉬운 질문, 이야기가 붙은 기록이 쌓이면 한 단씩 깊게, 조용히 접히면 다시 가볍게.
     * 삶과 죽음은 이야기가 꽤 붙은 뒤에야 연다.
     * 🔴 남은 시간(짧은 길은 쉽게·긴 길은 깊게)은 넣을 자리만 있다 — Pleos 공개 SDK 에서 내비 남은 시간을 못 찾았다(개발 5).
     */
    fun auto(mem: Memory, remainingMin: Int? = null): Choice {
        var level = when {
            mem.ride <= 1 || mem.warm < 2 -> Level.EASY
            mem.warm < 4 -> Level.MID
            else -> Level.HARD
        }
        if (remainingMin != null && remainingMin < 15) level = Level.EASY
        val cats = (0..4).toSet() + if (mem.warm >= 3) setOf(5) else emptySet()
        return Choice(cats, level)
    }

    private fun effective(c: Choice, mem: Memory) = if (c.auto) auto(mem) else c

    private fun widen(l: Level): Set<Level> = when (l) {
        Level.EASY -> setOf(Level.EASY, Level.MID)
        Level.MID -> setOf(Level.MID, Level.EASY) // 넓혀도 어려운 질문으로는 안 간다
        Level.HARD -> setOf(Level.HARD, Level.MID)
    }

    /**
     * 고른 칸에서 뽑을 주제들. 칸에 셋이 안 되면 난이도를 한 칸 넓히고, 그래도 없으면 범주만 본다(어려운 질문은 고른 사람에게만).
     * 그래도 없으면 쉬는 중인 주제까지, 끝으로는 아무 주제나 — 빈손으로 묻는 일은 없다.
     */
    fun pool(topics: List<Topic>, c: Choice, mem: Memory, exclude: String? = null): List<Topic> {
        val cats = c.categories.ifEmpty { Choice.DEFAULT.categories }
        fun resting(t: Topic) = mem.marks[t.id]?.let { it.done > 0 && mem.ride - it.done < COOL_RIDES } ?: false
        val noHard = Level.entries.toSet() - if (c.level == Level.HARD) emptySet() else setOf(Level.HARD)
        val tiers = listOf(setOf(c.level) to 3, widen(c.level) to 1, noHard to 1)
        // 고르지 않은 어려운 질문은 쉬는 주제를 다시 꺼내 쓰는 것보다도 뒤다(코덱스 R1 P2, 개발 5).
        val passes = listOf(false, true).flatMap { rest -> tiers.map { Triple(it.first, it.second, rest) } } +
            listOf(false, true).map { Triple(Level.entries.toSet(), 1, it) }
        for ((levels, need, rest) in passes) {
            val p = topics.filter { it.category in cats && it.level in levels && it.id != exclude && (rest || !resting(it)) }
            if (p.size >= need) return p
        }
        return topics.filter { it.id != exclude }.ifEmpty { topics }
    }

    /** 같이 달리기를 시작할 때. 고른 칸 안에 불붙었던 주제가 있으면 멈춘 질문 다음부터, 없으면 새로 뽑는다. */
    fun first(topics: List<Topic>, c: Choice, mem: Memory, rnd: Random): Pair<Topic, Int> {
        mem.ride++
        val p = pool(topics, effective(c, mem), mem)
        p.filter { (mem.marks[it.id]?.next ?: 0) > 0 }.maxByOrNull { mem.marks[it.id]!!.heat }
            ?.let { return it to mem.marks[it.id]!!.next }
        return pick(p, mem, rnd, c.auto, topics) to 0
    }

    /** 「다른 질문」 — 지금 주제를 빼고 고른 칸 안에서 다른 주제의 첫 질문. */
    fun other(topics: List<Topic>, c: Choice, mem: Memory, current: String?, rnd: Random): Topic =
        pick(pool(topics, effective(c, mem), mem, exclude = current), mem, rnd, c.auto, topics)

    /** 넘긴 주제일수록 덜 나온다. 차가 알아서 고를 때는 지난번에 불붙은 주제와 가까운 범주가 더 나온다. */
    private fun pick(p: List<Topic>, mem: Memory, rnd: Random, near: Boolean = false, all: List<Topic> = p): Topic {
        val lastCat = mem.last?.let { id -> all.firstOrNull { it.id == id }?.category }
        val close = if (near && lastCat != null) NEAR[lastCat].orEmpty() else emptySet()
        val w = p.map { (1.0 / (1 + (mem.marks[it.id]?.skips ?: 0))) * (if (it.category in close) NEAR_WEIGHT else 1.0) }
        var x = rnd.nextDouble() * w.sum()
        for (i in p.indices) { x -= w[i]; if (x < 0) return p[i] }
        return p.last()
    }

    /**
     * 한 주제를 그만둘 때(다음 주제로 넘김·접음·주차). asked = 마지막으로 물은 질문(0부터), talkMs = 이번에 그 주제에서 말이 오간 합.
     * 넷을 다 물었으면 쉬게 하고, 말이 많이 오갔으면 다음 질문부터 이어 묻게 하고, 아니면 접는다.
     */
    fun close(mem: Memory, t: Topic, asked: Int, talkMs: Int, skipped: Boolean) {
        val m = mem.mark(t.id)
        if (!skipped) {
            if (talkMs >= HOT_MS) { mem.warm = (mem.warm + 1).coerceAtMost(5); mem.last = t.id }
            else if (asked == 0) mem.warm = (mem.warm - 1).coerceAtLeast(-2) // 첫 질문에서 조용히 접힘
        }
        when {
            skipped -> { m.next = 0; m.heat = 0; m.skips++ }
            asked >= t.questions.size - 1 -> { m.next = 0; m.heat = 0; m.done = mem.ride; m.skips = 0 }
            talkMs >= HOT_MS -> { m.next = asked + 1; m.heat = talkMs; m.skips = 0 }
            else -> { m.next = 0; m.heat = 0 }
        }
    }

    // 저장 꼴: "ride,warm,last|id,next,heat,done,skips;…" — 못 읽는 조각은 버린다(처음부터 다시 뽑을 뿐이다).
    fun encode(mem: Memory): String = "${mem.ride},${mem.warm},${mem.last ?: ""}|" +
        mem.marks.entries.filter { (_, m) -> m != Mark() }
            .joinToString(";") { (id, m) -> "$id,${m.next},${m.heat},${m.done},${m.skips}" }

    fun decode(s: String?): Memory {
        if (s.isNullOrEmpty()) return Memory()
        val (head, body) = s.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val h = head.split(",")
        val mem = Memory(h[0].toIntOrNull() ?: 0, warm = h.getOrNull(1)?.toIntOrNull()?.coerceIn(-2, 5) ?: 0,
            last = h.getOrNull(2)?.takeIf { it.isNotEmpty() })
        for (part in body.split(";")) {
            val f = part.split(",")
            if (f.size != 5) continue
            val n = f.drop(1).map { it.toIntOrNull() ?: return@map null }
            if (n.any { it == null }) continue
            mem.marks[f[0]] = Mark(n[0]!!.coerceIn(0, 3), n[1]!!, n[2]!!, n[3]!!)
        }
        return mem
    }

    fun encodeChoice(c: Choice) = c.categories.sorted().joinToString(",") + "|" + c.level.name + if (c.auto) "|auto" else ""

    fun decodeChoice(s: String?): Choice? {
        if (s.isNullOrEmpty()) return null
        val f = s.split("|")
        val cats = f[0].split(",").mapNotNull { it.toIntOrNull() }.filter { it in 0..5 }.toSet()
        val level = Level.entries.firstOrNull { it.name == f.getOrNull(1) } ?: return null
        return if (cats.isEmpty()) null else Choice(cats, level, auto = f.getOrNull(2) == "auto")
    }
}
