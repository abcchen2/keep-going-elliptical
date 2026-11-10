# Design Document — iOS app

## Overview

A native Swift / SwiftUI app under `ios/`, built on GitHub Actions (`macos-26` runner,
default Xcode 26.x) into an unsigned IPA that the owner signs and installs with Sideloadly
and a free Apple ID. The code is a file-by-file port of the Android reference: same class
names, same comments on byte formats, same state model. Strings are generated from the
Android `strings.xml` files.

```
ios/
  project.yml                    XcodeGen spec (the .xcodeproj is generated in CI, not committed)
  KeepGoing/
    Info.plist
    App/        KeepGoingApp.swift (@main, TabView), L.swift (localised strings, safe formatting)
    Ble/        GattLink.swift, BleUuids.swift, Bytes.swift
    Protocol/   MachineProtocol.swift, V2Protocol.swift, V1Protocol.swift, HuanTongProtocol.swift,
                FtmsProtocol.swift, DemoMachine.swift, ProtocolDetector.swift, ResistanceTables.swift
    Workout/    CalorieModel.swift, WorkoutTracker.swift (incl. DeviceCounter)
    Model/      MachineState.swift, AppSettings.swift, UiText.swift
    EllipticalController.swift
    UI/         WorkoutTab.swift, HistoryTab.swift, DeviceTab.swift, Components.swift
    Resources/  en.lproj, zh-Hans.lproj, zh-Hant.lproj (generated Localizable.strings + InfoPlist.strings)
scripts/gen-ios-strings.ps1      Android strings.xml -> iOS .strings
.github/workflows/ios.yml        build + unsigned IPA + release asset
```

## Key decisions

| Decision | Alternatives | Why |
|---|---|---|
| Native Swift + SwiftUI | Kotlin Multiplatform, Flutter | KMP needs new Gradle plugins and dependencies that are not in the offline cache (tech steering) and still needs a Mac toolchain for iOS. Flutter means rewriting Android too. Swift talks to CoreBluetooth directly. Cost: logic exists twice; mitigated by a 1:1 file mapping and a parity hook. |
| XcodeGen `project.yml`, project generated in CI | Hand-written `.pbxproj` | A `.pbxproj` cannot be maintained without Xcode. `project.yml` is short text; new files are picked up by folder. |
| Strings generated from Android XML | Separate iOS string files | One source of truth, same keys, same wording; the existing string-sync hook keeps en/zh aligned for both apps. |
| Unsigned IPA, signed by Sideloadly | Signing in CI | Free Apple ID certificates cannot be used in CI; Sideloadly signs with the user's Apple ID at install time. |
| iOS 16 minimum, `ObservableObject` | iOS 17 `@Observable` | Covers older iPhones; nothing here needs iOS 17. |
| Swift 5 language mode, everything on the main queue | Swift 6 strict concurrency | Mirrors Android's main-thread model (CBCentralManager on the main queue) and avoids hundreds of isolation errors that cannot be fixed without a local compiler. |
| No background mode in v1 | `bluetooth-central` background mode | iOS suspends timers in the background, so the HuanTong 1 Hz keep-alive and the 1 Hz tracker would stall anyway. v1 keeps the screen awake like Android; background support is a later spec. |

## Android → iOS mapping

### GATT link (CoreBluetooth)

| Android `GattLink` | iOS `GattLink` |
|---|---|
| `BluetoothAdapter` + scanner in the controller | `GattLink` owns a lazily created `CBCentralManager` (first scan/connect triggers the permission prompt; demo mode never creates it). Actions wait for `.poweredOn`; `.poweredOff` / `.unauthorized` are reported to the controller. |
| `connectGatt(autoConnect=false)`, ~30 s system timeout | `central.connect(p)` never times out on iOS, so the link adds a 15 s connect timeout that cancels and reports a failed connection (feeds the normal reconnect logic). |
| `discoverServices()` after 400 ms delay | `discoverServices(nil)`, then `discoverCharacteristics(nil, for:)` for every service; ready when all are done. No delay needed. |
| `requestMtu(128)` | Not available; iOS negotiates MTU itself. The link logs `maximumWriteValueLength(for: .withoutResponse)`. All Mobi frames are ≤ 7 bytes. |
| Default write type from properties | `.withoutResponse` if `.writeWithoutResponse` is in the properties, else `.withResponse`. Same choice as Android's default `writeType`. |
| `onCharacteristicWrite` | With response: `didWriteValueFor`. Without response: the op completes right after the write if `canSendWriteWithoutResponse`, otherwise after `peripheralIsReady(toSendWriteWithoutResponse:)`. |
| CCCD write | `setNotifyValue(true, for:)` → `didUpdateNotificationStateFor` (iOS picks notify or indicate itself). |
| `onCharacteristicRead` vs `onCharacteristicChanged` | Both arrive as `didUpdateValueFor`. If the op in flight is a read of that characteristic, it completes the read; otherwise the value is a notification. |
| MAC address | `peripheral.identifier` (UUID string) is stored as `lastDeviceAddress`. "Connect last" uses `retrievePeripherals(withIdentifiers:)`; if iOS no longer knows the device, the app starts a scan and says so. |
| Queue, 3 s timeout, `pendingWrites` | Same FIFO queue of `Op` values, same timeout, same API names. |

The rest of the code depends only on this API, so protocol files read like the Kotlin ones:
`link.characteristic(svc, ch)`, `link.findCharacteristic(ch)`, `link.read`, `link.write`,
`link.enableNotify`, `link.hasService`, `link.pendingWrites`.

### Protocols, tracker, model

Straight ports of the Kotlin files with the same names, fields and invariants
(`mobi-protocol.md`, `workout-metrics.md`). Platform substitutions:

| Kotlin | Swift |
|---|---|
| `ByteArray`, `Byte.u()` | `[UInt8]`, `Int(b)`; helpers `u16be`, `u32be`, `u16le`, `s16le`, `u24le`, `toHex` in `Bytes.swift` |
| `UUID` / `uuid16("8800")` | `CBUUID(string: "8800")` |
| `host.postDelayed` / `Runnable` | `DispatchWorkItem` on the main queue |
| `SystemClock.elapsedRealtime()` | `ProcessInfo.processInfo.systemUptime` in ms (monotonic) |
| `StateFlow` | `@Published` on `ObservableObject` (controller, tracker) |
| `SharedPreferences` | `UserDefaults`, same keys; history JSON keeps the Android shape (`t, sec, m, kcal, rpm, res, hr, hrMax`) |
| `@StringRes Int` in state | `String` key (`UiText` = key + args), resolved at display time |

### Strings

`scripts/gen-ios-strings.ps1` reads `values/strings.xml` and `values-zh/strings.xml` and writes
`Localizable.strings` for `en`, `zh-Hans` and `zh-Hant` (Android's `values-zh` also serves
Traditional Chinese systems), plus `InfoPlist.strings` (display name from `app_name`,
Bluetooth usage text from `ios_bt_usage`). Conversion:

- XML entities and Android escapes (`\"`, `\'`, `&amp;`) are decoded; the value is re-escaped for `.strings`.
- `%n$s` → `%n$@`; `%n$d` / `%n$02X` → `%n$ld` / `%n$02lX` (Swift `Int` is 64-bit). Floats unchanged.
- `translatable="false"` entries are written to every language, because iOS does not fall back per key.

`L(key)` returns the raw string (like `getString(id)` without arguments, so a literal `%`
stays as is); `L(key, args...)` formats it. The formatter reads the specifier at each position
and converts the argument to match (`%@` → `String(describing:)`, `%ld` → `Int`, `%f` →
`Double`), so a Kotlin call that passes a number to `%s` cannot crash on iOS.

The only new key is `ios_bt_usage` (Info.plist permission text), added to both Android files
with `tools:ignore="UnusedResources"`.

### UI

SwiftUI `TabView` with the Android tab labels and order: Workout (`tab_workout`),
History (`tab_history`), Settings (`tab_settings`, showing device details and the log for now).
The Workout tab follows the Android layout: header + status + connect card + device list when
idle; compact session bar + workout card + demo card + resistance card while connected.
`UIApplication.shared.isIdleTimerDisabled` is set while READY or RECONNECTING.

## CI workflow (`.github/workflows/ios.yml`)

1. `actions/checkout`
2. `pwsh scripts/gen-ios-strings.ps1`
3. `brew install xcodegen` and `xcodegen generate` in `ios/`
4. `xcodebuild -scheme KeepGoing -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`
5. Copy `KeepGoing.app` into `Payload/`, zip as `keep-going-elliptical-unsigned.ipa`
6. Upload as artifact; on `v*` tags attach to the release (`softprops/action-gh-release`)

Bundle id `com.keepgoing.elliptical` (Sideloadly may rewrite it for a free account).

Target device: the owner's iPhone 17 Pro on iOS 27.0.1. The runner's default Xcode 26.6 builds
with the iOS 26 SDK, and such apps run on iOS 27; Xcode 27 is only a public preview on the
runner, so it is not pinned. Sideloadly supports iOS 27.

## Installing (owner's machine)

Sideloadly on Windows needs the web-installer versions of iTunes and iCloud (not the Microsoft
Store ones). The iPhone needs Developer Mode on (iOS 16+). A free Apple ID allows 3
sideloaded apps and the signature expires after 7 days; re-signing with Sideloadly keeps the
app and its data. The README gets a step-by-step section in both languages.

## Error handling

- Bluetooth off or permission denied: message on the connect card (reuses `toast_enable_bt` / `toast_need_permission`).
- Connect timeout, unknown protocol, disconnects: same phases, error keys and reconnect policy as Android.
- Malformed history JSON: start with an empty history, as on Android.

## Testing strategy

There is no Mac and no local Swift compiler, so:

1. **Compile in CI.** The skeleton (task 2) is pushed first so toolchain problems show up
   before the port. Each later task ends with a green CI build.
2. **Parity review.** Each Swift protocol / tracker file is reviewed side by side with its
   Kotlin original for byte formats, timing constants and order of operations.
3. **On the owner's iPhone.** Demo mode first (UI, tracker, history), then the real
   machine; the copied log is the evidence when something fails.

## Risks

- The first CI runs will likely fail on compile errors that cannot be caught locally; expect a few fix-and-push rounds.
- CoreBluetooth caches GATT tables per peripheral; if a box changes services, iOS may need Bluetooth toggled off/on. Logged service list helps spot this.
- Locking the phone suspends the app; HuanTong boards then drop the resistance and the link may close. Documented as a v1 limitation.
