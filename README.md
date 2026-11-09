# Keep Going Elliptical 🎃 (椭圆机续命)

**Kiroween 2026 · Category: Resurrection**

Mobi (莫比) sold smart ellipticals that have **no physical resistance buttons**: the magnet
resistance can only be changed from the Mobi phone app, and that app requires a login. Mobi
has since shut down and the login servers are gone. Machines that cost thousands of yuan, in
the homes of many owners, can no longer be adjusted.

Keep Going Elliptical brings them back. It is an Android app that talks to the machine over
Bluetooth LE **with no account and no network**, using the exact commands the official app
used. Those commands were recovered by reverse-engineering two versions of the official APK
with Kiro.

[中文说明 README.zh-CN.md](README.zh-CN.md) · [Protocol notes (EN)](docs/PROTOCOL.en.md) ·
[协议分析 (中文)](docs/PROTOCOL.md) · [How Kiro was used](docs/KIRO_USAGE.md)

## Why generic fitness apps fail on these machines

Mobi used four different control boards, and each one changes resistance in a non-standard way:

| Board (how it is detected) | What the official app does | What a generic client gets wrong |
|---|---|---|
| **2.0 box** (service 0x8800) | Writes `11 82 07` to 0x88FF ("unlock") once per connection, then `02 LL VV` to 0x880F. LL is the number of **decimal digits** of the level: 5 → `02 01 05`, 12 → `02 02 0C` | Without the unlock the box ignores writes; a fixed `01` breaks levels ≥ 10 |
| **1.0 box** (FFE0, frames start with 0xAB) | Writes `AB 03 00 TT SS LV xx` to FFE3, with TT/SS/xx **copied from the last frame the machine sent** | Fixed bytes are ignored; nothing can be sent before the first frame |
| **HuanTong board** (FFF0, name `MOBI-E…`) | Sends the user weight once, then `20 C1 LV 00 CS` **every second** for the whole session | A one-off write changes resistance briefly, then it falls back |
| **FTMS** (0x1826) | Request Control `00`, then `04 PP` with **one byte** PP = level × 10 | 2-byte (SINT16) or unscaled values land on the wrong level |

On top of that, the official app sends every GATT operation strictly one at a time. On Android,
a second write issued before the first completes is silently dropped (for example the resistance
write right after the unlock). This app does the same serialization.

## Features

- Scan and connect without login; the protocol is detected in the same order as the official app, or forced manually.
- Resistance: + / −, slider, presets. The range comes from the machine (0x8806 / 0x2AD6) or the official per-model table. The level the machine reports back is shown.
- Workout tracking: time, distance, calories, heart rate, cadence, speed, kcal/min, averages, and local history.
  Calories and distance use the machine's own totals when it sends them, otherwise the official formulas.
  Totals are accumulated once per second from real elapsed time, with stale-cadence detection and a burn-rate cap
  (the owner's previous home-made app showed 300+ kcal in under 10 minutes).
- Auto start / auto pause / reconnect that restores the level.
- Diagnostics: full GATT log with copy, raw hex writes to any characteristic.
- **Demo mode**: a simulated elliptical, so the app can be tried without a Mobi machine.
- English / Chinese UI and logs, following the system language.

## Try it (judges / testers)

1. Install the APK from the [latest GitHub Release](../../releases/latest) (Android 8.0+).
2. Open the app and tap **Demo mode (no device needed)**. No Bluetooth permission is needed.
3. Within 1–2 s the workout starts by itself. Change the resistance with **+ / −**: the "Machine feedback"
   number follows after ~300 ms, cadence drops at higher levels and heart rate rises.
4. In the **Demo mode** card, turn off **Pedalling** to see cadence go to 0 and, after the auto-pause time
   (Settings, default 60 s), the workout pauses itself.
5. Tap **Finish** to save the workout; it appears under **Workout history**.

The UI follows the system language: English by default, Simplified Chinese on Chinese systems
(`res/values` and `res/values-zh`). Switching the language while a workout runs keeps the session.

With a real Mobi machine: power it on and pedal a few strokes to wake the box, close the
official app, tap **Scan for devices**, then pick the highlighted `MOBI…` / `MB-…` device.

## Build

Requires JDK 17 and Android SDK 34.

```bash
./gradlew assembleDebug          # macOS / Linux
```

```powershell
$env:JAVA_HOME="C:\Program Files\jdk17"; .\gradlew.bat assembleDebug   # Windows
# or: powershell -File scripts/check.ps1   (build + lint, finds a JDK 17 automatically)
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/android.yml`) builds every
push; pushing a `v*` tag publishes the APK as a release.

## Project layout

```
app/src/main/java/com/keepgoing/elliptical/
├── EllipticalController.kt   scan, connect, reconnect, protocol choice, demo mode
├── ble/GattLink.kt           serialized GATT queue (one op in flight, 3 s timeout)
├── protocol/                 V2Protocol, V1Protocol, HuanTongProtocol, FtmsProtocol, DemoMachine
├── workout/                  WorkoutTracker (1 Hz loop), CalorieModel (official formulas)
├── model/                    state, settings
└── ui/                       Jetpack Compose
.kiro/                        specs, steering and hooks used to build this with Kiro
docs/                         protocol analysis (zh/en), Kiro usage write-up
```

## Built with Kiro

The protocol reverse-engineering, the Android app, the workout math and this submission were built
with Kiro during the Kiroween 2026 submission period. Specs, steering and hooks are in
[`.kiro/`](.kiro). Details: [docs/KIRO_USAGE.md](docs/KIRO_USAGE.md).

## Legal

This is an independent interoperability project so owners can keep using hardware they bought.
It is not affiliated with or endorsed by Mobi. The repository contains **no code, images or other
assets from the official app**. Only protocol facts needed for interoperability are documented.
The decompiled sources were used for analysis on the developer's machine and are not published.

Licensed under the [MIT License](LICENSE).
