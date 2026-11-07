# Design Document — Workout tracking

## Overview

`workout/WorkoutTracker` reads `MachineState` once per second and owns a `WorkoutStats`
StateFlow. `workout/CalorieModel` holds the official formulas. Protocols only publish raw
readings (`rpm` + timestamp, heart rate + timestamp, machine running totals).

## Key decisions

| Decision | Why |
|---|---|
| Integrate rate × elapsed time in one 1 Hz loop | Notifications arrive at 1–10 Hz; per-notification accumulation multiplies totals. |
| 5 s staleness for cadence | Official `zeroMovementData`; stops a frozen rpm from adding forever. |
| `DeviceCounter` increments | Machine totals may start non-zero, reset, or glitch. |
| Default to official "new" algorithm | "Old" exponential fit reaches ~25 kcal/min at 60 rpm level 20/24. |
| 20 kcal/min cap | Above elite-athlete sustained burn; guards against bad cadence. |

## Formulas (from SportStateMachineToolKt, official 4.5.17)

- old: `power/1000 + weight/3600` kcal/s, power = quadratic in `exp(rpm/100)` and `exp(res·32/max/10)`, ×1.5 for elliptical sub type 17.
- new: `(T[min(96, 96/max·res)]/1000·rpm·60 + 0.058264444·rpm² − 4.3285·rpm + 125.303 + 12(res−1)/7·(rpm/20 − 1.8)) / 3600` kcal/s.

Reference values at 60 rpm, max 24 (kcal/min, new / old): level 5 → 5.2 / 5.1, level 10 → 7.9 / 8.4, level 20 → 14.2 / 25 (capped at 20).

## Testing Strategy

Formula outputs were checked with a throwaway JVM run over a grid of rpm/level values
(table above); `DeviceCounter` was checked with a sequence containing an offset start, a reset
and a glitch (expected 15 m, got 15 m). Demo mode exercises the full loop on a phone.
