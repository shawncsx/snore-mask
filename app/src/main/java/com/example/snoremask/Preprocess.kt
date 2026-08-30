package com.example.snoremask

import kotlin.math.abs

object Preprocess {
    private var hpX1 = 0f; private var hpX2 = 0f; private var hpY1 = 0f; private var hpY2 = 0f
    private val lpHist = FloatArray(4)

    fun process(raw: FloatArray): FloatArray {
        val out = FloatArray(raw.size)
        for (i in raw.indices) {
            val hpIn = raw[i]
            val hpOut = hpIn - 2f * hpX1 + hpX2 + 1.998f * hpY1 - 0.998f * hpY2
            hpX2 = hpX1; hpX1 = hpIn; hpY2 = hpY1; hpY1 = hpOut

            var lpOut = 0.0003f * hpOut + 0.0012f * lpHist[0] + 0.0018f * lpHist[1] + 0.0012f * lpHist[2] + 0.0003f * lpHist[3]
            lpOut += 3.6f * lpHist[0] - 4.8f * lpHist[1] + 3.2f * lpHist[2] - 0.96f * lpHist[3]
            for (k in 3 downTo 1) lpHist[k] = lpHist[k - 1]
            lpHist[0] = lpOut
            out[i] = lpOut
        }
        var maxAbs = 0f
        for (v in out) maxAbs = maxOf(maxAbs, abs(v))
        if (maxAbs > 1f) for (i in out.indices) out[i] /= maxAbs
        return out
    }
}