# Requirements Document

> Origin: the BLE protocols were reverse-engineered in exploratory Kiro vibe sessions
> (decompiling the official Mobi APKs 3.5.9.1 and 4.5.17). This spec was written right
> after that exploration to pin the findings down as testable requirements; the
> implementation in `app/src/main/java/.../protocol/` follows it.

## Introduction

Mobi (莫比) smart ellipticals have no physical resistance buttons; resistance can only be
changed from the official app, which requires logging in to servers that no longer exist.
This feature connects to the machine directly over Bluetooth LE and controls resistance
exactly the way the official app did, without any account.

## Glossary

- **2.0 box**: control box exposing GATT service 0x8800.
- **1.0 box**: control box exposing FFE0/FFF0/FFC0 and streaming 0xAB frames.
- **HuanTong board**: FFF0 board advertised as `MOBI-…` (not `MOBI-C`).
- **FTMS**: Bluetooth SIG Fitness Machine Service 0x1826 as implemented by Mobi.

## Requirements

### Requirement 1: Find and connect without login

**User Story:** As an owner of a Mobi elliptical, I want to find and connect to my machine without an account, so that I can use it after the vendor shut down.

#### Acceptance Criteria

1. WHEN the user starts a scan THEN the system SHALL list nearby BLE devices for 12 seconds and highlight names starting with `MOBI` or `MB-` or advertising a known service.
2. WHEN the user selects a device THEN the system SHALL connect, discover services and request MTU 128 before any protocol traffic.
3. The system SHALL NOT contact any network service.
4. IF the connection drops during use AND auto-reconnect is enabled THEN the system SHALL retry up to 5 times with increasing delay and restore the last resistance level.

### Requirement 2: Detect the protocol like the official app

**User Story:** As a user, I want the app to pick the right protocol automatically, so that I do not need to know which control box my machine has.

#### Acceptance Criteria

1. WHEN services are discovered THEN the system SHALL choose, in order: 0x8800 → 2.0, FFE0 → 1.0, FFF0 with name `MOBI-` (not `MOBI-C`) → HuanTong, 0x1826 → FTMS, other FFF0/FFC0 → 1.0.
2. WHERE the user forced a protocol in settings THEN the system SHALL use it instead and log the auto-detected result.
3. IF no known service exists THEN the system SHALL disconnect and show an error.

### Requirement 3: Resistance control per protocol

**User Story:** As a user, I want + / − / slider changes to actually change the magnet resistance, so that I can train.

#### Acceptance Criteria

1. WHEN setting resistance on a 2.0 box THEN the system SHALL write `11 82 07` to 0x88FF once per connection and then `02 LL VV` to 0x880F, where LL is the number of decimal digits of VV.
2. WHEN setting resistance on a 1.0 box THEN the system SHALL write `AB 03 00 TT SS LV xx` to FFE3, copying TT, SS and xx from the latest frame the machine sent; IF no frame was received yet THEN the system SHALL queue the level and send it after the first frame.
3. WHILE connected to a HuanTong board THEN the system SHALL write the user weight `40 00 LB KG CS` once and then `20 C1 LV 00 CS` to FFF2 every second.
4. WHEN setting resistance over FTMS THEN the system SHALL first have written Request Control `00` and SHALL send `04 PP` with one byte PP = level × 10 (encoding switchable in settings).
5. The system SHALL serialize all GATT operations (one in flight, 3 s timeout) so no write is dropped by the Android stack.
6. The system SHALL clamp levels to the range read from the machine (0x8806, 0x2AD6) or, if absent, to the official per-model table; a manual max override SHALL take precedence.
7. WHEN the user changes resistance quickly THEN the system SHALL coalesce changes within 120 ms into one command carrying the latest value.

### Requirement 4: Diagnostics

**User Story:** As a user whose machine behaves differently, I want to see what is sent and received, so that I can report or fix the problem.

#### Acceptance Criteria

1. The system SHALL log every GATT write with its bytes, failures and timeouts, and allow copying the log.
2. WHEN connected THEN the system SHALL allow writing raw hex to any characteristic.
