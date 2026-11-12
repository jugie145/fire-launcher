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
    /** Windboeen in km/h (Sturm ab 75) */
    @Volatile var gust = 0.0
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
                        "&current=temperature_2m,weather_code,is_day,wind_gusts_10m&timezone=auto"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(txt).getJSONObject("current")
                code = cur.getInt("weather_code")
                day = cur.optInt("is_day", 1) == 1
                gust = cur.optDouble("wind_gusts_10m", 0.0)
                temp = Math.round(cur.getDouble("temperature_2m")).toInt()
                at = System.currentTimeMillis()
            } catch (e: Exception) {
            }
            main.post(cb)
        }
    }

    /** Wetterlage als Text; bei Sturmboeen "Sturm" (ausser bei Gewitter). */
    fun label(): String = if (gust >= 75 && code !in setOf(95, 96, 99)) "Sturm" else text(code)

    fun text(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Leicht bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"
        56, 57, 66, 67 -> "Eisregen"
        61, 63, 65 -> "Regen"
        75 -> "Starker Schnee"
        71, 73, 77 -> "Schnee"
        80, 81, 82 -> "Regenschauer"
        85 -> "Schneeschauer"
        86 -> "Starke Schneeschauer"
        95, 96, 99 -> "Gewitter"
        else -> ""
    }
}
