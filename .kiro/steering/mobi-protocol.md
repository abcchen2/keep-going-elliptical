---
inclusion: fileMatch
fileMatchPattern: "app/src/main/java/com/keepgoing/elliptical/protocol/**"
---

# Mobi protocol invariants

Do not "simplify" these; each one is why a naive client fails.

| Box | Invariant |
|---|---|
| 2.0 (0x8800) | Write `11 82 07` to 0x88FF once per connection before any 0x880F write. Resistance is `02 LL VV`, LL = decimal digit count of VV (`String.valueOf(v).length`). |
| 1.0 (FFE0) | Resistance `AB 03 00 TT SS LV xx` to FFE3, TT/SS/xx copied from the last frame the machine sent. Queue until a frame arrives. |
| HuanTong (FFF0, `MOBI-E…`) | Weight frame once, then `20 C1 LV 00 CS` every second for the whole session. A single write is not enough. |
| FTMS (0x1826) | Request control `00` first. Set resistance `04 PP`, PP one byte = level × 10. Cross Trainer Data flags are often 2 bytes, detect by payload length. |

Detection order: 0x8800 → FFE0 → FFF0 + `MOBI-` (not `MOBI-C`) → 0x1826 → FFF0/FFC0.

Every byte format must be backed by a decompiled source location in `docs/PROTOCOL.md`.
When changing protocol code, update `docs/PROTOCOL.md` and `docs/PROTOCOL.en.md`.
