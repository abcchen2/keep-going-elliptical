# Structure

```
app/src/main/java/com/keepgoing/elliptical/
  EllipticalController.kt   scan / connect / reconnect / protocol choice / demo mode
  ble/                      GattLink (serialized GATT), UUIDs, byte helpers
  protocol/                 one MachineProtocol per control box + DemoMachine + tables
  workout/                  WorkoutTracker (1 Hz loop), CalorieModel (official formulas)
  model/                    MachineState, settings, enums
  ui/                       Compose screens and theme
docs/                       PROTOCOL.md (zh), PROTOCOL.en.md (en), KIRO_USAGE.md
.kiro/                      specs, steering, hooks
```

- New machine families: add a `MachineProtocol` subclass, register it in `ProtocolDetector`,
  document bytes in both PROTOCOL files.
- New user-visible features: start with a spec in `.kiro/specs/<feature>/`.
- Settings live in `AppSettings` (SharedPreferences), read on demand.

## iOS (`ios/`, spec `.kiro/specs/ios-app`)
```
ios/project.yml             XcodeGen spec; the .xcodeproj is generated in CI, never committed
ios/KeepGoing/              Swift port, one file per Kotlin file: Ble/, Protocol/, Workout/, Model/, UI/
scripts/gen-ios-strings.ps1 Android strings.xml -> Resources/{en,zh-Hans,zh-Hant}.lproj (generated, ignored)
.github/workflows/ios.yml   macos-26 runner, unsigned IPA artifact, release asset on v* tags
```
- Android is the reference. A change to a protocol, `GattLink` behaviour or workout rule must be
  made in both `app/` and `ios/` (the "Keep Android and iOS in sync" hook checks this).
- iOS uses the Android string keys. New iOS-only text goes into both Android `strings.xml` files with
  an `ios_` prefix and `tools:ignore="UnusedResources"`; never hard-code UI text in Swift.
