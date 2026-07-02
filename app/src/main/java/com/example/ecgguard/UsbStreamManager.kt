package com.example.ecgguard

import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UsbStreamManager.kt
 * ====================
 * Receives ECG data from a laptop over a TCP socket forwarded via ADB.
 * This is an ALTERNATIVE to BleStreamManager — no ESP32 hardware or BLE needed.
 *
 * HOW IT WORKS
 * ────────────
 * 1. The app opens a TCP ServerSocket on port 9999 on the phone.
 * 2. The user runs "adb forward tcp:9999 tcp:9999" on the laptop.
 *    ADB tunnels the laptop's localhost:9999 to the phone's port 9999.
 * 3. The Python script (ecg_replay.py --usb --file ecg.csv) connects to
 *    localhost:9999 on the laptop and streams ECG bytes.
 * 4. ADB transparently forwards those bytes to this ServerSocket.
 *
 * DATA FORMAT — identical to BleStreamManager
 * ─────────────────────────────────────────────
 * Little-endian signed int16 (2 bytes per sample), packed back-to-back.
 * Same as what the ESP32 sends over BLE, so both managers share the same
 * ecgDataCallback in MainActivity without any format conversion.
 *
 * WHEN TO USE
 * ───────────
 * - Rapid testing of the UI and AI model without physical hardware
 * - Demo or presentation when BLE devices are unavailable
 * - Streaming a specific known-bad ECG file to test anomaly detection
 *
 * HOW TO CHANGE PORT
 * ──────────────────
 * Change PORT below AND update ecg_replay.py (--tcp-port argument).
 * Also re-run "adb forward tcp:NEW tcp:NEW" after changing.
 *
 * HOW TO SWITCH FROM TCP TO A REAL SERIAL PORT
 * ─────────────────────────────────────────────
 * Replace ServerSocket with Android UsbManager + UsbSerialDriver library.
 * The processBytes() function stays exactly the same — only the source changes.
 */
class UsbStreamManager(
    /** Called on log events (connection status, errors). Runs on the background thread. */
    private val onLog: (String) -> Unit,
    /**
     * Called whenever a fresh 2500-sample window is ready.
     * Runs on the background thread — the caller must switch to the UI thread
     * (runOnUiThread) before updating Compose state.
     */
    private val onDataReceived: (FloatArray, String) -> Unit
) {
    companion object {
        /**
         * TCP port the app listens on.
         * Must match the "adb forward tcp:X tcp:X" command and ecg_replay.py --tcp-port.
         * HOW TO CHANGE: update PORT, the adb command, and ecg_replay.py together.
         */
        const val PORT = 9999

        /**
         * Minimum buffer size before inference runs.
         * 2500 = 10 seconds at 250 Hz. Matches the ONNX model's input shape.
         * HOW TO CHANGE: if you retrain the model for a different window length
         * (e.g. 5s → 1250), update this AND the model input shape in ECGModel.kt.
         */
        private const val REQUIRED_SIZE = 2500

        /**
         * Maximum samples kept in the rolling buffer before trimming.
         * 3000 = REQUIRED_SIZE + 500 (a half-second of headroom).
         * HOW TO CHANGE: Increase if you want a longer overlap between windows.
         * Decrease to reduce memory use (minimum: REQUIRED_SIZE + 1).
         */
        private const val MAX_BUFFER_SIZE = 3000

        /**
         * TCP read chunk size in bytes.
         * 256 bytes = 128 int16 samples per read() call.
         * HOW TO CHANGE: Larger values (e.g. 512) reduce read() overhead.
         * Smaller values (e.g. 64) give more frequent processing but higher CPU overhead.
         */
        private const val READ_BUF_SIZE = 256
    }

    // @Volatile ensures the background thread sees the UI thread's write to `running`
    // when disconnect() sets it to false.
    @Volatile private var running      = false
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket?       = null
    private var thread: Thread?             = null

    // Rolling buffer — accumulates raw float samples decoded from int16 bytes.
    private val signalBuffer = ArrayList<Float>(MAX_BUFFER_SIZE)
    private var packetCount  = 0    // counts TCP read() calls, shown in the debug label

    private fun log(msg: String) {
        Log.d("ECG_USB", msg)
        onLog(msg)
    }

    /**
     * Opens the server socket and waits for the Python client to connect.
     * Runs the entire receive loop on a daemon background thread.
     *
     * Safe to call multiple times — no-op if already running.
     * Call disconnect() first if you want to restart.
     *
     * HOW TO CHANGE TIMEOUT: Add serverSocket.soTimeout = 60_000 (60 s)
     * after ServerSocket(PORT) so accept() gives up if no client connects.
     */
    fun connect() {
        if (running) return   // already listening
        running = true
        signalBuffer.clear()
        packetCount = 0

        thread = Thread {
            try {
                log("USB mode: opening listener on port $PORT…")
                log("PC step 1:  adb forward tcp:$PORT tcp:$PORT")
                log("PC step 2:  python ecg_replay.py --usb --file <ecg.csv>")

                serverSocket = ServerSocket(PORT)
                log("Waiting for Python client on :$PORT …")

                // accept() blocks until the Python script connects
                val client = serverSocket!!.accept()
                clientSocket = client
                log("USB client connected (${client.inetAddress.hostAddress})")

                val stream: InputStream = client.getInputStream()
                val readBuf = ByteArray(READ_BUF_SIZE)

                // ── Main receive loop ─────────────────────────────────────────
                while (running) {
                    val n = stream.read(readBuf, 0, READ_BUF_SIZE)
                    if (n < 0) {
                        // n == -1 means the Python script closed its socket
                        log("USB client disconnected.")
                        break
                    }
                    processBytes(readBuf, n)
                }

            } catch (e: Exception) {
                // Suppress "Socket closed" errors that happen during normal disconnect()
                if (running) log("USB error: ${e.message}")
            } finally {
                running = false
                closeAll()
            }
        }
        thread!!.isDaemon = true   // won't prevent the app from exiting
        thread!!.start()
    }

    /**
     * Stops the receive loop and closes all sockets.
     * Safe to call from any thread. Safe to call if not connected.
     */
    fun disconnect() {
        running = false       // signals the receive loop to stop
        closeAll()
        thread?.interrupt()   // unblocks the thread if it's sleeping
        thread = null
    }

    /**
     * Decodes [length] bytes of [data] as little-endian int16 samples,
     * appends them to the rolling buffer, and fires onDataReceived when ready.
     *
     * SLIDING WINDOW BEHAVIOUR:
     * Each TCP chunk shifts the 2500-sample window forward by [length/2] samples.
     * At 250 Hz with CHUNK_SAMPLES=10, a new window fires 25 times per second.
     * The ONNX model runs on every window → ~25 inferences per second.
     *
     * HOW TO REDUCE INFERENCE RATE:
     * Add a counter: only call onDataReceived every Nth call to processBytes.
     * e.g. if (packetCount % 5 == 0) onDataReceived(...)
     */
    private fun processBytes(data: ByteArray, length: Int) {
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        // Decode each pair of bytes as a signed 16-bit integer, store as Float
        while (buf.remaining() >= 2) {
            signalBuffer.add(buf.short.toFloat())
        }
        packetCount++

        if (signalBuffer.size >= REQUIRED_SIZE) {
            // Take the most recent REQUIRED_SIZE samples as the inference window
            val inputData = signalBuffer.takeLast(REQUIRED_SIZE).toFloatArray()
            onDataReceived(inputData, "USB Pkt #$packetCount | Buf: ${signalBuffer.size}")

            // Trim the front of the buffer to prevent unbounded memory growth
            if (signalBuffer.size > MAX_BUFFER_SIZE) {
                // Remove 250 samples (1 second) from the front — keeps overlap
                signalBuffer.subList(0, 250).clear()
            }
        }
    }

    /** Closes client and server sockets silently. */
    private fun closeAll() {
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
    }
}
