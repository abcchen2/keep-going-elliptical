# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /tools/proguard/proguard-android-optimize.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class javax.annotation.** { *; }

# Health Connect
-keep class androidx.health.connect.** { *; }

# BLE
-keep class android.bluetooth.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }
