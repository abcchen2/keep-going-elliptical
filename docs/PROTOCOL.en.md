# Mobi elliptical Bluetooth protocol notes

English translation of [PROTOCOL.md](PROTOCOL.md). Both files must stay in sync (a Kiro hook checks this).

Sources analysed:

| APK | Package | Version |
|---|---|---|
| `mobi莫比健身_3.5.9.1_APKPure.apk` | `com.anytum.mobi` | 3.5.9.1 (versionCode 134) |
| `mbjs_52901.apk` | `com.anytum.mobirowinglite` | 4.5.17 (versionCode 218) |

Tools: jadx 1.5.6 (decompile), apktool 3.0.2 (manifest).
Key source files (4.5.17): `com/anytum/mobi/device/bluetoothLe/handler/{BaseHandler,V2Handler,V1Handler,HuanTongHandler,FtmsHandler}.java`.
In 3.5.9.1 the same logic lives in `com/anytum/mobi/device/bluetoothLe/BluetoothLeService.java`.

The login only gates the account, courses and data upload (`upBleConnectInfo` and similar).
Bluetooth connection and resistance control are entirely local, so the machine can be
controlled directly once the login is skipped.

## 1. Protocol detection (BaseHandler.createHandler)

After connecting, MTU 128 is requested, then the services decide:

1. `0x8800` present → **2.0 protocol**
2. `FFE0` present → **1.0 protocol**
3. `FFF0` present and the name starts with `MOBI-` but not `MOBI-C` → **HuanTong** (`MOBI-E…` = elliptical)
4. `0x1826` present → **FTMS**
5. (3.5.9.1) any other device with `FFF0`/`FFC0` → 1.0

A machine can expose several of these (e.g. FTMS and a private service). The official app
**prefers the private protocol** in the order above. Generic elliptical apps usually go straight
to FTMS, which is a likely reason resistance misbehaves.

## 2. 2.0 protocol (service 0x8800)

| Char | Dir | Content |
|---|---|---|
| 8801 | read | `[0]` upload mode. 1 = cadence via 8811; 2/3 = aggregate data via 8813; bit 1 set = machine needs start/stop commands |
| 8802 | read | `[0]` device type (11 elliptical, 10 bike, 9 rower, 12 treadmill), `[1]` sub type |
| 8805 | read | `[0]` number of magnets (for cadence) |
| 8806 | read | `[0]` read/write mode code, `[1]` min level, `[2]` max level |
| 880E | notify | machine state 0 stop / 1 run / 2 pause |
| **88FF** | write | **unlock write permission: `11 82 07`** |
| **880F** | write | **control commands** |
| 8811 | notify | u16 big endian, ms per revolution; rpm = 60000 / interval / magnets |
| 8812 | read/notify | `[0]` current resistance level |
| 8813 | notify | `[0]` speed/10, `[2]` cadence, `[3..4]` duration, `[5..6]` distance, `[7..8]` kcal/10 (big endian) |

**Resistance** (`V2Handler.bleVer0x02Control`, commandCode 4):

```
once per connection:  88FF ← 11 82 07
then:                 880F ← 02 LL VV
```

`VV` is the level. `LL` is the **number of decimal digits** of the level, coded as
`String.valueOf(level).length()`, not a byte length:

| Level | Command |
|---|---|
| 5 | `02 01 05` |
| 12 | `02 02 0C` |
| 24 | `02 02 18` |

Typical mistakes in home-made apps:

- Skipping the 88FF unlock. The box accepts the 880F write but does nothing.
- Hard-coding the second byte to `01`. Levels 10 and above become malformed.

Other 880F commands: `03` stop, `04` start, `05` pause (only sent when 8801 bit 1 is set),
`00 LL VV` speed, `01 01 VV` incline.

**Per-model table** (used when 8806 is unavailable, 4.5.17):

| Elliptical sub type | Mode | Max level |
|---|---|---|
| 1 | 0 (official UI hides resistance) | 24 |
| 17 | 3 read/write | 24 |
| 18 (MEH3205) | 2 read only | 8 |
| 19 (MEH3204), 20 (MEH3201), 21 | 3 read/write | 24 |
| 22 | 3 read/write | **40** |
| other | 0 | 24 |

8806 `[0]` mapping (`getReadWriteMode`): 0→0, 4→1 write only, 5→3 read/write, anything else→2 read only.

## 3. 1.0 protocol (FFE0, 0xAB frames)

Subscribe to FFE4, FFE1, FFEA, FFF4, FFEB under FFE0/FFF0/FFC0 (on boxes named `MB-HW…`,
FFE4 is subscribed 2 s later).

Status frame:

```
AB 04 st TT SS bb xx i0 i1 i2 i3 .. .. RR
TT = type (11 elliptical)  SS = sub type  bb = battery
i0..i3 = ms per revolution (big endian)  RR (byte 13) = current level
```

**Resistance** (`writeBleVer0x01Resistance`), written to FFE0/FFE3:

```
AB 03 00 TT SS LV xx
```

`TT`, `SS` and `xx` must be **copied verbatim from the latest frame the machine sent** (bytes 3, 4, 6);
`LV` is the level. Consequences:

- nothing can be sent before the machine sends a frame (not powered, not pedalled yet);
- frames filled with fixed values or zeros are ignored by the box.

Cadence: rpm = 60000 / interval; divide by 2 again for sub type 17.

## 4. HuanTong board (FFF0, name `MOBI-E…`)

This board has **no "set resistance once" command**. Official flow (`HuanTongHandler`):

1. After FFF1 notifications are enabled, write the user weight to FFF2:
   `40 00 LB KG CS`, LB = kg × 2.2046226, CS = (0x40 + LB + KG) & 0xFF
2. Then, **every second for the whole workout**:

```
FFF2 ← 20 C1 LV 00 CS        CS = (0x20 + 0xC1 + LV) & 0xFF
```

(`0xC1` appears in the decompiled code as `Collation.LONG_PRIMARY_CE32_LOW_BYTE` = 193, a
constant the compiler happened to reuse.)

A single write changes the resistance briefly and then it falls back, or has no effect at all,
which matches the "connected but resistance behaves oddly" symptom. The board never reports
the level; the official app marks it write-only (mode 1) with range 1–24.

Cadence: in 12-byte FFF1 frames with `[1] != 0`, bytes `[2]` and `[3]` are formatted with
`"%X%02X"` and parsed as a **decimal** number (BCD): `01 23` → 123 rpm.

## 5. FTMS (0x1826, Mobi variant)

| Item | Official app | Typical generic FTMS client |
|---|---|---|
| Start | Write `00` (Request Control) before subscribing to anything | Subscribe to 2AD9 indications first |
| Set resistance `04` | **`04 PP`, PP is ONE byte = level × 10** | `04 lo hi` (SINT16) or the raw level |
| 2AD6 range | min/max SINT16 × 0.1 | same |
| 2ACE cross trainer data | flags usually **2 bytes** (`MB-E1010`, `Fibonacci` use 3); detected from payload length | 3 bytes as per spec, so every field after the flags is shifted |
| 2ACE resistance field | SINT16 × 0.1 | — |
| Start / stop / pause | `07` / `08 01` / `08 02` | — |

Sending `04 08` (meaning level 0.8) instead of `04 50` (level 8) leaves the machine on the
lowest level. With a 2-byte encoding the extra byte may be dropped or misread.

## 6. General notes

- The official app serializes all GATT operations with `BleGattMangerQueue`: the next one starts
  only after the previous callback. On Android a second `writeCharacteristic` issued too early
  returns `false` and the command is lost (e.g. writing resistance right after the unlock).
- The write type is Android's default for the characteristic (write-without-response when
  supported), same as FastBle.
- The official UI limits levels to 1–24 and sends them unscaled.

## 7. Workout data (time, distance, calories, heart rate)

Official motion state machine (4.5.17 `com/anytum/mobi/motionData/MotionData.java`, 3.5.9.1 `SportStateMachine.java`):

- **Source**: when the machine reports total distance/calories (2.0 box 8813, FTMS 2ACE/2AD2) the
  machine values are used; for cadence-only machines (1.0 box, HuanTong, 2.0 box in upload mode 1)
  the app computes them.
- **Speed**: `speed(km/h) = rpm / 2.68`, ellipticals additionally `/ 4`. 60 rpm ≈ 5.6 km/h.
- **Once per second** (`cumulativeData`, called by a 1 s timer): `distance += speed / 3.6`, `calories += kcal per second`.
- **Stopped pedalling**: cadence is zeroed after 5 s without a new value (`zeroMovementData`),
  auto-pause after 60 s, auto-stop after 600 s (defaults).

Calorie formulas (`SportStateMachineToolKt`; switchable in the official settings, default "old"):

| Algorithm | kcal per second |
|---|---|
| old | `power/1000 + weight/3600`. power is a quadratic surface fit in `exp(rpm/100)` and `exp(level×32/max/10)`; 0 kcal when power is 0; ×1.5 power for elliptical sub type 17 |
| new | `(T[min(96, 96/max×level)]/1000 × rpm × 60 + 0.058264444 rpm² − 4.3285 rpm + 125.303 + 12(level−1)/7 × (rpm/20 − 1.8)) / 3600` (the bracket is kcal per hour) |

`T` is a 97-value lookup table in `calculateCalories(rpm, res)` (4.5.17
`com/anytum/mobi/sportstatemachine/SportStateMachineToolKt.java`). This app keeps the same values
in `workout/CalorieModel.kt` (`NEW_TABLE`) so its totals match the official app.

At 60 rpm with max level 24 (kcal per minute):

| Level | new | old |
|---|---|---|
| 5 | 5.2 | 5.1 |
| 10 | 7.9 | 8.4 |
| 16 | 11.5 | 16.2 |
| 20 | 14.2 | ≈ 25 |

"300+ kcal in 10 minutes" needs ~30 kcal/min. Usual causes:

1. the "new" formula yields kcal **per hour** and the `/3600` was forgotten;
2. accumulating on every BLE notification (several per second) instead of per elapsed second;
3. adding the machine's **running total** again and again as if it were an increment;
4. continuing to add with the last cadence after the user stopped;
5. the "old" formula itself runs high at high levels.

What this app does (`workout/WorkoutTracker.kt`, `workout/CalorieModel.kt`):

- Accumulates only in one 1 Hz loop from real elapsed time, catching up at most 2 s per tick.
- Cadence older than 5 s counts as 0.
- Machine totals contribute only their per-second increase; counter resets and single garbage
  frames are handled; if the machine field stays at 0 the formula is used instead.
- Burn rate capped at 20 kcal/min.
- Defaults to the "new" formula, with a calibration factor.
- Time counts only while running; manual pause, auto pause (default 60 s) and disconnects do not count.
