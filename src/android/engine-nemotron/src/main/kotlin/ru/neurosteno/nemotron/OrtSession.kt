package ru.neurosteno.nemotron

import ru.neurosteno.core.nemotron.FloatTensor
import ru.neurosteno.core.nemotron.OnnxSession

/** JNI к libneurort.so (onnxruntime C API, ORT из sherpa-onnx AAR). */
internal object OrtNative {
    init {
        // Сначала ORT из sherpa-onnx AAR: libneurort открывает её через dlopen по имени и получает этот же экземпляр.
        System.loadLibrary("onnxruntime")
        System.loadLibrary("neurort")
    }

    @JvmStatic external fun version(): String
    @JvmStatic external fun create(path: String, threads: Int): Long
    @JvmStatic external fun inputNames(handle: Long): Array<String>
    @JvmStatic external fun outputNames(handle: Long): Array<String>
    @JvmStatic external fun run(handle: Long, names: Array<String>, data: Array<FloatArray>, shapes: Array<LongArray>): Array<Any>
    @JvmStatic external fun release(handle: Long)
}

/** ONNX-сессия onnxruntime (CPU, float32-входы/выходы). Не потокобезопасна для close() во время run(). */
class OrtSession(path: String, threads: Int) : OnnxSession {
    private var handle: Long = OrtNative.create(path, threads)
    val inputNames: List<String> = OrtNative.inputNames(handle).toList()
    val outputNames: List<String> = OrtNative.outputNames(handle).toList()

    override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
        check(handle != 0L) { "session closed" }
        val names = inputs.keys.toTypedArray()
        val res = OrtNative.run(
            handle,
            names,
            Array(names.size) { inputs.getValue(names[it]).data },
            Array(names.size) { inputs.getValue(names[it]).shape },
        )
        val out = LinkedHashMap<String, FloatTensor>()
        for ((i, name) in outputNames.withIndex()) out[name] = FloatTensor(res[2 * i + 1] as LongArray, res[2 * i] as FloatArray)
        return out
    }

    override fun close() {
        if (handle != 0L) { OrtNative.release(handle); handle = 0L }
    }

    companion object {
        fun runtimeVersion(): String = OrtNative.version()
    }
}
