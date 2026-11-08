package ru.neurosteno.core

import java.io.File
import java.nio.file.Files

/** Временные PCM одного запуска. Закрывается после последнего чтения аудио. */
class AudioWorkspace(cacheDir: File) : AutoCloseable {
    val directory: File = Files.createTempDirectory(cacheDir.toPath(), "audio-").toFile()
    val pcm: File = File(directory, "input.pcm")
    val asrPcm: File = File(directory, "input_asr.pcm")

    override fun close() {
        File(pcm.path + ".raw").delete()
        asrPcm.delete()
        pcm.delete()
        directory.delete()
    }

    companion object {
        /**
         * Удаляет рабочие каталоги прошлых запусков, оставшиеся после убийства процесса,
         * и PCM старой схемы (cache/input*.pcm). Вызывать, когда обработка не идёт.
         */
        fun cleanupStale(cacheDir: File) {
            cacheDir.listFiles()?.forEach { f ->
                when {
                    f.isDirectory && f.name.startsWith("audio-") -> f.deleteRecursively()
                    f.isFile && f.name.startsWith("input") && (f.name.endsWith(".pcm") || f.name.endsWith(".pcm.raw")) -> f.delete()
                }
            }
        }
    }
}
