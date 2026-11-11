package de.jugie.firelauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val component: ComponentName,
    val category: String
)

object Apps {
    fun loadAll(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val ai = ri.activityInfo ?: continue
                val p = ai.packageName
                if (p == ctx.packageName || map.containsKey(p)) continue
                val banner = try {
                    ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                } catch (e: Exception) { null }
                val icon = try { ai.applicationInfo.loadIcon(pm) } catch (e: Exception) { null }
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { p }
                map[p] = AppEntry(p, label, banner, icon, ComponentName(p, ai.name), cat)
            }
        }
        return map.values.sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, app: AppEntry, opts: Bundle? = null) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(i, opts)
        } catch (e: Exception) {
            val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            if (i != null) try { ctx.startActivity(i) } catch (e2: Exception) { }
        }
    }

    private class Want(val exact: String, val starts: String, val pkg: String)

    /** Standard-Reihenfolge: YouTube, Twitch, Netflix, Prime Video, WOW, Disney+, Spotify */
    private val DEFAULTS = listOf(
        Want("youtube", "youtube", "youtube"),
        Want("twitch", "twitch", "twitch"),
        Want("netflix", "netflix", "netflix"),
        Want("prime video", "prime video", "firebat"),
        Want("wow", "wow ", "wowtv"),
        Want("disney+", "disney", "disney"),
        Want("spotify", "spotify", "spotify")
    )

    fun defaultFavs(all: List<AppEntry>): List<String> {
        val out = ArrayList<String>()
        for (w in DEFAULTS) {
            val free = all.filter { !out.contains(it.pkg) }
            val hit = free.firstOrNull { it.label.lowercase().trim() == w.exact }
                ?: free.firstOrNull { it.label.lowercase().startsWith(w.starts) }
                ?: free.firstOrNull { it.pkg.lowercase().contains(w.pkg) }
            if (hit != null) out.add(hit.pkg)
        }
        return out
    }
}

object Prefs {
    const val MAX_FAVS = 7
    const val FAVS_VERSION = 5
    private fun sp(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun favs(c: Context): List<String>? =
        sp(c).getString("favs", null)?.split("\n")?.filter { it.isNotBlank() }
    fun setFavs(c: Context, l: List<String>) {
        sp(c).edit().putString("favs", l.joinToString("\n")).putInt("favs_v", FAVS_VERSION).apply()
    }
    fun favsVersion(c: Context): Int = sp(c).getInt("favs_v", 0)

    fun flag(c: Context, key: String): Boolean = sp(c).getBoolean(key, false)
    fun setFlag(c: Context, key: String) { sp(c).edit().putBoolean(key, true).apply() }

    fun redirect(c: Context): Boolean = sp(c).getBoolean("redirect", true)
    fun setRedirect(c: Context, v: Boolean) { sp(c).edit().putBoolean("redirect", v).apply() }

    fun bgAnim(c: Context): Boolean = sp(c).getBoolean("bg_anim", true)
    fun setBgAnim(c: Context, v: Boolean) { sp(c).edit().putBoolean("bg_anim", v).apply() }
    fun anims(c: Context): Boolean = sp(c).getBoolean("anims", true)
    fun setAnims(c: Context, v: Boolean) { sp(c).edit().putBoolean("anims", v).apply() }
    fun glass(c: Context): Int = sp(c).getInt("glass", 1)
    fun setGlass(c: Context, v: Int) { sp(c).edit().putInt("glass", v).apply() }
    fun bg(c: Context): Int = sp(c).getInt("bg", 0)
    fun setBg(c: Context, v: Int) { sp(c).edit().putInt("bg", v).apply() }
    fun h24(c: Context): Boolean = sp(c).getBoolean("h24", true)
    fun setH24(c: Context, v: Boolean) { sp(c).edit().putBoolean("h24", v).apply() }
    fun name(c: Context): String = sp(c).getString("name", "Julien") ?: "Julien"

    /** Aendert sich, sobald eine Einstellung geaendert wurde, die den Startbildschirm betrifft. */
    fun sig(c: Context): String = "" + bg(c) + bgAnim(c) + glass(c) + anims(c) + h24(c)
}

object Setup {
    private const val KEY = "enabled_accessibility_services"

    private fun names(c: Context): List<String> {
        val cn = ComponentName(c, HomeService::class.java)
        return listOf(cn.flattenToString(), cn.flattenToShortString())
    }

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun isEnabled(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, KEY) ?: ""
        val n = names(c)
        return s.split(":").any { n.contains(it) }
    }

    fun enable(c: Context): Boolean {
        if (!canWrite(c)) return false
        return try {
            if (!isEnabled(c)) {
                val cur = Settings.Secure.getString(c.contentResolver, KEY) ?: ""
                val next = if (cur.isBlank()) names(c)[0] else cur + ":" + names(c)[0]
                Settings.Secure.putString(c.contentResolver, KEY, next)
            }
            Settings.Secure.putInt(c.contentResolver, "accessibility_enabled", 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disable(c: Context): Boolean {
        if (!canWrite(c)) return false
        return try {
            val n = names(c)
            val cur = Settings.Secure.getString(c.contentResolver, KEY) ?: ""
            val next = cur.split(":").filter { it.isNotBlank() && !n.contains(it) }.joinToString(":")
            Settings.Secure.putString(c.contentResolver, KEY, next)
            true
        } catch (e: Exception) {
            false
        }
    }
}

object Ui {
    // Farbwelt
    const val MIDNIGHT = 0xFF080A12.toInt()
    const val NAVY = 0xFF111B30.toInt()
    const val OCEAN = 0xFF174E72.toInt()
    const val VIOLET = 0xFF49316D.toInt()
    const val CYAN = 0xFF42B9D0.toInt()
    const val BG = MIDNIGHT
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val TEXT_FAINT = 0x80FFFFFF.toInt()

    /** Staerke des Glaseffekts (0.6 dezent, 1.0 normal, 1.45 stark). */
    @Volatile var intensity = 1f
    @Volatile var anims = true
    /** Akzentfarbe der zuletzt ausgewaehlten App, damit Unterseiten denselben Hintergrund zeigen. */
    @Volatile var lastAccent = 0xFFFF0000.toInt()

    val smooth = PathInterpolator(0.22f, 0.9f, 0.3f, 1f)
    val light: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
    val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    fun load(c: Context) {
        intensity = when (Prefs.glass(c)) { 0 -> 0.6f; 2 -> 1.45f; else -> 1f }
        anims = Prefs.anims(c)
    }

    /** Animationsdauer, 0 wenn Animationen ausgeschaltet sind. */
    fun d(ms: Long): Long = if (anims) ms else 0L

    fun text(ctx: Context, s: String, size: Float, k: Float, color: Int, face: Typeface? = null): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k)
            setTextColor(color)
            includeFontPadding = false
            if (face != null) typeface = face
        }

    fun radial(c: Int, size: Int): GradientDrawable =
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = size / 2f
            colors = intArrayOf(c, c and 0x00FFFFFF)
        }

    /** Kleine Glas-Schaltflaeche fuer die Systemleiste. */
    fun pill(ctx: Context, s: String, k: Float, onClick: () -> Unit): TextView {
        val t = text(ctx, s, 22f, k, TEXT_DIM)
        t.setPadding((26 * k).toInt(), (13 * k).toInt(), (26 * k).toInt(), (13 * k).toInt())
        val g = GlassDrawable(999f, k)
        t.background = g
        t.isFocusable = true
        t.setOnFocusChangeListener { v, has ->
            g.boost = if (has) 1f else 0f
            t.setTextColor(if (has) Color.WHITE else TEXT_DIM)
            val s2 = if (has) 1.07f else 1f
            v.animate().scaleX(s2).scaleY(s2).setDuration(d(180)).setInterpolator(smooth).start()
        }
        t.setOnClickListener { onClick() }
        return t
    }

    /** Akzentfarbe einer App: feste Farben fuer die bekannten Dienste, sonst aus dem App-Icon. */
    fun accentFor(app: AppEntry): Int {
        val l = app.label.lowercase()
        val p = app.pkg.lowercase()
        return when {
            l.contains("spotify") || p.contains("spotify") -> 0xFF1ED860.toInt()
            l.contains("youtube") || p.contains("youtube") -> 0xFFFF0000.toInt()
            l.contains("twitch") || p.contains("twitch") -> 0xFF9246FF.toInt()
            l.contains("netflix") || p.contains("netflix") -> 0xFFE50000.toInt()
            l.startsWith("prime video") || p.contains("firebat") -> 0xFF2896D3.toInt()
            l.contains("disney") || p.contains("disney") -> 0xFF31BADD.toInt()
            l == "wow" || l.startsWith("wow ") -> 0xFF02AAB2.toInt()
            else -> iconColor(app.icon ?: app.banner)
        }
    }

    /** Mitgeliefertes Logo fuer die bekannten Dienste, 0 = Icon der App verwenden. */
    fun logoRes(app: AppEntry): Int {
        val l = app.label.lowercase().trim()
        val p = app.pkg.lowercase()
        return when {
            l.contains("spotify") || p.contains("spotify") -> R.drawable.logo_spotify
            l == "youtube" || p.endsWith(".youtube") -> R.drawable.logo_youtube
            l.contains("twitch") || p.contains("twitch") -> R.drawable.logo_twitch
            l.contains("netflix") || p.contains("netflix") -> R.drawable.logo_netflix
            l.startsWith("prime video") || p.contains("firebat") -> R.drawable.logo_prime
            l == "wow" || l.startsWith("wow ") -> R.drawable.logo_wow
            l.contains("disney") || p.contains("disney") -> R.drawable.logo_disney
            else -> 0
        }
    }

    fun descFor(app: AppEntry): String {
        val l = app.label.lowercase()
        val p = app.pkg.lowercase()
        return when {
            l.contains("spotify") || p.contains("spotify") -> "Deine Musik. Jederzeit."
            l.contains("youtube") || p.contains("youtube") -> "Deine Lieblingsvideos – jederzeit und überall."
            l.contains("twitch") || p.contains("twitch") -> "Livestreams, Gaming und mehr."
            l.contains("netflix") || p.contains("netflix") -> "Filme und Serien ohne Ende."
            l.startsWith("prime video") || p.contains("firebat") -> "Filme, Serien und Sport."
            l == "wow" || l.startsWith("wow ") -> "Serien, Filme und Live-Sport."
            l.contains("disney") || p.contains("disney") -> "Geschichten, die begeistern."
            else -> "OK drücken zum Öffnen."
        }
    }

    /** Kraeftige Farbe aus einem App-Icon; bei farblosen Icons ein ruhiges Violettblau. */
    private fun iconColor(src: Drawable?): Int {
        val fallback = 0xFF6F7BFF.toInt()
        if (src == null) return fallback
        return try {
            val d = src.constantState?.newDrawable()?.mutate() ?: return fallback
            val b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 12, 12)
            d.draw(Canvas(b))
            var r = 0f; var g = 0f; var bl = 0f; var n = 0f
            val hsv = FloatArray(3)
            for (x in 0 until 12) for (y in 0 until 12) {
                val px = b.getPixel(x, y)
                if (Color.alpha(px) < 128) continue
                Color.colorToHSV(px, hsv)
                val w = hsv[1] * hsv[2]
                r += Color.red(px) * w; g += Color.green(px) * w; bl += Color.blue(px) * w; n += w
            }
            b.recycle()
            if (n < 4f) return fallback
            Color.colorToHSV(Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt()), hsv)
            if (hsv[1] < 0.25f) return fallback
            hsv[1] = Math.max(hsv[1], 0.65f)
            hsv[2] = 0.95f
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * Glaskachel mit dem Original-Icon der App, optional mit Name darunter.
     * Bei Fokus: 112 %, leicht angehoben, Rahmen und Lichtschein in der Akzentfarbe.
     */
    fun glassTile(
        ctx: Context, app: AppEntry, w: Int, h: Int, k: Float,
        showLabel: Boolean, onFocus: (() -> Unit)? = null
    ): FrameLayout {
        val f = FrameLayout(ctx)
        f.clipChildren = false
        f.clipToPadding = false

        val shW = (w * 1.25f).toInt()
        val shadow = View(ctx)
        shadow.background = radial(0xA6000000.toInt(), shW)
        shadow.translationY = h * 0.2f
        shadow.scaleY = 0.82f
        shadow.alpha = 0.55f
        f.addView(shadow, FrameLayout.LayoutParams(shW, shW, Gravity.CENTER))

        val glSize = (w * 1.85f).toInt()
        val glow = View(ctx)
        glow.background = radial(0x30FFFFFF, glSize)
        glow.alpha = 0f
        f.addView(glow, FrameLayout.LayoutParams(glSize, glSize, Gravity.CENTER))

        val glass = GlassDrawable(Math.min(w, h) * 0.2f, k)
        val panel = View(ctx)
        panel.background = glass
        f.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val logo = logoRes(app)
        val icH = (h * (if (showLabel) 0.47f else 0.56f)).toInt()
        if (logo != 0) {
            // freigestelltes Logo direkt auf dem Glas
            iv.setImageResource(logo)
            col.addView(iv, LinearLayout.LayoutParams((w * 0.6f).toInt(), icH))
        } else {
            iv.setImageDrawable(app.icon ?: app.banner)
            iv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, v.width * 0.23f)
                }
            }
            iv.clipToOutline = true
            col.addView(iv, LinearLayout.LayoutParams(icH, icH))
        }
        if (showLabel) {
            val name = text(ctx, app.label, 23f, k, Color.WHITE, medium)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(name, LinearLayout.LayoutParams(w - (24 * k).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (h * 0.075f).toInt() })
        }
        f.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        f.isFocusable = true
        f.contentDescription = app.label
        var boostAnim: ValueAnimator? = null
        f.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.12f else 1f
            v.animate().scaleX(s).scaleY(s).translationY(if (has) -8f * k else 0f)
                .setDuration(d(300)).setInterpolator(smooth).start()
            glow.animate().alpha(if (has) 1f else 0f).setDuration(d(300)).start()
            shadow.animate().alpha(if (has) 1f else 0.55f).translationY(h * (if (has) 0.27f else 0.2f)).setDuration(d(300)).start()
            boostAnim?.cancel()
            val target = if (has) 1f else 0f
            if (anims) {
                val a = ValueAnimator.ofFloat(glass.boost, target)
                a.duration = 300
                a.addUpdateListener { glass.boost = it.animatedValue as Float }
                a.start()
                boostAnim = a
            } else {
                glass.boost = target
            }
            if (has) onFocus?.invoke()
        }
        return f
    }

    /**
     * Kachel des Startbildschirms: fertige Glasbilder (normal / ausgewaehlt), Logo und Name.
     * Bei Fokus blendet das hellere Glas ein, die Kachel waechst auf 108 % und hebt sich,
     * und einmal zieht ein Lichtstreif darueber.
     */
    fun imageTile(ctx: Context, app: AppEntry, w: Int, h: Int, k: Float, onFocus: ((View) -> Unit)?): FrameLayout {
        val f = FrameLayout(ctx)
        f.clipChildren = false
        f.clipToPadding = false
        val gw = (347 * k).toInt()
        val gh = (324 * k).toInt()
        val normal = ImageView(ctx)
        normal.setImageResource(R.drawable.glass_tile)
        normal.scaleType = ImageView.ScaleType.FIT_XY
        f.addView(normal, FrameLayout.LayoutParams(gw, gh, Gravity.CENTER))
        val lit = ImageView(ctx)
        lit.setImageResource(R.drawable.glass_tile_focus)
        lit.scaleType = ImageView.ScaleType.FIT_XY
        lit.alpha = 0f
        f.addView(lit, FrameLayout.LayoutParams(gw, gh, Gravity.CENTER))

        val clip = FrameLayout(ctx)
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 41f * k)
            }
        }
        clip.clipToOutline = true
        val streak = View(ctx)
        streak.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF))
        streak.alpha = 0f
        clip.addView(streak, FrameLayout.LayoutParams((w * 0.45f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(clip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val box = FrameLayout(ctx)
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val logo = logoRes(app)
        if (logo != 0) {
            iv.setImageResource(logo)
            box.addView(iv, FrameLayout.LayoutParams((140 * k).toInt(), (91 * k).toInt(), Gravity.CENTER))
        } else {
            iv.setImageDrawable(app.icon ?: app.banner)
            iv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, v.width * 0.23f)
                }
            }
            iv.clipToOutline = true
            box.addView(iv, FrameLayout.LayoutParams((90 * k).toInt(), (90 * k).toInt(), Gravity.CENTER))
        }
        col.addView(box, LinearLayout.LayoutParams((150 * k).toInt(), (96 * k).toInt()))
        val name = text(ctx, app.label, 23f, k, Color.WHITE, medium)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(name, LinearLayout.LayoutParams(w - (24 * k).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (15 * k).toInt() })
        f.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        f.isFocusable = true
        f.contentDescription = app.label
        f.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).translationY(if (has) -8f * k else 0f)
                .setDuration(d(300)).setInterpolator(smooth).start()
            lit.animate().alpha(if (has) 1f else 0f).setDuration(d(300)).start()
            normal.animate().alpha(if (has) 0f else 1f).setDuration(d(300)).start()
            streak.animate().cancel()
            if (has && anims) {
                streak.translationX = -w * 0.6f
                streak.alpha = 1f
                streak.animate().translationX(w * 1.2f).setStartDelay(80).setDuration(750)
                    .withEndAction { streak.alpha = 0f }.start()
            } else {
                streak.alpha = 0f
            }
            if (has) onFocus?.invoke(v)
        }
        return f
    }

    /** Weiches Einblenden, nacheinander versetzt. */
    fun fadeIn(v: View, delay: Long) {
        if (!anims) return
        v.alpha = 0f
        val a = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f)
        a.startDelay = delay
        a.duration = 420
        a.interpolator = smooth
        a.start()
    }
}
