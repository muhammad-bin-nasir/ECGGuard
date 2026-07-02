package com.example.ecgguard

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.util.Collections

/**
 * ECGModel.kt
 * ===========
 * Wraps the on-device ONNX LSTM Autoencoder that detects ECG anomalies.
 *
 * WHAT IS AN AUTOENCODER?
 * ───────────────────────
 * An autoencoder is a neural network trained to compress its input into a
 * compact representation and then reconstruct the original signal from that
 * compressed form. This one (LSTM_NSR_autoencoder_10s.onnx) was trained
 * exclusively on NORMAL SINUS RHYTHM (NSR) ECG recordings.
 *
 * HOW ANOMALY DETECTION WORKS
 * ───────────────────────────
 * - Normal ECG fed in → model reconstructs it accurately → small MSE → NORMAL RHYTHM
 * - Abnormal ECG (arrhythmia, VFib, etc.) fed in → model reconstructs poorly
 *   (it was never trained on these) → large MSE → ANOMALY DETECTED
 * - The threshold is 0.30 MSE (set in MainActivity.ecgDataCallback).
 *   ABOVE 0.30 = anomaly. BELOW 0.30 = normal.
 *   HOW TO CHANGE THE THRESHOLD: search for "0.30f" in MainActivity.kt
 *   and change it. Lower = more sensitive (more false positives). Higher = less sensitive.
 *
 * INPUT FORMAT
 * ────────────
 * Shape: [Batch=1, Sequence=2500, Features=1]
 *   - Batch size 1: one ECG window at a time
 *   - Sequence length 2500: 10 seconds × 250 Hz sample rate
 *   - Features 1: one floating-point ECG value per time step
 *
 * The input must be MEAN-CENTRED (subtract the mean of the 2500 samples) before
 * calling runInference(). This is done in MainActivity.ecgDataCallback:
 *   val mu = cleanData.average().toFloat()
 *   val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }
 *
 * HOW TO REPLACE THE MODEL
 * ─────────────────────────
 * 1. Train a new .onnx model with the same input shape [1, 2500, 1].
 * 2. Replace the file in app/src/main/assets/ with the new .onnx file.
 * 3. Update the filename string in the constructor below.
 * 4. Re-tune the MSE threshold (0.30f) in MainActivity.kt.
 *
 * HOW TO CHANGE TO A DIFFERENT WINDOW SIZE (not 10 seconds)
 * ─────────────────────────────────────────────────────────
 * 1. Retrain the model with the new sequence length.
 * 2. Change the shape array below: longArrayOf(1, NEW_SIZE, 1)
 * 3. Change 2500 → NEW_SIZE in the mse calculation loop.
 * 4. Change REQUIRED_SIZE in BleStreamManager and UsbStreamManager to match.
 * 5. Change the graphData slice in MainActivity (currently 500 samples for 2-second display).
 *
 * HOW TO CHANGE SAMPLE RATE (not 250 Hz)
 * ────────────────────────────────────────
 * If the ESP32 sends at a different rate (e.g. 360 Hz):
 * 1. Change SAMPLE_RATE_HZ in ecg_standalone.ino.
 * 2. Retrain the model on data at the new sample rate.
 * 3. Change sampleRate parameter in SignalProcessor functions (MainActivity.kt).
 * 4. Adjust REQUIRED_SIZE in BleStreamManager (e.g. 10 * 360 = 3600 samples for 10s).
 */
class ECGModel(context: Context) {

    /**
     * ONNX Runtime environment — manages memory and thread pools.
     * One environment per process is the recommended pattern.
     * HOW TO CHANGE: OrtEnvironment.getEnvironment("custom") lets you name it,
     * which can help with debugging in logcat.
     */
    private val env = OrtEnvironment.getEnvironment()

    /**
     * The loaded inference session (the model itself, ready to run).
     *
     * The model file is loaded once at construction time from the app's assets folder.
     * Loading is synchronous and takes ~50–200ms on first call (varies by device).
     * That is why ECGModel is created in MainActivity.onCreate(), not lazily.
     *
     * HOW TO CHANGE THE MODEL FILE:
     * Replace "LSTM_NSR_autoencoder_10s.onnx" with your new filename.
     * The file must be in app/src/main/assets/.
     *
     * HOW TO ADD OPTIMISATION OPTIONS:
     * val opts = OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
     * env.createSession(bytes, opts)
     */
    private val session = env.createSession(
        context.assets.open("LSTM_NSR_autoencoder_10s.onnx").readBytes()
    )

    /**
     * Runs one forward pass of the autoencoder and returns the MSE.
     *
     * @param window  A 2500-element FloatArray of MEAN-CENTRED, cleaned ECG samples.
     *                Must be exactly 2500 samples (10s at 250Hz).
     *                If your window size changes, update the shape and mse divisor below.
     *
     * @return Pair where:
     *   First  = MSE (Float) — reconstruction error. Used for anomaly detection.
     *            > 0.30 → ANOMALY. ≤ 0.30 → NORMAL.
     *   Second = reconstructed signal (FloatArray of length 2500).
     *            Currently unused in the main UI but available for overlay plotting.
     *            To display it: draw it on top of the real ECG in ECGChart().
     */
    fun runInference(window: FloatArray): Pair<Float, FloatArray> {

        // ── 1. Wrap the input FloatArray into an ONNX tensor ─────────────────
        // Shape [1, 2500, 1] = [batch, time_steps, features]
        // If you change the model's input shape, update these three numbers.
        val shape  = longArrayOf(1, 2500, 1)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), shape)

        // ── 2. Run inference ──────────────────────────────────────────────────
        // The input name comes from the model's export — we fetch it dynamically
        // so this code doesn't break if you retrain with a different input name.
        val inputName = session.inputNames.iterator().next()
        val result    = session.run(Collections.singletonMap(inputName, tensor))

        // ── 3. Extract the reconstructed output signal ────────────────────────
        val outputTensor  = result[0] as OnnxTensor
        val outputBuffer  = outputTensor.floatBuffer
        val reconstruction = FloatArray(2500)
        outputBuffer.get(reconstruction)

        // ── 4. Calculate MSE between original and reconstruction ──────────────
        // MSE = mean of (original[i] - reconstructed[i])²
        // A well-trained autoencoder gives low MSE on signals it knows (NSR).
        // It gives high MSE on signals it has never seen (arrhythmias).
        //
        // HOW TO CHANGE THE METRIC: Replace MSE with MAE (mean absolute error):
        //   maeSum += abs(window[i] - reconstruction[i])
        //   return Pair(maeSum / 2500f, reconstruction)
        // MAE is less sensitive to large spikes than MSE.
        var mseSum = 0.0f
        for (i in window.indices) {
            val err  = window[i] - reconstruction[i]
            mseSum  += err * err
        }
        val mse = mseSum / 2500f   // divide by window length to get the mean

        // ── 5. Free native memory — IMPORTANT, prevents memory leaks ─────────
        // ONNX Runtime allocates off-heap native memory. Android's GC does not
        // manage it. Always close result and tensor after reading the output.
        result.close()
        tensor.close()

        return Pair(mse, reconstruction)
    }
}
