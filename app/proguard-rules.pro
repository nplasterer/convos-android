# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Protobuf rules - CRITICAL for protobuf-lite to work with R8
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Keep protobuf classes and their members
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }

# Keep all proto generated classes
-keep class com.naomiplasterer.convos.proto.** { *; }
-keepclassmembers class com.naomiplasterer.convos.proto.** { *; }

# Keep XMTP protobuf classes (used by xmtp-android SDK)
-keep class org.xmtp.proto.** { *; }
-keepclassmembers class org.xmtp.proto.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# XMTP SDK rules
-keep class org.xmtp.android.library.** { *; }
-keepclassmembers class org.xmtp.android.library.** { *; }

# Room database rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Tink crypto library
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# JNA (Java Native Access) - Required by XMTP SDK
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }

# Keep JNA's internal peer field used for native memory management
-keepclassmembers class com.sun.jna.Pointer {
    long peer;
}
-keepclassmembers class com.sun.jna.Structure {
    *;
}
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <methods>;
}

# Keep uniffi bindings (Rust FFI used by XMTP)
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }

# Ignore JNA's desktop-only AWT references (not used on Android)
-dontwarn java.awt.**
-dontwarn javax.swing.**