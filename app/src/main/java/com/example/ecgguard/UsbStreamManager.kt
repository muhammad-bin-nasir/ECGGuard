package com.example.ecgguard

import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives ECG data from the laptop over a TCP socket forwarded via ADB.
 *
 * Setup on the PC side (run once per USB session):
 *   1.  adb forward tcp:9999 tcp:9999
 *   2.  python ecg_replay.py --usb --file your_ecg.csv
 *
 * The app opens a ServerSocket on port 9999. ADB's forward maps the PC's
 * localhost:9999 to this port on the device, so ecg_replay.py can connect
 * with a plain TCP socket – no Wi-Fi or BLE required.
 *
 * Data format: identical to the BLE stream – little-endian int16 ADC samples,
 * packed into arbitrary-size TCP segments.
 */
class UsbStreamManager(
    private val onLog: (String) -> Unit,
    private val onDataReceived: (FloatArray, String) -> Unit
) {
    companion object {
        const val PORT = 9999
        private const val REQUIRED_SIZE = 2500
        private const val MAX_BUFFER_SIZE = 3000
        private const val READ_BUF_SIZE = 256
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var thread: Thread? = null

    private val signalBuffer = ArrayList<Float>(MAX_BUFFER_SIZE)
    private var packetCount = 0

    private fun log(msg: String) {
        Log.d("ECG_USB", msg)
        onLog(msg)
    }

    /** Open the server socket and wait for one Python client connection. */
    fun connect() {
        if (running) return
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

                val client = serverSocket!!.accept()
                clientSocket = client
                log("USB client connected (${client.inetAddress.hostAddress})")

                val stream: InputStream = client.getInputStream()
                val readBuf = ByteArray(READ_BUF_SIZE)

                while (running) {
                    val n = stream.read(readBuf, 0, READ_BUF_SIZE)
                    if (n < 0) {
                        log("USB client disconnected.")
                        break
                    }
                    processBytes(readBuf, n)
                }
            } catch (e: Exception) {
                if (running) log("USB error: ${e.message}")
            } finally {
                running = false
                closeAll()
            }
        }
        thread!!.isDaemon = true
        thread!!.start()
    }

    fun disconnect() {
        running = false
        closeAll()
        thread?.interrupt()
        thread = null
    }

    private fun processBytes(data: ByteArray, length: Int) {
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 2) {
            signalBuffer.add(buf.short.toFloat())
        }
        packetCount++

        if (signalBuffer.size >= REQUIRED_SIZE) {
            val inputData = signalBuffer.takeLast(REQUIRED_SIZE).toFloatArray()
            onDataReceived(inputData, "USB Pkt #$packetCount | Buf: ${signalBuffer.size}")
            if (signalBuffer.size > MAX_BUFFER_SIZE) {
                signalBuffer.subList(0, 250).clear()
            }
        }
    }

    private fun closeAll() {
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
    }
}
