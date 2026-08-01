# Device Reporter

A tiny always-on Android app that reports your Pixel's state to your own API.
It replaces the paid "battery worker" apps — self-hosted, one device, no cost.

It calls your endpoint as an HTTP GET:

```
GET https://doughmination.uk/v2/devices?device=pixel&level=25&charging=1&lpm=0&wifi=Home
Header: X-Battery-Key: <your key>
```

The API updates only the fields it receives and leaves the rest untouched, so the
app sends a full snapshot on every event.

## What it reports and when

| Trigger | Fields sent |
|---|---|
| Wi-Fi connect / disconnect | full snapshot (`wifi` = SSID, or `0` when disconnected) |
| Charger connect / disconnect | full snapshot (`charging`) |
| Power-save (low power mode) toggle | full snapshot (`lpm`) |
| Chosen Bluetooth device connect / disconnect | full snapshot (`airpods`) |
| Every 5 minutes | full snapshot + `location` (battery `level`, etc.) |

A full snapshot is: `device`, `level`, `charging`, `lpm`, `wifi`, `watch`, `airpods`.
The 5-minute timer (and the in-app **test report**) additionally send `location`.

Every parameter value is URL-encoded before being placed in the query string.

### Field mapping

- **device** — the device name you set in the app.
- **level** — battery percentage `0–100`.
- **charging** — `1` plugged in, `0` on battery.
- **lpm** — `1`/`0` from Android's Battery Saver.
- **wifi** — SSID when connected; `0` when disconnected. (Withheld by the OS? the app
  omits `wifi` rather than sending a wrong value — see permissions below.)
- **watch** — manual toggle in settings. An Apple Watch cannot pair to a Pixel, so
  there is nothing to auto-detect; flip it yourself if you want `watch=1`.
- **airpods** — `1`/`0` based on the Bluetooth device you pick in settings. Open
  **Select AirPods device**, choose your AirPods (or any earbuds) from the paired list,
  and the app remembers it and reports `airpods=1` when it connects, `0` when it drops.
- **location** — sent only on the 5-minute timer. The app takes the last-known GPS fix and
  reverse-geocodes it to human-readable text `City, Region, Country` (not coordinates), e.g.
  `location=London, England, United Kingdom`. Built from Android's `Geocoder` fields
  `locality, adminArea, countryName`; any missing part is skipped. Omitted entirely if
  location permission is off, location services are off, or geocoding returns nothing.

## Settings screen

- **Base URL** — defaults to `https://doughmination.uk/v2`.
- **X-Battery-Key** — your secret; stored only on the device, never baked into the APK.
- **Device name** — e.g. `pixel`.
- **Watch connected** — manual switch.
- **Enable AirPods (Bluetooth) reporting** + **Select AirPods device**.
- **Save**, **Start / Stop service**, **Send test report now** (shows the exact request URL and result).

## Permissions

- **Location (fine)** — Android requires it to read the Wi-Fi SSID and the device location.
  Location services must be **on**, otherwise Android hides the SSID and no `location` is sent.
- **Background location ("Allow all the time")** — needed so the 5-minute timer can read
  location while the app is in the background. When you tap **Start**, Android opens the
  all-the-time location screen; choose it, or the `location` field only fills while the app is open.
- **Notifications** — the foreground service shows a quiet ongoing notification (Android needs this).
- **Bluetooth (connect)** — to list paired devices and detect the AirPods connection.
- **Battery optimization** — for the 5-minute timer to stay reliable, exempt the app:
  Settings → Apps → Device Reporter → Battery → **Unrestricted**.

## Build the APK (GitHub Actions)

This repo builds the installable APK for you in CI — no Android Studio needed.

1. Push this project to a GitHub repo.
2. The **Build APK** workflow runs automatically on every push (or run it manually from
   the **Actions** tab → *Build APK* → *Run workflow*).
3. Open the finished run and download the **device-reporter-debug-apk** artifact.
4. Unzip it to get `app-debug.apk`.

### Build locally instead (optional)

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Install on the Pixel

1. Copy `app-debug.apk` to the phone (or download the artifact directly on it).
2. Open it; allow **install from unknown sources** if prompted.
3. Launch **Device Reporter**, fill in the key, tap **Save**, then **Start reporting service**.
4. Grant the location, notification, and Bluetooth prompts.
5. Tap **Send test report now** to confirm the server receives it.

The debug APK is signed with Android's standard debug key — fine for a personal device.
For a "proper" signed release build, add a keystore and an `assembleRelease` signing config.

## Project layout

```
app/src/main/java/uk/doughmination/devicereporter/
  MainActivity.kt      settings UI + Bluetooth picker + permissions
  Prefs.kt             SharedPreferences config
  DeviceState.kt       battery / charging / lpm / wifi readers
  Reporter.kt          builds the URL and does the GET (X-Battery-Key header)
  ReporterService.kt   foreground service: receivers + 5-min timer
  BootReceiver.kt      restarts the service after reboot
.github/workflows/build.yml   CI that builds and uploads the APK
```
