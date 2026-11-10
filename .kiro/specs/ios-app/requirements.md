# Requirements Document

> Origin: the owner wants the app on his own iPhone. He has no Mac and no paid Apple
> Developer account, so the app is built on GitHub-hosted macOS runners and installed by
> self-signing with a free Apple ID (Sideloadly on Windows). Personal use first; App Store
> distribution may come later and is out of scope here.

## Introduction

A native iOS version of Keep Going Elliptical with the core job (connect over BLE without an
account and control resistance exactly like the official app), workout tracking and local
history. It must speak the same bytes as the Android app and follow the same workout metric
rules, because both talk to the same machines and the Android version is the reference.

## Glossary

- **Android reference**: the Kotlin app under `app/`, version 1.0.1 plus the bottom-navigation change.
- **Controller**: the iOS equivalent of `EllipticalController`.
- **GATT link**: the iOS equivalent of `GattLink`, a wrapper around CoreBluetooth.
- **Unsigned IPA**: an `.ipa` built with code signing disabled; Sideloadly signs it at install time.
- **Peripheral identifier**: the UUID CoreBluetooth assigns to a device. iOS does not expose MAC addresses.

## Requirements

### Requirement 1: Build and install without a Mac

**User Story:** As the owner without a Mac or developer account, I want an installable iOS package produced for me, so that I can put the app on my iPhone from Windows.

#### Acceptance Criteria

1. WHEN code is pushed to `main`, a `v*` tag is pushed, or the workflow is started manually THEN a GitHub Actions workflow on a GitHub-hosted macOS runner SHALL build the iOS app and upload an unsigned IPA as a workflow artifact.
2. WHEN a `v*` tag is pushed THEN the workflow SHALL attach the unsigned IPA to the GitHub Release, next to the APK.
3. The Xcode project SHALL be generated during the build from a text file in the repository, so that no Mac is needed to add or rename source files.
4. The unsigned IPA SHALL install with Sideloadly using a free Apple ID on iOS 16 or later.
5. The iOS build SHALL NOT change the Android build, its dependencies or its workflow.

### Requirement 2: Connect to the machine

**User Story:** As a Mobi owner, I want to find and connect my elliptical from the iPhone, so that I can use it without the official app.

#### Acceptance Criteria

1. WHEN the user taps "Scan" THEN the app SHALL scan for 12 s without a service filter and list devices that have a name or advertised services, Mobi-like devices (name `MOBI…`/`MB-…` or a known Mobi service) first, then by signal strength.
2. The app SHALL ask for Bluetooth permission only when the user first scans or connects; IF Bluetooth is off or permission is denied THEN the app SHALL say so instead of failing silently.
3. WHEN the user taps a device THEN the app SHALL connect, discover all services and characteristics, and pick the protocol in the Android order: 0x8800 → FFE0 → FFF0 + name `MOBI-` (not `MOBI-C`) → 0x1826 → FFF0/FFC0.
4. IF no known protocol is found THEN the app SHALL show an error and disconnect.
5. The app SHALL remember the last device (peripheral identifier and name) and offer "Connect last" on the next launch.
6. WHEN the link drops without the user asking THEN the app SHALL reconnect up to 5 times with a delay of 1.5 s × attempt, and restore the target resistance after reconnecting.
7. WHEN the user taps "Disconnect" THEN the app SHALL close the link and SHALL NOT reconnect.
8. WHILE connected or reconnecting THEN the app SHALL keep the screen awake.

### Requirement 3: Resistance control identical to Android

**User Story:** As a user, I want resistance changes to land on the right level on every Mobi box, so that the iPhone app is as reliable as the Android one.

#### Acceptance Criteria

1. The app SHALL implement the 2.0 (0x8800), 1.0 (FFE0/FFF0/FFC0), HuanTong (FFF0, `MOBI-E…`) and FTMS (0x1826) protocols with the byte formats and invariants in `.kiro/steering/mobi-protocol.md` and `docs/PROTOCOL.en.md`.
2. The app SHALL run at most one GATT operation at a time, in FIFO order, with a 3 s timeout per operation.
3. The app SHALL pick the write type from the characteristic properties the same way Android does by default: without response if the characteristic supports it, otherwise with response.
4. The app SHALL read the resistance range from the machine or the official tables exactly like the Android reference, and clamp every level to it.
5. The resistance screen SHALL offer −/+ buttons, a slider and five presets; fast taps SHALL be coalesced into one command after 120 ms (the last value wins).
6. The resistance screen SHALL show the level reported back by the machine when the protocol provides it.

### Requirement 4: Workout tracking identical to Android

**User Story:** As a user, I want the same honest time, distance and calorie numbers as on Android, so that both apps agree.

#### Acceptance Criteria

1. The app SHALL meet Requirements 1–3 of `.kiro/specs/workout-tracking/requirements.md` and the rules in `.kiro/steering/workout-metrics.md`.
2. Accumulation SHALL use a monotonic clock, at most 2 s per tick, so time spent with the app suspended is not extrapolated.
3. Until a settings screen exists, the app SHALL use the Android defaults: weight 60 kg, official "new" calorie formula, calorie factor 100 %, auto-pause 60 s, no max-resistance override, FTMS encoding UINT8 × 10, protocol auto-detect, auto-reconnect on.

### Requirement 5: Workout history

**User Story:** As a user, I want to look back at my workouts, so that I can see my progress.

#### Acceptance Criteria

1. WHEN a workout of at least 30 s is finished THEN the app SHALL save it on the phone, keeping the newest 30.
2. A "History" tab SHALL list all saved workouts, newest first, with date, duration, distance, calories, average cadence, average resistance and heart rate when available.
3. WHEN there are no workouts THEN the History tab SHALL show an empty-state message.
4. The user SHALL be able to clear the history.

### Requirement 6: Demo mode

**User Story:** As the owner, I want to try the iPhone app before walking to the machine, so that I can tell UI problems from Bluetooth problems.

#### Acceptance Criteria

1. The app SHALL provide the demo mode of `.kiro/specs/demo-mode/requirements.md` (simulated elliptical, same workout pipeline), started without Bluetooth permission.

### Requirement 7: Layout, language and diagnostics

**User Story:** As a Chinese-speaking user, I want the same layout and language as the Android app, and a log I can send when something goes wrong.

#### Acceptance Criteria

1. The app SHALL use bottom tabs like the Android app: Workout, History, and Device (device details and log; settings will be added here later).
2. The app SHALL follow the system language: English by default, Simplified Chinese when the system language is Chinese.
3. Every user-visible string SHALL come from the Android `strings.xml` files, so that both apps use the same wording and the same keys.
4. The Device tab SHALL show protocol, machine, model, firmware and battery while connected, and the last 400 log lines with a "Copy log" action.
5. The app SHALL make no network requests, use no accounts and collect no analytics.

## Out of scope (later)

- Settings screen (protocol choice, FTMS encoding, weight, calorie formula and factor, auto-pause, max-resistance override, auto-reconnect, verbose notify log).
- Raw-write debug tool.
- Keeping the session alive while the app is in the background or the phone is locked.
- App Store or TestFlight distribution, app icon artwork beyond a simple placeholder.
