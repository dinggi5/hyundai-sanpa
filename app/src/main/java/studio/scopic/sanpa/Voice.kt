package studio.scopic.sanpa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** 묻기 — Pleos TTS 가 한국어로 물음을 읽는다. 콜백은 [main] 스레드로 온다. */
class Asker(private val context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: Pair<String, (Boolean) -> Unit>? = null // 준비 전에 들어온 물음
    private var onEnd: ((Boolean) -> Unit)? = null
    private var speaking = false
    // 끊은 물음의 「멈췄다」 신호는 늦게 온다. Pleos TTS 에는 발화 id 가 없어서, 그 사이 새 물음을 읽기 시작하면
    // 늦은 신호가 새 물음의 콜백을 부른다(「다른 질문」을 누르면 바로 접혀 버렸다). 끊은 뒤에는 그 신호를 받아 버리거나
    // 잠깐 기다린 뒤에야 다음 물음을 읽는다.
    private var stopPending = false
    private var queued: Pair<String, (Boolean) -> Unit>? = null
    // 이 SDK 는 신호를 두 번씩 보낸다(개발 5 실측: onStart·onDone 이 같은 순간 두 번). 끊은 물음의 「멈췄다」 둘째 것이
    // 새 물음에 닿지 않게, 새 물음을 읽기 시작한 직후에 오는 신호는 버린다 — 물음 하나는 그보다 짧게 끝나지 않는다.
    private var speakAt = 0L
    private val stopWaited = Runnable { stopPending = false; queued?.let { (text, cb) -> queued = null; speak(text, cb) } }

    fun start() {
        if (tts != null) return
        val t = TextToSpeech(context, Mode.ON_DEVICE)
        tts = t
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (tts !== t) return@post
                    // setLocale·리스너는 initialize 가 끝난 뒤에. 앞에 부르면 SDK 안에서 죽는다(개발 2 실측).
                    t.setLocale(Locale.KOREA)
                    t.addEventListener(object : EventListener {
                        override fun onStart() {}
                        override fun onReady() {}
                        override fun onDone() = end(t, true)
                        override fun onError(e: ErrorType) { log("TTS 오류 $e"); end(t, false) }
                        override fun onStop() = end(t, false)
                        override fun onUpdatedRms(rms: Double) {}
                    })
                    ready = true
                    waiting?.let { (text, cb) -> waiting = null; speak(text, cb) }
                }
            }

            override fun onFailure(e: ErrorType) {
                log("TTS 초기화 실패 $e")
                // 실패한 것을 쥐고 있으면 다음 물음이 영영 기다린다. 내려놓고, 다음에 물을 때 다시 연다.
                main.post {
                    if (tts !== t) return@post
                    val cb = waiting?.second; waiting = null
                    tts = null
                    try { t.release() } catch (_: Exception) {}
                    cb?.invoke(false)
                }
            }
        })
    }

    private fun end(from: TextToSpeech, done: Boolean) {
        main.post {
            if (tts !== from) return@post
            if (stopPending) { speaking = false; main.removeCallbacks(stopWaited); stopWaited.run(); return@post } // 끊은 물음의 신호 — 버린다
            if (android.os.SystemClock.elapsedRealtime() - speakAt < FRESH_MS) { log("TTS 신호 버림(읽기 시작 직후)"); return@post }
            speaking = false
            val cb = onEnd; onEnd = null
            cb?.invoke(done)
        }
    }

    /** 다 읽었으면 [onEnd] 에 true. 끊기거나 실패하면 false — 그때는 이어서 담지 않는다. */
    fun speak(text: String, onEnd: (Boolean) -> Unit) {
        if (tts == null) start()
        if (!ready) { waiting?.second?.invoke(false); waiting = text to onEnd; return }
        if (stopPending) { queued?.second?.invoke(false); queued = text to onEnd; return }
        this.onEnd = onEnd
        speaking = true
        speakAt = android.os.SystemClock.elapsedRealtime()
        try { tts?.speak(text, true) } catch (e: Exception) { log("TTS 예외 $e"); speaking = false; this.onEnd = null; onEnd(false) }
    }

    /** 읽던 것을 멈춘다. 콜백은 부르지 않는다. */
    fun interrupt() {
        waiting = null; onEnd = null; queued = null
        if (speaking && !stopPending) { stopPending = true; main.postDelayed(stopWaited, STOP_WAIT_MS) }
        speaking = false
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun stop() {
        main.removeCallbacks(stopWaited)
        waiting = null; onEnd = null; ready = false; queued = null; stopPending = false; speaking = false
        val t = tts; tts = null
        try { t?.stop() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }

    private companion object {
        const val STOP_WAIT_MS = 700L // 끊은 물음의 신호가 이만큼 안 오면 기다리지 않고 다음 물음을 읽는다
        const val FRESH_MS = 300L
    }
}

/**
 * 담기 — 차 마이크로 한마디를 16kHz 모노 WAV 에 담는다.
 * 손을 쓰지 않아도 끝난다: 말이 있은 뒤 [QUIET_MS] 동안 조용하면 다 말한 것으로 본다.
 * 처음부터 [NO_VOICE_MS] 동안 말이 없으면 답하지 않은 것으로 본다(보내지 않는다).
 */
class Recorder(private val context: Context, private val main: Handler, private val log: (String) -> Unit) {
    enum class End { SAVED, SILENT, FAILED, CANCELLED }

    private class Session { @Volatile var finish = false; @Volatile var trust = false; @Volatile var cancel = false }
    private var session: Session? = null
    val active: Boolean get() = session != null

    /** [onLevel] 은 0~1 의 소리 크기. [onEnd] 는 한 번만 온다. SAVED 일 때만 [out] 이 있다. */
    fun start(out: File, onLevel: (Float) -> Unit, onEnd: (End) -> Unit) {
        if (session != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("녹음 권한 없음"); onEnd(End.FAILED); return
        }
        val s = Session()
        session = s
        // 앱 안에서 하나뿐인 번호 — 화면이 다시 만들어져 Recorder 가 새로 생겨도, 아직 끝나지 않은 지난 녹음과 임시 파일이 겹치지 않게.
        val tmp = File(out.parentFile, out.name + "." + TAKES.incrementAndGet() + ".tmp")
        thread(name = "sanpa-rec") {
            val end = capture(s, out, tmp, onLevel)
            main.post {
                if (session === s) session = null
                onEnd(if (s.cancel) End.CANCELLED else end)
            }
        }
    }

    /**
     * 여기까지만 담는다. [trust] 면 「다 말했어요」를 누른 것 — 누른 사람의 말을 믿어 소리가 작게 담겼어도 보낸다.
     * 아니면(차가 출발해서 끊는 것) 말이 담겼을 때만 보낸다.
     */
    fun finish(trust: Boolean) { session?.let { it.trust = trust; it.finish = true } }

    /** 버린다. */
    fun cancel() { session?.let { synchronized(it) { it.cancel = true } }; session = null }

    private fun capture(s: Session, out: File, tmp: File, onLevel: (Float) -> Unit): End {
        var rec: AudioRecord? = null
        // 다 담긴 뒤에만 [out] 을 바꾼다. 임시 파일은 담을 때마다 따로 — 방금 버린 녹음의 뒷정리가 새 녹음의 파일을 지우지 않게.
        try {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { log("AudioRecord 초기화 실패"); return End.FAILED }
            val block = ShortArray(RATE / 10) // 100ms
            val bytes = ByteBuffer.allocate(block.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            // 바탕 소음: 지금까지 들은 100ms 토막 가운데 조용한 쪽 10% 자리의 크기.
            // 가장 조용한 토막 하나로 잡으면 마이크가 잠깐 끊긴 무음(0)에 끌려가, 소음이 있는 곳에서 영영 「조용해짐」이 안 온다(개발 3 실측).
            val heard = ArrayList<Double>(MAX_SECONDS * 10)
            var floor = 0.0
            var voicedMs = 0; var quietMs = 0; var peak = 0.0; var loudest = 0.0
            var reason = "상한"
            RandomAccessFile(tmp, "rw").use { f ->
                f.setLength(0); f.write(ByteArray(44))
                rec.startRecording()
                while (written < RATE * MAX_SECONDS) {
                    if (s.cancel) return End.CANCELLED
                    if (s.finish) { reason = "누름"; break }
                    val n = rec.read(block, 0, block.size)
                    if (n <= 0) { log("녹음 read=$n"); return End.FAILED }
                    var sumSq = 0.0
                    bytes.clear()
                    for (i in 0 until n) { val v = block[i].toDouble(); sumSq += v * v; bytes.putShort(block[i]) }
                    f.write(bytes.array(), 0, n * 2); written += n
                    val rms = sqrt(sumSq / n)
                    if (rms > peak) peak = rms
                    if (written > RATE / 5) { // 마이크가 열리는 첫 순간은 뺀다
                        val at = heard.binarySearch(rms).let { if (it < 0) -it - 1 else it }
                        heard.add(at, rms)
                        floor = heard[heard.size / 10]
                    }
                    val threshold = (floor * 4).coerceIn(VOICE_MIN, VOICE_MAX)
                    val ms = n * 1000 / RATE
                    // 처음 [LEAD_MS] 는 세지 않는다 — 방금 읽은 물음의 끝자락이 마이크로 되돌아온다(에뮬레이터 실측).
                    // 「조용하다」는 이 사람의 말소리에 견주어 잰다: 가장 컸던 말소리의 [QUIET_RATIO] 아래면 조용한 것.
                    // 문턱만으로 재면 방 안의 작은 소리(기척·바람)에도 조용함이 끊겨 끝이 한참 늦는다(개발 3 실측: 51초).
                    if (written > RATE / 1000 * LEAD_MS) {
                        if (rms > threshold) { voicedMs += ms; if (rms > loudest) loudest = rms }
                        if (rms > maxOf(threshold / 2, loudest * QUIET_RATIO)) quietMs = 0 else quietMs += ms
                    }
                    val level = (rms / (threshold * 4)).coerceIn(0.0, 1.0).toFloat()
                    main.post { if (!s.cancel) onLevel(level) }
                    if (voicedMs >= SPOKE_MS && quietMs >= QUIET_MS) { reason = "조용해짐"; break }
                    if (voicedMs < SPOKE_MS && written >= RATE / 1000 * NO_VOICE_MS) { reason = "말 없음"; break }
                }
                f.seek(0); f.write(wavHeader(written * 2))
            }
            // 다 담았으면 바로 마이크를 놓는다. 보내는 동안까지 쥐고 있지 않는다.
            rec.stop(); rec.release(); rec = null
            val spoke = voicedMs >= SPOKE_MS
            log("녹음 끝($reason): ${written * 1000L / RATE}ms 말 ${voicedMs}ms 바탕 ${floor.toInt()} 최대 ${peak.toInt()}")
            if (written < RATE) return End.SILENT // 1초도 안 된다
            if (!spoke && !(s.finish && s.trust)) return End.SILENT
            // 버리기와 [out] 바꾸기가 엇갈리지 않게 — 버린 녹음은 [out] 을 건드리지 않는다(코덱스 개발 4 R1).
            synchronized(s) {
                if (s.cancel) return End.CANCELLED
                out.delete()
                if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true) }
            }
            return End.SAVED
        } catch (e: Exception) {
            log("녹음 예외 $e")
            return End.FAILED
        } finally {
            try { rec?.release() } catch (_: Exception) {}
            tmp.delete()
        }
    }

    private fun wavHeader(dataLen: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }.array()

    companion object {
        private val TAKES = java.util.concurrent.atomic.AtomicInteger()

        private val swept = java.util.concurrent.atomic.AtomicBoolean()

        /**
         * 녹음·받기 중에 프로세스가 죽으면 남는 임시 파일(원음 `take.wav.N.tmp`, 가족 답 `child-….tmp`)을 지운다.
         * 프로세스마다 처음 한 번만 — 이 프로세스가 지금 쓰는 파일은 건드리지 않는다.
         */
        fun sweep(dir: File) {
            if (!swept.compareAndSet(false, true)) return
            dir.listFiles { f -> f.name.endsWith(".tmp") && (f.name.startsWith("take.wav.") || f.name.startsWith("child-")) }?.forEach { it.delete() }
        }

        const val RATE = 16000
        const val MAX_SECONDS = 60
        // 물음 끝자락(실측 신호 뒤 0.9·1.6초)만으로 「말했다」가 되어 물음 자체를 보낼 수 있었다(코덱스 개발 6 R1).
        // 끝자락 실측 최대보다 조금만 길게 — 길수록 곧바로 한 짧은 답을 못 센다(코덱스 개발 6 R2).
        const val LEAD_MS = 1700
        const val SPOKE_MS = 400      // 이만큼은 소리가 나야 「말했다」
        const val QUIET_MS = 4000     // 말한 뒤 이만큼 조용하면 다 말한 것
        const val QUIET_RATIO = 0.15
        const val NO_VOICE_MS = 12000 // 처음부터 이만큼 말이 없으면 넘어간다
        const val VOICE_MIN = 150.0   // 말소리 문턱(100ms rms)의 아래·위 한계. 바탕 소음의 4배를 이 사이로 자른다.
        const val VOICE_MAX = 6000.0
    }
}

/** 틀기 — 가족의 한마디를 튼다. 음악은 잠깐 낮춘다. */
class Player(private val context: Context, private val log: (String) -> Unit) {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    val playing: Boolean get() = player != null

    enum class End { DONE, INTERRUPTED, ERROR }

    /** 틀기 시작했으면 true(틀 수 없는 파일이면 false). [onEnd] 는 한 번 온다. */
    fun play(file: File, onEnd: (End) -> Unit): Boolean {
        if (!file.exists()) return false
        stop()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am = context.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                // 「낮춰 달라」(CAN_DUCK)도 멈춘다. 말소리(CONTENT_TYPE_SPEECH)는 시스템이 저절로 낮춰 주지 않아서,
                // 그대로 두면 내비 안내와 가족의 목소리가 같은 크기로 겹친다(코덱스 R1). 다음에 다시 튼다.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    log("오디오 포커스 잃음 $change")
                    if (player != null) { stop(); onEnd(End.INTERRUPTED) }
                }
            }.build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            log("오디오 포커스 못 받음 — 틀지 않는다"); onEnd(End.INTERRUPTED); return true
        }
        focus = req
        return try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.path)
            // 오류는 같은 재생에 여러 번 올 수 있다. 이미 내려놓은 재생의 것은 버린다.
            mp.setOnCompletionListener { if (player === mp) { stop(); onEnd(End.DONE) } }
            mp.setOnErrorListener { _, what, extra ->
                if (player === mp) { log("틀기 오류 $what/$extra"); stop(); onEnd(End.ERROR) }
                true
            }
            mp.prepare(); mp.start()
            log("틀기 시작 ${player?.duration}ms")
            true
        } catch (e: Exception) {
            log("틀기 실패 $e"); stop(); false
        }
    }

    fun stop() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}

/**
 * 듣기만 — 같이 탔을 때 말이 오가는지, 잦아들었는지만 잰다. 아무것도 담지 않고 아무 데도 보내지 않는다.
 * 100ms 토막의 소리 크기만 [TalkMeter] 에 넣고 버린다. 숫자는 [start] 가 돌려준 [Run] 에서 읽는다(지난 듣기의 늦은 값이 섞이지 않게).
 */
class Hearer(private val context: Context, private val log: (String) -> Unit) {
    class Run {
        @Volatile var stop = false
        @Volatile var failed = false
        @Volatile var elapsedMs = 0
        @Volatile var voicedMs = 0
        @Volatile var quietMs = 0
    }

    private var run: Run? = null

    /** 마이크를 못 쓰면 null. */
    fun start(): Run? {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("듣기: 녹음 권한 없음"); return null
        }
        val r = Run()
        run = r
        thread(name = "sanpa-hear") { listen(r) }
        return r
    }

    fun stop() { run?.stop = true; run = null }

    private fun listen(r: Run) {
        var rec: AudioRecord? = null
        try {
            val rate = Recorder.RATE
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { log("듣기: AudioRecord 초기화 실패"); r.failed = true; return }
            val block = ShortArray(rate / 10) // 100ms
            val meter = TalkMeter()
            rec.startRecording()
            while (!r.stop) {
                val n = rec.read(block, 0, block.size)
                if (n <= 0) { log("듣기 read=$n"); r.failed = true; return }
                var sumSq = 0.0
                for (i in 0 until n) { val v = block[i].toDouble(); sumSq += v * v }
                if (meter.feed(sqrt(sumSq / n), n * 1000 / rate) && BuildConfig.DEBUG) {
                    log("듣기: ${meter.elapsedMs}ms 말 ${meter.voicedMs} 조용 ${meter.quietMs} 바탕 ${meter.floor.toInt()}")
                }
                r.voicedMs = meter.voicedMs; r.quietMs = meter.quietMs; r.elapsedMs = meter.elapsedMs
            }
        } catch (e: Exception) {
            log("듣기 예외 $e"); r.failed = true
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
        }
    }
}

/**
 * 1초씩 「말이 오간 1초였나」를 센다. 소리 크기 숫자만 받는 순수 계산이다(시험: TalkMeterTest).
 *  - 바탕 소음: 최근 [WINDOW] 토막 가운데 조용한 쪽 10% 자리. 처음부터가 아니라 최근 것만 본다 — 길이 바뀌면 소음도 바뀐다.
 *  - 1초(10토막) 가운데 [LOUD_BLOCKS] 개 이상이 바탕의 [FACTOR] 배를 넘으면 「말이 오간 1초」.
 *    사람 말은 숨·쉼표로 자주 끊겨서, 이어진 소리만 세면 대화를 거의 못 센다(개발 4 실측).
 *    문 닫는 소리·기침 한 번(한두 토막)은 말도 아니고 조용함을 끊지도 않는다(개발 3 실측: 작은 소리마다 끊으면 끝이 한참 늦는다).
 *  - 처음 [LEAD_MS] 는 세지 않는다 — 방금 읽은 물음의 끝자락. TTS 의 「다 읽었다」가 소리보다 먼저 와서
 *    담기([Recorder.LEAD_MS] 1.7초)보다 조금 더 길게 잡는다(개발 4 실측: 신호 뒤 0.9·1.6초에 끝자락이 잡혔다).
 */
class TalkMeter {
    var elapsedMs = 0; private set
    var voicedMs = 0; private set   // 말이 오간 초의 합
    var quietMs = 0; private set    // 마지막으로 말이 오간 뒤 흐른 시간
    var floor = 0.0; private set
    private val window = DoubleArray(WINDOW)
    private var count = 0
    private var idx = 0
    private var loud = 0
    private var blocks = 0

    /** 100ms 토막 하나. 1초가 찼으면 true. */
    fun feed(rms: Double, ms: Int): Boolean {
        elapsedMs += ms
        window[idx] = rms; idx = (idx + 1) % WINDOW; if (count < WINDOW) count++
        if (elapsedMs <= LEAD_MS) return false
        if (!measured) { measured = true; floor = quietest() } // 첫 1초부터 바탕에 견준다. 0 에 견주면 주행 소음도 말로 센다(검사로 잡음).
        if (rms > (floor * FACTOR).coerceIn(Recorder.VOICE_MIN, Recorder.VOICE_MAX)) loud++
        if (++blocks < 10) return false
        if (loud >= LOUD_BLOCKS) { voicedMs += 1000; quietMs = 0 } else quietMs += 1000
        loud = 0; blocks = 0
        floor = quietest()
        return true
    }

    private var measured = false
    private fun quietest(): Double = window.copyOf(count).apply { sort() }[count / 10]

    companion object {
        const val WINDOW = 300   // 30초
        const val LEAD_MS = 2000
        const val LOUD_BLOCKS = 3
        const val FACTOR = 3.0
    }
}
