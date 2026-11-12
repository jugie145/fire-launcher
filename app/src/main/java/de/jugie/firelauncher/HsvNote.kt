package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Naechste HSV-Spiele (aus content.json, Quelle OpenLigaDB) und wann die Nachricht erscheint. */
object Hsv {
    class Match(val kick: Long, val opp: String)
    class Info(val title: String, val opp: String, val line3: String, val glow: Int)

    const val BLUE = 0
    const val VIOLET = 1
    const val RED = 2

    private const val MATCH_LEN = 2 * 60 * 60 * 1000L

    private fun day(t: Long): Long {
        val tz = TimeZone.getDefault()
        return (t + tz.getOffset(t)) / 86400000L
    }

    /**
     * Freitagsspiel: nur Freitag. Samstag: Freitag und Samstag. Sonntag: Freitag bis Sonntag.
     * Alle anderen Tage (englische Woche): nur am Spieltag. Bis etwa zum Abpfiff.
     */
    fun info(list: List<Match>, now: Long): Info? {
        val m = list.filter { now < it.kick + MATCH_LEN }.minByOrNull { it.kick } ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = m.kick
        val before = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> 1
            Calendar.SUNDAY -> 2
            else -> 0
        }
        val kd = day(m.kick)
        val today = day(now)
        if (today < kd - before || today > kd) return null
        val diff = kd - today
        val title: String
        val glow: Int
        when (diff) {
            0L -> { title = "HSV SPIELT HEUTE!"; glow = BLUE }
            1L -> { title = "HSV SPIELT MORGEN!"; glow = VIOLET }
            else -> {
                val wd = SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(m.kick)).uppercase(Locale.GERMANY)
                title = "HSV SPIELT AM $wd!"
                glow = RED
            }
        }
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(m.kick))
        val left = (m.kick - now) / 60000L
        val line3 = when {
            now >= m.kick -> "Läuft gerade · Anstoß war $time Uhr"
            left < 60 -> "Anstoß in " + Math.max(1, left) + " Min. · $time Uhr"
            left < 120 -> "Anstoß in 1 Std. " + (left - 60) + " Min. · $time Uhr"
            else -> "Anstoß: $time Uhr"
        }
        return Info(title, m.opp, line3, glow)
    }
}

/** Mitteilung wie beim iPhone: gleitet oben herein, wird zum Verschwinden kurz gestaucht. */
class HsvNoteView(ctx: Context, private val k: Float) : FrameLayout(ctx) {
    private val glass = ImageView(ctx)
    private val title: TextView
    private val opp: TextView
    private val line3: TextView
    private var anim: ValueAnimator? = null
    var showing = false
        private set
    private val springIn = PathInterpolator(0.2f, 1.25f, 0.35f, 1f)
    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private val easeIn = PathInterpolator(0.45f, 0f, 0.7f, 0.2f)

    private fun px(v: Int): Int = (v * k).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        glass.scaleType = ImageView.ScaleType.FIT_XY
        glass.setImageResource(R.drawable.note_blue)
        addView(glass, LayoutParams(px(740), px(236), Gravity.CENTER))
        val logo = ImageView(ctx)
        logo.setImageResource(R.drawable.logo_hsv)
        logo.scaleType = ImageView.ScaleType.FIT_XY
        addView(logo, LayoutParams(px(88), px(66)).apply {
            leftMargin = px(50 + 32)
            topMargin = px(50 + 35)
        })
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        title = Ui.text(ctx, "", 31f, k, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD))
        opp = Ui.text(ctx, "", 23f, k, 0xE0FFFFFF.toInt())
        line3 = Ui.text(ctx, "", 23f, k, 0xE0FFFFFF.toInt())
        for (t in listOf(title, opp, line3)) {
            t.maxLines = 1
            t.includeFontPadding = true
        }
        col.addView(title)
        col.addView(opp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        col.addView(line3, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        addView(col, LayoutParams(px(640 - 148 - 20), px(136)).apply {
            leftMargin = px(50 + 148)
            topMargin = px(50)
        })
        alpha = 0f
        visibility = View.INVISIBLE
    }

    fun set(i: Hsv.Info) {
        title.text = i.title
        opp.text = "Gegner: " + i.opp
        line3.text = i.line3
        glass.setImageResource(
            when (i.glow) {
                Hsv.VIOLET -> R.drawable.note_violet
                Hsv.RED -> R.drawable.note_red
                else -> R.drawable.note_blue
            }
        )
    }

    fun show() {
        anim?.cancel()
        showing = true
        visibility = View.VISIBLE
        pivotX = width / 2f
        pivotY = px(50).toFloat()
        scaleX = 1f
        scaleY = 1f
        if (!Ui.anims) {
            alpha = 1f
            translationY = 0f
            return
        }
        translationY = -200f * k
        alpha = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 650
        a.interpolator = springIn
        a.addUpdateListener {
            val p = it.animatedValue as Float
            translationY = -200f * k * (1f - p)
            alpha = Math.min(1f, it.animatedFraction * 2f)
        }
        a.start()
        anim = a
    }

    fun hide(animate: Boolean = true) {
        anim?.cancel()
        showing = false
        if (!animate || !Ui.anims) {
            alpha = 0f
            visibility = View.INVISIBLE
            return
        }
        pivotX = width / 2f
        pivotY = px(50).toFloat()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 620
        a.addUpdateListener {
            val f = it.animatedFraction
            if (f < 0.28f) {
                // kurz stauchen
                val e = easeOut.getInterpolation(f / 0.28f)
                translationY = 5f * k * e
                scaleX = 1f - 0.03f * e
                scaleY = 1f - 0.10f * e
                alpha = 1f
            } else {
                // und nach oben weg
                val e = easeIn.getInterpolation((f - 0.28f) / 0.72f)
                translationY = 5f * k + (-205f * k) * e
                scaleX = 0.97f - 0.05f * e
                scaleY = 0.90f + 0.02f * e
                alpha = 1f - e
            }
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!showing) visibility = View.INVISIBLE
            }
        })
        a.start()
        anim = a
    }
}
