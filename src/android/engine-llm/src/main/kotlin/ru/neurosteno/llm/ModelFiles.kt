package ru.neurosteno.llm

import android.content.Context
import java.io.File

object ModelFiles {
    const val LLM_ASSET = "models/llm/model.gguf"

    /**
     * llama.cpp читает модель по пути (mmap), а asset внутри APK пути не имеет —
     * копируем один раз во внутреннее хранилище. Повторно — только если размер не совпадает.
     */
    fun ensureOnDisk(context: Context, asset: String = LLM_ASSET): File? {
        val dst = File(context.filesDir, asset.substringAfterLast('/'))
        val size = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull() ?: return null
        if (dst.exists() && dst.length() == size) return dst
        val tmp = File(dst.path + ".part")
        context.assets.open(asset).use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
        if (!tmp.renameTo(dst)) { tmp.delete(); return null }
        return dst
    }
}
