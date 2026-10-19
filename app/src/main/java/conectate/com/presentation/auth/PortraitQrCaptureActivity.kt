package conectate.com.presentation.auth

import com.journeyapps.barcodescanner.CaptureActivity

class PortraitQrCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) return
        var enabled = false
        val button = android.widget.Button(this).apply {
            text = "Encender linterna"
            setOnClickListener {
                val scanner = (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.view.ViewGroup)
                    ?.let { root -> findScanner(root) } ?: return@setOnClickListener
                enabled = !enabled
                if (enabled) scanner.setTorchOn() else scanner.setTorchOff()
                text = if (enabled) "Apagar linterna" else "Encender linterna"
            }
        }
        val density = resources.displayMetrics.density
        addContentView(button, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (52 * density).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (48 * density).toInt() })
    }

    private fun findScanner(view: android.view.View): com.journeyapps.barcodescanner.DecoratedBarcodeView? {
        if (view is com.journeyapps.barcodescanner.DecoratedBarcodeView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findScanner(view.getChildAt(index))?.let { return it }
        return null
    }
}
