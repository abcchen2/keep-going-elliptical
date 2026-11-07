# Implementation Plan — Demo mode

- [x] 1. Add the DEMO protocol kind
  - Add `ProtocolKind.DEMO` with a label that says the machine is simulated.
  - Make `ProtocolDetector.create` reject DEMO.
  - _Requirements: 1.2, 3.3_

- [x] 2. Implement `DemoMachine`
  - Extend `MachineProtocol`; implement `start`, `setResistance`, `release`, no-op `onNotify`.
  - 1 s tick with the cadence and heart-rate model from the design.
  - Echo resistance after 300 ms; `pedalling` flag.
  - Never report device distance/calorie totals.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.2_

- [x] 3. Wire demo mode into `EllipticalController`
  - `startDemo()`, release on `connect()` / `disconnect()`, `demoPedalling` property.
  - _Requirements: 1.2, 1.3, 1.4, 1.5, 3.1_

- [x] 4. UI entry points
  - Demo button on the connect card (no Bluetooth permission prompt).
  - Pedalling switch in the workout card while in demo mode.
  - _Requirements: 1.1, 2.5, 3.3_

- [x] 5. Build and lint
  - `gradlew assembleDebug lintDebug` passes.
  - _Requirements: all_
