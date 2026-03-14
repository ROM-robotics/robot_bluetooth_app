package com.romrobotics.bluetoothcontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bluetooth SPP (Serial Port Profile) Service
 *
 * Manages RFCOMM connection to the robot's Bluetooth server.
 * Protocol: Newline-terminated plain text commands/responses.
 *
 * Server commands:
 *   PING                         → PONG
 *   SEARCH_WIFI                  → WIFI_LIST:SSID|Signal|Security,...
 *   CONNECT_WIFI:ssid:password   → CONNECT_OK / CONNECT_FAIL:reason
 *   CURRENT_WIFI                 → CURRENT_WIFI:ssid / CURRENT_WIFI:NOT_CONNECTED
 */
class BluetoothService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothService"
        // Standard SPP UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val RESPONSE_TIMEOUT_MS = 30000L
        // Default RFCOMM channel (must match server's channel)
        private const val RFCOMM_CHANNEL = 1
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Called on main thread when connection state changes */
    var onConnectionChange: ((Boolean) -> Unit)? = null

    /** Called on main thread when connection attempt fails with error detail */
    var onConnectionError: ((String) -> Unit)? = null

    /** Called on main thread when a line of data is received from robot */
    var onDataReceived: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // For synchronous send-and-wait-for-response
    private var pendingResponse: AtomicReference<CountDownLatch?> = AtomicReference(null)
    private var lastResponse: AtomicReference<String?> = AtomicReference(null)

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    // ============================================
    // Connect
    // ============================================

    /**
     * Connect to robot by MAC address. Runs on background thread.
     * Calls [onConnectionChange] on main thread when done.
     */
    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        // Disconnect existing connection first
        disconnect()

        Thread {
            try {
                val adapter = bluetoothAdapter
                    ?: throw IOException("[BT-E01] Bluetooth adapter not available")

                if (!adapter.isEnabled) {
                    throw IOException("[BT-E02] Bluetooth is not enabled")
                }

                val device = adapter.getRemoteDevice(macAddress)
                    ?: throw IOException("[BT-E03] Device not found: $macAddress")

                // Cancel discovery to speed up connection
                adapter.cancelDiscovery()

                Log.d(TAG, "Connecting to $macAddress ...")

                // Strategy 1: Try insecure RFCOMM via UUID (no encryption required)
                var connected = false
                try {
                    Log.d(TAG, "Trying createInsecureRfcommSocketToServiceRecord...")
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    connected = true
                    Log.d(TAG, "Connected via insecure RFCOMM UUID")
                } catch (e1: IOException) {
                    Log.w(TAG, "Insecure UUID failed: ${e1.message}")
                    closeSocket()

                    // Strategy 2: Try secure RFCOMM via UUID
                    try {
                        Log.d(TAG, "Trying createRfcommSocketToServiceRecord...")
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        socket?.connect()
                        connected = true
                        Log.d(TAG, "Connected via secure RFCOMM UUID")
                    } catch (e2: IOException) {
                        Log.w(TAG, "Secure UUID failed: ${e2.message}")
                        closeSocket()

                        // Strategy 3: Direct channel connection via reflection (bypasses SDP)
                        try {
                            Log.d(TAG, "Trying direct RFCOMM channel $RFCOMM_CHANNEL via reflection...")
                            val method = device.javaClass.getMethod(
                                "createInsecureRfcommSocket",
                                Int::class.javaPrimitiveType
                            )
                            socket = method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
                            socket?.connect()
                            connected = true
                            Log.d(TAG, "Connected via direct RFCOMM channel $RFCOMM_CHANNEL")
                        } catch (e3: Exception) {
                            Log.e(TAG, "Direct channel failed: ${e3.message}")
                            closeSocket()
                        }
                    }
                }

                if (!connected) {
                    throw IOException("[BT-E04] All connection methods failed for $macAddress")
                }

                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                isConnected = true
                Log.d(TAG, "Connected to $macAddress")

                mainHandler.post {
                    onConnectionChange?.invoke(true)
                }

                // Start reading data from robot
                startReadThread()

            } catch (e: Exception) {
                Log.e(TAG, "[BT-E04] Connection failed: ${e.message}")
                isConnected = false
                closeSocket()
                val errorMsg = e.message ?: "[BT-E04] Unknown error"
                mainHandler.post {
                    onConnectionError?.invoke(errorMsg)
                    onConnectionChange?.invoke(false)
                }
            }
        }.start()
    }

    // ============================================
    // Disconnect
    // ============================================

    /**
     * Disconnect from robot.
     */
    fun disconnect() {
        readThread?.interrupt()
        readThread = null
        closeSocket()

        if (isConnected) {
            isConnected = false
            mainHandler.post {
                onConnectionChange?.invoke(false)
            }
        }
    }

    // ============================================
    // Send
    // ============================================

    /**
     * Send a text command to the robot (newline-terminated).
     * Fire-and-forget, does not wait for response.
     */
    fun send(data: String) {
        if (!isConnected || outputStream == null) {
            Log.w(TAG, "[BT-E05] Cannot send — not connected")
            return
        }

        Thread {
            try {
                outputStream?.write("$data\n".toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                Log.d(TAG, "Sent: $data")
            } catch (e: IOException) {
                Log.e(TAG, "[BT-E06] Send failed: ${e.message}")
                handleDisconnect()
            }
        }.start()
    }

    /**
     * Send a command and wait for one line of response (blocking).
     * Must NOT be called from main thread.
     *
     * @param command  The command to send (e.g. "SEARCH_WIFI")
     * @param timeoutMs  Max time to wait for response in milliseconds
     * @return Response string, or null if timeout/error
     */
    fun sendAndReceive(command: String, timeoutMs: Long = RESPONSE_TIMEOUT_MS): String? {
        if (!isConnected || outputStream == null) {
            Log.w(TAG, "[BT-E07] Cannot sendAndReceive — not connected")
            return null
        }

        // Prepare latch for waiting
        val latch = CountDownLatch(1)
        lastResponse.set(null)
        pendingResponse.set(latch)

        try {
            // Send command
            outputStream?.write("$command\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
            Log.d(TAG, "Sent (sync): $command")

            // Wait for response
            val received = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            pendingResponse.set(null)

            return if (received) {
                val resp = lastResponse.get()
                Log.d(TAG, "Received (sync): $resp")
                resp
            } else {
                Log.w(TAG, "[BT-E08] Response timeout for: $command")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[BT-E09] sendAndReceive failed: ${e.message}")
            pendingResponse.set(null)
            return null
        }
    }

    /**
     * Send raw bytes to the robot.
     */
    fun sendBytes(data: ByteArray) {
        if (!isConnected || outputStream == null) return

        Thread {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "[BT-E10] Send bytes failed: ${e.message}")
                handleDisconnect()
            }
        }.start()
    }

    // ============================================
    // Read Thread
    // ============================================

    private fun startReadThread() {
        readThread = Thread {
            val buffer = ByteArray(1024)
            val lineBuffer = StringBuilder()

            while (!Thread.interrupted() && isConnected) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) {
                        handleDisconnect()
                        break
                    }

                    val received = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    lineBuffer.append(received)

                    // Process complete lines (newline-terminated)
                    while (lineBuffer.contains("\n")) {
                        val newlineIdx = lineBuffer.indexOf("\n")
                        val line = lineBuffer.substring(0, newlineIdx).trim()
                        lineBuffer.delete(0, newlineIdx + 1)

                        if (line.isNotEmpty()) {
                            // If someone is waiting for a synchronous response, deliver there
                            val latch = pendingResponse.getAndSet(null)
                            if (latch != null) {
                                lastResponse.set(line)
                                latch.countDown()
                            } else {
                                // Otherwise deliver via callback
                                mainHandler.post {
                                    onDataReceived?.invoke(line)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (isConnected) {
                        Log.e(TAG, "[BT-E11] Read error: ${e.message}")
                        handleDisconnect()
                    }
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "BT-ReadThread"
            start()
        }
    }

    // ============================================
    // Internal
    // ============================================

    private fun handleDisconnect() {
        if (!isConnected) return
        isConnected = false
        closeSocket()

        // Unblock any pending synchronous wait
        pendingResponse.getAndSet(null)?.countDown()

        mainHandler.post {
            onConnectionChange?.invoke(false)
        }
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
