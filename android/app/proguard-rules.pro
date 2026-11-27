-keep class com.linkme.androidbridge.core.protocol.** { *; }
-keep class com.linkme.androidbridge.core.crypto.** { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
