# Uniffi-generated Kotlin FFI bridge: classes bind to the native library by
# exact package/name, and JNA loads the .so by reflection — R8 must keep all.
-keep class uniffi.igris_protocol.** { *; }

# JNA runtime (libigris_protocol loading + native calls go through it).
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { public *; }
-keepclassmembers class * extends com.sun.jna.** { <fields>; <methods>; }
-dontwarn com.sun.jna.**
-dontwarn sun.misc.**

# Keep FFI-linked methods reachable when Serde/JSON code reflects on them.
-keepclasseswithmembernames class * {
    native <methods>;
}
