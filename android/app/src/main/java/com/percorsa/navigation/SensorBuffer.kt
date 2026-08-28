package com.percorsa.navigation

class SensorBuffer(
    private val capacity: Int = 500
) {

    private val buffer = ArrayDeque<SensorSample>()

    @Synchronized
    fun add(sample: SensorSample) {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }

        buffer.addLast(sample)
    }

    @Synchronized
    fun size(): Int {
        return buffer.size
    }

    @Synchronized
    fun getAll(): List<SensorSample> {
        return buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}