package com.example.ecgguard

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.sqrt

class ECGModel(context: Context) {
    // 1. Initialize ONNX Environment
    private val env = OrtEnvironment.getEnvironment()

    // 2. Load the Model from Assets
    // Note: If this crashes, ensure the filename 'ecg_model.onnx' matches exactly what is in your assets folder
    private val session = env.createSession(context.assets.open("ecg_model_final.onnx").readBytes())

    fun calculateMSE(window: FloatArray): Float {
        // --- Z-Score Normalization ---
        // Crucial: Makes the model independent of hardware gain/voltage differences
        val mean = window.average().toFloat()
        val variance = window.fold(0.0f) { acc, v -> acc + (v - mean) * (v - mean) } / window.size
        val std = sqrt(variance)

        // Normalize (checking for division by zero)
        val normWindow = if (std > 1e-6) {
            FloatArray(window.size) { (window[it] - mean) / std }
        } else {
            window
        }

        // --- Prepare Input Tensor ---
        // Shape: [Batch=1, Seq=2500, Features=1]
        val shape = longArrayOf(1, 2500, 1)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(normWindow), shape)

        // --- Run Inference ---
        val inputName = session.inputNames.iterator().next()
        val result = session.run(Collections.singletonMap(inputName, tensor))

        // --- Extract Output ---
        val outputTensor = result[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        val reconstruction = FloatArray(2500)
        outputBuffer.get(reconstruction)

        // --- Calculate MSE (Reconstruction Error) ---
        var mse = 0.0f
        for (i in normWindow.indices) {
            val err = normWindow[i] - reconstruction[i]
            mse += err * err
        }

        // Close resources to prevent memory leaks
        result.close()
        tensor.close()

        return mse / 2500
    }
}