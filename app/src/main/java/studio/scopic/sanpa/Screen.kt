package studio.scopic.sanpa

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 한 화면에 보일 것. 화면에는 늘 물음 하나와 한 줄, 누를 것은 많아야 둘. */
data class Scene(
    val title: String,
    val note: String = "",
    val label: String? = null,
    val listening: Boolean = false,
    val primary: Pair<String, () -> Unit>? = null,
    val secondary: Pair<String, () -> Unit>? = null,
    val pair: (() -> Unit)? = null,
    val pick: (() -> Unit)? = null,
    /** 달리는 중에도 [primary] 를 남긴다 — 같이 탔을 때의 「다른 질문」 하나뿐. */
    val driving: Boolean = false,
)

/**
 * 화면. 어두운 바탕에 물음 하나를 크게 둔다.
 * 치수는 dp 가 아니라 화면 짧은 변에 대한 비율이다 — 차마다 화면 규격과 밀도가 달라서(Pleos 에뮬레이터는 2560×1440·160dpi).
 */
class Screen(private val activity: Activity) {
    private val side = activity.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
    private fun u(v: Int): Int = (v * side / 1440f).toInt()

    val root = FrameLayout(activity).apply { setBackgroundColor(CANVAS); fitsSystemWindows = true }
    private val pad = FrameLayout(activity).apply { setPadding(u(120), u(96), u(120), u(96)) }

    private val mark = text(32, INK_3, 500).apply { text = "산파" }
    private val pairLink = text(32, INK_2, 500).apply { text = "가족 연결"; setPadding(u(32), u(24), 0, u(24)) }
    private val pickLink = text(32, INK_2, 500).apply { text = "질문 고르기"; setPadding(u(32), u(24), u(32), u(24)) }
    private val links = LinearLayout(activity).apply { addView(pickLink); addView(pairLink) }
    private val dot = View(activity).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DANGER) }
    }
    private val label = text(36, INK_2, 500)
    private val labelRow = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LinearLayout.LayoutParams(u(20), u(20)).apply { rightMargin = u(20) })
        addView(label)
    }
    private val title = text(88, INK, 700).apply { setLineSpacing(0f, 1.25f) }
    private val note = text(40, INK_2, 400).apply { setLineSpacing(0f, 1.5f) }
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(labelRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = u(32) })
        // 너비를 WRAP 으로 못 박는다. 세로 LinearLayout 의 기본값(MATCH_PARENT)이면 긴 물음이 아랫줄 너비에 맞춰 잘린다.
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(note, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(40) })
    }
    private val primary = button(filled = true)
    private val secondary = button(filled = false)
    private val buttons = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(secondary, LinearLayout.LayoutParams(WRAP_CONTENT, u(128)).apply { rightMargin = u(24) })
        addView(primary, LinearLayout.LayoutParams(WRAP_CONTENT, u(128)))
    }

    // 가족 연결
    private val qr = ImageView(activity)
    private val pairClose = button(filled = true).apply { text = "닫기" }
    private val pairLayer = FrameLayout(activity).apply {
        visibility = View.GONE
        val words = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(88, INK, 700).apply { text = "가족 연결" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(text(40, INK_2, 400).apply {
                setLineSpacing(0f, 1.5f)
                text = "가족의 휴대전화 카메라로 옆의 그림을 비춰 주세요.\n혼자 탔을 때 남긴 한마디를 그 화면에서 듣고 답할 수 있어요."
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(40) })
        }
        addView(words, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        val card = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = u(32).toFloat() }
            setPadding(u(40), u(40), u(40), u(40))
            addView(qr, FrameLayout.LayoutParams(u(480), u(480)))
        }
        addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { bottomMargin = u(80) })
        addView(pairClose, FrameLayout.LayoutParams(WRAP_CONTENT, u(128), Gravity.END or Gravity.BOTTOM))
    }
    private val mainLayer = FrameLayout(activity)

    // 질문 고르기 — 차가 알아서(하나) 또는 범주 2×3(여러 개) + 난이도 셋(하나). 누르는 대로 저장한다.
    private val catCells = List(6) { cell() }
    private val levelCells = Level.entries.map { cell().apply { text = it.label } }
    private val autoCell = cell().apply { text = "차가 알아서" }
    private val pickDone = button(filled = true).apply { text = "다 골랐어요" }
    private val pickLayer = FrameLayout(activity).apply {
        visibility = View.GONE
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val head = FrameLayout(activity).apply {
                addView(text(88, INK, 700).apply { text = "어떤 질문을 드릴까요?" },
                    FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
                addView(autoCell, FrameLayout.LayoutParams(u(440), u(128), Gravity.END or Gravity.CENTER_VERTICAL))
            }
            addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(text(36, INK_2, 500).apply { text = "주제" },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(72); bottomMargin = u(24) })
            for (r in 0 until 2) addView(row(catCells.subList(r * 3, r * 3 + 3)),
                LinearLayout.LayoutParams(MATCH_PARENT, u(128)).apply { if (r > 0) topMargin = u(24) })
            addView(text(36, INK_2, 500).apply { text = "단계" },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(64); bottomMargin = u(24) })
            addView(row(levelCells), LinearLayout.LayoutParams(MATCH_PARENT, u(128)))
        }
        addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        addView(pickDone, FrameLayout.LayoutParams(WRAP_CONTENT, u(128), Gravity.END or Gravity.BOTTOM))
    }

    private var shown: Triple<String, String, String?>? = null
    private var qrFor: String? = null

    init {
        mainLayer.addView(mark, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP))
        mainLayer.addView(links, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { topMargin = -u(24) })
        mainLayer.addView(body, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        mainLayer.addView(buttons, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.BOTTOM))
        pad.addView(mainLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pad.addView(pairLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pad.addView(pickLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(pad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun show(s: Scene) {
        pairLayer.visibility = View.GONE
        pickLayer.visibility = View.GONE
        mainLayer.visibility = View.VISIBLE
        // 글이 바뀔 때만 짧게 떠오른다. 같은 화면을 다시 그릴 때는 가만히 둔다.
        val key = Triple(s.title, s.note, s.label)
        if (key != shown) {
            shown = key
            body.animate().cancel()
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(200).start()
        }
        title.text = s.title
        note.text = s.note
        note.visibility = if (s.note.isEmpty()) View.GONE else View.VISIBLE
        label.text = s.label ?: ""
        labelRow.visibility = if (s.label == null) View.GONE else View.VISIBLE
        dot.visibility = if (s.listening) View.VISIBLE else View.GONE
        if (!s.listening) dot.alpha = 1f
        bind(primary, s.primary)
        bind(secondary, s.secondary)
        pairLink.visibility = if (s.pair == null) View.GONE else View.VISIBLE
        pairLink.setOnClickListener(s.pair?.let { f -> View.OnClickListener { f() } })
        pickLink.visibility = if (s.pick == null) View.GONE else View.VISIBLE
        pickLink.setOnClickListener(s.pick?.let { f -> View.OnClickListener { f() } })
    }

    /** 질문 고르기. 범주는 마지막 하나를 끌 수 없다. */
    fun showPicker(start: Choice, names: List<String>, onChange: (Choice) -> Unit, onClose: () -> Unit) {
        var c = start
        fun paint() {
            // 차가 알아서 고르는 동안은 손으로 고른 칸을 비워 둔다. 칸을 누르면 손으로 고르기로 돌아온다.
            mark(autoCell, c.auto)
            catCells.forEachIndexed { i, v -> mark(v, !c.auto && i in c.categories) }
            levelCells.forEachIndexed { i, v -> mark(v, !c.auto && Level.entries[i] == c.level) }
        }
        autoCell.setOnClickListener { c = c.copy(auto = !c.auto); onChange(c); paint() }
        catCells.forEachIndexed { i, v ->
            v.text = names.getOrElse(i) { "" }
            v.setOnClickListener {
                // 알아서 고르다가 칸을 누르면 그 칸 하나에서 시작한다.
                val next = if (c.auto) setOf(i) else if (i in c.categories) c.categories - i else c.categories + i
                if (next.isNotEmpty()) { c = c.copy(categories = next, auto = false); onChange(c); paint() }
            }
        }
        levelCells.forEachIndexed { i, v -> v.setOnClickListener { c = c.copy(level = Level.entries[i], auto = false); onChange(c); paint() } }
        pickDone.setOnClickListener { onClose() }
        paint()
        mainLayer.visibility = View.GONE
        pairLayer.visibility = View.GONE
        pickLayer.visibility = View.VISIBLE
        shown = null
    }

    private fun row(cells: List<View>) = LinearLayout(activity).apply {
        cells.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = u(24) }) }
    }

    /** 고르는 칸. 고른 것은 잉크로 채우고(버튼과 같은 말), 안 고른 것은 한 단 뜬 바탕. */
    private fun cell() = text(40, INK_2, 600).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = u(32).toFloat(); setColor(RAISED) }
    }

    private fun mark(v: TextView, on: Boolean) {
        (v.background as GradientDrawable).setColor(if (on) INK else RAISED)
        v.setTextColor(if (on) CANVAS else INK_2)
        v.isSelected = on
    }

    /** 듣는 중의 소리 크기(0~1). 점이 말소리를 따라 밝아진다. */
    fun level(v: Float) { dot.alpha = 0.3f + 0.7f * v }

    fun showPair(url: String, onClose: () -> Unit) {
        if (qrFor != url) {
            qrFor = url
            qr.setImageDrawable(BitmapDrawable(activity.resources, qrBitmap(url)).apply { isFilterBitmap = false })
        }
        pairClose.setOnClickListener { onClose() }
        pickLayer.visibility = View.GONE
        mainLayer.visibility = View.GONE
        pairLayer.visibility = View.VISIBLE
        shown = null
    }

    private fun qrBitmap(content: String): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    private fun bind(b: TextView, action: Pair<String, () -> Unit>?) {
        b.visibility = if (action == null) View.GONE else View.VISIBLE
        b.text = action?.first ?: ""
        b.setOnClickListener(action?.let { a -> View.OnClickListener { a.second() } })
    }

    private fun text(size: Int, color: Int, weight: Int) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        letterSpacing = -0.01f
        includeFontPadding = false
    }

    private fun button(filled: Boolean) = text(40, if (filled) CANVAS else INK, 600).apply {
        gravity = Gravity.CENTER
        minWidth = u(320)
        setPadding(u(64), 0, u(64), 0)
        background = GradientDrawable().apply {
            cornerRadius = u(32).toFloat()
            if (filled) setColor(INK) else setColor(RAISED)
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    private companion object {
        val CANVAS = Color.parseColor("#151517")
        val RAISED = Color.parseColor("#242428")
        val INK = Color.parseColor("#F2F2F4")
        val INK_2 = Color.parseColor("#9A9AA1")
        val INK_3 = Color.parseColor("#6E6E73")
        val DANGER = Color.parseColor("#F04452")
    }
}
