package com.romrobotics.bluetoothcontroller

import android.os.Bundle
import android.view.View
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
 *   SEARCH_WIFI                  → WIFI_LIST:SSID|Signal|Security|Known,...
 *   CONNECT_WIFI:ssid:password   → CONNECT_OK:INTERNET_OK / CONNECT_OK:NO_INTERNET / CONNECT_FAIL:reason
 *   DISCONNECT_WIFI              → DISCONNECT_OK / DISCONNECT_OK:NOT_CONNECTED / DISCONNECT_FAIL:reason
 *   CURRENT_WIFI                 → CURRENT_WIFI:ssid:INTERNET_OK / CURRENT_WIFI:ssid:NO_INTERNET / CURRENT_WIFI:NOT_CONNECTED
 *
 * Server push (unsolicited):
 *   WIFI_CHANGED:ssid:INTERNET_OK / WIFI_CHANGED:ssid:NO_INTERNET / WIFI_CHANGED:NOT_CONNECTED
 *
 * Known = "Y" for saved/previously connected networks, "N" otherwise.
 * Known networks can reconnect without password.
 */
class WifiManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiManageBinding

    /** Track if selected network is open (no password needed) */
    private var isOpenNetwork = false

    /** Track if selected network is a known/saved network on robot */
    private var isKnownNetwork = false

    /** Reference to the shared BluetoothService from MainActivity */
    private val btService: BluetoothService?
        get() = MainActivity.btServiceInstance

    /** Saved original connection callback from MainActivity (to restore on pause) */
    private var originalConnectionCallback: ((Boolean) -> Unit)? = null

    /** Saved original data received callback from MainActivity (to restore on pause) */
    private var originalDataCallback: ((String) -> Unit)? = null

    private fun onBluetoothLost() {
        Toast.makeText(this, "[WF-E12] Bluetooth connection lost", Toast.LENGTH_LONG).show()
        setButtonsEnabled(false)
        updateBtStatusIndicator(false)
    }

    private fun updateBtStatusIndicator(connected: Boolean) {
        supportActionBar?.subtitle = if (connected) "🔵 BT Connected" else "🔴 BT Disconnected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWifiManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "WiFi Management"

        setupButtons()
        updateWifiStatus("—", "Checking...")

        // Fetch current WiFi status on open
        fetchCurrentWifi()
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
            // Remove connection callback to prevent MA-E06 toast during intentional quit
            btService?.onConnectionChange = null
            btService?.onDataReceived = null
            btService?.disconnect()
            finishAffinity()
            // Kill the process to fully remove from memory
            android.os.Process.killProcess(android.os.Process.myPid())
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
        val password = binding.editPassword.text.toString()

        if (ssid.isEmpty()) {
            binding.layoutSsid.error = "[WF-E06] Please enter WiFi SSID"
            return
        }
        binding.layoutSsid.error = null

        // Only require password for non-open and non-known networks
        if (!isOpenNetwork && !isKnownNetwork && password.isEmpty()) {
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
            val command = if (isOpenNetwork || isKnownNetwork && password.isEmpty()) {
                // Open network or known network with saved password — no password needed
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
                    response.startsWith("CONNECT_OK") -> {
                        val internet = if (response.contains(":")) response.split(":", limit = 2)[1] else ""
                        val internetStatus = if (internet == "INTERNET_OK") "Connected \u2714 Internet" else "Connected \u2716 No Internet"
                        Toast.makeText(this, "WiFi connected to $ssid", Toast.LENGTH_SHORT).show()
                        updateWifiStatus(ssid, internetStatus)
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
     * Send DISCONNECT_WIFI command to robot.
     * Response: DISCONNECT_OK / DISCONNECT_OK:NOT_CONNECTED / DISCONNECT_FAIL:reason
     */
    private fun disconnectWifi() {
        val bt = btService
        if (bt == null || !bt.isConnected) {
            Toast.makeText(this, "[WF-E11] Bluetooth not connected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnDisconnectWifi.isEnabled = false
        binding.btnDisconnectWifi.text = "Disconnecting..."

        Thread {
            val response = bt.sendAndReceive("DISCONNECT_WIFI", timeoutMs = 20000)

            runOnUiThread {
                binding.btnDisconnectWifi.isEnabled = true
                binding.btnDisconnectWifi.text = getString(R.string.disconnect_wifi)

                when {
                    response == null -> {
                        Toast.makeText(this, "[WF-E14] No response (timeout)", Toast.LENGTH_SHORT).show()
                    }
                    response.startsWith("DISCONNECT_OK") -> {
                        val detail = if (response.contains(":")) response.split(":", limit = 2)[1] else ""
                        if (detail == "NOT_CONNECTED") {
                            Toast.makeText(this, "WiFi was not connected", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "WiFi disconnected", Toast.LENGTH_SHORT).show()
                        }
                        updateWifiStatus("—", "Disconnected")
                    }
                    response.startsWith("DISCONNECT_FAIL:") -> {
                        val reason = response.removePrefix("DISCONNECT_FAIL:")
                        Toast.makeText(this, "[WF-E15] Disconnect failed: $reason", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(this, "Response: $response", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    /**
     * Fetch current WiFi status from robot on activity open.
     */
    private fun fetchCurrentWifi() {
        val bt = btService
        if (bt == null || !bt.isConnected) {
            updateWifiStatus("—", "BT not connected")
            return
        }

        Thread {
            val response = bt.sendAndReceive("CURRENT_WIFI", timeoutMs = 20000)

            runOnUiThread {
                if (response != null && response.startsWith("CURRENT_WIFI:")) {
                    val payload = response.removePrefix("CURRENT_WIFI:")
                    if (payload == "NOT_CONNECTED") {
                        updateWifiStatus("—", "Not connected")
                    } else {
                        // Format: ssid:INTERNET_OK or ssid:NO_INTERNET
                        val parts = payload.split(":", limit = 2)
                        val ssid = parts[0]
                        val internet = parts.getOrElse(1) { "" }
                        val internetStatus = if (internet == "INTERNET_OK") "Connected \u2714 Internet" else "Connected \u2716 No Internet"
                        updateWifiStatus(ssid, internetStatus)
                    }
                } else if (response == null) {
                    updateWifiStatus("—", "Status check timeout")
                } else {
                    updateWifiStatus("—", "Unknown")
                }
            }
        }.start()
    }

    // ============================================
    // WiFi List Parsing & Selection
    // ============================================

    data class WifiNetwork(val ssid: String, val signal: String, val security: String, val isKnown: Boolean = false)

    /**
     * Parse WIFI_LIST response.
     * Format: SSID|Signal|Security|Known,SSID|Signal|Security|Known,...
     * Known = "Y" for saved/known networks, "N" otherwise
     */
    private fun parseWifiList(listStr: String): List<WifiNetwork> {
        if (listStr.isBlank() || listStr == "No networks found") return emptyList()

        return listStr.split(",").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                WifiNetwork(
                    ssid = parts[0],
                    signal = parts.getOrElse(1) { "?" },
                    security = parts.getOrElse(2) { "" },
                    isKnown = parts.getOrElse(3) { "N" } == "Y"
                )
            } else null
        }
    }

    /**
     * Show a dialog listing scanned WiFi networks.
     * User can select one to fill in the SSID field.
     */
    private fun showWifiSelectionDialog(networks: List<WifiNetwork>) {
        val displayItems = networks.map {
            val knownTag = if (it.isKnown) " [Saved]" else ""
            "${it.ssid}  (${it.signal}%)  ${it.security}$knownTag"
        }

        AlertDialog.Builder(this)
            .setTitle("Select WiFi Network")
            .setItems(displayItems.toTypedArray()) { _, which ->
                val selected = networks[which]
                binding.editSsid.setText(selected.ssid)

                // Auto-detect open network (no security)
                val securityStr = selected.security
                isOpenNetwork = securityStr.isBlank() || securityStr.contains("--") || securityStr.equals("Open", ignoreCase = true)
                isKnownNetwork = selected.isKnown

                if (isOpenNetwork || isKnownNetwork) {
                    // Hide password field for open or known networks
                    binding.layoutPassword.visibility = View.GONE
                    binding.editPassword.setText("")
                } else {
                    // Show password field and auto-focus for quick entry
                    binding.layoutPassword.visibility = View.VISIBLE
                    binding.editPassword.setText("")
                    binding.editPassword.requestFocus()
                }

                val statusMsg = if (isKnownNetwork) "Selected: ${selected.ssid} (saved)" else "Selected: ${selected.ssid}"
                Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()
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

            // Listen for server push messages (e.g. WIFI_CHANGED)
            originalDataCallback = bt.onDataReceived
            bt.onDataReceived = { data ->
                if (data.startsWith("WIFI_CHANGED:")) {
                    val payload = data.removePrefix("WIFI_CHANGED:")
                    runOnUiThread {
                        if (payload == "NOT_CONNECTED") {
                            updateWifiStatus("\u2014", "Not connected")
                        } else {
                            val parts = payload.split(":", limit = 2)
                            val ssid = parts[0]
                            val internet = parts.getOrElse(1) { "" }
                            val internetStatus = if (internet == "INTERNET_OK") "Connected \u2714 Internet" else "Connected \u2716 No Internet"
                            updateWifiStatus(ssid, internetStatus)
                        }
                    }
                } else {
                    // Forward other data to original callback
                    originalDataCallback?.invoke(data)
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
        }
    }

    override fun onPause() {
        // Restore original callbacks
        btService?.onConnectionChange = originalConnectionCallback
        btService?.onDataReceived = originalDataCallback
        originalConnectionCallback = null
        originalDataCallback = null
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
