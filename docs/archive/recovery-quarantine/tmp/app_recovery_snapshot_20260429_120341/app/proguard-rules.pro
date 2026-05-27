# Keep Kotlin metadata used by reflection-adjacent libraries and tooling.
-keep class kotlin.Metadata { *; }

# Keep generated serialization companions when referenced by name.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.internal.** { *; }

# Supabase / Realtime transitively references SLF4J binder implementations that are absent on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Reactor may reference optional BlockHound integration classes that are not packaged on Android.
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn reactor.core.scheduler.ReactorBlockHoundIntegration
