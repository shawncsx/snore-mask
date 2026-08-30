package com.example.snoremask

class YinDetector(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 1024
) {
    private val yinBuffer = FloatArray(frameSize / 2)
    private var lastF0 = 100f

    fun detect(x: FloatArray): Pair<Float, Float> {
        val half = frameSize / 2
        for (tau in 1 until half) {
            var sum = 0f
            for (j in 0 until half) {
                val diff = x[j] - x[j + tau]
                sum += diff * diff
            }
            yinBuffer[tau] = sum
        }
        yinBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1 until half) {
            runningSum += yinBuffer[tau]
            yinBuffer[tau] *= tau / runningSum
        }
        var tau = -1
        for (t in 2 until half - 1) {
            if (yinBuffer[t] < 0.15f && yinBuffer[t] < yinBuffer[t - 1] && yinBuffer[t] <= yinBuffer[t + 1]) {
                tau = t; break
            }
        }
        if (tau == -1) return lastF0 to 0f
        val y1 = yinBuffer[tau - 1]; val y2 = yinBuffer[tau]; val y3 = yinBuffer[tau + 1]
        val denom = y1 - 2 * y2 + y3
        val interp = if (denom != 0f) tau + 0.5f * (y1 - y3) / denom else tau.toFloat()
        val f0 = sampleRate / interp
        if (f0 !in 40f..350f) return lastF0 to 0f
        lastF0 = f0
        return f0 to (1f - yinBuffer[tau])
    }
}