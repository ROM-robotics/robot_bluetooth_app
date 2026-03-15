package com.romrobotics.bluetoothcontroller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.EnumMap
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Standalone QR scanner screen using ZXing's embedded view.
 * Continuous decode with autofocus for better reads.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_RESULT = "scan_result"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var handled = false
    private var torchOn = false
    private val decodeFormats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)
    private val decodeHints: MutableMap<DecodeHintType, Any> =
        EnumMap(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            val text = result?.text?.trim()
            if (handled || text.isNullOrEmpty()) return
            handled = true
            val intent = Intent().putExtra(EXTRA_SCAN_RESULT, text)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {
            // Ignored
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        barcodeView = findViewById(R.id.barcodeScanner)

        // Restrict to QR/Data Matrix and enable TRY_HARDER for picky cameras
        barcodeView.decoderFactory = DefaultDecoderFactory(decodeFormats, decodeHints)

        barcodeView.decodeContinuous(callback)

        val torchButton = findViewById<MaterialButton>(R.id.btnTorch)
        val hasFlash = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        if (!hasFlash) {
            torchButton.visibility = View.GONE
        } else {
            torchButton.setOnClickListener {
                torchOn = !torchOn
                if (torchOn) {
                    barcodeView.setTorchOn()
                    torchButton.text = getString(R.string.qr_torch_off)
                } else {
                    barcodeView.setTorchOff()
                    torchButton.text = getString(R.string.qr_torch_on)
                }
            }
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        handled = false
        runCatching { barcodeView.resume() }
    }

    override fun onPause() {
        super.onPause()
        if (torchOn) {
            runCatching { barcodeView.setTorchOff() }
            torchOn = false
        }
        runCatching { barcodeView.pause() }
    }
}
