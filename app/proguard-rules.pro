# Preserve only metadata consumed at runtime. Kotlin serialization, Compose,
# Room, Hilt, Media3, Coil, Ktor and OkHttp ship their own consumer rules; broad
# package keeps here defeat R8 shrinking and noticeably increase startup I/O.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Generated serializers are referenced by generated code. Allow their names and
# implementation to be optimized while retaining members that a serializer
# lookup can access.
-keepclassmembers,allowoptimization,allowobfuscation class **$$serializer { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# App contracts use generated kotlinx serializers and generated Room/Hilt code;
# no application package needs a blanket keep. Android manifest components and
# WorkManager workers are retained by Android/AndroidX rules below.
-keepclassmembers enum * { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { *; }
-keepclasseswithmembers class * {
    @dagger.* <methods>;
    @javax.inject.* <fields>;
}
-keep class conectate.com.Hilt_* { *; }
-keep class conectate.com.**_HiltModules** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_MembersInjector { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# WorkManager / Firebase
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Supabase / Ktor / Realtime provide consumer rules (including Ktor engine
# ServiceLoader entries and atomicfu fields). Keep only optional-dependency
# warnings here instead of retaining both libraries wholesale.
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**

# WebRTC / Socket.IO
-keep class org.webrtc.** { *; }
-keep class * implements org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepclassmembers enum org.webrtc.** { *; }
# WebRTC's native library loads JNI Zero bootstrap classes by their original
# names. R8 cannot infer those native references and otherwise removes JniInit.
-keep class org.jni_zero.** { *; }
-keepnames class org.jni_zero.**
-keepclassmembers class * {
    native <methods>;
}
-keep @interface org.webrtc.CalledByNative
-keep @interface org.webrtc.CalledByNativeUnchecked
-keepclassmembers class * {
    @org.webrtc.CalledByNative <methods>;
    @org.webrtc.CalledByNative <fields>;
    @org.webrtc.CalledByNativeUnchecked <methods>;
    @org.webrtc.CalledByNativeUnchecked <fields>;
}
-dontwarn org.webrtc.**

# Compose, Navigation, Coil and Media3 are intentionally governed by their
# published consumer rules. Do not add blanket keeps for these packages.

# Supabase / Realtime transitively references SLF4J binder implementations that are absent on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder

# Reactor may reference optional BlockHound integration classes that are not packaged on Android.
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn reactor.core.scheduler.ReactorBlockHoundIntegration

# Optional desktop/JVM classes referenced transitively by networking libraries.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp publishes its own R8 rules. Optional platform providers are absent on
# some Android devices and are already covered above.
-dontwarn okhttp3.internal.platform.**

# Production privacy/performance: debug builds retain their diagnostic detail,
# while the minified Play artifact removes direct logcat calls. Several legacy
# flows include user/chat/message identifiers in diagnostic strings; production
# observability must use the already configured structured Crashlytics/backend
# channels instead of exposing those values through the device log buffer.
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int w(java.lang.String, java.lang.String);
    public static int w(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int w(java.lang.String, java.lang.Throwable);
    public static int e(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int wtf(java.lang.String, java.lang.String);
    public static int wtf(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int println(int, java.lang.String, java.lang.String);
}
