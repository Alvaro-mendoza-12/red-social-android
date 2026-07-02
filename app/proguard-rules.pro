# Keep Kotlin metadata used by reflection-adjacent libraries and tooling.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# Keep generated serialization companions when referenced by name.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.internal.** { *; }
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep app network/data contracts. Release builds are minified, but these classes are
# consumed by kotlinx.serialization, Ktor/Supabase compatibility routes and runtime JSON.
-keep class conectate.com.data.model.** { *; }
-keep class conectate.com.data.localfallback.** { *; }
-keep class conectate.com.data.repository.** { *; }
-keep class conectate.com.data.supabase.** { *; }
-keep class conectate.com.data.signaling.** { *; }
-keep class conectate.com.data.webrtc.** { *; }
-keep class conectate.com.core.** { *; }
-keep class conectate.com.di.** { *; }
-keep class conectate.com.util.** { *; }
-keep class conectate.com.util.ConversationBackgroundCodec** { *; }

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

# Supabase / Ktor / Realtime
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# WebRTC / Socket.IO
-keep class org.webrtc.** { *; }
-keep class * implements org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepclassmembers enum org.webrtc.** { *; }
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
-keep class io.socket.** { *; }
-dontwarn org.webrtc.**

# Ktor & Supabase (Fix localhost URL bug in R8 full mode)
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class io.ktor.** { *; }
-keepclassmembers class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**

# Compose / Navigation (release obfuscation can break reflection in some tooling paths)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Coil / Media3
-keep class coil.** { *; }
-keep class androidx.media3.** { *; }

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
