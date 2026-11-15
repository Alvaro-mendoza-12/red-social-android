# The test APK omits runtime dependencies already supplied by the tested APK.
# JUnit reaches this QA runtime transitively and would otherwise be removed as
# unused by the application, leaving AndroidJUnitRunner without RunListener.
# These rules apply to auditRelease only; production release rules stay separate.
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
