package conectate.com.presentation.auth

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class PortraitQrCaptureActivity : CaptureActivity() {
    private var scanner: DecoratedBarcodeView? = null

    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { scanner = it }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) return
        var enabled = false
        val button = android.widget.Button(this).apply {
            tag = "qr-torch-toggle"
            text = "Encender linterna"
            setOnClickListener {
                val activeScanner = scanner ?: return@setOnClickListener
                if (isFinishing || isDestroyed) return@setOnClickListener
                try {
                    val next = !enabled
                    if (next) activeScanner.setTorchOn() else activeScanner.setTorchOff()
                    enabled = next
                    text = if (enabled) "Apagar linterna" else "Encender linterna"
                } catch (_: RuntimeException) {
                    android.widget.Toast.makeText(this@PortraitQrCaptureActivity, "La linterna no está disponible. Puedes seguir escaneando.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val density = resources.displayMetrics.density
        addContentView(button, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (52 * density).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (48 * density).toInt() })
    }

    override fun onDestroy() {
        scanner = null
        super.onDestroy()
    }
}
