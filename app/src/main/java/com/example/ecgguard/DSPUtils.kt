package com.example.ecgguard

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object DSPUtils {

    // Main Pipeline: Calls all cleaning steps in order
    fun preprocess(raw: FloatArray): FloatArray? {
        // 1. Sanitize (Remove Infinity/NaN)
        val clean = replaceNaNs(raw)

        // 2. Mechanical Gatekeeper (Fail fast if signal is noise/disconnected)
        if (!isMechanicallySound(clean)) return null

        // 3. Detrend (Remove baseline wander like breathing)
        val detrended = detrendLinear(clean)

        // 4. Smooth (Savitzky-Golay filter)
        return savgolFilter(detrended)
    }

    // Checks if the signal is valid ECG or just noise
    private fun isMechanicallySound(window: FloatArray): Boolean {
        if (window.isEmpty()) return false
        val mean = window.average()

        // Calculate Standard Deviation
        val variance = window.fold(0.0) { acc, v -> acc + (v - mean).pow(2) } / window.size
        val std = sqrt(variance)

        // Check 1: Flatline (Sensor disconnected) - Signal too weak
        if (std < 0.001) return false

        // Check 2: Artifacts (Too many extreme values) - Signal thrashing
        val outlierCount = window.count { abs(it) > 3.0 }
        if ((outlierCount.toDouble() / window.size) > 0.1) return false

        return true
    }

    private fun replaceNaNs(data: FloatArray): FloatArray {
        return FloatArray(data.size) { i ->
            if (data[i].isNaN() || data[i].isInfinite()) 0.0f else data[i]
        }
    }

    // Linear Detrending: Fits a line y = mx + c and subtracts it
    private fun detrendLinear(y: FloatArray): FloatArray {
        val n = y.size
        val x = FloatArray(n) { it.toFloat() }

        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.zip(y).sumOf { (xi, yi) -> (xi * yi).toDouble() }
        val sumXX = x.sumOf { (it * it).toDouble() }

        val slope = ((n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)).toFloat()
        val intercept = ((sumY - slope * sumX) / n).toFloat()

        return FloatArray(n) { i -> y[i] - (slope * i + intercept) }
    }

    // Savitzky-Golay Coefficients (Window 11, Poly 3) - Hardcoded for speed
    private val SAVGOL_COEFFS = floatArrayOf(
        -0.0839f, 0.0210f, 0.1026f, 0.1606f, 0.1956f,
        0.2072f,
        0.1956f, 0.1606f, 0.1026f, 0.0210f, -0.0839f
    )

    private fun savgolFilter(x: FloatArray): FloatArray {
        val out = x.clone()
        val halfWin = 5 // (11-1)/2

        // Apply convolution
        for (i in halfWin until x.size - halfWin) {
            var sum = 0.0f
            for (j in SAVGOL_COEFFS.indices) {
                sum += x[i - halfWin + j] * SAVGOL_COEFFS[j]
            }
            out[i] = sum
        }
        return out
    }
}