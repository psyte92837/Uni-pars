# Uni-pars R8 rules.

# Keep generic signatures, annotations and Kotlin metadata used by Jackson and kotlinx.serialization.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# Anthropic Java SDK: builders and Jackson (de)serialization rely on reflection.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }

# Our serializable models (recipes are stored as JSON; field names must survive).
-keep class com.unipars.engine.** { *; }
-keep class com.unipars.ai.** { *; }
-keepclassmembers class com.unipars.app.data.** { *; }

# WebView JavaScript bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Optional dependencies referenced by libraries but not used on Android.
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn com.github.victools.**
-dontwarn com.google.re2j.**
-dontwarn com.standardwebhooks.**
-dontwarn io.opentelemetry.**
-dontwarn io.swagger.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn org.slf4j.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
