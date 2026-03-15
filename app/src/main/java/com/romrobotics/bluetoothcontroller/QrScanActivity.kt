package com.romrobotics.bluetoothcontroller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BeepManager
import com.journeyapps.barcodescanner.CameraSettings
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
    private lateinit var beepManager: BeepManager
    private var handled = false

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            val text = result?.text?.trim()
            if (handled || text.isNullOrEmpty()) return
            handled = true
            beepManager.playBeepSoundAndVibrate()
            val intent = Intent().putExtra(EXTRA_SCAN_RESULT, text)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
            // Ignored
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        barcodeView = findViewById(R.id.barcodeScanner)
        beepManager = BeepManager(this)

        // Restrict to QR/Data Matrix to speed up decoding
        val formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)
        barcodeView.decoderFactory = DefaultDecoderFactory(formats)

        // Use continuous autofocus and back camera
        barcodeView.barcodeView.cameraSettings = CameraSettings().apply {
            requestedCameraId = -1 // default back camera
            isAutoFocusEnabled = true
        }

        barcodeView.decodeContinuous(callback)

        findViewById<View>(R.id.btnClose).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        handled = false
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
}
