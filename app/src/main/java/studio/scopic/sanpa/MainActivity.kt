package studio.scopic.sanpa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 산파 — 차가 묻는다. 같이 탔으면 차 안에서, 혼자면 떨어진 가족과 이야기가 시작된다.
 *
 * 어느 자리인지는 동승석 착석으로 차가 고른다.
 * 같이 탔을 때(옆자리에 누가 있고 달리는 중):
 *  - 출발하고 잠시 뒤, 음악이 없으면 고른 주제·단계 안에서 주제 하나를 뽑아 첫 질문을 소리 내어 묻고 빠진다.
 *    지난번에 말이 많이 오간 주제가 있으면 멈춘 질문 다음부터 잇는다([Bank]).
 *  - 듣기만 한다(담지도 보내지도 않는다). 말이 오가다 잦아들면 다음 질문. 처음부터 내내 조용하면 접는다.
 *  - 「다른 질문」 하나만은 달리는 중에도 누를 수 있다(옆자리에 사람이 있을 때만 나오는 화면이다).
 *  - 주차하거나 옆자리가 비면 그만둔다.
 * 세워 둔 동안에는 차 화면에서 주제·단계를 고른다.
 * 혼자일 때:
 *  - 차를 켜면(앱이 뜨면): 가족의 한마디가 새로 와 있으면 튼다. 음악이 나오면 기다린다.
 *  - 주차하면(기어가 P 로 들어오면): 물음 하나를 소리 내어 묻고, 한마디를 담아 가족에게 보낸다.
 * 달리는 동안에는 「다른 질문」 말고는 화면 조작을 받지 않는다.
 * 화면은 [Screen] 이 [Scene] 을 받아 그리고, 여기서는 「지금 무엇을 하는 중인가」만 정한다.
 */
class MainActivity : Activity() {
    private enum class Mode { HOME, ASKING, LISTENING, SENDING, NOTICE, PLAYING, RIDE_ASKING, RIDE_LISTENING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var screen: Screen
    private lateinit var server: Server
    private lateinit var car: CarGate
    private lateinit var asker: Asker
    private lateinit var recorder: Recorder
    private lateinit var player: Player
    private lateinit var hearer: Hearer
    private var io: ExecutorService? = null

    private var started = false
    @Volatile private var epoch = 0 // 화면이 내려갈 때마다 올린다 — 그 전에 떠난 비동기 일의 화면 갱신을 버린다
    private var mode = Mode.HOME
    private var remote: Remote? = null
    private var reachable = true
    private var current: Question? = null
    private var notice: Pair<String, String>? = null
    private var pairOpen = false
    private var pickOpen = false
    private var wantAskUntil = 0L   // 주차한 뒤 이때까지는 틈이 나면 묻는다
    private var retryAt = 0L        // 못 보낸 한마디를 다시 보낼 때
    private var retryDelay = 0L
    private var autoPlayed = false  // 차를 켠 뒤 저절로 트는 것은 한 번
    private var playRetryAt = 0L
    private var lastRefresh = 0L
    private var asks = 0            // 몇 번째 묻기인가 — 지난 묻기의 시간 제한이 새 묻기를 끊지 않게

    // 같이 탔을 때. 주차하거나 옆자리가 비면 처음으로 돌아간다.
    private var rideSince = 0L      // 같이 달리기 시작한 때(0 = 지금은 같이 달리는 중이 아님)
    private var rideTopic: Topic? = null
    private var rideStep = -1       // 지금 질문(-1 = 아직 안 물음)
    private var rideTalkMs = 0      // 지금 주제에서 말이 오간 합 — 다음에 탈 때 이어 물을지 정한다
    private var rideRead = false    // 지금 질문을 끝까지 읽었는가 — 덜 읽은 질문은 「물었다」로 적지 않는다(코덱스 R1)
    private var lastTopic: String? = null // 접은 뒤 「다른 질문」이 방금 주제를 다시 내지 않게(코덱스 R1)
    private var rideAskedAt = 0L
    private var rideDone = false    // 이번에는 다 물었거나 접었다
    private var heard: Hearer.Run? = null

    private val pendingFile: File get() = File(filesDir, "pending.wav")
    private val takeFile: File get() = File(cacheDir, "take.wav")
    private var pendingRound: String?
        get() = prefs.getString("pendingRound", null)
        set(v) { prefs.edit().putString("pendingRound", v).apply() }
    private var playedAt: Long
        get() = prefs.getLong("playedAt", 0)
        set(v) { prefs.edit().putLong("playedAt", v).apply() }
    /** 틀 수 없었던 한마디. 그것 하나 때문에 다음 물음이 막히지 않게 「지나간 것」으로 친다. */
    private var skippedAt: Long
        get() = prefs.getLong("skippedAt", 0)
        set(v) { prefs.edit().putLong("skippedAt", v).apply() }
    /** 한마디를 담아 가족에게 보내도 좋다고 하셨는가. 그 전에는 묻지도 담지도 않는다. */
    private var consented: Boolean
        get() = prefs.getBoolean("consented", false)
        set(v) { prefs.edit().putBoolean("consented", v).apply() }

    /** 같이 탔을 때 고른 범주·난이도. 아직 안 골랐으면 null(그동안은 [Choice.DEFAULT]). */
    private var picked: Choice?
        get() = Bank.decodeChoice(prefs.getString("choice", null))
        set(v) { prefs.edit().putString("choice", v?.let { Bank.encodeChoice(it) }).apply() }
    private val choice: Choice get() = picked ?: Choice.DEFAULT
    /** 주제마다의 기억(멈춘 질문·말이 오간 초). 무슨 말을 했는지는 담지 않는다. */
    private var memory: Memory
        get() = Bank.decode(prefs.getString("bank", null))
        set(v) { prefs.edit().putString("bank", Bank.encode(v)).apply() }

    private fun log(msg: String) { Log.i(TAG, msg) }
    private fun now() = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getPreferences(MODE_PRIVATE)
        Recorder.sweep(cacheDir)
        server = Server(BuildConfig.SERVER, pairCode())
        car = CarGate(this, main, ::log, onChange = { carChanged(); render(); decide() }, onParked = {
            log("주차함 — 틈이 나면 묻는다")
            wantAskUntil = now() + ASK_WINDOW_MS
            refresh()
        })
        asker = Asker(this, main, ::log)
        recorder = Recorder(this, main, ::log)
        player = Player(this, ::log)
        hearer = Hearer(this, ::log)
        screen = Screen(this)
        setContentView(screen.root)
        if (consented) askMicPermission()
        debugCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugCommand(intent)
    }

    /** 디버그 빌드에서만: am start … --es do ask|park. 다른 앱이 녹음·전송을 시키지 못하게 release 에서는 듣지 않는다. */
    private var debugDo: String? = null
    private fun debugCommand(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        debugDo = intent?.getStringExtra("do")
        if (started) runDebug()
    }
    private fun runDebug() {
        when (debugDo) {
            "ask" -> if (mode == Mode.HOME && consented) ask()
            "park" -> { wantAskUntil = now() + ASK_WINDOW_MS; refresh() }
            "ride" -> { if (rideSince > 0) rideSince = now() - RIDE_START_MS; decide() } // 같이 달리는 중이면 기다리지 않고 묻는다
            "other" -> otherQuestion()
            "next" -> if (mode == Mode.RIDE_LISTENING) nextStep() // 잦아들기를 기다리지 않고 다음 질문
            "forget" -> { memory = Memory(); picked = null; log("질문 기억·고른 것 지움") }
        }
        debugDo = null
    }

    private fun askMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    /** 이 차의 짝 코드. 처음 켤 때 차가 만든다 — 저장소에도 APK 에도 들어 있지 않다. */
    private fun pairCode(): String {
        if (BuildConfig.DEBUG && PAIR_FORMAT.matches(BuildConfig.PAIR)) return BuildConfig.PAIR
        prefs.getString("pair", null)?.let { return it }
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        val code = String(CharArray(16) { abc[r.nextInt(abc.length)] })
        prefs.edit().putString("pair", code).apply()
        return code
    }

    // ── 켜고 끄기 ─────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        started = true
        mode = Mode.HOME; notice = null; autoPlayed = false; playRetryAt = 0; retryAt = 0; retryDelay = 0
        io = Executors.newSingleThreadExecutor()
        asker.start()
        car.start()
        render()
        refresh()
        main.postDelayed(tick, TICK_MS)
        runDebug()
    }

    override fun onStop() {
        started = false
        epoch++
        main.removeCallbacksAndMessages(null)
        recorder.cancel() // 화면이 내려가면 마이크를 놓는다. 담던 것은 버린다.
        takeFile.delete()
        player.stop()
        hearer.stop(); heard = null
        asker.stop()
        car.stop()
        io?.shutdown(); io = null
        wantAskUntil = 0; pairOpen = false; pickOpen = false
        resetRide()
        super.onStop()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            rideTick()
            if (mode == Mode.HOME && now() - lastRefresh > REFRESH_MS) refresh() else { render(); decide() }
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 서버 상태를 다시 읽고, 할 일이 있으면 한다. */
    private fun refresh() {
        val e = epoch
        lastRefresh = now()
        io?.execute {
            val r = server.state()
            main.post {
                if (e != epoch) return@post
                reachable = r != null
                if (r != null) remote = r
                render(); decide()
            }
        }
    }

    // ── 때 고르기 ─────────────────────────────────────────

    /** 지난 한마디에 답이 와서 들었거나, 사흘이 지났으면 새 물음을 꺼내도 된다. */
    private fun canAskNew(r: Remote): Boolean =
        r.parentAt == 0L || (r.childAt > 0 && !unheard(r)) || System.currentTimeMillis() - r.parentAt > STALE_MS

    private fun unheard(r: Remote?): Boolean = r?.roundId != null && r.childAt > playedAt && r.childAt != skippedAt

    /** 하던 일을 차 상태가 바뀌어서 멈춰야 하는가. */
    private fun carChanged() {
        if (!started) return
        if (car.moving) wantAskUntil = 0 // 다시 출발했으면 이번 주차의 물음은 접는다
        val riding = car.moving && car.passenger == true
        if (!riding) resetRide() else if (rideSince == 0L) rideSince = now()
        // 후진·중립에서는 묻지 않는다. 묻던 중이면 이번 줄기는 접는다(후진 주차 중에 물음이 나오지 않게, 코덱스 R1).
        if (riding && !car.forward && (mode == Mode.RIDE_ASKING || mode == Mode.RIDE_LISTENING)) {
            log("후진·중립 — 같이 묻기를 접음"); if (mode == Mode.RIDE_ASKING) asker.interrupt(); endRide()
        }
        when (mode) {
            // 묻는 중에 출발하거나 옆자리에 누가 타면 그만둔다.
            Mode.ASKING -> if (car.moving || !car.alone) { log("출발·동승 — 묻기를 멈춤"); asker.interrupt(); mode = Mode.HOME }
            // 담는 중에 옆자리에 누가 타면 버린다 — 「같이 탔을 때는 담지도 보내지도 않는다」. 탄 사람의 말이 섞였을 수 있다(코덱스 R1).
            Mode.LISTENING -> if (!car.alone) { log("옆자리에 누가 탐 — 담던 것을 버림"); recorder.cancel(); takeFile.delete(); mode = Mode.HOME }
                // 혼자 담는 중에 출발하면 거기까지만. 말이 담겼으면 보낸다.
                else if (car.moving) { log("출발 — 담기를 끝냄"); recorder.finish(trust = false) }
            // 트는 중에 옆자리에 누가 타면 멈춘다. 다시 혼자가 되면 처음부터 튼다.
            Mode.PLAYING -> if (!car.alone || (car.moving && !car.forward)) {
                log("옆자리에 누가 탐·후진·중립 — 틀기를 멈춤"); player.stop(); autoPlayed = false; mode = Mode.HOME
            }
            else -> {}
        }
    }

    private fun decide() {
        if (!started || mode != Mode.HOME || !car.settled) return
        if (!consented) return
        if (maybeStartRide()) return
        if (pendingRound != null) { if (now() >= retryAt) upload(); return }
        val r = remote ?: return
        // 후진·중립에서는 가족의 한마디를 저절로 틀지 않는다(코덱스 R1 — 주차·출발 뒤 D·기어 모름만).
        val quiet = car.steady && car.alone && !car.musicPlaying && (!car.moving || car.forward)
        if (unheard(r)) {
            if (quiet && !autoPlayed && now() >= playRetryAt) playChild(auto = true)
            return
        }
        // 저절로 묻는 것은 주차해 있는 것이 확인될 때만.
        if (now() < wantAskUntil && car.parked && quiet && canAskNew(r)) { wantAskUntil = 0; ask() }
    }

    // ── 같이 탔을 때: 묻고 빠지기 ──────────────────────────────

    /** 같이 달리기가 끝났다(주차·옆자리 빔·화면 내려감). 묻던 것도 듣던 것도 그만둔다. */
    private fun resetRide() {
        if (mode == Mode.RIDE_ASKING) asker.interrupt()
        if (mode == Mode.RIDE_LISTENING || mode == Mode.RIDE_ASKING) { log("같이 달리기 끝 — 그만 물음"); mode = Mode.HOME }
        closeTopic(skipped = false)
        hearer.stop(); heard = null
        rideSince = 0; rideStep = -1; rideDone = false
    }

    /** 듣던 것을 놓기 전에 말이 오간 초를 지금 주제에 더한다. */
    private fun stopHearing() {
        heard?.let { rideTalkMs += it.voicedMs }
        hearer.stop(); heard = null
    }

    /** 지금 주제를 그만둔다 — 말이 많이 오갔으면 다음에 이어 묻게, 넷을 다 물었으면 쉬게 적어 둔다. */
    private fun closeTopic(skipped: Boolean) {
        val t = rideTopic ?: return
        stopHearing()
        val asked = if (rideRead) rideStep else rideStep - 1
        if (asked >= 0 || (skipped && rideStep >= 0)) {
            val mem = memory
            Bank.close(mem, t, asked, rideTalkMs, skipped)
            memory = mem
            log("주제 접음: ${t.id} ${rideStep + 1}/${t.questions.size} 말 ${rideTalkMs}ms${if (skipped) " (다른 질문)" else ""}")
        }
        lastTopic = t.id
        rideTopic = null; rideStep = -1; rideTalkMs = 0; rideRead = false
    }

    /** 같이 달린 지 조금 지났고 음악이 없으면 첫 질문을 묻는다. 물었으면 true. */
    private fun maybeStartRide(): Boolean {
        if (rideSince == 0L || rideStep >= 0 || rideDone) return false
        if (!car.forward || !car.steady || car.musicPlaying || now() - rideSince < RIDE_START_MS) return false
        val mem = memory
        val (t, at) = Bank.first(QuestionBank.topics, choice, mem, Random.Default)
        memory = mem
        if (at > 0) log("지난번에 이야기가 이어진 주제 — ${at + 1}번째 질문부터")
        rideTopic = t; rideTalkMs = 0
        rideAsk(at)
        return true
    }

    /**
     * 「다른 질문」. 묻는 중이든 듣는 중이든, 오늘 물음을 접은 뒤든 — 고른 칸 안에서 다른 주제의 첫 질문을 바로 읽는다.
     * 넘긴 주제는 덜 나오게 적어 둔다.
     */
    private fun otherQuestion() {
        val asking = mode == Mode.RIDE_ASKING || mode == Mode.RIDE_LISTENING
        val again = mode == Mode.HOME && rideSince > 0 && rideDone
        if (!asking && !again) return
        if (!car.forward || car.passenger != true || car.musicPlaying) return // 음악이 나오면 끼어들지 않는다(코덱스 R1)
        if (mode == Mode.RIDE_ASKING) asker.interrupt()
        val cur = rideTopic?.id ?: lastTopic
        closeTopic(skipped = true)
        rideTopic = Bank.other(QuestionBank.topics, choice, memory, cur, Random.Default)
        rideDone = false
        rideAsk(0)
    }

    private fun rideAsk(step: Int) {
        val t = rideTopic ?: return
        stopHearing()
        rideStep = step; rideRead = false
        mode = Mode.RIDE_ASKING; render()
        log("같이 묻기: ${t.id} ${step + 1}/${t.questions.size}")
        val e = epoch
        val n = ++asks
        asker.speak(t.questions[step]) { done ->
            if (e != epoch || mode != Mode.RIDE_ASKING || n != asks) return@speak
            if (!done) { log("같이 묻기 실패 — 이번엔 접음"); endRide(); return@speak }
            rideRead = true
            rideAskedAt = now()
            heard = hearer.start()
            if (heard == null) { endRide(); return@speak } // 들을 수 없으면 다음 걸음의 때를 모른다. 한 걸음으로 끝낸다.
            mode = Mode.RIDE_LISTENING; render()
        }
        main.postDelayed({
            if (e == epoch && mode == Mode.RIDE_ASKING && n == asks) { log("같이 묻기 시간 초과"); asker.interrupt(); endRide() }
        }, ASK_TIMEOUT_MS)
    }

    private fun endRide() {
        closeTopic(skipped = false)
        rideDone = true
        if (mode == Mode.RIDE_ASKING || mode == Mode.RIDE_LISTENING) mode = Mode.HOME
        render()
    }

    private fun nextStep() {
        val t = rideTopic ?: return endRide()
        if (rideStep + 1 < t.questions.size) rideAsk(rideStep + 1) else endRide()
    }

    /** 듣는 동안 2초마다: 잦아들었으면 다음 질문, 처음부터 조용하면 접는다. */
    private fun rideTick() {
        if (mode != Mode.RIDE_LISTENING) return
        val h = heard ?: return endRide()
        if (rideTopic == null) return endRide()
        val since = now() - rideAskedAt
        when {
            h.failed -> { log("듣기 실패 — 접음"); endRide() }
            // 누가 음악을 켰으면 이야기는 거기까지다. 방금 끝난 물음 소리가 음악으로 잡히지 않게 잠깐은 보지 않는다.
            since > MUSIC_GRACE_MS && car.musicPlaying -> { log("음악이 켜짐 — 접음"); endRide() }
            h.voicedMs < RIDE_SPOKE_MS && h.elapsedMs >= RIDE_FOLD_MS -> { log("물은 뒤 내내 조용함 — 접음"); endRide() }
            h.voicedMs >= RIDE_SPOKE_MS && h.quietMs >= RIDE_QUIET_MS && since >= RIDE_GAP_MS -> {
                log("잦아듦(말 ${h.voicedMs}ms, 조용 ${h.quietMs}ms)")
                nextStep()
            }
        }
    }

    // ── 묻고 담아 보내기 ───────────────────────────────────

    private fun ask() {
        val q = Questions.at(prefs.getInt("qIndex", 0))
        current = q; pairOpen = false
        mode = Mode.ASKING; render()
        log("묻기: ${q.id}")
        val e = epoch
        val n = ++asks
        asker.speak(q.text) { done ->
            log("묻기 끝 done=$done")
            if (e != epoch || mode != Mode.ASKING) return@speak
            if (done) listen(q) else show("지금은 묻지 못했어요.", "잠시 뒤에 다시 물을게요.")
        }
        // 읽기가 끝났다는 답이 끝내 안 오면 묻는 화면에 갇힌다. 기다릴 만큼만 기다린다.
        main.postDelayed({
            if (e == epoch && mode == Mode.ASKING && n == asks) {
                log("묻기 시간 초과"); asker.interrupt(); show("지금은 묻지 못했어요.", "잠시 뒤에 다시 물을게요.")
            }
        }, ASK_TIMEOUT_MS)
    }

    private fun listen(q: Question) {
        prefs.edit().putInt("qIndex", prefs.getInt("qIndex", 0) + 1).apply() // 넘어간 물음을 다시 꺼내 조르지 않는다
        mode = Mode.LISTENING; render()
        val e = epoch
        recorder.start(takeFile, onLevel = { if (e == epoch && mode == Mode.LISTENING) screen.level(it) }) { end ->
            if (e != epoch) return@start
            when (end) {
                Recorder.End.SAVED -> {
                    // 회차 id: 같은 물음을 다시 물어도 지난 한마디를 덮어쓰지 않는다.
                    // 끝의 시각은 서버가 회차의 앞뒤를 가리는 데 쓴다. 차 시계가 뒤로 가도 지난 회차보다는 늘 크게 만든다.
                    // 지난 파일을 먼저 치우고, 회차를 디스크에 적고(commit), 파일을 옮긴다 — 그 사이에 꺼지면 파일 없는 회차로 남아 다음에 내려놓는다.
                    // 반대 순서면 회차를 모르는 파일만 남아 다음 녹음이 지운다(코덱스 개발 6 R1).
                    pendingFile.delete()
                    val at = maxOf(System.currentTimeMillis(), prefs.getLong("lastRoundAt", 0) + 1)
                    prefs.edit().putLong("lastRoundAt", at).putString("pendingText", q.text)
                        .putString("pendingRound", q.id + "-" + java.lang.Long.toString(at, 36)).commit()
                    if (!takeFile.renameTo(pendingFile)) { pendingRound = null; show("한마디를 담지 못했어요.", ""); return@start }
                    retryDelay = 0
                    upload()
                }
                Recorder.End.SILENT -> show("오늘은 넘어갈게요.", "다음에 주차하면 다른 물음을 드릴게요.")
                Recorder.End.FAILED -> show("마이크를 쓸 수 없었어요.", "산파가 마이크를 쓸 수 있게 허락해 주세요.")
                Recorder.End.CANCELLED -> {}
            }
        }
    }

    /** 못 보낸 한마디는 지우지 않는다. 차를 다시 켜도 남아 있다가 닿을 때 간다. */
    private fun upload() {
        val round = pendingRound ?: return
        val text = prefs.getString("pendingText", null) ?: ""
        if (!pendingFile.exists() || text.isEmpty()) { pendingRound = null; return }
        mode = Mode.SENDING; render()
        val e = epoch
        val file = pendingFile
        io?.execute {
            val code = server.uploadParent(file, round, text)
            main.post {
                log("보내기 응답 $code (회차 $round)")
                // 서버가 「이 한마디는 받을 수 없다」고 한 것만 내려놓는다(형식·크기·지난 회차). 다시 보내도 같다.
                // 그 밖의 실패(연결·권한·서버 고장)는 서버를 고치면 풀리니 한마디를 쥐고 다시 보낸다.
                val gone = code == 200 || code in REFUSED
                if (gone && pendingRound == round) { pendingRound = null; file.delete() }
                if (e != epoch) return@post
                when {
                    code == 200 -> {
                        remote = Remote(round, text, System.currentTimeMillis(), 0, null)
                        show("보냈어요.", "답이 오면 차를 켤 때 들려 드릴게요.")
                        refresh()
                    }
                    gone -> show("보내지 못했어요.", "다음에 주차하면 다시 물을게요.")
                    else -> {
                        retryDelay = (retryDelay * 2).coerceIn(RETRY_MIN_MS, RETRY_MAX_MS)
                        retryAt = now() + retryDelay
                        mode = Mode.HOME; render()
                    }
                }
            }
        }
    }

    // ── 가족의 한마디 틀기 ──────────────────────────────────

    private fun playChild(auto: Boolean) {
        val r = remote ?: return
        val round = r.roundId ?: return
        if (r.childAt == 0L) return
        pairOpen = false
        mode = Mode.PLAYING; render()
        val e = epoch
        val file = File(cacheDir, "child-${r.childAt}") // 받을 때마다 다른 파일
        io?.execute {
            val ok = file.exists() || server.downloadChild(round, r.childV, file)
            // 화면이 내려갔다 다시 뜬 뒤의 지난 일은 치우지 않는다 — 새 일이 받은 파일·받는 중인 임시 파일을 지울 수 있다(코덱스 개발 6 R1).
            if (e == epoch) cacheDir.listFiles { f -> f.name.startsWith("child-") && !f.name.endsWith(".tmp") && f != file }?.forEach { it.delete() }
            main.post {
                if (e != epoch || mode != Mode.PLAYING) return@post
                // 받는 사이 누가 탔거나 음악이 켜졌으면 저절로 틀지 않는다.
                if (!ok) { // 받지 못했다 — 잠시 뒤에 다시
                    log("가족의 한마디를 받지 못함"); file.delete()
                    playRetryAt = now() + PLAY_RETRY_MS; mode = Mode.HOME; render(); return@post
                }
                if (auto && !(car.alone && !car.musicPlaying && (!car.moving || car.forward))) { mode = Mode.HOME; render(); return@post }
                val playable = player.play(file) { end ->
                    if (e != epoch) return@play
                    when (end) {
                        // 끝까지 튼 것만 「들었다」로 적는다. 끊기면 다음에 차를 켤 때 다시 튼다.
                        Player.End.DONE -> if (r.childAt > playedAt) playedAt = r.childAt
                        Player.End.ERROR -> unplayable(r.childAt, file)
                        Player.End.INTERRUPTED -> playRetryAt = now() + PLAY_RETRY_MS
                    }
                    if (mode == Mode.PLAYING) mode = Mode.HOME
                    render(); decide()
                }
                // 저절로 트는 것은 한 번 — 실제로 소리가 나기 시작했을 때만 센다(포커스를 못 받았으면 잠시 뒤 다시).
                if (auto && player.playing) autoPlayed = true
                if (!playable) { unplayable(r.childAt, file); mode = Mode.HOME; render() }
            }
        }
    }

    private fun unplayable(at: Long, file: File) {
        log("틀 수 없는 한마디 — 지나간 것으로 친다")
        skippedAt = at
        file.delete()
    }

    // ── 화면 ──────────────────────────────────────────────

    /** 잠깐 보이고 처음 화면으로 돌아가는 한마디. */
    private fun show(title: String, note: String) {
        notice = title to note
        mode = Mode.NOTICE; render()
        val e = epoch
        main.postDelayed({ if (e == epoch && mode == Mode.NOTICE) { mode = Mode.HOME; notice = null; render(); decide() } }, NOTICE_MS)
    }

    private fun render() {
        if (!started) return
        val touch = car.canTouch
        if (!touch) { pairOpen = false; pickOpen = false }
        if (pickOpen) {
            screen.showPicker(choice, QuestionBank.categories, onChange = { picked = it }) { pickOpen = false; render() }
            return
        }
        if (pairOpen) {
            screen.showPair(BuildConfig.SERVER + "/?pair=" + pairCode()) { pairOpen = false; render() }
            return
        }
        val r = remote
        val v = when (mode) {
            Mode.ASKING -> Scene(title = current?.text ?: "")
            Mode.LISTENING -> Scene(label = "듣고 있어요", listening = true, title = current?.text ?: "",
                note = "편하게 말씀하세요. 다 말씀하시면 알아서 보내 드려요.",
                primary = "다 말했어요" to { recorder.finish(trust = true) })
            Mode.SENDING -> Scene(title = prefs.getString("pendingText", null) ?: "", note = "보내고 있어요.")
            Mode.NOTICE -> Scene(title = notice?.first ?: "", note = notice?.second ?: "")
            Mode.PLAYING -> Scene(label = "한마디가 왔어요", title = r?.text ?: "", note = "가족이 남긴 답이에요.",
                // 끝까지 안 들었으니 「들었다」로 적지 않는다. 다음에 차를 켤 때 다시 튼다(이번에는 저절로 다시 틀지 않는다).
                primary = "그만 듣기" to { player.stop(); autoPlayed = true; mode = Mode.HOME; render() })
            Mode.RIDE_ASKING -> rideScene(note = "")
            Mode.RIDE_LISTENING -> rideScene(note = if (rideStep + 1 >= (rideTopic?.questions?.size ?: 0)) "마지막 물음이에요."
                else "이야기가 잦아들면 다음 물음을 드릴게요.")
            Mode.HOME -> home(r)
        }
        // 달리는 중에는 누를 것을 다 걷는다. 「다른 질문」(driving = true) 하나만 남긴다.
        screen.show(if (touch) v else v.copy(primary = v.primary.takeIf { v.driving }, secondary = null, pair = null, pick = null))
    }

    private val other: Pair<String, () -> Unit> = "다른 질문" to { otherQuestion() }

    private fun rideScene(note: String): Scene {
        val t = rideTopic
        val step = rideStep.coerceAtLeast(0)
        return Scene(label = t?.let { "${step + 1} / ${it.questions.size}" }, title = t?.questions?.getOrNull(step) ?: "", note = note,
            primary = other.takeIf { !car.musicPlaying }, driving = true)
    }

    /** 옆자리에 누가 있을 때의 처음 화면. 혼자일 때의 일(가족의 한마디)은 혼자가 될 때까지 기다린다. */
    private fun together(pair: () -> Unit, pick: () -> Unit): Scene = when {
        car.moving && rideDone -> Scene(title = "오늘 물음은 여기까지예요.", note = "다음에 같이 타면 다른 물음을 드릴게요.",
            primary = other.takeIf { car.forward && !car.musicPlaying }, driving = true)
        car.moving -> Scene(title = "곧 물음 하나를 드릴게요.",
            note = if (car.musicPlaying) "음악이 멈추면 드릴게요." else "같이 탄 분들께 소리 내어 묻고, 이야기는 듣기만 해요.")
        // 처음 같이 탔을 때 한 번 고르게 한다. 그 뒤엔 위의 「질문 고르기」로 언제든.
        picked == null -> Scene(title = "어떤 질문을 드릴까요?",
            note = "고르지 않으면 차가 알아서 골라요. 처음엔 쉬운 질문부터 드려요.",
            primary = "질문 고르기" to pick, pair = pair)
        else -> Scene(title = "출발하면 물음 하나를 드릴게요.",
            note = "같이 탄 분들께 소리 내어 묻고, 이야기는 듣기만 해요. 담지도 보내지도 않아요.", pair = pair, pick = pick)
    }

    private fun home(r: Remote?): Scene {
        val askNow = "물음 듣기" to { ask() }
        val pair = { pairOpen = true; render() }
        val pick = { pickOpen = true; render() }
        val waiting = now() < wantAskUntil
        val blocked = if (car.musicPlaying) "음악이 끝나면" else null
        return when {
            !consented -> Scene(
                title = "차가 물음을 드려도 될까요?",
                note = "같이 탔을 때는 소리 내어 묻고, 말이 잦아드는지 소리 크기만 들어요.\n" +
                    "혼자일 때는 남기신 한마디만 연결한 가족에게 보내요. 그 밖의 말은 담지 않아요.",
                primary = "좋아요" to { consented = true; askMicPermission(); render(); decide() })
            !car.alone -> together(pair, pick)
            pendingRound != null -> Scene(title = prefs.getString("pendingText", null) ?: "",
                note = "아직 보내지 못했어요. 차가 다시 보내 볼게요.", primary = "지금 보내기" to { upload() }, pair = pair)
            r == null || r.roundId == null || r.parentAt == 0L -> Scene(
                title = "주차하면 물음 하나를 드릴게요.",
                note = when {
                    !reachable -> "지금은 연결이 안 돼요. 잠시 뒤에 다시 볼게요."
                    waiting && blocked != null -> "$blocked 물을게요."
                    else -> "한마디 남기시면 가족에게 전해 드려요."
                },
                primary = askNow, pair = pair)
            unheard(r) -> Scene(label = "한마디가 왔어요", title = r.text ?: "",
                note = if (blocked != null) "$blocked 들려 드릴게요." else "가족이 답을 남겼어요.",
                primary = "지금 듣기" to { playChild(auto = false) }, pair = pair)
            r.childAt > 0 && r.childAt == skippedAt -> Scene(title = r.text ?: "",
                note = "가족의 한마디를 틀지 못했어요. 주차하면 새 물음을 드릴게요.", primary = askNow, pair = pair)
            r.childAt > 0 -> Scene(title = r.text ?: "",
                note = if (waiting && blocked != null) "$blocked 새 물음을 드릴게요." else "주고받은 한마디예요. 주차하면 새 물음을 드릴게요.",
                primary = askNow, secondary = "다시 듣기" to { playChild(auto = false) }, pair = pair)
            else -> Scene(title = r.text ?: "",
                note = "한마디를 보냈어요. 답이 오면 차를 켤 때 들려 드릴게요.",
                secondary = "새 물음 듣기" to { ask() }, pair = pair)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val TAG = "sanpa"
        val PAIR_FORMAT = Regex("[a-z0-9]{6,32}")
        const val TICK_MS = 2000L
        const val REFRESH_MS = 60_000L          // 처음 화면에 있는 동안 답이 왔는지 보는 간격
        const val NOTICE_MS = 5000L
        const val ASK_WINDOW_MS = 120_000L      // 주차한 뒤 이 안에 틈이 안 나면 이번에는 묻지 않는다
        const val STALE_MS = 3 * 24 * 3600_000L // 답이 없는 채 사흘이면 새 물음으로 넘어간다
        const val RETRY_MIN_MS = 15_000L
        const val RETRY_MAX_MS = 300_000L
        const val PLAY_RETRY_MS = 30_000L
        const val ASK_TIMEOUT_MS = 30_000L
        // 같이 탔을 때. 설계(PROJECT.md)는 「조용해진 지 30~40초 + 앞 물음에서 5분」이지만, 시연에서 한 바퀴가 보이도록 줄였다.
        const val RIDE_START_MS = 20_000L      // 같이 출발하고 이만큼 지나서 첫 물음
        const val RIDE_SPOKE_MS = 3_000        // 물은 뒤 이만큼은 말이 오가야 「이야기했다」(1초 단위로 센다)
        const val RIDE_QUIET_MS = 20_000       // 이야기가 이만큼 잦아들면 다음 걸음
        const val RIDE_GAP_MS = 45_000L        // 앞 물음에서 이만큼은 지나야 다음 걸음
        const val RIDE_FOLD_MS = 45_000        // 물은 뒤 이만큼 내내 조용하면 접는다
        const val MUSIC_GRACE_MS = 3_000L
        val REFUSED = setOf(400, 409, 413, 415)
    }
}
