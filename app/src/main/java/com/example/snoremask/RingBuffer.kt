package com.example.snoremask

class RingBuffer<T : Any>(capacity: Int) {
    private val buffer = Array<Any?>(capacity) { null }
    private var writeIdx = 0
    private var count = 0
    private val lock = Any()

    @Suppress("UNCHECKED_CAST")
    fun write(value: T) {
        synchronized(lock) {
            buffer[writeIdx] = value
            writeIdx = (writeIdx + 1) % buffer.size
            count = minOf(count + 1, buffer.size)
        }
    }

    fun readFrame(frameSize: Int): FloatArray {
        val out = FloatArray(frameSize)
        synchronized(lock) {
            if (count < frameSize) return FloatArray(frameSize)
            var readIdx = (writeIdx - count + buffer.size) % buffer.size
            for (i in 0 until frameSize) {
                out[i] = (buffer[readIdx] as? Float) ?: 0f
                readIdx = (readIdx + 1) % buffer.size
            }
        }
        return out
    }
}