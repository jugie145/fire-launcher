package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wave: WaveBackground
    private lateinit var content: LinearLayout
    private lateinit var hero: Hero
    private lateinit var row: LinearLayout
    private lateinit var greet: TextView
    private lateinit var greetBox: View
    private lateinit var wIcon: WeatherIcon
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private var gtaBg: GtaBackground? = null
    private lateinit var countdown: TextView
    private var flick: android.animation.ValueAnimator? = null
    private lateinit var nowCard: NowCard
    private lateinit var search: SearchOverlay
    private lateinit var fSettings: View
    private lateinit var fSleep: View
    private lateinit var fSearch: View
    private lateinit var fApps: View
    private lateinit var sleepLabel: TextView
    private var cardShown = false
    private var spotX = -1f
    private var spotY = -1f
    private var liveGoals = -1
    private var liveOn = false
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var shown: String? = null
    private var spotSet = false
    private var sig = ""
    private var returnToFooter = false
    private var settingsButton: View? = null
    private lateinit var header: View
    private lateinit var bar: View
    private lateinit var clock: RollingClock
    private lateinit var note: HsvNoteView
    private var tiles: List<DockTile> = emptyList()
    private var hsvGames: List<Hsv.Match> = emptyList()
    /** Zeitpunkt (uptime), an dem die HSV-Nachricht zuletzt verschwunden ist; 0 = noch nie */
    private var noteHiddenAt = 0L
    private var resumed = false

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun lp(w: Int, h: Int, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    private fun divider(height: Int, left: Int, right: Int): View {
        val v = View(this)
        v.setBackgroundColor(0x40FFFFFF)
        v.layoutParams = LinearLayout.LayoutParams(Math.max(1, px(2)), px(height)).apply {
            leftMargin = px(left)
            rightMargin = px(right)
        }
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        k = resources.displayMetrics.widthPixels / 1920f
        Ui.load(this)
        Content.appCtx = applicationContext
        sig = Prefs.sig(this)
        favApps = emptyList()
        shown = null
        spotSet = false

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        wave = WaveBackground(this)
        root.addView(wave, FrameLayout.LayoutParams(match, match))
        gtaBg = null
        if (Theme.gta) {
            val g = GtaBackground(this, k)
            gtaBg = g
            root.addView(g, FrameLayout.LayoutParams(match, match))
        }

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(80), px(48), px(80), px(34))
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(match, match))

        // Kopfzeile: Uhr | Datum links, Begruessung exakt mittig, Ort | Wetter rechts
        val header = FrameLayout(this)
        this.header = header
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        // Uhr, deren Ziffern beim Minutenwechsel weiterrollen
        clock = RollingClock(this, 86 * k, if (Theme.gta) Theme.bold else Ui.medium, Prefs.h24(this))
        if (Theme.gta) clock.shadow(0.01f, 4f * k, 4f * k, Theme.SHADOW)
        line.addView(clock)
        line.addView(divider(80, 38, 36))
        val dateCol = LinearLayout(this)
        dateCol.orientation = LinearLayout.VERTICAL
        for (f in arrayOf("EEEE,", "d. MMMM")) {
            val t = TextClock(this)
            t.format24Hour = f
            t.format12Hour = f
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k)
            t.setTextColor(Ui.TEXT_DIM)
            t.includeFontPadding = false
            if (Theme.gta) {
                t.setTextColor(0xFFFFD08A.toInt())
                t.typeface = Theme.bold
                t.isAllCaps = true
                t.letterSpacing = 0.07f
                Neon.shadow(t, k * 0.5f)
            }
            dateCol.addView(t, lp(wrap, wrap, top = if (f == "EEEE,") 0 else 8))
        }
        line.addView(dateCol)
        line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Wetter spiegelbildlich zur Uhr: Ort und Wetterlage zweizeilig | Symbol und grosse Temperatur
        val placeCol = LinearLayout(this)
        placeCol.orientation = LinearLayout.VERTICAL
        placeCol.gravity = Gravity.END
        placeCol.addView(Ui.text(this, "Grabow", 28f, k, Ui.TEXT_DIM))
        wCond = Ui.text(this, "", 28f, k, Ui.TEXT_DIM)
        wCond.maxLines = 1
        placeCol.addView(wCond, lp(wrap, wrap, top = 8))
        line.addView(placeCol)
        line.addView(divider(80, 38, 34))
        wIcon = WeatherIcon(this)
        line.addView(wIcon, LinearLayout.LayoutParams(px(76), px(76)))
        wTemp = Ui.text(this, "–°", 86f, k, if (Theme.gta) 0xFFFFD08A.toInt() else Color.WHITE, if (Theme.gta) Theme.bold else Ui.medium)
        if (Theme.gta) wTemp.setShadowLayer(16f * k, 0f, 0f, 0xB3FF8C50.toInt())
        wTemp.maxLines = 1
        line.addView(wTemp, lp(wrap, wrap, left = 16))
        line.clipChildren = false
        line.clipToPadding = false
        header.clipChildren = false
        header.addView(line, FrameLayout.LayoutParams(match, wrap))
        // Begruessung exakt mittig; im GTA-Design als Leuchtreklame mit Countdown darunter
        val greetBox = LinearLayout(this)
        greetBox.orientation = LinearLayout.VERTICAL
        greetBox.gravity = Gravity.CENTER_HORIZONTAL
        greet = Ui.text(this, "", if (Theme.gta) 44f else 37f, k, Color.WHITE)
        greetBox.addView(greet)
        countdown = Ui.text(this, "", 26f, k, Color.WHITE, Theme.bold)
        countdown.letterSpacing = 0.08f
        countdown.setShadowLayer(12f * k, 0f, 0f, 0xCCFF3FA4.toInt())
        countdown.visibility = if (Theme.gta) View.VISIBLE else View.GONE
        greetBox.addView(countdown, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(2) })
        flick?.cancel()
        flick = null
        if (Theme.gta) {
            Neon.greet(greet, k)
            flick = Neon.flicker(greet)
        }
        greetBox.clipChildren = false
        this.greetBox = greetBox
        header.addView(greetBox, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        content.addView(header)

        // Grosse Flaeche mit wechselnden Inhalten
        hero = Hero(this, k)
        hero.id = View.generateViewId()
        hero.onOpen = { a, item ->
            if (item == null || !Apps.openLink(this, a, item.link)) Apps.launch(this, a)
        }
        // Farben aus dem Inhalt: Farbfleck nimmt die Hauptfarbe des gezeigten Bildes
        hero.onColor = { c ->
            when (Prefs.colorMode(this)) {
                1 -> if (spotX >= 0f && !nowCardFocused()) wave.setSpot(spotX, spotY, c, true)
                2 -> wave.setSpot2(c)
            }
        }
        content.addView(hero, lp(match, px(460), top = 44))

        // App-Leiste
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        row.clipToPadding = false
        content.addView(row, lp(match, px(DockTile.SIZE), top = 62))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Fussleiste: Einstellungen, Sleeptimer | Laeuft gerade (mittig) | Suche, Alle Apps.
        // Laeuft nichts, verteilen sich die vier Knoepfe gleichmaessig ueber die Breite.
        val bar = FrameLayout(this)
        this.bar = bar
        bar.clipChildren = false
        bar.clipToPadding = false
        fSettings = footItem(R.drawable.ic_gear, "Einstellungen") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        settingsButton = fSettings
        sleepLabel = Ui.text(this, "", 23f, k, Ui.TEXT_DIM)
        fSleep = footItem(R.drawable.ic_moon, "", sleepLabel) { cycleSleep() }
        fSearch = footItem(R.drawable.ic_search_dim, "Suche") {
            // waehrend der Suche keine HSV-Nachricht
            if (note.showing) {
                handler.removeCallbacks(noteHide)
                handler.removeCallbacks(noteTick)
                handler.removeCallbacks(liveTick)
                liveOn = false
                note.hide()
                greetAway(false)
                noteHiddenAt = android.os.SystemClock.uptimeMillis()
                handler.removeCallbacks(noteCheck)
                handler.postDelayed(noteCheck, notePauseMs)
            }
            search.open()
        }
        fApps = footItem(R.drawable.ic_grid, "Alle Apps") {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        for (v in listOf(fSettings, fSleep, fSearch, fApps)) {
            v.id = View.generateViewId()
            bar.addView(v, FrameLayout.LayoutParams(px(252), px(64)))
        }
        (fApps.layoutParams as FrameLayout.LayoutParams).leftMargin = px(1508)
        nowCard = NowCard(this, k)
        nowCard.id = View.generateViewId()
        nowCard.visibility = View.GONE
        nowCard.onSel = { on -> cardSelected(on) }
        nowCard.onAction = { a ->
            if (a == "open") {
                val sp = favApps.firstOrNull { Content.kind(it) == "spotify" } ?: Apps.loadAll(this).firstOrNull { Content.kind(it) == "spotify" }
                if (sp != null) Apps.launch(this, sp)
            } else NowPlaying.control(this, a)
        }
        bar.addView(nowCard, FrameLayout.LayoutParams(px(740), px(204)).apply {
            leftMargin = px(560 - 50)
            topMargin = px(-20 - 50)
        })
        cardShown = false
        layoutFooter(false)
        updateSleepLabel()
        content.addView(bar, LinearLayout.LayoutParams(match, px(64)))

        // HSV-Nachricht oben mittig, liegt ueber der Begruessung
        note = HsvNoteView(this, k)
        root.addView(note, FrameLayout.LayoutParams(px(740), px(204), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = px(30 - 50)
        })

        search = SearchOverlay(this, k) { Apps.loadAll(this) }
        search.onClose = { fSearch.requestFocus() }
        root.addView(search, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    /** Knopf der Fussleiste (alle gleich gross): bei Fokus blendet das fertige Glasbild ein. */
    private fun footItem(icon: Int, label: String, textView: TextView? = null, onClick: () -> Unit): View {
        val box = FrameLayout(this)
        box.clipChildren = false
        val glass = ImageView(this)
        glass.setImageResource(R.drawable.glass_pill_settings)
        glass.scaleType = ImageView.ScaleType.FIT_XY
        glass.alpha = 0f
        box.addView(glass, FrameLayout.LayoutParams(px(260), px(72), Gravity.CENTER))
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val ic = ImageView(this)
        ic.setImageResource(icon)
        line.addView(ic, LinearLayout.LayoutParams(px(34), px(34)))
        val t = textView ?: Ui.text(this, label, 23f, k, Ui.TEXT_DIM)
        if (Theme.gta) {
            t.typeface = Theme.bold
            t.isAllCaps = true
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 25f * k)
        }
        t.maxLines = 1
        line.addView(t, lp(wrap, wrap, left = 16))
        box.addView(line, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        box.isFocusable = true
        box.setOnFocusChangeListener { v, has ->
            glass.animate().alpha(if (has) 1f else 0f).setDuration(Ui.d(180)).start()
            t.setTextColor(if (has) Color.WHITE else Ui.TEXT_DIM)
            val s = if (has) 1.05f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(Ui.d(180)).setInterpolator(Ui.smooth).start()
        }
        box.setOnClickListener { onClick() }
        return box
    }

    /** Positionen der Leiste; mit Karte: 0 | 266 | Karte 560-1200 | 1242 | 1508, sonst gleichmaessig. */
    private fun layoutFooter(animate: Boolean) {
        val with = cardShown
        val sx = px(if (with) 266 else 503)
        val qx = px(if (with) 1242 else 1005)
        val delay = if (with) 0L else 180L
        val dur = if (with) 560L else 700L
        val curve = android.view.animation.PathInterpolator(0.2f, 1.15f, 0.35f, 1f)
        for ((v, x) in listOf(fSleep to sx, fSearch to qx)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val from = lp.leftMargin + v.translationX
            lp.leftMargin = x
            v.layoutParams = lp
            v.animate().cancel()
            if (animate && Ui.anims) {
                v.translationX = from - x
                v.animate().translationX(0f).setStartDelay(delay).setDuration(dur).setInterpolator(curve).start()
            } else v.translationX = 0f
        }
        fSettings.nextFocusRightId = fSleep.id
        fSleep.nextFocusLeftId = fSettings.id
        // zur Karte: man landet auf Pause, links/rechts davon voriger/naechster Titel
        fSleep.nextFocusRightId = if (with) nowCard.toggleBtn.id else fSearch.id
        fSearch.nextFocusLeftId = if (with) nowCard.toggleBtn.id else fSleep.id
        fSearch.nextFocusRightId = fApps.id
        fApps.nextFocusLeftId = fSearch.id
        nowCard.coverView.nextFocusLeftId = fSleep.id
        nowCard.nextBtn.nextFocusRightId = fSearch.id
    }

    private fun nowCardFocused() = ::nowCard.isInitialized && nowCard.hasFocus()

    /** Steht man auf "Laeuft gerade": Spotify-Gruen im Hintergrund und oben gross, was laeuft. */
    private fun cardSelected(on: Boolean) {
        val all = favApps
        if (on) {
            nowCard.post {
                val loc = IntArray(2)
                nowCard.getLocationInWindow(loc)
                val cx = loc[0] + nowCard.width / 2f
                val cy = loc[1] + nowCard.height / 2f
                wave.setSpot(cx, cy, if (Theme.gta) Theme.PINK else 0xFF1ED860.toInt(), true, 1f)
            }
            hero.showMix()
        } else {
            // zurueck zur zuletzt gewaehlten App
            val app = all.firstOrNull { it.pkg == shown }
            val tile = tiles.getOrNull(lastFocus)
            if (app != null) {
                hero.show(app)
                if (tile != null) {
                    tile.post {
                        val loc = IntArray(2)
                        tile.getLocationInWindow(loc)
                        val cx = loc[0] + tile.width * tile.scaleX / 2f
                        val cy = loc[1] + tile.height * tile.scaleY / 2f
                        val boost = when (Content.kind(app)) {
                            "prime" -> 1.7f
                            "wow", "disney" -> 2.4f
                            else -> 1f
                        }
                        wave.setSpot(cx, cy, Ui.accentFor(app), true, boost)
                    }
                }
            }
        }
    }

    /** Karte "Laeuft gerade": nur wenn Musik laeuft und nicht schon gross oben (Spotify ausgewaehlt). */
    private fun updateNow(t: Spotify.Track?) {
        hero.nowPlaying(t)
        nowCard.set(t)
        val spotifySel = shown != null && favApps.firstOrNull { it.pkg == shown }?.let { Content.kind(it) == "spotify" } == true
        val want = t != null && !spotifySel
        if (want == cardShown) return
        cardShown = want
        if (want) {
            nowCard.visibility = View.VISIBLE
            if (Ui.anims) {
                nowCard.alpha = 0f
                nowCard.translationY = 200f * k
                nowCard.animate().alpha(1f).translationY(0f).setStartDelay(220).setDuration(650)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 1.25f, 0.35f, 1f)).start()
            }
        } else {
            if (nowCard.hasFocus()) fSearch.requestFocus()
            if (Ui.anims) {
                nowCard.animate().alpha(0f).translationY(200f * k).setStartDelay(0).setDuration(620)
                    .setInterpolator(android.view.animation.PathInterpolator(0.45f, 0f, 0.7f, 0.2f))
                    .withEndAction { if (!cardShown) nowCard.visibility = View.GONE }.start()
            } else nowCard.visibility = View.GONE
        }
        layoutFooter(true)
    }

    private val nowListener: (Spotify.Track?) -> Unit = { t -> updateNow(t) }

    private val sleepSteps = intArrayOf(0, 30, 60, 90, -1)
    private var sleepIdx = 0
    private var sleepEdit = false
    private var sleepMin = 45

    /** OK auf dem Sleeptimer: Aus, 30, 60, 90, Benutzerdefiniert (dann ◀ ▶ einstellen, OK bestaetigt). */
    private fun cycleSleep() {
        if (sleepEdit) {
            sleepEdit = false
            startSleep(sleepMin)
            return
        }
        if (HomeService.sleepRemainingSeconds() <= 0) sleepIdx = 0
        sleepIdx = (sleepIdx + 1) % sleepSteps.size
        val nx = sleepSteps[sleepIdx]
        when {
            nx == -1 -> {
                sleepEdit = true
                updateSleepLabel()
            }
            nx == 0 -> {
                HomeService.cancelSleepTimer()
                updateSleepLabel(0)
            }
            else -> startSleep(nx)
        }
    }

    private fun startSleep(min: Int) {
        if (!HomeService.startSleepTimer(min)) {
            android.widget.Toast.makeText(this, "Für den Sleeptimer muss „Home-Taste umleiten“ in den Einstellungen an sein.", android.widget.Toast.LENGTH_LONG).show()
        }
        updateSleepLabel(min)
    }

    private fun updateSleepLabel(force: Int = -1) {
        if (sleepEdit) {
            sleepLabel.text = "◀  $sleepMin Min.  ▶"
            return
        }
        val left = HomeService.sleepRemainingSeconds()
        sleepLabel.text = when {
            force == 0 -> "Sleeptimer"
            force > 0 -> "Sleep · $force Min."
            left > 0 -> "Sleep · " + ((left + 59) / 60) + " Min."
            else -> "Sleeptimer"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (Theme.gta) {
                val c = Theme.countdown()
                val sp = android.text.SpannableString(if (c == null) "GTA VI  IST DA!" else "GTA VI IN  $c")
                sp.setSpan(android.text.style.ForegroundColorSpan(0xFFFFB36B.toInt()), 0, if (c == null) 6 else 9, 0)
                countdown.text = sp
            }
            // Einstellen abbrechen, wenn man woanders hingeht
            if (sleepEdit && !fSleep.hasFocus()) sleepEdit = false
            updateSleepLabel()
            handler.postDelayed(this, 1000)
        }
    }

    /** Begruessung macht der HSV-Nachricht Platz und kommt danach wieder. */
    private fun greetAway(away: Boolean) {
        greetBox.animate().cancel()
        if (!Ui.anims) {
            greetBox.alpha = if (away) 0f else 1f
            return
        }
        if (away) {
            greetBox.animate().alpha(0f).translationY(22f * k).scaleX(0.92f).scaleY(0.92f).setStartDelay(0).setDuration(300)
                .setInterpolator(android.view.animation.PathInterpolator(0.45f, 0f, 0.7f, 0.2f)).start()
        } else {
            greetBox.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(520).setDuration(620)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 1.25f, 0.35f, 1f)).start()
        }
    }

    /** Schoner selbst starten, wenn eine Weile nichts gedrueckt wurde. */
    private val idleCheck = object : Runnable {
        override fun run() {
            if (resumed && !search.showing && Idle.due(this@MainActivity)) Idle.start(this@MainActivity)
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Idle.touch()
        // Sleeptimer einstellen: ◀ ▶ im Minutentakt, gedrueckt halten wird schneller
        if (sleepEdit && fSleep.hasFocus()) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            if (dir != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val rc = event.repeatCount
                    val step = if (rc > 20) 10 else if (rc > 8) 5 else 1
                    sleepMin = Math.max(1, Math.min(300, sleepMin + dir * step))
                    updateSleepLabel()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    sleepEdit = false
                    sleepIdx = 0
                    updateSleepLabel()
                }
                return true
            }
        }
        // Mediatasten steuern Spotify, solange die Karte ausgewaehlt ist
        if (nowCardFocused() && event.action == KeyEvent.ACTION_DOWN) {
            val a = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "toggle"
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                else -> null
            }
            if (a != null) {
                NowPlaying.control(this, a)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    override fun onResume() {
        super.onResume()
        if (Prefs.redirect(this) && Setup.canWrite(this) && !Setup.isEnabled(this)) Setup.enable(this)
        Saver.apply(this)
        if (sig != Prefs.sig(this)) build()
        wave.start()
        gtaBg?.start()
        NowPlaying.listen(nowListener)
        NowPlaying.start(this)
        updateNow(NowPlaying.track)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Idle.touch()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 20_000L)
        hero.start()
        if (returnToFooter) {
            returnToFooter = false
            settingsButton?.post { settingsButton?.requestFocus() }
        }
        // Einflug: Kopfzeile, grosse Flaeche, Kacheln und Fussleiste nacheinander von unten
        rise(header, 0)
        rise(hero, 110)
        for (i in 0 until row.childCount) rise(row.getChildAt(i), 260L + i * 50L)
        rise(bar, 640)
        clock.start()
        wIcon.live = true
        for (t in tiles) t.resumeEffects()
        resumed = true
        handler.removeCallbacks(noteCheck)
        handler.postDelayed(noteCheck, 1200)
        updateGreeting()
        loadFavs()
        loadWeather()
    }

    override fun onPause() {
        wave.stop()
        gtaBg?.stop()
        NowPlaying.unlisten(nowListener)
        NowPlaying.stop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(liveTick)
        liveOn = false
        hero.stop()
        resumed = false
        clock.stop()
        wIcon.live = false
        for (t in tiles) t.stopEffects()
        handler.removeCallbacks(noteCheck)
        handler.removeCallbacks(noteHide)
        handler.removeCallbacks(noteTick)
        if (note.showing) {
            note.hide(false)
            greetAway(false)
            noteHiddenAt = android.os.SystemClock.uptimeMillis()
        }
        super.onPause()
    }

    private fun updateGreeting() {
        val name = Prefs.name(this)
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        greet.text = when {
            h >= 23 || h < 5 -> "Noch wach, $name?"
            h in 5..10 -> "Guten Morgen, $name."
            dow == Calendar.FRIDAY && h >= 16 -> "Schönes Wochenende, $name."
            h in 11..13 -> "Mahlzeit, $name."
            h in 14..17 -> "Hallo $name."
            else -> "Guten Abend, $name."
        }
    }

    private fun loadFavs() {
        thread {
            val all = Apps.loadAll(this)
            var favs: List<String>? = Prefs.favs(this)
            if (favs == null || Prefs.favsVersion(this) < Prefs.FAVS_VERSION) {
                val merged = ArrayList(Apps.defaultFavs(all))
                favs?.forEach { if (!merged.contains(it) && merged.size < Prefs.MAX_FAVS) merged.add(it) }
                favs = merged
                Prefs.setFavs(this, merged)
            }
            var cur: List<String> = favs
            // Spotify einmalig als siebte App ergaenzen, sobald es installiert ist
            if (!Prefs.flag(this, "spotify_done")) {
                val sp = all.firstOrNull { it.pkg.lowercase().contains("spotify") || it.label.lowercase().startsWith("spotify") }
                if (sp != null) {
                    if (!cur.contains(sp.pkg) && cur.size < Prefs.MAX_FAVS) {
                        cur = cur + sp.pkg
                        Prefs.setFavs(this, cur)
                    }
                    Prefs.setFlag(this, "spotify_done")
                }
            }
            val byPkg = all.associateBy { it.pkg }
            val list = cur.mapNotNull { byPkg[it] }.take(Prefs.MAX_FAVS)
            handler.post { showFavs(list) }
        }
    }

    /** Grosse Flaeche und Farbbereich des Hintergrunds auf die ausgewaehlte App umstellen. */
    private fun select(app: AppEntry, v: View) {
        val accent = Ui.accentFor(app)
        Ui.lastAccent = accent
        v.post {
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val cx = loc[0] + v.width * v.scaleX / 2f
            val cy = loc[1] + v.height * v.scaleY / 2f
            spotX = cx
            spotY = cy
            val boost = when (Content.kind(app)) {
                "prime" -> 1.7f
                "wow", "disney" -> 2.4f
                else -> 1f
            }
            wave.setSpot(cx, cy, accent, spotSet, boost)
            spotSet = true
        }
        // Spiegelungen neigen sich zur ausgewaehlten Kachel
        val sel = tiles.indexOfFirst { it === v }
        if (sel >= 0) tiles.forEachIndexed { i, t ->
            t.setSheen(if (i == sel) 34f else Math.max(-70f, Math.min(70f, (sel - i) * 22f)), i == sel)
        }
        // von der grossen Flaeche mit "Runter" zurueck zu dieser Kachel
        hero.nextFocusDownId = v.id
        if (shown != app.pkg) {
            shown = app.pkg
            hero.show(app)
            updateNow(NowPlaying.track)
        }
    }

    private fun showFavs(list: List<AppEntry>) {
        if (row.childCount > 0 && list.isNotEmpty() && list.map { it.pkg } == favApps.map { it.pkg }) return
        favApps = list
        row.removeAllViews()
        if (list.isEmpty()) {
            shown = null
            row.addView(Ui.text(this, "Unter „Alle Apps“ eine App lange gedrückt halten, um sie hier anzupinnen.", 28f, k, Ui.TEXT_DIM))
            return
        }
        val n = list.size
        val w = px(DockTile.SIZE)
        val h = px(DockTile.SIZE)
        val avail = resources.displayMetrics.widthPixels - px(160)
        val gap = if (n > 1) Math.max(0, Math.min(px(64), (avail - w * n) / (n - 1))) else 0
        val built = ArrayList<DockTile>()
        list.forEachIndexed { i, app ->
            val tile = DockTile(this, app, k)
            tile.onSelect = { v ->
                lastFocus = i
                select(app, v)
            }
            tile.setOnClickListener { v ->
                // Kachel drueckt sich ein, dann zoomt die App aus der Kachel heraus auf
                tile.press {
                    val opts = try {
                        ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
                    } catch (e: Exception) {
                        null
                    }
                    Apps.launch(this, app, opts)
                }
            }
            tile.id = View.generateViewId()
            tile.nextFocusUpId = hero.id
            val cell = FrameLayout(this)
            cell.clipChildren = false
            cell.clipToPadding = false
            cell.addView(tile, FrameLayout.LayoutParams(w, h))
            row.addView(cell, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
            rise(cell, 260L + i * 50L)
            built.add(tile)
        }
        tiles = built
        val idx = if (lastFocus < list.size) lastFocus else 0
        (row.getChildAt(idx) as? FrameLayout)?.getChildAt(0)?.requestFocus()
    }

    /** Weich von unten einschweben (wie beim iPhone nach dem Entsperren). */
    private fun rise(v: View, delay: Long) {
        v.animate().cancel()
        if (!Ui.anims) {
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        v.alpha = 0f
        v.translationY = 44f * k
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(700).setInterpolator(Ui.smooth).start()
    }

    // ---------- HSV-Nachricht: 20 s sichtbar, dann 5 min Pause, nur auf dem Startbildschirm ----------

    private val noteShowMs = 20_000L
    private val notePauseMs = 5 * 60_000L

    private val noteCheck: Runnable = object : Runnable {
        override fun run() = checkNote(this)
    }

    private fun checkNote(self: Runnable) {
        if (Theme.gta || !Prefs.hsvNote(this)) return
        if (::search.isInitialized && search.showing) {
            handler.postDelayed(self, 30_000L)
            return
        }
        Content.loadHsv { games ->
            if (!resumed) return@loadHsv
            hsvGames = games
            val info = Hsv.info(games, System.currentTimeMillis())
            val now = android.os.SystemClock.uptimeMillis()
            if (info == null) {
                // kein Spiel in Sicht: in einer Viertelstunde wieder nachsehen
                handler.postDelayed(self, 15 * 60_000L)
                return@loadHsv
            }
            val wait = if (noteHiddenAt == 0L) 0L else notePauseMs - (now - noteHiddenAt)
            if (wait > 0) {
                handler.postDelayed(self, wait)
                return@loadHsv
            }
            if (info.live && info.match != null) {
                // waehrend des Spiels bleibt die Karte stehen und zeigt den Spielstand
                note.setLive(info, 0, 0, null)
                note.show()
                greetAway(true)
                liveOn = true
                liveGoals = -1
                handler.removeCallbacks(liveTick)
                handler.post(liveTick)
                return@loadHsv
            }
            note.set(info)
            note.show()
            greetAway(true)
            handler.postDelayed(noteTick, 30_000L)
            handler.postDelayed(noteHide, noteShowMs)
        }
    }

    /** Live-Stand jede Minute; bei einem HSV-Tor die Tor-Animation. */
    private val liveTick: Runnable = object : Runnable {
        override fun run() {
            if (!liveOn) return
            val info = Hsv.info(hsvGames, System.currentTimeMillis())
            val m = info?.match
            if (info == null || !info.live || m == null || search.showing) {
                liveOn = false
                note.hide()
                greetAway(false)
                noteHiddenAt = android.os.SystemClock.uptimeMillis()
                handler.postDelayed(noteCheck, notePauseMs)
                return
            }
            Content.loadLive(m) { l ->
                if (!liveOn || l == null) return@loadLive
                note.setLive(info, l.hsv, l.opp, l.lastHsv)
                if (liveGoals >= 0 && l.hsv > liveGoals) note.goal()
                liveGoals = l.hsv
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    /** Countdown aktuell halten, solange die Nachricht steht. */
    private val noteTick: Runnable = object : Runnable {
        override fun run() {
            val info = Hsv.info(hsvGames, System.currentTimeMillis())
            if (info != null && note.showing) {
                note.set(info)
                handler.postDelayed(this, 30_000L)
            }
        }
    }

    private val noteHide: Runnable = Runnable {
        handler.removeCallbacks(noteTick)
        note.hide()
        greetAway(false)
        noteHiddenAt = android.os.SystemClock.uptimeMillis()
        handler.postDelayed(noteCheck, notePauseMs)
    }

    private fun loadWeather() {
        Weather.load {
            val t = Weather.temp ?: return@load
            wIcon.kind = WeatherIcon.kindFor(Weather.code, Weather.day)
            wTemp.text = "$t°"
            wCond.text = Weather.label()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Startbildschirm: Zurueck macht nichts
    }
}
