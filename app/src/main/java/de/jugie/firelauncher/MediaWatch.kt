package de.jugie.firelauncher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Leerer Benachrichtigungs-Dienst: noetig, um die laufenden Mediensitzungen abfragen zu duerfen. */
class MediaWatch : NotificationListenerService()

object Playback {
    private const val KEY = "enabled_notification_listeners"

    /** Zugriff einmal selbst eintragen (geht wie bei der Home-Taste ueber die Systemeinstellungen). */
    fun enable(c: Context) {
        if (!Setup.canWrite(c)) return
        try {
            val me = ComponentName(c, MediaWatch::class.java).flattenToString()
            val cur = Settings.Secure.getString(c.contentResolver, KEY) ?: ""
            if (!cur.split(":").contains(me)) {
                Settings.Secure.putString(c.contentResolver, KEY, if (cur.isBlank()) me else "$cur:$me")
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Wird gerade ein Video abgespielt? Musik von Spotify zaehlt nicht (der Schoner zeigt sie ja an).
     * Ohne Zugriff auf die Sitzungen: Notloesung ueber "Ton laeuft".
     */
    fun videoPlaying(c: Context): Pair<Boolean, String> {
        try {
            val m = c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = m.getActiveSessions(ComponentName(c, MediaWatch::class.java))
            val playing = list.filter {
                val st = it.playbackState?.state
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
            }.map { it.packageName }
            val other = playing.filter { !it.contains("spotify") }
            return (other.isNotEmpty()) to ("Sitzungen: " + (if (playing.isEmpty()) "keine spielt" else playing.joinToString()))
        } catch (e: Exception) {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.isMusicActive to ("ohne Sitzungszugriff, Ton: " + (if (am.isMusicActive) "an" else "aus"))
        }
    }
}
