package com.example.ecgguard

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * DSPUtils.kt
 * ===========
 * Digital Signal Processing helpers used to clean the raw ECG signal
 * before it is fed into the ONNX model (ECGModel.kt).
 *
 * NOTE: This object is an earlier version of the pipeline. The active pipeline
 * used during inference in MainActivity.kt is SignalProcessor (defined at the
 * bottom of MainActivity.kt), which is a direct Kotlin port of the Python
 * training script. Both objects implement identical math — DSPUtils uses
 * slightly different Savitzky-Golay coefficients (normalised floats vs integer
 * coefficients with a divisor). If you ever unify them, use SignalProcessor
 * since its coefficients more precisely match the Python training preprocessing.
 *
 * PIPELINE ORDER
 * ──────────────
 * raw ADC samples → replaceNaNs → isMechanicallySound (gate) → detrendLinear → savgolFilter → model
 *
 * WHY EACH STEP?
 * ──────────────
 * 1. replaceNaNs     – ADC overflows or division errors can produce NaN/Infinity.
 *                      These propagate through all subsequent math and corrupt results.
 * 2. isMechanicallySound – Fast rejection of obviously bad windows (flat line =
 *                      electrode off skin; extreme spikes = motion artifact).
 *                      Returns null from preprocess() so the window is skipped entirely.
 * 3. detrendLinear   – Breathing causes the ECG baseline to drift slowly up and down.
 *                      Subtracting a fitted straight line removes this drift without
 *                      touching the fast ECG shape.
 * 4. savgolFilter    – Savitzky-Golay is a polynomial smoothing filter that reduces
 *                      high-frequency noise while preserving sharp peaks (QRS complex).
 *                      Standard moving-average would blur the peaks.
 */
object DSPUtils {

    /**
     * Runs the full cleaning pipeline on [raw] and returns the processed window.
     *
     * Returns null if the signal fails the mechanical quality gate (flat line or
     * too many extreme values). Callers should skip inference when null is returned.
     *
     * HOW TO EXTEND THE PIPELINE:
     * Add extra steps between the existing calls, e.g.:
     *   val bandpassed = bandpassFilter(detrended, lowHz = 0.5f, highHz = 40f)
     *   return savgolFilter(bandpassed)
     */
    fun preprocess(raw: FloatArray): FloatArray? {
        // Step 1: Replace invalid floating-point values
        val clean = replaceNaNs(raw)

        // Step 2: Gate — return null to discard this window entirely
        if (!isMechanicallySound(clean)) return null

        // Step 3: Remove slow baseline drift
        val detrended = detrendLinear(clean)

        // Step 4: Smooth without blurring QRS peaks
        return savgolFilter(detrended)
    }

    /**
     * Quality gatekeeper — returns true if the window looks like valid ECG.
     *
     * TWO CHECKS:
     *   Check 1 — Flatline (std < 0.001):
     *     When the electrode falls off the skin, the AD8232 reads a constant value.
     *     Standard deviation of a flat signal is ≈ 0. Threshold 0.001 catches this.
     *     HOW TO CHANGE: Raise 0.001 to reject very weak signals too (e.g. 0.005).
     *     Lowering it risks passing through flatlines.
     *
     *   Check 2 — Extreme spike ratio (> 10% of samples exceed ±3.0):
     *     Motion artifacts cause large spikes far outside normal ECG amplitude.
     *     If more than 10% of the window is extreme, it's noise.
     *     HOW TO CHANGE: Raise 0.1 to be more tolerant of movement (more false normals).
     *     Lower 0.1 to be stricter (more dropped windows during legitimate activity).
     *     Change 3.0 if your ECG amplitudes are scaled differently.
     */
    private fun isMechanicallySound(window: FloatArray): Boolean {
        if (window.isEmpty()) return false
        val mean = window.average()

        val variance = window.fold(0.0) { acc, v -> acc + (v - mean).pow(2) } / window.size
        val std = sqrt(variance)

        if (std < 0.001) return false   // flatline / disconnected electrode

        val outlierCount = window.count { abs(it) > 3.0 }
        if ((outlierCount.toDouble() / window.size) > 0.1) return false  // too many spikes

        return true
    }

    /**
     * Replaces NaN and Infinity values with 0.0.
     *
     * WHY ZERO? The detrending and Savitzky-Golay filter treat 0 as a neutral
     * mid-point value. A sparse replacement with 0 causes minimal distortion
     * compared to propagating NaN through the pipeline.
     *
     * ALTERNATIVE: Replace with the previous valid sample (last-value hold).
     * Implement as: var last = 0f; data[i].isNaN() ? last : data[i].also { last = it }
     */
    private fun replaceNaNs(data: FloatArray): FloatArray {
        return FloatArray(data.size) { i ->
            if (data[i].isNaN() || data[i].isInfinite()) 0.0f else data[i]
        }
    }

    /**
     * Linear Detrending — removes slow baseline wander from breathing.
     *
     * Fits a straight line y = m·x + c to the entire window using least squares
     * regression, then subtracts that line sample-by-sample. The result is the
     * ECG AC component centred around zero, with the slow DC trend removed.
     *
     * HOW TO CHANGE:
     * - For more aggressive detrending (e.g. remove polynomial curves from
     *   postural changes), replace this with a polynomial fit of degree 2 or 3.
     * - For very short windows, detrending can actually distort the signal.
     *   Disable this step if your window is < 1 second.
     */
    private fun detrendLinear(y: FloatArray): FloatArray {
        val n = y.size
        val x = FloatArray(n) { it.toFloat() }   // x = [0, 1, 2, ..., n-1]

        // Compute sums needed for least-squares slope and intercept
        val sumX  = x.sum()
        val sumY  = y.sum()
        val sumXY = x.zip(y).sumOf { (xi, yi) -> (xi * yi).toDouble() }
        val sumXX = x.sumOf { (it * it).toDouble() }

        // Slope: m = (n·ΣXY − ΣX·ΣY) / (n·ΣX² − (ΣX)²)
        val slope     = ((n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)).toFloat()
        // Intercept: c = (ΣY − m·ΣX) / n
        val intercept = ((sumY - slope * sumX) / n).toFloat()

        // Subtract the fitted line from each sample
        return FloatArray(n) { i -> y[i] - (slope * i + intercept) }
    }

    /**
     * Savitzky-Golay Smoothing Filter — reduces noise while preserving ECG peaks.
     *
     * These are pre-computed convolution coefficients for a window of 11 samples
     * and polynomial order 3. They were generated by the scipy.signal.savgol_filter
     * formula offline and hardcoded here for efficiency (avoids runtime matrix math).
     *
     * HOW TO CHANGE SMOOTHING STRENGTH:
     * - More smoothing (blurrier): use a wider window (e.g. 21 taps) or lower poly order.
     *   You would need to recompute the coefficients using scipy or an online SG calculator.
     * - Less smoothing (noisier but sharper): use a narrower window (e.g. 7 taps) or higher poly order.
     *
     * IMPORTANT: Changing coefficients does NOT require matching changes in the ONNX model,
     * but it changes what the model "sees" and may affect MSE thresholds.
     *
     * Edge handling: The edges (first and last 5 samples) are left unchanged from [x].
     * This is acceptable because the sliding window only uses the centre portion for inference.
     */
    private val SAVGOL_COEFFS = floatArrayOf(
        -0.0839f, 0.0210f, 0.1026f, 0.1606f, 0.1956f,
         0.2072f,                                        // centre tap (highest weight)
         0.1956f, 0.1606f, 0.1026f, 0.0210f, -0.0839f
    )

    private fun savgolFilter(x: FloatArray): FloatArray {
        val out     = x.clone()    // copy edges unchanged
        val halfWin = 5            // = (11 - 1) / 2

        // Convolve: for each interior sample, compute weighted sum of 11 neighbours
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
