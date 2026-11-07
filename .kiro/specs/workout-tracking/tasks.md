# Implementation Plan — Workout tracking

- [x] 1. Extract official formulas from `MotionData` / `SportStateMachineToolKt` into `CalorieModel`
  - _Requirements: 2.5, 2.6_
- [x] 2. Protocols publish raw readings with timestamps (rpm, heart rate, machine totals, machine type)
  - _Requirements: 2.2, 3.1_
- [x] 3. `WorkoutTracker` 1 Hz loop, lifecycle, auto start / pause, disconnect pause
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1_
- [x] 4. `DeviceCounter` for machine totals, fallback to formula when totals stay flat
  - _Requirements: 2.3, 2.4_
- [x] 5. Averages and heart-rate stats
  - _Requirements: 3.2_
- [x] 6. Workout card, source labels, kcal/min, history card, settings (algorithm, factor, auto-pause)
  - _Requirements: 1.5, 2.7_
- [x] 7. Forward start/pause/stop to the machine
  - _Requirements: 1.6_
