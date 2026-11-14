# JNA (used by the UniFFI bindings) looks up classes and fields reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# Generated UniFFI bindings: structures are accessed by JNA via reflection.
-keep class dev.drift.core.** { *; }
