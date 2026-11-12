package de.jugie.firelauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Spotify ueber die offizielle Web-Schnittstelle. Anmeldung mit PKCE (kein Geheimschluessel):
 * am Handy die Adresse des Sticks oeffnen, bei Spotify anmelden, die Rueckleitungsseite auf
 * GitHub reicht den Code an den Stick weiter.
 */
object Spotify {
    const val CLIENT_ID = "63e5b7670f3c4075aef76123da1afbe9"
    const val REDIRECT = "https://jugie145.github.io/fire-launcher/spotify.html"
    private const val SCOPES = "user-read-currently-playing user-read-playback-state user-modify-playback-state " +
        "user-read-recently-played user-top-read"
    private val rnd = SecureRandom()
    @Volatile private var verifier: String? = null
    @Volatile private var state: String? = null

    class Track(
        val title: String, val artist: String, val album: String, val cover: String,
        val duration: Long, val progress: Long, val playing: Boolean
    )

    private fun rand(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun connected(c: Context): Boolean = Prefs.str(c, "sp_refresh") != null

    fun logout(c: Context) {
        Prefs.setStr(c, "sp_refresh", null)
        Prefs.setStr(c, "sp_access", null)
    }

    /** Anmeldeadresse bei Spotify; im state steht die Adresse des Sticks fuer die Rueckleitung. */
    fun authUrl(hostPort: String): String {
        val v = rand(64)
        verifier = v
        val ch = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(v.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val st = hostPort + "~" + rand(16)
        state = st
        return "https://accounts.spotify.com/authorize?response_type=code&client_id=$CLIENT_ID" +
            "&scope=" + enc(SCOPES) + "&redirect_uri=" + enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=$ch&state=" + enc(st)
    }

    fun exchange(c: Context, code: String, st: String): Boolean {
        val v = verifier ?: return false
        if (st != state) return false
        val body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT) +
            "&client_id=$CLIENT_ID&code_verifier=" + enc(v)
        val r = tokenCall(body) ?: return false
        save(c, r)
        verifier = null
        return Prefs.str(c, "sp_refresh") != null
    }

    private fun tokenCall(body: String): JSONObject? = try {
        val con = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 8000
        con.readTimeout = 10000
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        con.outputStream.use { it.write(body.toByteArray()) }
        if (con.responseCode == 200) JSONObject(con.inputStream.bufferedReader().use { it.readText() }) else null
    } catch (e: Throwable) {
        null
    }

    private fun save(c: Context, r: JSONObject) {
        val acc = r.optString("access_token", "")
        if (acc.isBlank()) return
        Prefs.setStr(c, "sp_access", acc)
        Prefs.setNum(c, "sp_exp", System.currentTimeMillis() + (r.optLong("expires_in", 3600) - 60) * 1000L)
        val ref = r.optString("refresh_token", "")
        if (ref.isNotBlank()) Prefs.setStr(c, "sp_refresh", ref)
    }

    @Synchronized
    private fun token(c: Context): String? {
        val ref = Prefs.str(c, "sp_refresh") ?: return null
        val acc = Prefs.str(c, "sp_access")
        if (acc != null && System.currentTimeMillis() < Prefs.num(c, "sp_exp")) return acc
        val r = tokenCall("grant_type=refresh_token&refresh_token=" + enc(ref) + "&client_id=$CLIENT_ID") ?: return null
        save(c, r)
        return Prefs.str(c, "sp_access")
    }

    /** Antwort der Schnittstelle: Code und Text (Text leer bei 204). */
    private fun api(c: Context, method: String, path: String): Pair<Int, String>? {
        val t = token(c) ?: return null
        return try {
            val con = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
            con.requestMethod = method
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.setRequestProperty("Authorization", "Bearer $t")
            if (method != "GET") {
                con.doOutput = true
                con.setFixedLengthStreamingMode(0)
                con.outputStream.close()
            }
            val code = con.responseCode
            val txt = if (code == 200) con.inputStream.bufferedReader().use { it.readText() } else ""
            code to txt
        } catch (e: Throwable) {
            null
        }
    }

    /** Was gerade laeuft; null = nichts oder nicht angemeldet. */
    fun current(c: Context): Track? {
        val (code, txt) = api(c, "GET", "/me/player/currently-playing?additional_types=episode") ?: return null
        if (code != 200 || txt.isBlank()) return null
        return try {
            val o = JSONObject(txt)
            val item = o.optJSONObject("item") ?: return null
            val episode = item.optString("type") == "episode"
            val artists = item.optJSONArray("artists")
            val artist = if (episode) item.optJSONObject("show")?.optString("name", "") ?: ""
            else (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
            val album = if (episode) "Podcast" else item.optJSONObject("album")?.optString("name", "") ?: ""
            val imgs = if (episode) item.optJSONArray("images") else item.optJSONObject("album")?.optJSONArray("images")
            val cover = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("url", "") else ""
            Track(
                item.optString("name", ""), artist, album, cover,
                item.optLong("duration_ms", 1), o.optLong("progress_ms", 0), o.optBoolean("is_playing", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** play, pause, next, prev */
    fun control(c: Context, action: String): Boolean {
        val r = when (action) {
            "play" -> api(c, "PUT", "/me/player/play")
            "pause" -> api(c, "PUT", "/me/player/pause")
            "next" -> api(c, "POST", "/me/player/next")
            "prev" -> api(c, "POST", "/me/player/previous")
            else -> null
        } ?: return false
        return r.first in 200..204
    }

    /** Persoenliche Vorschlaege: zuletzt gehoert und Top-Songs der letzten Wochen. */
    fun suggestions(c: Context): List<HeroItem> {
        if (!connected(c)) return emptyList()
        val out = ArrayList<HeroItem>()
        fun track(t: JSONObject, label: String, desc: String) {
            val name = t.optString("name", "")
            if (name.isBlank()) return
            val arr = t.optJSONArray("artists")
            val artist = (0 until (arr?.length() ?: 0)).joinToString(", ") { arr!!.getJSONObject(it).optString("name") }
            val imgs = t.optJSONObject("album")?.optJSONArray("images")
            val cover = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("url", "") else ""
            val uri = t.optString("uri", "")
            val web = t.optJSONObject("external_urls")?.optString("spotify", "") ?: ""
            out.add(HeroItem(name, artist, desc, if (cover.isBlank()) emptyList() else listOf(cover), true, "$uri|$web", label))
        }
        val seen = HashSet<String>()
        api(c, "GET", "/me/player/recently-played?limit=20")?.let { (code, txt) ->
            if (code == 200) try {
                val arr = JSONObject(txt).optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i).optJSONObject("track") ?: continue
                    if (seen.add(t.optString("id"))) track(t, "Zuletzt gehört", "Zuletzt gehört")
                }
            } catch (e: Exception) {
            }
        }
        api(c, "GET", "/me/top/tracks?limit=15&time_range=short_term")?.let { (code, txt) ->
            if (code == 200) try {
                val arr = JSONObject(txt).optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) {
                    track(arr.getJSONObject(i), "Deine Top-Songs", "Platz " + (i + 1) + " deiner letzten Wochen")
                }
            } catch (e: Exception) {
            }
        }
        return out
    }
}

/** Was auf Spotify laeuft, fuer Startbildschirm und Bildschirmschoner. Fragt alle 5 s nach. */
object NowPlaying {
    @Volatile var track: Spotify.Track? = null
        private set
    private var at = 0L
    private val listeners = ArrayList<(Spotify.Track?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var users = 0
    private var ctx: Context? = null
    private val poll = object : Runnable {
        override fun run() {
            fetch()
            main.postDelayed(this, 5000)
        }
    }

    fun listen(l: (Spotify.Track?) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (Spotify.Track?) -> Unit) {
        listeners.remove(l)
    }

    fun start(c: Context) {
        ctx = c.applicationContext
        users++
        if (users == 1) main.post(poll)
    }

    fun stop() {
        users = Math.max(0, users - 1)
        if (users == 0) main.removeCallbacks(poll)
    }

    private fun fetch() {
        val c = ctx ?: return
        if (!Spotify.connected(c)) {
            if (track != null) publish(null)
            return
        }
        pool.execute {
            val t = Spotify.current(c)
            main.post { publish(t) }
        }
    }

    private fun publish(t: Spotify.Track?) {
        track = t
        at = SystemClock.uptimeMillis()
        for (l in ArrayList(listeners)) l(t)
    }

    /** Aktuelle Position, zwischen den Abfragen weitergerechnet. */
    fun position(): Long {
        val t = track ?: return 0
        return if (t.playing) Math.min(t.duration, t.progress + SystemClock.uptimeMillis() - at) else t.progress
    }

    /** Mediatasten: sofort anzeigen, dann an Spotify schicken und nachfragen. */
    fun control(c: Context, action: String) {
        val t = track ?: return
        val cmd = if (action == "toggle") (if (t.playing) "pause" else "play") else action
        if (cmd == "play" || cmd == "pause") {
            publish(Spotify.Track(t.title, t.artist, t.album, t.cover, t.duration, position(), cmd == "play"))
        }
        val app = c.applicationContext
        pool.execute {
            Spotify.control(app, cmd)
            main.postDelayed({ fetch() }, 700)
        }
    }
}

/**
 * Kleiner Webserver im WLAN (nur solange die Einstellungen offen sind): Spotify-Anmeldung und
 * Hochladen eigener Hintergruende vom Handy.
 */
object LocalServer {
    const val PORT = 8765
    @Volatile private var sock: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null

    fun ip(): String? {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun address(): String = (ip() ?: "?") + ":" + PORT

    fun start(c: Context) {
        if (sock != null) return
        val app = c.applicationContext
        val s = try { ServerSocket(PORT) } catch (e: Exception) { return }
        sock = s
        Thread {
            while (!s.isClosed) {
                val cl = try { s.accept() } catch (e: Exception) { break }
                Thread { try { handle(app, cl) } catch (e: Throwable) { } finally { try { cl.close() } catch (e: Exception) { } } }.start()
            }
        }.start()
    }

    fun stop() {
        try { sock?.close() } catch (e: Exception) { }
        sock = null
    }

    private fun handle(c: Context, cl: Socket) {
        cl.soTimeout = 30000
        val inp = cl.getInputStream()
        // Kopfzeilen bis zur Leerzeile lesen
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || head.length > 16000) break
        }
        val lines = head.toString().split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val full = parts[1]
        val path = full.substringBefore("?")
        val query = full.substringAfter("?", "").split("&").filter { it.contains("=") }
            .associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        var len = 0L
        for (l in lines) if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(":").trim().toLongOrNull() ?: 0
        val out = cl.getOutputStream()
        fun send(code: Int, html: String, extra: String = "") {
            val body = html.toByteArray()
            out.write(("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
            out.write(body)
            out.flush()
        }
        when {
            method == "GET" && path == "/" -> send(200, page("Liquid TV", """
                <p><a class="b" href="/login">Spotify verbinden</a></p>
                <p><a class="b" href="/bilder">Hintergründe hochladen</a></p>"""))
            method == "GET" && path == "/login" -> {
                val url = Spotify.authUrl(address())
                out.write("HTTP/1.1 302 Found\r\nLocation: $url\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
            method == "GET" && path == "/spotify" -> {
                val ok = Spotify.exchange(c, query["code"] ?: "", query["state"] ?: "")
                send(200, page(if (ok) "Verbunden" else "Hat nicht geklappt",
                    if (ok) "<p>Spotify ist jetzt mit deinem Fernseher verbunden. Du kannst dieses Fenster schließen.</p>"
                    else "<p>Bitte am Fernseher die Anmeldung neu starten.</p>"))
                if (ok) main.post { onChange?.invoke() }
            }
            method == "GET" && path == "/bilder" -> send(200, page("Hintergründe", UPLOAD.replace("{n}", Theme.bgFiles(c).size.toString())))
            method == "POST" && path == "/bilder/neu" -> {
                val dir = Theme.bgDir(c)
                val f = File(dir, "bg_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt() + ".jpg")
                var left = len
                val buf = ByteArray(65536)
                f.outputStream().use { o ->
                    while (left > 0) {
                        val n = inp.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(buf, 0, n)
                        left -= n
                    }
                }
                if (f.length() < 1000) f.delete()
                send(200, "ok")
                main.post { onChange?.invoke() }
            }
            method == "POST" && path == "/bilder/leeren" -> {
                Theme.bgFiles(c).forEach { it.delete() }
                send(200, "ok")
                main.post { onChange?.invoke() }
            }
            else -> send(404, page("Nicht gefunden", "<p><a href=\"/\">Zur Übersicht</a></p>"))
        }
    }

    private fun page(title: String, body: String) = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Liquid TV · $title</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#080A12;color:#fff;
font:17px/1.5 -apple-system,system-ui,sans-serif;text-align:center;padding:24px;box-sizing:border-box}h1{font-weight:500;font-size:24px}
p{color:rgba(255,255,255,.8)}a{color:#fff}.b{display:inline-block;padding:14px 26px;border-radius:999px;border:1.5px solid rgba(255,255,255,.35);
background:rgba(255,255,255,.1);text-decoration:none;margin:6px}input{margin:18px 0}</style></head><body><div><h1>$title</h1>$body</div></body></html>"""

    private const val UPLOAD = """<p>Auf dem Fernseher sind {n} Bilder.</p>
<p><input id="f" type="file" accept="image/*" multiple></p><p id="s"></p>
<p><a class="b" href="#" id="x">Alle löschen</a></p>
<script>
var s=document.getElementById('s');
document.getElementById('f').onchange=async function(){var fs=this.files;for(var i=0;i<fs.length;i++){
s.textContent='Lade '+(i+1)+' von '+fs.length+' …';await fetch('/bilder/neu',{method:'POST',body:fs[i]});}
s.textContent='Fertig: '+fs.length+' Bilder hochgeladen.';};
document.getElementById('x').onclick=async function(e){e.preventDefault();if(!confirm('Alle Hintergründe löschen?'))return;
await fetch('/bilder/leeren',{method:'POST'});location.reload();};
</script>"""
}
