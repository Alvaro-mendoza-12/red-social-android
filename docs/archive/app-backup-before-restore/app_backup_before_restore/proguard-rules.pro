# Removed Firebase classes after migration to Supabase

# Keep Agora SDK
-keep class io.agora.** { *; }

# Keep data models for serialization
-keepclassmembers class conectate.com.data.model.** {
    *;
}

# Keep ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
