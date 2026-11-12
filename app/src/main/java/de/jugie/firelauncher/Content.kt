package de.jugie.firelauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class HeroItem(
    val title: String,
    val meta: String,
    val desc: String,
    val images: List<String>,
    val square: Boolean = false,
    /** Direktsprung in die App, z. B. die Adresse eines Videos. Leer = nur App oeffnen. */
    val link: String = "",
    /** Eigene Ueberschrift fuer diesen Eintrag, z. B. "Trends in Deutschland". Leer = Ueberschrift der Reihe. */
    val label: String = ""
)

class HeroData(val label: String, val source: String, val items: List<HeroItem>)

/** Kleiner Bildlader mit Zwischenspeicher. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun cached(urls: List<String>): Bitmap? = if (urls.isEmpty()) null else cache.get(urls[0])

    /** Grosses Bild fuer den Bildschirmschoner, mindestens [minW] breit, ohne Zwischenspeicher. */
    fun loadBig(urls: List<String>, minW: Int, cb: (Bitmap?) -> Unit) {
        if (urls.isEmpty()) {
            cb(null)
            return
        }
        pool.execute {
            var b: Bitmap? = null
            for (u in urls) {
                b = fetch(u, minW)
                if (b != null) break
            }
            val res = b
            main.post { cb(res) }
        }
    }

    /** Probiert die Adressen der Reihe nach; das Ergebnis kommt im Hauptthread zurueck. */
    fun load(urls: List<String>, cb: (Bitmap?) -> Unit) {
        if (urls.isEmpty()) {
            cb(null)
            return
        }
        val hit = cache.get(urls[0])
        if (hit != null) {
            cb(hit)
            return
        }
        pool.execute {
            var b: Bitmap? = null
            for (u in urls) {
                b = fetch(u)
                if (b != null) break
            }
            val res = b
            if (res != null) cache.put(urls[0], res)
            main.post { cb(res) }
        }
    }

    private fun fetch(u: String, minW: Int = 1280): Bitmap? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "LiquidTV/1.0")
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth < 200) return null
            var sample = 1
            while (o.outWidth / (sample * 2) >= minW) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
        } catch (e: Throwable) {
            null
        }
    }
}

/** Inhalte fuer die grosse Flaeche, je nach ausgewaehlter App. */
object Content {
    /** YouTube-Kanaele (Name, Kanal-ID). */
    private val CHANNELS = listOf(
        "Trymacs" to "UC6Gc4KQ1ueDnh8x7plaAD3w",
        "Beam" to "UCp56TyePXVBOaMIsCl1dYyQ",
        "Eligella" to "UChhbR4PloyFZPhyW4iVNuEQ",
        "SecondTry" to "UCkeWXiGjQPK972Bx_t9e5Ug",
        "TryReact" to "UC267Hlq_ACFuWzZYfazrDGQ",
        "tomatolix" to "UCNCktfoFAXtXnMlhjyc9SPA"
    )

    private val cache = HashMap<String, Pair<Long, HeroData>>()
    /** fuer persoenliche Spotify-Vorschlaege */
    @Volatile var appCtx: android.content.Context? = null
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun kind(app: AppEntry): String {
        val l = app.label.lowercase().trim()
        val p = app.pkg.lowercase()
        return when {
            l.contains("spotify") || p.contains("spotify") -> "spotify"
            l == "youtube" || p.endsWith(".youtube") -> "youtube"
            l.contains("twitch") || p.contains("twitch") -> "twitch"
            l.contains("netflix") || p.contains("netflix") -> "netflix"
            l.startsWith("prime video") || p.contains("firebat") -> "prime"
            l == "wow" || l.startsWith("wow ") -> "wow"
            l.contains("disney") || p.contains("disney") -> "disney"
            else -> ""
        }
    }

    /** Naechste HSV-Spiele aus content.json. */
    fun loadHsv(cb: (List<Hsv.Match>) -> Unit) {
        pool.execute {
            val out = ArrayList<Hsv.Match>()
            try {
                val arr = remote()?.optJSONArray("hsv")
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val utc = o.optString("utc", "").take(19)
                    val t = try { fmt.parse(utc)?.time } catch (e: Exception) { null } ?: continue
                    out.add(Hsv.Match(t, o.optString("opp", ""), o.optLong("id", 0), o.optBoolean("home", true)))
                }
            } catch (e: Throwable) {
            }
            main.post { cb(out) }
        }
    }

    /** Live-Stand eines HSV-Spiels: Tore HSV, Tore Gegner, letztes Tor fuer den HSV (null = keins). */
    class Live(val hsv: Int, val opp: Int, val lastHsv: Boolean?)

    fun loadLive(m: Hsv.Match, cb: (Live?) -> Unit) {
        if (m.id <= 0) {
            cb(null)
            return
        }
        pool.execute {
            var r: Live? = null
            try {
                val o = JSONObject(get("https://api.openligadb.de/getmatchdata/" + m.id) ?: "")
                val goals = o.optJSONArray("goals")
                var g1 = 0
                var g2 = 0
                var lastHsv: Boolean? = null
                if (goals != null) for (i in 0 until goals.length()) {
                    val g = goals.getJSONObject(i)
                    val n1 = g.optInt("scoreTeam1", g1)
                    val n2 = g.optInt("scoreTeam2", g2)
                    val team1 = n1 > g1
                    g1 = n1
                    g2 = n2
                    lastHsv = team1 == m.home
                }
                r = if (m.home) Live(g1, g2, lastHsv) else Live(g2, g1, lastHsv)
            } catch (e: Throwable) {
            }
            main.post { cb(r) }
        }
    }

    class SaverItem(val kind: String, val item: HeroItem)

    /** Filme und Serien aller Anbieter fuer den Bildschirmschoner, abwechselnd und ohne doppelte Titel. */
    fun loadSaver(cb: (List<SaverItem>) -> Unit) {
        pool.execute {
            val lists = ArrayList<List<SaverItem>>()
            for (k in listOf("netflix", "prime", "wow", "disney", "kino")) {
                val d = try { section(k) } catch (e: Throwable) { null } ?: continue
                lists.add(d.items.filter { it.images.isNotEmpty() }.shuffled().map { SaverItem(k, it) })
            }
            val out = ArrayList<SaverItem>()
            val seen = HashSet<String>()
            var i = 0
            while (true) {
                var any = false
                for (l in lists) {
                    if (i < l.size) {
                        any = true
                        if (seen.add(l[i].item.title.lowercase())) out.add(l[i])
                    }
                }
                if (!any) break
                i++
            }
            main.post { cb(out) }
        }
    }

    /** Liefert null, wenn es fuer die App (noch) keine Inhalte gibt. */
    fun load(app: AppEntry, cb: (HeroData?) -> Unit) {
        val k = kind(app)
        if (k == "") {
            cb(null)
            return
        }
        val hit = synchronized(cache) { cache[k] }
        // Twitch aendert sich schnell, deshalb dort kuerzer zwischenspeichern
        val ttl = if (k == "twitch") 4 * 60 * 1000L else 20 * 60 * 1000L
        if (hit != null && System.currentTimeMillis() - hit.first < ttl) {
            cb(mix(hit.second))
            return
        }
        pool.execute {
            val d = try {
                when (k) {
                    "youtube" -> youtube()
                    "spotify" -> spotify()
                    else -> section(k)
                }
            } catch (e: Throwable) {
                null
            }
            if (d != null && d.items.isNotEmpty()) {
                synchronized(cache) { cache[k] = Pair(System.currentTimeMillis(), d) }
                main.post { cb(mix(d)) }
            } else {
                main.post { cb(hit?.second?.let { mix(it) }) }
            }
        }
    }

    /**
     * Jedes Mal eine neue Auswahl: Eintraege nach Rubrik gruppieren, jede Rubrik mischen und
     * reihum in zufaelliger Rubrik-Reihenfolge ziehen. So wechseln Rubriken und Titel staendig.
     */
    private fun mix(d: HeroData, n: Int = 10): HeroData {
        if (d.items.size <= 1) return d
        val groups = d.items.groupBy { it.label }.values.map { it.shuffled().toMutableList() }.shuffled()
        val out = ArrayList<HeroItem>()
        var added = true
        while (out.size < n && added) {
            added = false
            for (g in groups) {
                if (g.isNotEmpty() && out.size < n) {
                    out.add(g.removeAt(0))
                    added = true
                }
            }
        }
        return HeroData(d.label, d.source, out)
    }

    private fun get(u: String, bearer: String? = null): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "LiquidTV/1.0")
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                c.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- YouTube: neueste Videos der Kanaele ueber deren oeffentliche Feeds ----------

    private class Video(val id: String, val title: String, val channel: String, val time: Long, val views: Long)

    private fun youtube(): HeroData? {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val all = ArrayList<Video>()
        val channels = LinkedHashMap<String, String>()
        for ((name, id) in CHANNELS) channels[id] = name
        try {
            val extra = remote()?.optJSONArray("youtube_channels")
            if (extra != null) for (i in 0 until extra.length()) {
                val o = extra.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.startsWith("UC") && !channels.containsKey(id)) channels[id] = o.optString("query", o.optString("name", ""))
            }
        } catch (e: Exception) {
        }
        for ((id, name) in channels) {
            val xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=$id") ?: continue
            try {
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(StringReader(xml))
                var inEntry = false
                var tag = ""
                var vid = ""
                var title = ""
                var pub = ""
                var views = 0L
                var n = 0
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && n < 15) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            tag = p.name ?: ""
                            if (tag == "entry") {
                                inEntry = true
                                vid = ""
                                title = ""
                                pub = ""
                                views = 0L
                            }
                            if (tag == "media:statistics") {
                                views = p.getAttributeValue(null, "views")?.toLongOrNull() ?: 0L
                            }
                        }
                        XmlPullParser.TEXT -> if (inEntry) {
                            when (tag) {
                                "yt:videoId" -> vid += p.text
                                "title" -> title += p.text
                                "published" -> pub += p.text
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (p.name == "entry") {
                                inEntry = false
                                val t = try { fmt.parse(pub.trim())?.time ?: 0L } catch (e: Exception) { 0L }
                                val ttl = title.trim()
                                if (vid.isNotBlank() && ttl.isNotBlank() && !ttl.lowercase().contains("#shorts")) {
                                    all.add(Video(vid.trim(), ttl, name, t, views))
                                    n++
                                }
                            }
                            tag = ""
                        }
                    }
                    ev = p.next()
                }
            } catch (e: Throwable) {
            }
        }
        if (all.isEmpty()) return null
        fun item(v: Video, label: String, meta: String) = HeroItem(
            v.title, meta, "",
            listOf(
                "https://i.ytimg.com/vi/" + v.id + "/maxresdefault.jpg",
                "https://i.ytimg.com/vi/" + v.id + "/sddefault.jpg",
                "https://i.ytimg.com/vi/" + v.id + "/hqdefault.jpg"
            ),
            false, "https://www.youtube.com/watch?v=" + v.id, label
        )
        fun views(n: Long): String = when {
            n >= 1_000_000 -> String.format(Locale.GERMANY, "%.1f Mio. Aufrufe", n / 1_000_000.0)
            n >= 1000 -> (n / 1000).toString() + " Tsd. Aufrufe"
            else -> "$n Aufrufe"
        }
        val byTime = all.sortedByDescending { it.time }
        // Neu: je Kanal das neueste, dann die naechstneuen
        val fresh = byTime.take(10)
        val freshIds = fresh.map { it.id }.toSet()
        // Beliebt: meistgesehene Videos der letzten Wochen, die nicht schon unter "Neu" stehen
        val popular = all.filter { it.id !in freshIds }.sortedByDescending { it.views }.take(8)
        val popIds = popular.map { it.id }.toSet()
        // Aelter: zufaellige Videos, die man vielleicht verpasst hat
        val older = all.filter { it.id !in freshIds && it.id !in popIds }.shuffled().take(8)
        val items = ArrayList<HeroItem>()
        fresh.forEach { items.add(item(it, "Neu von deinen Kanälen", it.channel + "  ·  " + ago(it.time))) }
        popular.forEach { items.add(item(it, "Beliebt bei deinen Kanälen", it.channel + "  ·  " + views(it.views))) }
        older.forEach { items.add(item(it, "Schon gesehen?", it.channel + "  ·  " + ago(it.time))) }
        val trends = (section("youtube_trends")?.items ?: emptyList()).filter { t -> items.none { it.link == t.link } }
        items.addAll(trends.take(8))
        return HeroData("YouTube", "Quelle: YouTube", items)
    }

    private fun ago(t: Long): String {
        if (t <= 0L) return ""
        val min = (System.currentTimeMillis() - t) / 60000L
        return when {
            min < 60 -> "gerade eben"
            min < 120 -> "vor 1 Stunde"
            min < 24 * 60 -> "vor " + (min / 60) + " Stunden"
            min < 48 * 60 -> "gestern"
            min < 14 * 24 * 60 -> "vor " + (min / 1440) + " Tagen"
            else -> "vor " + (min / (7 * 1440)) + " Wochen"
        }
    }

    // ---------- Filme/Serien, Twitch und YouTube-Trends: fertige Datei aus dem Repository ----------
    // Die Datei wird von GitHub alle paar Minuten neu erzeugt; die Zugangsschluessel bleiben dort.

    private const val REMOTE = "https://raw.githubusercontent.com/jugie145/fire-launcher/data/content.json"
    private var remote: JSONObject? = null
    private var remoteAt = 0L

    private fun remote(): JSONObject? {
        val now = System.currentTimeMillis()
        if (remote != null && now - remoteAt < 5 * 60 * 1000L) return remote
        val txt = get(REMOTE) ?: return remote
        try {
            remote = JSONObject(txt)
            remoteAt = now
        } catch (e: Exception) {
        }
        return remote
    }

    /**
     * Direktspruenge mit Ersatz-Varianten. Streaming-Apps bekommen eine Suche nach dem Titel;
     * nimmt die App keine davon an, oeffnet sich einfach die App.
     */
    fun links(key: String, title: String, link: String): String {
        val q = android.net.Uri.encode(title)
        return when (key) {
            "twitch" -> {
                val login = link.substringAfterLast("/", "")
                if (login.isBlank()) link else "twitch://stream/$login|twitch://open?stream=$login|$link"
            }
            "netflix" -> "https://www.netflix.com/search?q=$q|nflx://www.netflix.com/search?q=$q"
            "disney" -> "https://www.disneyplus.com/search?q=$q|disneyplus://search?q=$q"
            "prime" -> "https://app.primevideo.com/search?phrase=$q|https://www.amazon.de/gp/video/search?phrase=$q"
            else -> link
        }
    }

    private fun section(key: String): HeroData? {
        val o = remote()?.optJSONObject(key) ?: return null
        val arr = o.optJSONArray("items") ?: return null
        val items = ArrayList<HeroItem>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val title = r.optString("title", "").trim()
            if (title.isBlank()) continue
            val imgs = ArrayList<String>()
            val ia = r.optJSONArray("images")
            if (ia != null) for (j in 0 until ia.length()) imgs.add(ia.getString(j))
            items.add(
                HeroItem(
                    title, r.optString("meta", ""), r.optString("desc", ""), imgs,
                    r.optBoolean("square", false), links(key, title, r.optString("link", "")), r.optString("label", "")
                )
            )
        }
        if (items.isEmpty()) return null
        return HeroData(o.optString("label", ""), o.optString("source", ""), items)
    }

    /** Persoenliche Vorschlaege (zuletzt gehoert, Top-Songs) plus Charts. */
    private fun spotify(): HeroData? {
        val mine = appCtx?.let { try { Spotify.suggestions(it) } catch (e: Throwable) { null } } ?: emptyList()
        val ch = charts()
        val all = mine + (ch?.items ?: emptyList())
        if (all.isEmpty()) return null
        return HeroData("Spotify", "", all)
    }

    // ---------- Musik: oeffentliche Charts fuer Deutschland ----------

    private fun charts(): HeroData? {
        val items = ArrayList<HeroItem>()
        for ((kind, label) in listOf("songs" to "Top-Songs Deutschland", "albums" to "Top-Alben Deutschland")) {
            val txt = get("https://rss.applemarketingtools.com/api/v2/de/music/most-played/25/$kind.json") ?: continue
            val arr = try { JSONObject(txt).optJSONObject("feed")?.optJSONArray("results") } catch (e: Exception) { null } ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val art = r.optString("artworkUrl100", "")
                val imgs = if (art.isBlank()) emptyList() else listOf(art.replace("100x100bb", "600x600bb"), art)
                items.add(
                    HeroItem(
                        r.optString("name"), r.optString("artistName"), "Platz " + (i + 1) + " in Deutschland", imgs, true,
                        "spotify:search:" + android.net.Uri.encode(r.optString("name") + " " + r.optString("artistName")) +
                            "|https://open.spotify.com/search/" + android.net.Uri.encode(r.optString("name") + " " + r.optString("artistName")),
                        label
                    )
                )
            }
        }
        if (items.isEmpty()) return null
        return HeroData("Charts", "Quelle: Apple Music Charts", items)
    }
}
