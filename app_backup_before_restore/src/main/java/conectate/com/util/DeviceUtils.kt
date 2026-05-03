package conectate.com.util

import android.os.Build

/**
 * Utility to detect problematic Android brands that aggressively kill background apps
 */
object DeviceUtils {
    
    /**
     * Checks if the current device is from a brand known to aggressively optimize battery
     * (Xiaomi, Oppo, Realme, OnePlus, Vivo)
     */
    fun isProblematicBrand(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in listOf(
            "xiaomi",
            "oppo",
            "realme", 
            "oneplus",
            "vivo",
            "redmi",
            "poco",
            "infinix",
            "tecno",
            "itel"
        )
    }
    
    /**
     * Gets a user-friendly name for battery optimization settings based on brand
     */
    fun getBatteryOptimizationGuideName(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> "MIUI Battery Saver"
            "oppo" -> "ColorOS Battery Optimization"
            "realme" -> "RealmeUI Battery Optimization"
            "oneplus" -> "OxygenOS Battery Optimization"
            "vivo" -> "FuntouchOS Battery Optimization"
            "infinix", "tecno", "itel" -> "Power Marathon / Battery Optimization"
            else -> "Battery Optimization"
        }
    }
}


