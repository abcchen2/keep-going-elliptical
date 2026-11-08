# Devpost submission draft (Kiroween 2026)

Copy these fields into the Devpost form. Replace the `<…>` placeholders.

## Basics

- **Project name:** Keep Going Elliptical
- **Elevator pitch:** Mobi went bust and took the login server with it, leaving smart ellipticals with no way to change resistance. We reverse-engineered the official app with Kiro and brought the machines back to life, no account needed.
- **Category:** Resurrection
- **Repository:** `https://github.com/<your-user>/keep-going-elliptical`
- **App URL:** `https://github.com/<your-user>/keep-going-elliptical/releases/latest` (APK download)
- **Video:** `<YouTube link>`
- **Built with:** Kotlin, Jetpack Compose, Android Bluetooth LE (GATT), FTMS, jadx, Gradle, Kiro IDE (specs, steering, hooks)

## About the project

### Inspiration

My Mobi (莫比) elliptical has no resistance buttons. Resistance can only be changed from the Mobi
app, which requires a login. Mobi closed and the servers went with it, so a machine worth
thousands of yuan became a fixed-resistance exerciser. Many other owners are in the same
situation. I had already written a small app that could connect, but resistance never behaved.

### What it does

- Connects to Mobi ellipticals over Bluetooth LE with no account and no internet.
- Detects which of the four Mobi control boards the machine uses, in the same order as the official app.
- Controls resistance with the exact commands the official app sent, and shows the level the machine reports back.
- Tracks each workout: time, distance, calories, heart rate, cadence, averages and history, using the official formulas plus guards against over-counting.
- Demo mode simulates a machine so anyone can try it without the hardware.

### How I built it

Entirely in Kiro. A vibe session decompiled both versions of the official APK (3.5.9.1 and
4.5.17) with jadx and traced the obfuscated BLE layer. The findings became specs, steering files
and hooks, and drove the Android implementation. See `docs/KIRO_USAGE.md`.

### Challenges

- Each control board changes resistance differently:
  - an unlock write plus a "decimal digit count" byte on the 2.0 box;
  - bytes echoed from the machine's own last frame on the 1.0 box;
  - a mandatory 1 Hz keep-alive on the HuanTong board;
  - a one-byte ×10 value over FTMS, with non-standard 2-byte data flags.
- Android silently drops a GATT write issued before the previous one finishes, so everything goes through a serialized queue.
- Calories: the official "old" formula reaches ~25 kcal/min at high levels, and accumulating per notification multiplies totals. The tracker integrates once per second from real elapsed time, ignores stale cadence, and adds only the increments of machine counters.

### Accomplishments

- One app covers four protocols, and every byte format is traced back to a location in the decompiled source.
- The machine works again without the vendor.

### What I learned

- In reverse-engineering, the bugs hide in "obvious" details. The length byte turned out to be a digit count, and a constant was an ICU symbol the compiler reused.
- Specs are most useful right after exploration, to turn discoveries into checkable commitments.

### What's next

- Test on more Mobi models with other owners, using the copyable GATT log.
- A foreground service so the HuanTong keep-alive survives the screen turning off.

## Testing instructions (judges)

1. Download the APK from the release link and install it (Android 8.0+). Allow installing from unknown sources if asked.
2. Tap **Demo mode (no device needed)**. No Bluetooth permission or hardware is needed.
3. The workout starts within 1–2 s. Use **+ / −** under **Resistance**. The "Machine feedback" number follows after ~300 ms, cadence drops at higher levels and heart rate rises.
4. In the **Demo mode** card, switch off **Pedalling**. Cadence goes to 0. After the auto-pause time (default 60 s; **Settings → Seconds without pedalling before auto-pause**) the workout pauses.
5. Tap **Finish**. The workout appears in **Workout history**.
6. Optional: expand **Log & debug** to see the event log.

The app follows the phone language: English by default, Simplified Chinese on Chinese systems.

## Pre-existing code declaration

None. The project was created on 2026-11-08 inside the submission period. My earlier home-made app
is not part of this repository. The official Mobi APKs were analysed locally; no code or assets
from them are included. Third-party dependencies are listed in `app/build.gradle.kts`.

## Demo video script (under 3 minutes)

Record on the phone and the machine. No Mobi logos or copyrighted music. Add English subtitles.

| Time | Shot | Voice-over / subtitle |
|---|---|---|
| 0:00–0:20 | The elliptical, close-up of the console with no buttons; the official app stuck on a login error | "This elliptical has no resistance buttons. The only way to change it was the vendor's app, and the vendor is gone." |
| 0:20–0:45 | Kiro IDE: decompiled `V2Handler`, `HuanTongHandler`; the protocol table in `docs/PROTOCOL.en.md` | "With Kiro I decompiled two versions of the official app and found why generic apps fail: an unlock write, a digit-count byte, a 1 Hz keep-alive, a one-byte FTMS value." |
| 0:45–1:30 | Phone: scan, connect, protocol detected; press + several times; show the machine feedback number; feet on the pedals as resistance gets heavier | "No account, no internet. The app detects the board and sends exactly what the official app sent, and the machine reports the level back." |
| 1:30–2:05 | Workout card: time, distance, kcal/min ≈ 8 at level 10, heart rate; stop pedalling → auto pause; finish → history | "Workout tracking uses the official formulas, integrated once per second, so ten minutes no longer means 300 calories." |
| 2:05–2:30 | Demo mode on a second phone | "No machine? Demo mode runs the same pipeline." |
| 2:30–2:55 | `.kiro/` folder: specs, steering, hooks | "Specs captured the protocol, steering guards the invariants, and hooks keep the build and docs honest. Thousands of machines can keep going." |

## Optional bonus prizes

- **Bonus Blog Prize:** a post about this build on an external blog, saying it was written for Kiroween 2026.
- **Social Blitz:** a post starting with `#KiroweenHackathon26` about your favourite Kiro feature.
