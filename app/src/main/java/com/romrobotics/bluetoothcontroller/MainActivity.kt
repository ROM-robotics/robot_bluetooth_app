package com.romrobotics.bluetoothcontroller

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.romrobotics.bluetoothcontroller.databinding.ActivityMainBinding

/**
 * Main Activity
 *
 * - MAC address input (manual or QR camera scan)
 * - Bluetooth connect button
 * - Navigate to WiFi management
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    lateinit var btService: BluetoothService
        private set

    private var wasConnected = false

    companion object {
        private const val PREFS_NAME = "bt_robot_prefs"
        private const val KEY_DEVICE_MAC = "device_mac"

        /** Accessible from WifiManageActivity to send commands */
        var btServiceInstance: BluetoothService? = null
            private set
    }

    // QR code scanner launcher
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            val scanned = result.contents.trim()
            // Accept raw MAC or extract from string
            val mac = extractMacAddress(scanned)
            if (mac != null) {
                binding.editMacAddress.setText(mac)
                Toast.makeText(this, "MAC address scanned", Toast.LENGTH_SHORT).show()
            } else {
                // Put raw content if no MAC pattern found
                binding.editMacAddress.setText(scanned)
                Toast.makeText(this, "Scanned: $scanned", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, "Camera permission is required for QR scanning", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize Bluetooth service (singleton-like via companion)
        btService = BluetoothService(this)
        btServiceInstance = btService

        // Bluetooth connection callbacks
        btService.onConnectionChange = { connected ->
            updateConnectionStatus(connected)
            if (!connected && wasConnected) {
                Toast.makeText(this, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected
        }

        btService.onDataReceived = { data ->
            // Log unexpected data from robot
            android.util.Log.d("MainActivity", "Robot data: $data")
        }

        btService.onConnectionError = { errorMsg ->
            Toast.makeText(this, "Connection failed: $errorMsg", Toast.LENGTH_LONG).show()
        }

        // Request Bluetooth permissions
        requestBluetoothPermissions()

        // Load saved MAC address
        val savedMac = prefs.getString(KEY_DEVICE_MAC, "") ?: ""
        if (savedMac.isNotEmpty()) {
            binding.editMacAddress.setText(savedMac)
        }

        setupButtons()
        updateConnectionStatus(false)
    }

    // ============================================
    // Buttons
    // ============================================

    private fun setupButtons() {
        // Camera scan button
        binding.btnCameraScan.setOnClickListener {
            if (hasCameraPermission()) {
                launchQrScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Connect button -> connect BT and go to WifiManageActivity
        binding.btnConnect.setOnClickListener {
            val mac = binding.editMacAddress.text.toString().trim()

            if (mac.isEmpty()) {
                binding.layoutMacAddress.error = "Please enter MAC address"
                return@setOnClickListener
            }

            if (!isValidMac(mac)) {
                binding.layoutMacAddress.error = "Invalid format (XX:XX:XX:XX:XX:XX)"
                return@setOnClickListener
            }

            binding.layoutMacAddress.error = null

            // Save MAC address
            prefs.edit().putString(KEY_DEVICE_MAC, mac).apply()

            // Disable button while connecting
            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = "Connecting..."

            // Connect via Bluetooth SPP
            btService.connect(mac)

            Toast.makeText(this, "Connecting to $mac ...", Toast.LENGTH_SHORT).show()

            // Navigate to WiFi Management activity
            val intent = Intent(this, WifiManageActivity::class.java)
            startActivity(intent)
        }
    }

    // ============================================
    // QR Scanner
    // ============================================

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.DATA_MATRIX)
            setPrompt("Scan QR code containing MAC address")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
        }
        qrScanLauncher.launch(options)
    }

    // ============================================
    // Helpers
    // ============================================

    private fun updateConnectionStatus(connected: Boolean) {
        runOnUiThread {
            binding.btnConnect.text = getString(R.string.connect)
            binding.btnConnect.isEnabled = !connected
        }
    }

    private fun extractMacAddress(input: String): String? {
        val macRegex = Regex("([0-9A-Fa-f]{2}[:\\-]){5}[0-9A-Fa-f]{2}")
        val match = macRegex.find(input)
        return match?.value?.uppercase()?.replace("-", ":")
    }

    private fun isValidMac(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // Bluetooth Permissions
    // ============================================

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    // ============================================
    // Lifecycle
    // ============================================

    override fun onResume() {
        super.onResume()
        // Refresh connection status when returning from WifiManageActivity
        updateConnectionStatus(btService.isConnected)
    }

    override fun onDestroy() {
        btService.disconnect()
        btServiceInstance = null
        super.onDestroy()
    }
}
