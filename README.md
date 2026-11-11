# Fire Launcher

Eigener, werbefreier Startbildschirm für den Fire TV Stick (Fire OS, nicht Vega OS).

- Uhr, Datum und Wetter (Grabow)
- 6 Favoriten mit den Original-Bildern der Apps
- „Alle Apps“: OK lange halten pinnt eine App als Favorit an oder löst sie
- Auf einem Favoriten OK lange halten oder die Menü-Taste: verschieben oder entfernen

## Installieren

In der Downloader-App auf dem Stick diese Adresse eingeben:

    https://github.com/jugie145/fire-launcher/releases/latest/download/fire-launcher.apk

## Home-Taste einrichten

Fire OS blockiert ADB-Verbindungen vom Stick zu sich selbst. Deshalb einmalig von einem
Computer oder Android-Handy im selben WLAN (ADB-Debugging am Stick einschalten):

    adb connect <IP-des-Sticks>:5555
    adb shell pm grant de.jugie.firelauncher android.permission.WRITE_SECURE_SETTINGS

Danach im Launcher „Launcher einrichten“ öffnen und „Prüfen“ drücken.

## Rückgängig

Einstellungen → Anwendungen → Installierte Anwendungen verwalten → Fire Launcher → Deinstallieren.

Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions).
