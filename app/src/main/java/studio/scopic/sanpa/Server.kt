package studio.scopic.sanpa

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** 서버가 아는 이 짝의 지금 상태. id 는 회차 id 다(같은 물음을 다시 물어도 회차는 새로 생긴다). */
data class Remote(val roundId: String?, val text: String?, val parentAt: Long, val childAt: Long, val childV: String? = null)

/** 서버 왕복. 전부 막는 호출이라 화면 스레드에서 부르지 않는다. */
class Server(private val base: String, private val pair: String) {
    private companion object { val V_FORMAT = Regex("[a-z0-9]{1,30}") }

    private fun open(path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 20000
        }

    /** 못 읽으면 null. */
    fun state(): Remote? {
        var c: HttpURLConnection? = null
        return try {
            c = open("/api/state?pair=$pair")
            if (c.responseCode != 200) return null
            val s = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val q = s.optJSONObject("question")
            Remote(
                roundId = q?.optString("id")?.takeIf { it.isNotEmpty() },
                text = q?.optString("text")?.takeIf { it.isNotEmpty() },
                parentAt = s.optJSONObject("parent")?.optLong("at") ?: 0,
                childAt = s.optJSONObject("child")?.optLong("at") ?: 0,
                childV = s.optJSONObject("child")?.optString("v")?.takeIf { V_FORMAT.matches(it) },
            )
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** 차에서 담은 한마디를 올린다(서버 쪽 이름은 parent). 응답 코드를 돌려준다. 닿지 못했으면 0. */
    fun uploadParent(wav: File, roundId: String, text: String): Int {
        var c: HttpURLConnection? = null
        return try {
            c = open("/api/voice?pair=$pair&who=parent&q=$roundId&text=" + URLEncoder.encode(text, "UTF-8")).apply {
                requestMethod = "PUT"; doOutput = true
                setRequestProperty("Content-Type", "audio/wav")
                setFixedLengthStreamingMode(wav.length())
            }
            wav.inputStream().use { i -> c.outputStream.use { i.copyTo(it) } }
            c.responseCode
        } catch (e: Exception) {
            0
        } finally {
            c?.disconnect()
        }
    }

    /**
     * 그 회차의 가족 답(서버 쪽 이름은 child)을 [out] 에 받는다. [v] 는 상태에서 본 판 — 그사이 가족이 다시 녹음해도
     * 상태에서 본 그 답을 받는다(코덱스 개발 6 R1). 임시 파일은 받을 때마다 따로 — 겹친 두 받기가 서로의 파일을 깨지 않게.
     */
    fun downloadChild(roundId: String, v: String?, out: File): Boolean {
        var c: HttpURLConnection? = null
        val tmp = File(out.parentFile, out.name + "." + System.nanoTime() + ".tmp")
        return try {
            c = open("/api/voice?pair=$pair&who=child&q=$roundId" + (v?.let { "&v=$it" } ?: ""))
            if (c.responseCode != 200) return false
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(out)
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
            c?.disconnect()
        }
    }
}
