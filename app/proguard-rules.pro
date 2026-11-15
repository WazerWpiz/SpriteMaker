# Resprite FOSS clone — keep serialization + model classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.fengeon.resprite_desktop.**$$serializer { *; }
-keepclassmembers class com.fengeon.resprite_desktop.** {
    *** Companion;
}
-keepclasseswithmembers class com.fengeon.resprite_desktop.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep view binding
-keep class com.fengeon.resprite_desktop.databinding.** { *; }

# Strip logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
