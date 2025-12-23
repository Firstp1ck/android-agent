# Android Agent ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.agent.**$$serializer { *; }
-keepclassmembers class com.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep core types
-keep class com.agent.core.** { *; }

# Accessibility Service
-keep class com.agent.service.AgentAccessibilityService { *; }

# Compose
-dontwarn androidx.compose.**

