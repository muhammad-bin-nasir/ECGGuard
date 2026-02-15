package com.example.ecgguard

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.util.Collections

class ECGModel(context: Context) {
    // 1. Initialize ONNX Environment
    private val env = OrtEnvironment.getEnvironment()

    // 2. Load the Model from Assets
    private val session = env.createSession(context.assets.open("LSTM_NSR_autoencoder_10s.onnx").readBytes())

    // Returns a Pair containing:
    // First: The calculated MSE (Float)
    // Second: The reconstructed signal (FloatArray)
    fun runInference(window: FloatArray): Pair<Float, FloatArray> {

        // --- 1. Prepare Input Tensor ---
        // The 'window' array is already mean-centered by MainActivity
        // Shape: [Batch=1, Seq=2500, Features=1]
        val shape = longArrayOf(1, 2500, 1)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), shape)

        // --- 2. Run Inference ---
        val inputName = session.inputNames.iterator().next()
        val result = session.run(Collections.singletonMap(inputName, tensor))

        // --- 3. Extract Output ---
        val outputTensor = result[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        val reconstruction = FloatArray(2500)
        outputBuffer.get(reconstruction)

        // --- 4. Calculate MSE (Reconstruction Error) ---
        var mseSum = 0.0f
        for (i in window.indices) {
            val err = window[i] - reconstruction[i]
            mseSum += err * err
        }
        val mse = mseSum / 2500f

        // --- 5. Close resources to prevent memory leaks ---
        result.close()
        tensor.close()

        return Pair(mse, reconstruction)
    }
}