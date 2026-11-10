# KUVAVpn ProGuard rules

# --- gomobile generated bindings (libv2ray / AndroidLibXrayLite) ---
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**

# --- hev-socks5-tunnel JNI ---
-keep class com.kuva.vpn.core.tunnel.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.kuva.vpn.**$$serializer { *; }
-keepclassmembers class com.kuva.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.kuva.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / TLS ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Xray configs are parsed inside the native core; keep JSON model fields ---
-keep class com.kuva.vpn.core.config.model.** { *; }

# Keep crash reports useful
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
