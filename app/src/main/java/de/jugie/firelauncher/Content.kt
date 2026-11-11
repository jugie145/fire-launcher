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

    private fun fetch(u: String): Bitmap? {
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
            while (o.outWidth / (sample * 2) >= 1280) sample *= 2
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
            cb(hit.second)
            return
        }
        pool.execute {
            val d = try {
                when (k) {
                    "youtube" -> youtube()
                    "spotify" -> charts()
                    else -> section(k)
                }
            } catch (e: Throwable) {
                null
            }
            if (d != null && d.items.isNotEmpty()) {
                synchronized(cache) { cache[k] = Pair(System.currentTimeMillis(), d) }
                main.post { cb(d) }
            } else {
                main.post { cb(hit?.second) }
            }
        }
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

    private class Video(val id: String, val title: String, val channel: String, val time: Long)

    private fun youtube(): HeroData? {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val all = ArrayList<Video>()
        for ((name, id) in CHANNELS) {
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
                var n = 0
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && n < 3) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            tag = p.name ?: ""
                            if (tag == "entry") {
                                inEntry = true
                                vid = ""
                                title = ""
                                pub = ""
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
                                    all.add(Video(vid.trim(), ttl, name, t))
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
        val own = all.sortedByDescending { it.time }.take(8).map {
            HeroItem(
                it.title, it.channel + "  ·  " + ago(it.time), "",
                listOf(
                    "https://i.ytimg.com/vi/" + it.id + "/maxresdefault.jpg",
                    "https://i.ytimg.com/vi/" + it.id + "/sddefault.jpg",
                    "https://i.ytimg.com/vi/" + it.id + "/hqdefault.jpg"
                ),
                false, "https://www.youtube.com/watch?v=" + it.id
            )
        }
        // zwischen die eigenen Kanaele alle zwei Videos eines aus den Trends mischen
        val trends = (section("youtube_trends")?.items ?: emptyList()).filter { t -> own.none { it.link == t.link } }
        val items = ArrayList<HeroItem>()
        var ti = 0
        own.forEachIndexed { i, v ->
            items.add(v)
            if (i % 2 == 1 && ti < trends.size && ti < 4) items.add(trends[ti++])
        }
        return HeroData("Neu von deinen Kanälen", "Quelle: YouTube", items)
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
                    r.optBoolean("square", false), r.optString("link", ""), r.optString("label", "")
                )
            )
        }
        if (items.isEmpty()) return null
        return HeroData(o.optString("label", ""), o.optString("source", ""), items)
    }

    // ---------- Musik: oeffentliche Charts fuer Deutschland ----------

    private fun charts(): HeroData? {
        val txt = get("https://rss.applemarketingtools.com/api/v2/de/music/most-played/10/songs.json") ?: return null
        val arr = JSONObject(txt).optJSONObject("feed")?.optJSONArray("results") ?: return null
        val items = ArrayList<HeroItem>()
        for (i in 0 until Math.min(6, arr.length())) {
            val r = arr.getJSONObject(i)
            val art = r.optString("artworkUrl100", "")
            val imgs = if (art.isBlank()) emptyList() else listOf(art.replace("100x100bb", "600x600bb"), art)
            items.add(
                HeroItem(
                    r.optString("name"), r.optString("artistName"), "Platz " + (i + 1) + " in Deutschland", imgs, true,
                    "spotify:search:" + android.net.Uri.encode(r.optString("name") + " " + r.optString("artistName"))
                )
            )
        }
        if (items.isEmpty()) return null
        return HeroData("Charts Deutschland", "Quelle: Apple Music Charts", items)
    }
}
