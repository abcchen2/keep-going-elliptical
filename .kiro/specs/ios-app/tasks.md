# Implementation Plan — iOS app

- [x] 1. String generator
  - `scripts/gen-ios-strings.ps1`: Android `strings.xml` (en, zh) → `Localizable.strings` (en, zh-Hans, zh-Hant) and `InfoPlist.strings`.
  - Add `ios_bt_usage` to both Android files with `tools:ignore="UnusedResources"`.
  - Run it on Windows and check escapes, format specifiers and key counts; Android build and lint still pass.
  - _Requirements: 7.2, 7.3_

- [x] 2. Project skeleton and CI (push early to prove the toolchain)
  - `ios/project.yml`, `Info.plist` (Bluetooth usage, iOS 16, en/zh localisations), `@main` app with three empty tabs.
  - `.github/workflows/ios.yml` producing the unsigned IPA artifact and release asset.
  - `.gitignore` for generated project, strings and build output.
  - Push (after asking the owner) and get a green run.
  - _Requirements: 1.1, 1.2, 1.3, 1.5_

- [x] 3. Model and helpers
  - `MachineState`, enums, `WorkoutStats`, `WorkoutRecord`, `UiText`, `AppSettings` (UserDefaults, Android defaults and keys), `Bytes`, `BleUuids`, `ResistanceTables`, `L`.
  - _Requirements: 3.4, 4.3, 7.3_

- [x] 4. `GattLink` on CoreBluetooth
  - Lazy central, power/permission state, scan, connect with 15 s timeout, full discovery, serialized FIFO queue with 3 s timeout, write-type choice, write-without-response flow control, read vs notify dispatch, retrieve by identifier.
  - _Requirements: 2.1, 2.2, 2.3, 2.5, 3.2, 3.3_

- [x] 5. Protocols
  - `MachineProtocol` base (range, reports, standard services), `V2Protocol`, `V1Protocol`, `HuanTongProtocol`, `FtmsProtocol`, `ProtocolDetector`.
  - Side-by-side parity check with the Kotlin files.
  - _Requirements: 2.3, 2.4, 3.1, 3.4, 3.6_

- [x] 6. Workout tracking
  - `CalorieModel`, `WorkoutTracker` (1 Hz loop, lifecycle, auto start/pause, disconnect pause), `DeviceCounter`, history persistence.
  - Parity check against `workout-metrics.md`.
  - _Requirements: 4.1, 4.2, 5.1_

- [x] 7. Demo mode
  - `DemoMachine` port.
  - _Requirements: 6.1_

- [x] 8. `EllipticalController`
  - Scan list and sorting, connect / connect last / disconnect, protocol pick, reconnect policy, resistance coalescing, demo start, log buffer (400 lines).
  - _Requirements: 2.1, 2.4, 2.5, 2.6, 2.7, 3.5_

- [x] 9. UI
  - Workout tab (header, status, connect card, device list, session bar, workout card, demo card, resistance card), History tab, Settings tab (device details + log + copy), screen kept awake.
  - _Requirements: 2.8, 3.5, 3.6, 5.2, 5.3, 5.4, 7.1, 7.4_

- [x] 10. Green CI build with the full app
  - Fix compile errors until the IPA artifact is produced.
  - _Requirements: 1.1_

- [x] 11. Docs, steering and hooks
  - README / README.zh-CN: iOS install with Sideloadly (iTunes/iCloud web versions, Developer Mode, 7-day re-sign).
  - `structure.md` / `tech.md`: `ios/` layout, strings generation, CI.
  - Hook: when a protocol or workout file changes on one platform, check the other platform still matches.
  - _Requirements: 1.4_

- [ ] 12. Owner check on the iPhone
  - Install with Sideloadly, run demo mode, then the real machine; send the copied log if anything fails.
  - _Requirements: 1.4, 2, 3, 4, 5, 6_
