---
inclusion: fileMatch
fileMatchPattern: "app/src/main/java/com/keepgoing/elliptical/workout/**"
---

# Workout metric rules

The owner's earlier app reported 300+ kcal in under 10 minutes. Keep these rules:

1. Accumulate only in `WorkoutTracker.tick()` (1 Hz) using real elapsed time, max 2 s per tick.
2. Never accumulate inside BLE callbacks.
3. Cadence older than 5 s counts as 0.
4. Machine totals are counters: add increments via `DeviceCounter`, never the raw total.
5. The official "new" calorie formula is kcal per hour; divide by 3600.
6. Burn rate 0 at 0 rpm; cap at `CalorieModel.MAX_KCAL_PER_MIN` (20).
7. Sanity reference at 60 rpm, max 24, new algorithm: level 10 ≈ 7.9 kcal/min.
