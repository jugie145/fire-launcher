package de.jugie.firelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

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
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { p }
                map[p] = AppEntry(p, label, banner, icon, ComponentName(p, ai.name), cat)
            }
        }
        return map.values.sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, app: AppEntry) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(i)
        } catch (e: Exception) {
            val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            if (i != null) try { ctx.startActivity(i) } catch (e2: Exception) { }
        }
    }

    private val DEFAULTS = listOf("netflix", "youtube", "disney", "prime video", "twitch", "wow")

    fun defaultFavs(all: List<AppEntry>): List<String> {
        val out = ArrayList<String>()
        for (key in DEFAULTS) {
            val hit = all.firstOrNull {
                val l = it.label.lowercase()
                !out.contains(it.pkg) && (if (key == "wow") (l == "wow" || l.startsWith("wow ")) else l.contains(key))
            }
            if (hit != null) out.add(hit.pkg)
        }
        return out
    }
}

object Prefs {
    const val MAX_FAVS = 6
    private fun sp(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun favs(c: Context): List<String>? =
        sp(c).getString("favs", null)?.split("\n")?.filter { it.isNotBlank() }
    fun setFavs(c: Context, l: List<String>) {
        sp(c).edit().putString("favs", l.joinToString("\n")).apply()
    }
    fun redirect(c: Context): Boolean = sp(c).getBoolean("redirect", true)
    fun setRedirect(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("redirect", v).apply()
    }
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
    const val BG = 0xFF0A0A0C.toInt()
    const val PANEL = 0xFF1C1C21.toInt()
    const val MUTED = 0xFFA1A1AA.toInt()
    const val SOFT = 0xFFE4E4E7.toInt()

    fun round(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun ring(radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(stroke, Color.WHITE)
        }

    /** Dunkle Durchschnittsfarbe eines App-Bildes fuer die grosse Flaeche oben. */
    fun shelfColor(src: Drawable?): Int {
        if (src == null) return PANEL
        return try {
            val d = src.constantState?.newDrawable()?.mutate() ?: return PANEL
            val b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 16, 9)
            d.draw(Canvas(b))
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (x in 0 until 16) for (y in 0 until 9) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
            }
            b.recycle()
            if (n == 0L) return PANEL
            val f = 0.42
            val rr = (r / n * f).toInt(); val gg = (g / n * f).toInt(); val bb = (bl / n * f).toInt()
            if (rr + gg + bb < 40) PANEL else Color.rgb(rr, gg, bb)
        } catch (e: Exception) {
            PANEL
        }
    }

    fun tile(ctx: Context, app: AppEntry, h: Int, k: Float, onFocus: (() -> Unit)? = null): FrameLayout {
        val radius = 22f * k
        val f = FrameLayout(ctx)
        f.background = round(PANEL, radius)
        f.outlineProvider = ViewOutlineProvider.BACKGROUND
        f.clipToOutline = true
        val iv = ImageView(ctx)
        if (app.banner != null) {
            iv.setImageDrawable(app.banner)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            iv.setImageDrawable(app.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (h * 0.2f).toInt()
            iv.setPadding(p, p, p, p)
        }
        f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.isFocusable = true
        f.contentDescription = app.label
        f.elevation = 4f * k
        val ringD = ring(radius, Math.max(2, (4f * k).toInt()))
        f.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            v.elevation = (if (has) 24f else 4f) * k
            v.foreground = if (has) ringD else null
            if (has) onFocus?.invoke()
        }
        return f
    }
}
