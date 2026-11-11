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
    val link: String = ""
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
        "Eligella" to "UChhbR4PloyFZPhyW4iVNuEQ"
    )

    private val cache = HashMap<String, Pair<Long, HeroData>>()
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var genres: Map<Int, String>? = null
    private var providers: Map<String, Int>? = null

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
        if (k == "" || k == "twitch") {
            cb(null)
            return
        }
        val hit = synchronized(cache) { cache[k] }
        if (hit != null && System.currentTimeMillis() - hit.first < 30 * 60 * 1000L) {
            cb(hit.second)
            return
        }
        pool.execute {
            val d = try {
                when (k) {
                    "youtube" -> youtube()
                    "spotify" -> charts()
                    else -> tmdb(k, app.label)
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
                while (ev != XmlPullParser.END_DOCUMENT && n < 4) {
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
        val items = all.sortedByDescending { it.time }.take(6).map {
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

    // ---------- Filme und Serien: TMDB (Anbieter-Daten von JustWatch) ----------

    private fun tmdbGet(path: String): JSONObject? {
        val key = BuildConfig.TMDB_KEY.trim()
        if (key.isEmpty()) return null
        val base = "https://api.themoviedb.org/3"
        val txt = if (key.length > 60) get(base + path, key)
        else get(base + path + (if (path.contains("?")) "&" else "?") + "api_key=" + key)
        return if (txt == null) null else try { JSONObject(txt) } catch (e: Exception) { null }
    }

    private fun tmdb(kind: String, appName: String): HeroData? {
        if (BuildConfig.TMDB_KEY.trim().isEmpty()) return null
        if (genres == null) {
            val g = HashMap<Int, String>()
            for (t in arrayOf("tv", "movie")) {
                val arr = tmdbGet("/genre/$t/list?language=de")?.optJSONArray("genres") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    g[o.getInt("id")] = o.getString("name")
                }
            }
            if (g.isNotEmpty()) genres = g
        }
        if (providers == null) {
            val m = HashMap<String, Int>()
            val arr = tmdbGet("/watch/providers/tv?watch_region=DE&language=de-DE")?.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    m[o.optString("provider_name").lowercase().trim()] = o.optInt("provider_id")
                }
            }
            if (m.isNotEmpty()) providers = m
        }
        val pm = providers ?: emptyMap()
        val pid = when (kind) {
            "netflix" -> pm["netflix"] ?: 8
            "prime" -> pm["amazon prime video"] ?: 9
            "disney" -> pm["disney plus"] ?: 337
            else -> pm["wow"] ?: 30
        }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -75)
        val recent = df.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -290)
        val year = df.format(cal.time)
        val common = "language=de-DE&watch_region=DE&with_watch_providers=$pid&with_watch_monetization_types=flatrate&sort_by=popularity.desc"
        val tv = parse(tmdbGet("/discover/tv?$common&air_date.gte=$recent"), true)
        val mv = parse(tmdbGet("/discover/movie?$common&primary_release_date.gte=$year"), false)
        val items = ArrayList<HeroItem>()
        var i = 0
        while (items.size < 6 && (i < tv.size || i < mv.size)) {
            if (i < tv.size) items.add(tv[i])
            if (i < mv.size && items.size < 6) items.add(mv[i])
            i++
        }
        if (items.isEmpty()) return null
        return HeroData("Neu und beliebt bei $appName", "Daten: TMDB · JustWatch", items)
    }

    private fun parse(o: JSONObject?, tv: Boolean): List<HeroItem> {
        val out = ArrayList<HeroItem>()
        val arr = o?.optJSONArray("results") ?: return out
        val g = genres ?: emptyMap()
        for (i in 0 until arr.length()) {
            if (out.size >= 4) break
            val r = arr.getJSONObject(i)
            val backdrop = r.optString("backdrop_path", "")
            if (backdrop.isBlank() || backdrop == "null") continue
            val title = r.optString(if (tv) "name" else "title", "").trim()
            if (title.isBlank()) continue
            val date = r.optString(if (tv) "first_air_date" else "release_date", "")
            val parts = ArrayList<String>()
            if (date.length >= 4) parts.add(date.substring(0, 4))
            parts.add(if (tv) "Serie" else "Film")
            val gs = ArrayList<String>()
            val ga = r.optJSONArray("genre_ids")
            if (ga != null) for (j in 0 until Math.min(2, ga.length())) g[ga.getInt(j)]?.let { gs.add(it) }
            if (gs.isNotEmpty()) parts.add(gs.joinToString(", "))
            val vote = r.optDouble("vote_average", 0.0)
            if (vote > 0.1) parts.add("★ " + String.format(Locale.GERMANY, "%.1f", vote))
            out.add(
                HeroItem(
                    title, parts.joinToString("  ·  "), r.optString("overview", "").trim(),
                    listOf("https://image.tmdb.org/t/p/w1280$backdrop")
                )
            )
        }
        return out
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
