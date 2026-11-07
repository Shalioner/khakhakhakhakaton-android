package ru.neurosteno.core.nemotron

/** Плотный float32-тензор (row-major). */
class FloatTensor(val shape: LongArray, val data: FloatArray) {
    init {
        require(shape.fold(1L) { a, b -> a * b } == data.size.toLong()) {
            "shape ${shape.contentToString()} != ${data.size} values"
        }
    }

    fun dim(i: Int): Int = shape[i].toInt()
}

/**
 * Минимальный интерфейс ONNX-сессии: float-входы по имени → выходы по имени в порядке модели.
 * На телефоне — JNI к onnxruntime C API (engine-nemotron/OrtSession), в тестах — фейк на JVM.
 */
interface OnnxSession : AutoCloseable {
    fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor>
}

/** Источник отсчётов 16 кГц моно в [-1, 1], читаемый окнами (PcmFile на Android, массив в тестах). */
interface SampleSource {
    val numSamples: Long

    /** Отсчёты [start, start + count) ∩ [0, numSamples). */
    fun read(start: Long, count: Int): FloatArray
}

class ArraySampleSource(private val x: FloatArray) : SampleSource {
    override val numSamples: Long get() = x.size.toLong()
    override fun read(start: Long, count: Int): FloatArray {
        val s = start.coerceIn(0, numSamples).toInt()
        val e = minOf(numSamples, start + count).toInt().coerceAtLeast(s)
        return x.copyOfRange(s, e)
    }
}
