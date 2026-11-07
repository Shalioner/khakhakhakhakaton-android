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
}
