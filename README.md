# Liquid TV

Eigener, werbefreier Startbildschirm für den Fire TV Stick (Fire OS, nicht Vega OS).

- Glas-Design mit animiertem, abstraktem Hintergrund
- Uhr, Datum, Begrüßung und Wetter (Grabow)
- 6 Favoriten als Glaskacheln mit den Original-Logos der Apps
- „Alle Apps“: OK lange halten pinnt eine App als Favorit an oder löst sie
- Eigene Einstellungen: Hintergrund, Animationen, Glaseffekt, Uhrzeitformat, App-Reihenfolge

## Installieren

In der Downloader-App auf dem Stick diese Adresse eingeben:

    https://github.com/jugie145/fire-launcher/releases/latest/download/fire-launcher.apk

## Home-Taste einrichten

Fire OS blockiert ADB-Verbindungen vom Stick zu sich selbst. Deshalb einmalig von einem
Computer oder Android-Handy im selben WLAN (ADB-Debugging am Stick einschalten):

    adb connect <IP-des-Sticks>:5555
    adb shell pm grant de.jugie.firelauncher android.permission.WRITE_SECURE_SETTINGS

Danach den Launcher einmal öffnen.

## Rückgängig

Einstellungen → Anwendungen → Installierte Anwendungen verwalten → Fire Launcher → Deinstallieren.

Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions).

## Bildschirmschoner

Liquid TV bringt einen eigenen Bildschirmschoner mit (Einstellungen → Bildschirmschoner):
„Filme & Serien“ zeigt Szenenbilder neuer Titel, „Wellen & Uhr“ das Wellenbild mit wandernden
App-Farben. Er startet nach 5, 10 oder 15 Minuten, jede Taste beendet ihn. „Fire TV Standard“
stellt den Bildschirmschoner von Amazon wieder her. Nach dem ersten Umstellen einmal neu starten.
