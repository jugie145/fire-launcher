package de.jugie.firelauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Schaltet Umleitung und Bildschirmschoner nach einem Neustart wieder ein, falls Fire OS sie zurueckgesetzt hat. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs.redirect(context)) Setup.enable(context)
        Saver.apply(context)
    }
}
