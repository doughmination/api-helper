# Device Reporter

A little Android app that quietly tells your own API how your Pixel is doing — battery,
charging, low-power mode, Wi-Fi, Bluetooth earbuds, and roughly where it is. It's the
free, self-hosted answer to those paid "battery status" apps, built for one phone: mine.

Under the hood it just POSTs to your endpoint with the fields in the query string and your
secret key in a header (no request body):

```
POST https://doughmination.uk/v2/devices?device=pixel&level=25&charging=1&lpm=0&wifi=Home
X-Battery-Key: <your key>
```

Your API only touches the fields it actually receives, so the app is happy to send a full
picture every time without clobbering anything it didn't mean to.

## When it phones home

It reports the moment something interesting happens, plus a heartbeat every five minutes:

- You join or leave a Wi-Fi network → sends the SSID (or `wifi=0` when you're off Wi-Fi).
- You plug in or unplug the charger.
- Battery Saver flips on or off.
- Your chosen Bluetooth earbuds connect or disconnect.
- Every 5 minutes regardless, as a battery check — and this one also includes your location.

## What each field means

- **device** — whatever name you set in the app.
- **level** — battery percentage, 0–100.
- **charging** — `1` plugged in, `0` on battery.
- **lpm** — `1`/`0` from Android's Battery Saver.
- **wifi** — the network name when connected, or `0` when you're not.
- **watch** — a manual switch. An Apple Watch can't pair to a Pixel, so there's nothing to
  detect automatically; flip it yourself if you ever want `watch=1`.
- **airpods** — `1`/`0` based on a Bluetooth device you choose. Tap **Select AirPods device**,
  pick your buds from the paired list, and the app remembers them and reports when they
  connect or drop.
- **location** — sent on the 5-minute heartbeat only. The app takes your last-known GPS fix
  and turns it into readable text like `London, England, United Kingdom` (never raw
  coordinates). If it can't get a location, it just leaves the field out.

Every value is URL-encoded, so spaces, commas, and symbols in a network name or place name
travel safely.

## The settings screen

Base URL (defaults to `https://doughmination.uk/v2`), your **X-Battery-Key**, and a device
name. Below that: the manual **watch** switch, an **AirPods** toggle with a device picker,
and buttons to **Save**, **Start**/**Stop** the service, and **Send a test report now** —
the test button shows you the exact URL it sent and whether the server liked it. Your key
lives only on the phone; it's never baked into the APK.

## Permissions, and why

- **Location (fine)** — Android won't hand over the Wi-Fi name or your location without it.
  Location services need to be switched on, too.
- **Background location ("Allow all the time")** — so the 5-minute heartbeat can still read
  your location when the app isn't open. When you tap Start, Android will ask; pick
  all-the-time or the location field only fills while the app is in front.
- **Notifications** — the background service shows one quiet, permanent notification. Android
  requires it, and it doubles as a handy "last report was OK at 14:32" status line.
- **Bluetooth** — to list your paired devices and notice when the earbuds come and go.
- **Battery** — set the app to **Unrestricted** (Settings → Apps → Device Reporter → Battery)
  or Android may throttle the 5-minute timer.

## Getting the APK

There's no Android Studio needed — GitHub builds it for you.

**Quick test build:** every push runs the workflow and produces a debug APK. Open the run
under the **Actions** tab and download the **device-reporter-debug-apk** artifact. Good for
trying things out; signed with Android's throwaway debug key.

**Proper signed release:** go to the **Actions** tab → **Build APK** → **Run workflow**, type
a version like `v1.0.0` in the box, and run it. GitHub builds a signed APK, creates that tag,
and publishes it as the latest Release. This is the one to actually keep on your phone.
(If you'd rather, pushing a `v*` tag from your terminal does the exact same thing.)

A minute later, your repo's **Releases** page has `device-reporter-v1.0.0.apk` sitting at the
top marked "Latest". Download it straight onto the Pixel.

### One-time signing setup

You need a keystore (your personal signing key) and four repo secrets. Make the keystore once:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias device-reporter \
  -keyalg RSA -keysize 2048 -validity 10000
```

It'll ask for a password and a few details — remember the password. Then base64-encode the
keystore so it can live in a secret:

```bash
base64 -i release.keystore | pbcopy   # macOS: now in your clipboard
```

In your repo, go to **Settings → Secrets and variables → Actions** and add four secrets:

- `SIGNING_KEYSTORE_BASE64` — paste the base64 blob from above.
- `SIGNING_STORE_PASSWORD` — the keystore password.
- `SIGNING_KEY_ALIAS` — `device-reporter` (or whatever `-alias` you used).
- `SIGNING_KEY_PASSWORD` — the key password (same as the store password unless you set a separate one).

Keep `release.keystore` itself safe and out of git — losing it means you can't ship signed
updates that install over an existing copy. That's it; tag a version and the release builds itself.

### Building locally instead

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # falls back to the debug key if no signing secrets are set
```

## Installing on the Pixel

Copy the APK over (or download it directly on the phone), open it, and allow installing from
unknown sources if prompted. Launch **Device Reporter**, put in your key, hit **Save**, then
**Start reporting service**, and say yes to the location, notification, and Bluetooth prompts.
Tap **Send test report now** to make sure the server hears it.

## How it's laid out

```
app/src/main/java/uk/doughmination/devicereporter/
  MainActivity.kt      settings screen, Bluetooth picker, permission prompts
  Prefs.kt             saved configuration
  DeviceState.kt       reads battery / charging / lpm / wifi / location
  Reporter.kt          builds the URL and sends it (with the X-Battery-Key header)
  ReporterService.kt   the always-on service: event triggers + 5-minute timer
  BootReceiver.kt      brings the service back after a reboot
.github/workflows/build.yml   builds the debug APK and publishes signed releases
```
