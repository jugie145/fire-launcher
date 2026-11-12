package de.jugie.firelauncher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Wetter fuer Grabow von Open-Meteo, eine halbe Stunde zwischengespeichert. */
object Weather {
    @Volatile var temp: Int? = null
    @Volatile var code = -1
    @Volatile var day = true
    @Volatile private var at = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Ruft [cb] im Hauptthread auf, sobald Werte da sind (oder das Laden gescheitert ist). */
    fun load(cb: () -> Unit) {
        if (temp != null && System.currentTimeMillis() - at < 30 * 60 * 1000L) {
            cb()
            return
        }
        thread {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=53.28&longitude=11.57" +
                        "&current=temperature_2m,weather_code,is_day&timezone=auto"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(txt).getJSONObject("current")
                code = cur.getInt("weather_code")
                day = cur.optInt("is_day", 1) == 1
                temp = Math.round(cur.getDouble("temperature_2m")).toInt()
                at = System.currentTimeMillis()
            } catch (e: Exception) {
            }
            main.post(cb)
        }
    }

    fun text(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Leicht bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67 -> "Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Schauer"
        85, 86 -> "Schneeschauer"
        95, 96, 99 -> "Gewitter"
        else -> ""
    }
}
