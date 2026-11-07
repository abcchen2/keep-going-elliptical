# Structure

```
app/src/main/java/com/keepgoing/elliptical/
  EllipticalController.kt   scan / connect / reconnect / protocol choice / demo mode
  ble/                      GattLink (serialized GATT), UUIDs, byte helpers
  protocol/                 one MachineProtocol per control box + DemoMachine + tables
  workout/                  WorkoutTracker (1 Hz loop), CalorieModel (official formulas)
  model/                    MachineState, settings, enums
  ui/                       Compose screens and theme
docs/                       PROTOCOL.md (zh), PROTOCOL.en.md (en), KIRO_USAGE.md, SUBMISSION.md
.kiro/                      specs, steering, hooks
```

- New machine families: add a `MachineProtocol` subclass, register it in `ProtocolDetector`,
  document bytes in both PROTOCOL files.
- New user-visible features: start with a spec in `.kiro/specs/<feature>/`.
- Settings live in `AppSettings` (SharedPreferences), read on demand.
