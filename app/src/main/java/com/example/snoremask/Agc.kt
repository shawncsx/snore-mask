package com.example.snoremask

class Agc(
    private val attackMs: Int = 200,
    private val releaseMs: Int = 2000,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 256
) {
    private var gain = 1.0
    private val attackCoeff = Math.exp(-1.0 / (attackMs * sampleRate / 1000.0 / frameSize))
    private val releaseCoeff = Math.exp(-1.0 / (releaseMs * sampleRate / 1000.0 / frameSize))

    fun process(frame: FloatArray, residualEnergy: Double): FloatArray {
        val target = 0.001
        val error = target / maxOf(residualEnergy, 1e-6)
        val coeff = if (error > gain) attackCoeff else releaseCoeff
        gain = gain * coeff + error * (1 - coeff)
        gain = gain.coerceIn(0.0, 4.0)
        val out = FloatArray(frame.size)
        for (i in frame.indices) out[i] = (frame[i] * gain).toFloat()
        return out
    }
}