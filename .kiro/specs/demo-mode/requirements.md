# Requirements Document

## Introduction

Hackathon judges (and anyone evaluating the app) will almost never own a Mobi elliptical.
Demo mode simulates a connected machine entirely in software so the full app — resistance
control, workout tracking, calorie math, history — can be exercised on any Android phone
without Bluetooth hardware. It must reuse the real workout pipeline so what the judge sees
is the same code path a real machine drives.

## Glossary

- **Controller**: `EllipticalController`, owner of the machine session state.
- **Workout tracker**: `WorkoutTracker`, the 1 Hz accumulation loop.
- **Simulated machine**: the software stand-in for a Mobi elliptical.

## Requirements

### Requirement 1: Start and stop demo mode

**User Story:** As a judge without a Mobi elliptical, I want to start a simulated machine from the connect screen, so that I can try every feature of the app.

#### Acceptance Criteria

1. WHEN the user is not connected to a machine THEN the connect screen SHALL show a "Demo mode" action.
2. WHEN the user starts demo mode THEN the Controller SHALL enter the connected (READY) phase with protocol "Demo" without requesting Bluetooth permissions.
3. WHEN demo mode starts THEN the Controller SHALL stop any running BLE scan.
4. WHEN the user taps disconnect during demo mode THEN the simulated machine SHALL stop and the Controller SHALL return to the IDLE phase.
5. IF a real connection is requested while demo mode is running THEN the Controller SHALL stop demo mode before connecting.

### Requirement 2: Realistic simulated data

**User Story:** As a judge, I want the simulated machine to behave like a real one, so that the workout numbers I see are meaningful.

#### Acceptance Criteria

1. WHILE demo mode is running THEN the simulated machine SHALL report a cadence once per second through the same `reportRpm` path real protocols use.
2. WHILE demo mode is running THEN cadence SHALL vary smoothly between roughly 45 and 70 rpm and SHALL decrease as resistance increases.
3. WHEN the user sets a resistance level THEN the simulated machine SHALL report that level back after a short delay (about 300 ms), like a 2.0 box echoing on 0x8812.
4. WHILE demo mode is running THEN the simulated machine SHALL report a heart rate that rises with resistance and cadence, within 80–170 bpm.
5. WHEN the user toggles "pedalling" off in demo mode THEN cadence SHALL drop to 0 so auto-pause can be observed.
6. The simulated machine SHALL NOT report machine-side distance or calorie totals, so the formula path of the Workout tracker is exercised.

### Requirement 3: Same workout pipeline

**User Story:** As a judge, I want demo workouts to go through the real tracker, so that I am evaluating the actual implementation.

#### Acceptance Criteria

1. WHILE demo mode is running THEN the Workout tracker SHALL auto-start, accumulate, auto-pause and save history exactly as with a real machine.
2. The simulated machine SHALL use the resistance range 1–24 and machine type elliptical (11).
3. WHEN demo mode is running THEN the status card SHALL clearly label the session as simulated.
