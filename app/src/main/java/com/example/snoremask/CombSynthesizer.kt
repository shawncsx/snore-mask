package com.example.snoremask

class CombSynthesizer(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 256,
    private val maxHarmonics: Int = 12
) {
    private var f0 = 100f
    private var globalPhase = 0L
    private val twoPi = 2.0 * Math.PI

    fun updateF0(newF0: Float) { f0 = newF0.coerceIn(40f, 350f) }

    fun nextFrame(): FloatArray {
        val out = FloatArray(frameSize)
        for (i in 0 until frameSize) {
            val t = (globalPhase + i).toDouble() / sampleRate
            var sample = 0.0
            for (h in 1..maxHarmonics) {
                val freq = f0 * h
                val phase = twoPi * freq * t
                val weight = when (h) {
                    2, 3 -> 1.8
                    4, 5 -> 1.4
                    else -> 1.0
                }
                val env = 0.5 * (1 - Math.cos(twoPi * i / frameSize))
                sample += weight * Math.sin(phase) * env
            }
            out[i] = (sample / maxHarmonics).toFloat()
        }
        globalPhase += frameSize
        return out
    }
}