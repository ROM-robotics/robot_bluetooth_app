package com.romrobotics.bluetoothcontroller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

        // Restrict to QR/Data Matrix to speed up decoding and enable TRY_HARDER for picky cameras
        val formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)
        val hints: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE)
        barcodeView.decoderFactory = DefaultDecoderFactory(formats, hints, null, Int.MAX_VALUE)

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
