package app.oddech.fala

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Kółko oddechowe: rośnie podczas wdechu, maleje podczas wydechu.
 * Faza pochodzi z tego samego generatora, który gra dźwięk, więc
 * animacja jest zsynchronizowana z falą.
 */
class BreathView(context: Context) : View(context) {

    /** Faza cyklu 0..1 (0 - 0,5 wdech, 0,5 - 1 wydech) albo null, gdy metronom nie gra. */
    var phaseProvider: () -> Double? = { null }

    private var shown = 0f // 0 = najmniejsze koło, 1 = największe
    private var looping = false
    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#C81E88E5")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#1E88E5")
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#66888888")
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun startLoop() {
        looping = true
        postInvalidateOnAnimation()
    }

    fun stopLoop() {
        looping = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val phase = phaseProvider()
        val target: Float
        val label: String
        if (phase == null) {
            target = 0f
            label = ""
        } else {
            target = (0.5 - 0.5 * cos(2.0 * PI * phase)).toFloat()
            label = if (phase < 0.5) "Wdech" else "Wydech"
        }
        // Lekkie wygładzenie; po zatrzymaniu koło wolno wraca do najmniejszego rozmiaru.
        shown += (target - shown) * (if (phase == null) 0.08f else 0.4f)

        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f - 6f * density
        if (maxR > 0f) {
            val minR = maxR * 0.35f
            val r = minR + (maxR - minR) * shown

            canvas.drawCircle(cx, cy, maxR, guide)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            if (label.isNotEmpty()) {
                canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2f, text)
            }
        }

        if (looping) postInvalidateOnAnimation()
    }
}
