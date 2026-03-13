package com.romrobotics.bluetoothcontroller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.romrobotics.bluetoothcontroller.databinding.ActivityWifiManageBinding

/**
 * WiFi Management Activity
 *
 * Sends WiFi commands to robot PC via Bluetooth RFCOMM.
 *
 * Server protocol:
 *   PING                         → PONG
 *   SEARCH_WIFI                  → WIFI_LIST:SSID|Signal|Security,...
 *   CONNECT_WIFI:ssid:password   → CONNECT_OK / CONNECT_FAIL:reason
 *   CURRENT_WIFI                 → CURRENT_WIFI:ssid / CURRENT_WIFI:NOT_CONNECTED
 */
class WifiManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiManageBinding

    private val securityTypes = arrayOf("WPA2-Personal", "WPA3-Personal", "WEP", "Open (None)")

    /** Reference to the shared BluetoothService from MainActivity */
    private val btService: BluetoothService?
        get() = MainActivity.btServiceInstance

    /** Saved original connection callback from MainActivity (to restore on pause) */
    private var originalConnectionCallback: ((Boolean) -> Unit)? = null

    /** Handler for periodic Bluetooth connection health check */
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private val BT_HEALTH_CHECK_INTERVAL_MS = 5000L
    private var consecutiveFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 2

    private fun scheduleNextHealthCheck() {
        healthCheckHandler.postDelayed(btHealthCheckRunnable, BT_HEALTH_CHECK_INTERVAL_MS)
    }

    private val btHealthCheckRunnable = Runnable {
        val bt = btService
        if (bt == null || !bt.isConnected) {
            onBluetoothLost()
            return@Runnable
        }

        // Send PING on background thread to verify connection is alive
        Thread {
            val response = bt.sendAndReceive("PING", timeoutMs = 4000)
            runOnUiThread {
                if (response == "PONG") {
                    consecutiveFailures = 0
                    updateBtStatusIndicator(true)
                } else {
                    consecutiveFailures++
                    android.util.Log.w("WifiManageActivity",
                        "[WF-E13] PING failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        onBluetoothLost()
                        return@runOnUiThread
                    }
                }
                // Schedule next check
                scheduleNextHealthCheck()
            }
        }.start()
    }

    private fun onBluetoothLost() {
        Toast.makeText(this, "[WF-E12] Bluetooth connection lost", Toast.LENGTH_LONG).show()
        setButtonsEnabled(false)
        updateBtStatusIndicator(false)
        stopHealthCheck()
    }

    private fun updateBtStatusIndicator(connected: Boolean) {
        supportActionBar?.subtitle = if (connected) "🔵 BT Connected" else "🔴 BT Disconnected"
    }

    private fun startHealthCheck() {
        consecutiveFailures = 0
        healthCheckHandler.removeCallbacks(btHealthCheckRunnable)
        healthCheckHandler.postDelayed(btHealthCheckRunnable, BT_HEALTH_CHECK_INTERVAL_MS)
    }

    private fun stopHealthCheck() {
        healthCheckHandler.removeCallbacks(btHealthCheckRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWifiManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "WiFi Management"

        setupSecuritySpinner()
        setupButtons()
        updateWifiStatus("—", "Unknown")

        // Fetch current WiFi status on open
        fetchCurrentWifi()
    }

    // ============================================
    // Security Type Dropdown
    // ============================================

    private fun setupSecuritySpinner() {
        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_item_dark,
            securityTypes
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        }

        binding.spinnerSecurity.adapter = adapter

        binding.spinnerSecurity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isOpen = position == 3
                binding.layoutPassword.visibility = if (isOpen) View.GONE else View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ============================================
    // Buttons
    // ============================================

    private fun setupButtons() {
        // Scan WiFi networks button
        binding.btnScanWifi.setOnClickListener {
            scanWifiNetworks()
        }

        // Connect WiFi button
        binding.btnConnectWifi.setOnClickListener {
            connectWifi()
        }

        // Disconnect WiFi button
        binding.btnDisconnectWifi.setOnClickListener {
            disconnectWifi()
        }

        // Quit app button
        binding.btnQuit.setOnClickListener {
            // Disconnect Bluetooth before quitting
            btService?.disconnect()
            finishAffinity()
        }
    }

    // ============================================
    // WiFi Commands (via Bluetooth)
    // ============================================

    /**
     * Send SEARCH_WIFI command to robot.
     * Response: WIFI_LIST:SSID|Signal|Security,...
     * Shows dialog to pick a network.
     */
    private fun scanWifiNetworks() {
        val bt = btService
        if (bt == null || !bt.isConnected) {
            Toast.makeText(this, "[WF-E01] Bluetooth not connected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnScanWifi.isEnabled = false
        binding.btnScanWifi.text = "Scanning..."

        Thread {
            val response = bt.sendAndReceive("SEARCH_WIFI", timeoutMs = 25000)

            runOnUiThread {
                binding.btnScanWifi.isEnabled = true
                binding.btnScanWifi.text = getString(R.string.scan_wifi_networks)

                if (response == null) {
                    Toast.makeText(this, "[WF-E02] No response from robot (timeout)", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                if (response.startsWith("ERROR:")) {
                    Toast.makeText(this, "[WF-E03] Error: ${response.removePrefix("ERROR:")}", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                if (response.startsWith("WIFI_LIST:")) {
                    val listPart = response.removePrefix("WIFI_LIST:")
                    val networks = parseWifiList(listPart)

                    if (networks.isEmpty()) {
                        Toast.makeText(this, "[WF-E04] No WiFi networks found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    showWifiSelectionDialog(networks)
                } else {
                    Toast.makeText(this, "[WF-E05] Unexpected response: $response", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Send CONNECT_WIFI:ssid:password to robot.
     */
    private fun connectWifi() {
        val ssid = binding.editSsid.text.toString().trim()
        val securityIndex = binding.spinnerSecurity.selectedItemPosition
        val password = binding.editPassword.text.toString()

        if (ssid.isEmpty()) {
            binding.layoutSsid.error = "[WF-E06] Please enter WiFi SSID"
            return
        }
        binding.layoutSsid.error = null

        if (securityIndex != 3 && password.isEmpty()) {
            binding.layoutPassword.error = "[WF-E07] Please enter password"
            return
        }
        binding.layoutPassword.error = null

        val bt = btService
        if (bt == null || !bt.isConnected) {
            Toast.makeText(this, "[WF-E08] Bluetooth not connected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnConnectWifi.isEnabled = false
        binding.btnConnectWifi.text = "Connecting..."
        updateWifiStatus(ssid, "Connecting...")

        Thread {
            val command = if (securityIndex == 3) {
                // Open network — no password
                "CONNECT_WIFI:$ssid:"
            } else {
                "CONNECT_WIFI:$ssid:$password"
            }

            val response = bt.sendAndReceive(command, timeoutMs = 35000)

            runOnUiThread {
                binding.btnConnectWifi.isEnabled = true
                binding.btnConnectWifi.text = getString(R.string.connect_wifi)

                when {
                    response == null -> {
                        Toast.makeText(this, "[WF-E09] No response (timeout)", Toast.LENGTH_SHORT).show()
                        updateWifiStatus(ssid, "Timeout")
                    }
                    response == "CONNECT_OK" -> {
                        Toast.makeText(this, "WiFi connected to $ssid", Toast.LENGTH_SHORT).show()
                        updateWifiStatus(ssid, "Connected")
                    }
                    response.startsWith("CONNECT_FAIL:") -> {
                        val reason = response.removePrefix("CONNECT_FAIL:")
                        Toast.makeText(this, "[WF-E10] WiFi failed: $reason", Toast.LENGTH_LONG).show()
                        updateWifiStatus(ssid, "Failed: $reason")
                    }
                    else -> {
                        Toast.makeText(this, "Response: $response", Toast.LENGTH_SHORT).show()
                        updateWifiStatus(ssid, response)
                    }
                }
            }
        }.start()
    }

    /**
     * Send disconnect WiFi command (not in current server protocol,
     * but included for future use).
     */
    private fun disconnectWifi() {
        val bt = btService
        if (bt == null || !bt.isConnected) {
            Toast.makeText(this, "[WF-E11] Bluetooth not connected", Toast.LENGTH_SHORT).show()
            return
        }

        // Server doesn't have a disconnect_wifi command yet,
        // but we send it for forward compatibility
        bt.send("DISCONNECT_WIFI")
        Toast.makeText(this, "WiFi disconnect command sent", Toast.LENGTH_SHORT).show()
        updateWifiStatus("—", "Disconnected")
    }

    /**
     * Fetch current WiFi status from robot on activity open.
     */
    private fun fetchCurrentWifi() {
        val bt = btService
        if (bt == null || !bt.isConnected) return

        Thread {
            val response = bt.sendAndReceive("CURRENT_WIFI", timeoutMs = 10000)

            runOnUiThread {
                if (response != null && response.startsWith("CURRENT_WIFI:")) {
                    val ssid = response.removePrefix("CURRENT_WIFI:")
                    if (ssid == "NOT_CONNECTED") {
                        updateWifiStatus("—", "Not connected")
                    } else {
                        updateWifiStatus(ssid, "Connected")
                    }
                }
            }
        }.start()
    }

    // ============================================
    // WiFi List Parsing & Selection
    // ============================================

    data class WifiNetwork(val ssid: String, val signal: String, val security: String)

    /**
     * Parse WIFI_LIST response.
     * Format: SSID|Signal|Security,SSID|Signal|Security,...
     */
    private fun parseWifiList(listStr: String): List<WifiNetwork> {
        if (listStr.isBlank() || listStr == "No networks found") return emptyList()

        return listStr.split(",").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                WifiNetwork(
                    ssid = parts[0],
                    signal = parts.getOrElse(1) { "?" },
                    security = parts.getOrElse(2) { "" }
                )
            } else null
        }
    }

    /**
     * Show a dialog listing scanned WiFi networks.
     * User can select one to fill in the SSID field.
     */
    private fun showWifiSelectionDialog(networks: List<WifiNetwork>) {
        val displayItems = networks.map { "${it.ssid}  (${it.signal}%)  ${it.security}" }

        AlertDialog.Builder(this)
            .setTitle("Select WiFi Network")
            .setItems(displayItems.toTypedArray()) { _, which ->
                val selected = networks[which]
                binding.editSsid.setText(selected.ssid)

                // Auto-select security type based on server response
                val secIdx = when {
                    selected.security.contains("WPA3", ignoreCase = true) -> 1
                    selected.security.contains("WPA", ignoreCase = true) -> 0
                    selected.security.contains("WEP", ignoreCase = true) -> 2
                    selected.security.isBlank() || selected.security.contains("--") -> 3
                    else -> 0
                }
                binding.spinnerSecurity.setSelection(secIdx)

                Toast.makeText(this, "Selected: ${selected.ssid}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================
    // WiFi Status Display
    // ============================================

    private fun updateWifiStatus(ssid: String, status: String) {
        binding.textWifiSsid.text = "SSID: $ssid"
        binding.textWifiStatus.text = "Status: $status"
    }

    // ============================================
    // Navigation
    // ============================================

    override fun onResume() {
        super.onResume()
        // Monitor BT connection state while in this activity
        val bt = btService
        if (bt != null) {
            originalConnectionCallback = bt.onConnectionChange
            bt.onConnectionChange = { connected ->
                // Forward to original callback (MainActivity)
                originalConnectionCallback?.invoke(connected)
                // Handle disconnect in this activity
                if (!connected) {
                    runOnUiThread {
                        onBluetoothLost()
                    }
                }
            }
        }
        // Check if already disconnected
        if (bt == null || !bt.isConnected) {
            setButtonsEnabled(false)
            updateBtStatusIndicator(false)
        } else {
            setButtonsEnabled(true)
            updateBtStatusIndicator(true)
            // Start periodic health check
            startHealthCheck()
        }
    }

    override fun onPause() {
        // Stop health check and restore original callback
        stopHealthCheck()
        btService?.onConnectionChange = originalConnectionCallback
        originalConnectionCallback = null
        super.onPause()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnScanWifi.isEnabled = enabled
        binding.btnConnectWifi.isEnabled = enabled
        binding.btnDisconnectWifi.isEnabled = enabled
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
