# Kiosk Browser

Ein quelloffener Kiosk-Browser für Android (ab Android 8.0) mit dem Funktionsumfang eines kommerziellen „Plus“-Kiosk-Browsers: Vollbild-WebView, harte Kiosk-Sperre, Fernverwaltung, REST-API, MQTT/Home Assistant, Bewegungserkennung, Bildschirmschoner, Zeitpläne und eine JavaScript-Schnittstelle.

> Bedienung auf dem Gerät: **Ecke oben links 5× schnell antippen** → PIN (Standard `1234`) → Menü mit Einstellungen, Neu laden, Kiosk verlassen, Beenden.

## Funktionen

| Bereich | Funktionen |
|---|---|
| **Web** | Vollbild-WebView, Start-URL, URL-Whitelist/Blacklist (Wildcards `*` oder `regex:`), eigener User-Agent, Desktop-Modus, Zoom, Textgröße, Autoplay, HTML5-Vollbildvideo, Datei-Upload, Downloads, WebRTC (Kamera/Mikrofon), Geolocation, SSL-Fehler ignorieren (optional), Remote-Debugging |
| **Neuladen** | Zur Start-URL nach Inaktivität, periodisches Neuladen, automatischer Retry nach Ladefehlern, Neuladen bei Netzwerk-Wiederverbindung, Pull-to-Refresh, Neustart der WebView bei Renderer-Absturz |
| **Kiosk-Modus** | Sperrt Zurück/Home/Übersicht über Android Lock-Task, PIN-geschütztes Menü über unsichtbare Ecke, optional als Launcher (Home-App), automatisch in den Vordergrund holen, mit Device-Owner: Statusleiste und Sperrbildschirm deaktivieren, abgesicherter Modus gesperrt, Home-App erzwungen |
| **Anzeige** | Bildschirm anlassen, Ausrichtung sperren, Helligkeit (App oder System), Bildschirm aus (abdunkeln oder Gerätesperre per Geräteadministrator) |
| **Bildschirmschoner** | Nach Inaktivität: Uhr (mit Burn-in-Schutz) oder beliebige URL, eigene Helligkeit, Ende bei Berührung oder Bewegung |
| **Zeitplan** | Bildschirm zu festen Uhrzeiten aus- und einschalten |
| **Bewegungserkennung** | Kamera (vorne/hinten) mit einstellbarer Empfindlichkeit, weckt Bildschirm, beendet Bildschirmschoner, sendet Events (JS, MQTT) |
| **Geräuscherkennung** | Mikrofon-Pegel in dB mit Schwellwert-Event |
| **Fernverwaltung** | Eingebaute Weboberfläche (Port 2323) mit Passwort: Geräteinfos, URL laden, Bildschirm an/aus, Helligkeit, Lautstärke, Sprachausgabe, Sound abspielen, Screenshot, Kamerabild, Einstellungen als JSON bearbeiten, App neu starten |
| **REST-API** | Alle Befehle per HTTP – ideal für Home Assistant, ioBroker, Node-RED, FHEM |
| **MQTT** | Status, Events, Befehle, Last-Will, **Home Assistant Auto-Discovery** (Akku, Bewegung, Bildschirm-Schalter, Helligkeit, Lautstärke, URL, TTS, Buttons) |
| **JavaScript-API** | `window.kiosk` mit Geräteinfos, Steuerbefehlen, Events und Zugriff auf alle REST-Befehle |
| **Sonstiges** | Autostart nach Boot, Neustart nach Absturz, Sprachausgabe (TTS), Lautstärke, Einstellungen exportieren/importieren, Deutsch/Englisch |

## Installation

APK bauen:

```bash
./gradlew assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Jeder Push baut die APK auch per GitHub Actions (Reiter *Actions* → Artefakt `kiosk-browser-apk`).

### Empfohlene Einrichtung für eine harte Kiosk-Sperre (Device Owner)

Ohne Device Owner nutzt die App Androids Bildschirmfixierung (der Nutzer muss diese einmal bestätigen). Für eine vollständige Sperre wird die App als Device Owner gesetzt – das geht nur auf einem Gerät **ohne angemeldete Konten** (frisch zurückgesetzt, Google-Konto beim Einrichten überspringen):

```bash
adb install app-debug.apk
adb shell dpm set-device-owner com.kioskbrowser.app/.KioskAdminReceiver
```

Danach in den Einstellungen *Kiosk-Modus aktivieren*. Entfernen des Device Owner: App-Einstellungen → *App beenden* und dann
`adb shell dpm remove-active-admin com.kioskbrowser.app/.KioskAdminReceiver` (nur für Debug-Builds möglich) oder Werksreset.

Weitere hilfreiche Berechtigungen (Einstellungen → Werkzeuge):
- *Über anderen Apps einblenden* – für Autostart und „in den Vordergrund holen“ ohne Device Owner
- *Systemeinstellungen ändern* – Systemhelligkeit
- *Geräteadministrator* – Bildschirm wirklich ausschalten (Methode „Gerät sperren“)

## REST-API

Fernverwaltung aktivieren und **Passwort setzen** (ohne Passwort sind alle API-Aufrufe gesperrt). Das Passwort wird als Parameter `password` oder Header `X-Password` übergeben.

| Endpunkt | Beschreibung |
|---|---|
| `GET /` | Weboberfläche |
| `GET /api/info` | Geräteinfos (JSON) |
| `GET/POST /api/cmd?cmd=<befehl>&...` | Befehl ausführen |
| `GET /api/settings` | Alle Einstellungen (ohne Passwörter) |
| `POST /api/settings` | Einstellungen setzen (JSON-Objekt, wird zusammengeführt) |
| `GET /api/screenshot` | Screenshot (PNG) |
| `GET /api/camshot` | Kamerabild (JPEG, Bewegungserkennung muss aktiv sein) |

Befehle:

| Befehl | Parameter |
|---|---|
| `loadUrl` | `url` |
| `loadStartUrl`, `reload`, `goBack` | – |
| `clearCache`, `clearCookies`, `clearWebStorage` | – |
| `screenOn`, `screenOff`, `screen` | `value=on/off` |
| `startScreensaver`, `stopScreensaver`, `screensaver` | `value=on/off` |
| `setBrightness` | `value` 0–255 (−1 = System) |
| `setVolume` | `value` 0–100 |
| `speak` | `text`, optional `lang` (z. B. `de-DE`) |
| `stopSpeaking` | – |
| `playSound` | `url`, optional `loop=true` |
| `stopSound` | – |
| `toast` | `text` |
| `runJs` | `code` |
| `toForeground`, `restartApp`, `exitApp` | – |
| `lockKiosk`, `unlockKiosk` | – |
| `setSetting` | `key`, `value` |
| `getSetting` | `key` |
| `deviceInfo`, `listCommands` | – |

Beispiele:

```bash
curl "http://192.168.1.50:2323/api/cmd?cmd=loadUrl&url=https://example.com&password=geheim"
curl -H "X-Password: geheim" "http://192.168.1.50:2323/api/cmd?cmd=screenOff"
curl -H "X-Password: geheim" -d '{"start_url":"http://homeassistant.local:8123","kiosk_mode":true}' http://192.168.1.50:2323/api/settings
curl -H "X-Password: geheim" -o shot.png http://192.168.1.50:2323/api/screenshot
```

## MQTT

| Topic | Inhalt |
|---|---|
| `<präfix>/<geräte-id>/state` | Geräteinfos als JSON (retained, periodisch und bei Ereignissen) |
| `<präfix>/<geräte-id>/availability` | `online` / `offline` (Last Will) |
| `<präfix>/<geräte-id>/event/<name>` | Events wie `motion`, `sound`, `screenOn`, `screenOff`, `pageFinished`, `onScreensaverStart` |
| `<präfix>/<geräte-id>/cmd/<befehl>` | Befehl an dieses Gerät, Payload JSON (`{"url":"…"}`) oder einfacher Wert |
| `<präfix>/cmd/<befehl>` | Befehl an alle Geräte |
| `<präfix>/<geräte-id>/result` | Ergebnis des letzten Befehls |

Beispiel: `mosquitto_pub -t kiosk/abcd1234/cmd/loadUrl -m "https://example.com"`

Mit aktivierter Home Assistant Discovery erscheint das Tablet automatisch als Gerät mit Sensoren, Schaltern und Buttons.

## JavaScript-API (`window.kiosk`)

```js
kiosk.getDeviceId(); kiosk.getDeviceName(); kiosk.getBatteryLevel(); kiosk.isPlugged();
kiosk.getIp4Address(); JSON.parse(kiosk.getDeviceInfo()); kiosk.getCurrentUrl();
kiosk.isScreenOn(); kiosk.isInScreensaver(); kiosk.isMotionDetected(); kiosk.getSoundLevel();
kiosk.getScreenBrightness(); kiosk.setScreenBrightness(128); kiosk.getVolume(); kiosk.setVolume(50);
kiosk.turnScreenOn(); kiosk.turnScreenOff(); kiosk.startScreensaver(); kiosk.stopScreensaver();
kiosk.loadUrl('https://…'); kiosk.loadStartUrl(); kiosk.reload(); kiosk.clearCache(); kiosk.clearCookies();
kiosk.showToast('Hallo'); kiosk.textToSpeech('Hallo Welt', 'de-DE'); kiosk.stopTextToSpeech();
kiosk.playSound('https://…/ding.mp3', false); kiosk.stopSound();
kiosk.bringToForeground(); kiosk.restartApp();
kiosk.getStringSetting('start_url'); kiosk.setStringSetting('start_url', 'https://…');
kiosk.getBooleanSetting('kiosk_mode'); kiosk.setBooleanSetting('pull_to_refresh', true);

// Events
kiosk.bind('motion', 'document.body.classList.add("awake")');
window.addEventListener('kiosk', e => console.log(e.detail.event, e.detail.data));

// Jeder REST-Befehl, Ergebnis an eine globale Callback-Funktion
kiosk.exec('setBrightness', '{"value":"200"}', 'onResult');
```

PIN und Passwörter sind über JavaScript weder lesbar noch änderbar.

Events: `motion`, `sound`, `screenOn`, `screenOff`, `onScreensaverStart`, `onScreensaverStop`, `pageFinished`, `onNetworkError`, `networkConnected`, `networkDisconnected`, `urlBlocked`, `wrongPin`, `onResume`, `onPause`, `scheduledSleep`, `scheduledWake`, `onRendererCrash`.

## Einstellungsschlüssel

Alle Einstellungen lassen sich per REST (`/api/settings`, `setSetting`), MQTT, JS oder JSON-Import setzen. Wichtige Schlüssel:

`start_url`, `url_whitelist`, `url_blacklist`, `kiosk_mode`, `settings_pin`, `exit_taps`, `lock_task`, `force_foreground`, `disable_status_bar`, `disable_keyguard`, `fullscreen`, `keep_screen_on`, `orientation` (`auto|portrait|landscape|reverse_portrait|reverse_landscape`), `brightness`, `screen_off_mode` (`dim|lock`), `screensaver_timeout_sec`, `screensaver_url`, `screensaver_brightness`, `sleep_time`, `wake_time`, `reload_on_idle_sec`, `reload_interval_min`, `error_reload_sec`, `motion_detection`, `motion_camera`, `motion_sensitivity`, `sound_detection`, `sound_threshold_db`, `remote_admin_enabled`, `remote_admin_port`, `remote_admin_password`, `mqtt_enabled`, `mqtt_url`, `mqtt_user`, `mqtt_password`, `mqtt_prefix`, `mqtt_ha_discovery`, `device_name`, `start_on_boot`, `restart_on_crash`, `user_agent`, `desktop_mode`, `enable_zoom`, `text_zoom`, `autoplay_media`, `allow_web_cam_mic`, `allow_geolocation`, `allow_downloads`, `block_external_apps`, `ignore_ssl_errors`, `js_interface`, `web_debugging`.

## Sicherheitshinweise

- Fernverwaltung und MQTT übertragen unverschlüsselt (HTTP / `tcp://`). Nur im vertrauenswürdigen LAN verwenden oder `ssl://` für MQTT nutzen.
- Standard-PIN `1234` nach der Einrichtung unbedingt ändern.
- Der Release-Build ist mit dem Debug-Schlüssel signiert, damit das CI-Artefakt installierbar ist – für den produktiven Einsatz eine eigene Signatur verwenden.

## Projektstruktur

```
app/src/main/java/com/kioskbrowser/app/
  MainActivity.kt        WebView, Kiosk-Sperre, Bildschirmschoner, Bildschirm an/aus, PIN-Menü
  SettingsActivity.kt    Einstellungen + Werkzeuge (Berechtigungen, Export/Import)
  KioskService.kt        Vordergrunddienst: Webserver, MQTT, Kamera, Mikrofon, Zeitplan, Watchdog
  CommandDispatcher.kt   Gemeinsame Befehle für REST, MQTT und JavaScript
  RemoteAdminServer.kt   Eingebauter HTTP-Server (NanoHTTPD)
  MqttManager.kt         MQTT + Home Assistant Discovery (Eclipse Paho)
  MotionDetector.kt      Bewegungserkennung + Kamerabild (CameraX)
  SoundDetector.kt       Geräuschpegel
  JsInterface.kt         window.kiosk
  DeviceController.kt    Geräteinfos, Helligkeit, Sperre, TTS, Sound, Lautstärke
  UrlFilter.kt, Prefs.kt, EventHub.kt, KioskApp.kt, BootReceiver.kt, KioskAdminReceiver.kt
app/src/main/assets/admin.html   Weboberfläche der Fernverwaltung
```

## Lizenz

MIT
