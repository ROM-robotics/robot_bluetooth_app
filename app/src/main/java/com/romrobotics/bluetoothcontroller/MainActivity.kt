package com.romrobotics.bluetoothcontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import android.app.Activity
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

    // QR scanner launcher (uses custom in-app scanner for better reliability)
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT)?.trim()
            if (!scanned.isNullOrEmpty()) {
                val mac = extractMacAddress(scanned)
                if (mac != null) {
                    binding.editMacAddress.setText(mac)
                    Toast.makeText(this, "MAC address scanned", Toast.LENGTH_SHORT).show()
                } else {
                    binding.editMacAddress.setText(scanned)
                    Toast.makeText(this, "Scanned: $scanned", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Bluetooth enable launcher
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            setUiEnabled(true)
        } else {
            Toast.makeText(this, "[MA-E07] Bluetooth must be enabled to use this app", Toast.LENGTH_LONG).show()
            setUiEnabled(false)
        }
    }

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, "[MA-E01] Camera permission is required for QR scanning", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "[MA-E02] Bluetooth disconnected", Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected
        }

        btService.onDataReceived = { data ->
            // Log unexpected data from robot
            android.util.Log.d("MainActivity", "Robot data: $data")
        }

        btService.onConnectionError = { errorMsg ->
            Toast.makeText(this, "[MA-E03] Connection failed: $errorMsg", Toast.LENGTH_LONG).show()
        }

        // Request Bluetooth permissions
        requestBluetoothPermissions()

        // Load saved MAC address (default: robot's BT address)
        val savedMac = prefs.getString(KEY_DEVICE_MAC, "88:D8:2E:76:DD:5A") ?: "88:D8:2E:76:DD:5A"
        binding.editMacAddress.setText(savedMac)

        setupButtons()
        updateConnectionStatus(false)

        // Check Bluetooth is enabled
        checkBluetoothEnabled()
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

        // Connect button -> connect BT, navigate only after connected
        binding.btnConnect.setOnClickListener {
            val mac = binding.editMacAddress.text.toString().trim()

            if (mac.isEmpty()) {
                binding.layoutMacAddress.error = "[MA-E04] Please enter MAC address"
                return@setOnClickListener
            }

            if (!isValidMac(mac)) {
                binding.layoutMacAddress.error = "[MA-E05] Invalid format (XX:XX:XX:XX:XX:XX)"
                return@setOnClickListener
            }

            binding.layoutMacAddress.error = null

            // Save MAC address
            prefs.edit().putString(KEY_DEVICE_MAC, mac).apply()

            // Disable button while connecting
            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = "Connecting..."

            // Set up one-time callback to navigate on successful connection
            btService.onConnectionChange = { connected ->
                updateConnectionStatus(connected)
                if (connected) {
                    // Navigate to WiFi Management activity only when connected
                    val intent = Intent(this, WifiManageActivity::class.java)
                    startActivity(intent)
                    wasConnected = true
                } else {
                    if (wasConnected) {
                        Toast.makeText(this, "[MA-E06] Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                    } else {
                        // Connection attempt failed — re-enable button
                        runOnUiThread {
                            binding.btnConnect.isEnabled = true
                            binding.btnConnect.text = getString(R.string.connect)
                        }
                    }
                }
            }

            // Connect via Bluetooth SPP
            btService.connect(mac)

            Toast.makeText(this, "Connecting to $mac ...", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================
    // QR Scanner
    // ============================================

    private fun launchQrScanner() {
        val intent = Intent(this, QrScanActivity::class.java)
        qrScanLauncher.launch(intent)
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

    /**
     * Check if Bluetooth is enabled. If not, prompt user to enable it.
     * UI is disabled until Bluetooth is turned on.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun checkBluetoothEnabled() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "[MA-E08] This device does not support Bluetooth", Toast.LENGTH_LONG).show()
            setUiEnabled(false)
            return
        }

        if (!adapter.isEnabled) {
            setUiEnabled(false)
            Toast.makeText(this, "[MA-E07] Please enable Bluetooth to continue", Toast.LENGTH_LONG).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        } else {
            setUiEnabled(true)
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.btnConnect.isEnabled = enabled
        binding.btnCameraScan.isEnabled = enabled
        binding.editMacAddress.isEnabled = enabled
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
        // Restore default connection callback (stop auto-navigation)
        btService.onConnectionChange = { connected ->
            updateConnectionStatus(connected)
            if (!connected && wasConnected) {
                Toast.makeText(this, "[MA-E02] Bluetooth disconnected", Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected
        }
        // Refresh connection status when returning from WifiManageActivity
        updateConnectionStatus(btService.isConnected)

        // Re-check Bluetooth state every time activity resumes
        checkBluetoothEnabled()
    }

    override fun onDestroy() {
        btService.disconnect()
        btServiceInstance = null
        super.onDestroy()
    }
}
