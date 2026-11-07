# Requirements Document

> Origin: requested after the first version shipped. The owner's previous home-made app
> showed 300+ kcal after less than 10 minutes, so the calorie and distance rules below were
> derived from the official app's `MotionData` / `SportStateMachineToolKt` and hardened
> against the known accumulation bugs.

## Introduction

Per-workout statistics like the official app: duration, distance, calories, heart rate,
cadence and averages, with an honest source label and a local history.

## Requirements

### Requirement 1: Session lifecycle

**User Story:** As a user, I want the workout to start, pause and stop sensibly, so that the time reflects real exercise.

#### Acceptance Criteria

1. WHEN fresh cadence (> 0 rpm, younger than 5 s) is received while idle THEN the system SHALL start a workout automatically.
2. WHEN no fresh cadence is received for the configured auto-pause time (default 60 s, 0 = off) THEN the system SHALL auto-pause; WHEN cadence returns THEN it SHALL resume.
3. WHEN the machine disconnects during a workout THEN the system SHALL auto-pause.
4. The system SHALL count time only while running.
5. WHEN the user finishes a workout of at least 30 s THEN the system SHALL save it to local history (last 30).
6. WHEN the workout starts, pauses or stops THEN the system SHALL forward start/pause/stop to machines that support it (2.0 with 8801 bit 1, FTMS).

### Requirement 2: Correct accumulation

**User Story:** As a user, I want distance and calories that are physically plausible, so that I can trust them.

#### Acceptance Criteria

1. The system SHALL accumulate in a single 1 Hz loop using real elapsed time, never per BLE notification, and SHALL not extrapolate more than 2 s after a stall.
2. Cadence older than 5 s SHALL count as 0.
3. WHERE the machine reports running totals (2.0 0x8813, FTMS) THEN the system SHALL add only their per-second increase, ignore counter resets and single-frame glitches, and adopt a persistent jump as a new baseline without counting it.
4. IF a machine total never increases during the session THEN the system SHALL use the formula instead.
5. WHERE formulas are used THEN speed SHALL be rpm / 2.68 (/4 for ellipticals) km/h and calories SHALL use the official "new" (default) or "old" algorithm, per second.
6. The formula burn rate SHALL be 0 at 0 rpm and SHALL be capped at 20 kcal/min; a user calibration factor (30–200 %) SHALL apply.
7. The UI SHALL show the current kcal/min and whether each total comes from the machine or the formula.

### Requirement 3: Heart rate and averages

#### Acceptance Criteria

1. The system SHALL show live heart rate from 0x180D/FTMS when younger than 5 s.
2. The system SHALL compute time-weighted average cadence, average resistance, average and max heart rate over running time.
