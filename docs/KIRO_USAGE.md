# How Kiro was used

Everything in this repository was built in the **Kiro IDE** during the Kiroween 2026 submission
period, by one developer working in Chinese with the Kiro agent in Autopilot mode. Kiro CLI,
Kiro Web and Kiro Crew were not used.

## 1. Vibe sessions: reverse-engineering a dead product

The starting point was a problem rather than a codebase: two APKs of the official Mobi app and
an elliptical whose resistance "connects but does not adjust properly" with a home-made app. In
a conversational (vibe) session the agent:

1. Found JDK 21 on the machine, downloaded jadx and decompiled both APKs (22,625 and 11,333 classes).
2. Located the BLE layer among obfuscated packages (`u.j.a`, `e.j.a.a`, …) and read the
   connect callback, the four protocol handlers, the GATT queue and the FastBle wrapper.
3. Spotted the non-obvious details, each of which explains a failure mode of a naive client:
   - the 88FF unlock and the "decimal digit count" length byte (2.0);
   - copying bytes from the last machine frame (1.0);
   - the 1 Hz HuanTong keep-alive, whose `0xC1` constant was hidden behind an unrelated ICU
     constant (`Collation.LONG_PRIMARY_CE32_LOW_BYTE`) by the compiler;
   - FTMS one-byte ×10 resistance and the 2-byte Cross Trainer flags.
4. Noticed that the second APK was a **newer** version (4.5.17 vs 3.5.9.1) with an FTMS handler,
   and compared both instead of trusting the first.
5. Wrote the Android project (Kotlin, Compose), built it with Gradle and fixed lint errors.

Vibe mode suited this phase: the requirements were unknown until the decompiled code was read.

## 2. Specs: turning findings into commitments

`/.kiro/specs/` contains three specs:

| Spec | How it was used |
|---|---|
| `mobi-resistance-control` | Written right after the exploration, to pin the reverse-engineered facts down as EARS acceptance criteria (detection order, exact frames, serialized GATT, range sources). It is the checklist the protocol code follows. |
| `workout-tracking` | Written when the owner reported that their previous app showed 300+ kcal in under 10 minutes. The requirements encode the official formulas and every guard against over-counting. The design records the reference values the formulas must produce. |
| `demo-mode` | **Spec first, then code.** Judges do not own a Mobi elliptical, so requirements → design → tasks were written before any demo code, then the tasks were implemented one by one and checked off. |

Compared with vibe coding: the specs made "done" testable. For example, workout-tracking
requirement 2.3 forced a decision on how machine counters behave after a reset or a glitch,
which led to `DeviceCounter` (and a bug fix when a glitch frame followed by a real value was
over-counted: 18 m instead of 15 m in the check run).

## 3. Steering

`/.kiro/steering/`:

- `product.md`: who the users are, privacy (no network), "never ship official code or assets".
- `tech.md`: toolchain pinned to the versions in the local Gradle cache, plus the BLE rules
  (everything through `GattLink`, keep the default write type, MTU 128).
- `structure.md`: where new protocols and features go.
- `mobi-protocol.md` (`fileMatch: protocol/**`): the invariants that must never be
  "simplified away", loaded only when protocol code is in context.
- `workout-metrics.md` (`fileMatch: workout/**`): the seven accumulation rules.

The two file-scoped steering files work best: they keep long protocol details out of unrelated
sessions but make sure an edit to `V2Protocol.kt` cannot forget the unlock.

## 4. Hooks

`/.kiro/hooks/`:

| Hook | Trigger | Purpose |
|---|---|---|
| Build and lint after spec task | PostTaskExec | Runs `scripts/check.ps1` (finds a JDK 17, `assembleDebug lintDebug`) so a spec task is never closed on a broken build. |
| Keep protocol docs in sync | PostFileSave on `protocol/*.kt` | Agent re-checks bytes/UUIDs against `docs/PROTOCOL.md` and `docs/PROTOCOL.en.md` and updates both. |
| Workout metrics sanity check | PostFileSave on `workout/*.kt` | Agent re-validates the accumulation rules. |

## 5. Verification

- Each change set was finished with `assembleDebug` and `lintDebug` (0 lint errors).
- The calorie formulas and `DeviceCounter` were checked with a throwaway JVM run over a grid of
  cadence/level values. The table in `docs/PROTOCOL.en.md` §7 comes from that run.
- Byte formats were cross-checked between the two official versions.
- Hardware testing is done by the owner on the real machine; the in-app GATT log can be copied
  and pasted back into Kiro for analysis.

## 6. Not used

MCP servers, Kiro powers, custom agents and property-based tests were not used. The project needed
local tools (JDK, jadx, Gradle) more than external integrations.
