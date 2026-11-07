# Tech

- Kotlin 1.9.20, AGP 8.2.0, Gradle 8.5 wrapper, JDK 17, compileSdk/targetSdk 34, minSdk 26.
- UI: Jetpack Compose (BOM 2024.02.02, compiler 1.5.4), Material 3. No AppCompat.
- State: `kotlinx.coroutines` StateFlow; everything BLE-related runs on the main thread.
- Keep dependencies to the current set; versions match the local Gradle cache so the
  project builds offline.

## Commands (Windows)

```powershell
$env:JAVA_HOME="C:\Program Files\jdk17"
.\gradlew.bat assembleDebug        # build
.\gradlew.bat lintDebug            # must report 0 errors
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## BLE rules

- All GATT operations go through `GattLink` (one in flight, FIFO, 3 s timeout). Never call
  `BluetoothGatt` directly from protocol code.
- Support both API 33+ and legacy GATT callbacks; copy notification byte arrays immediately.
- Keep the characteristic's default write type (official app behaviour via FastBle).
- Request MTU 128 after service discovery, before protocol setup.
- Protocols publish raw readings into `MachineState`; they never accumulate totals.
