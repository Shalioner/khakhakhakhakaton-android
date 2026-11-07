package ru.neurosteno.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AudioWorkspaceTest {
    @Test fun parallelRunsKeepTheirOwnPcm() {
        val root = Files.createTempDirectory("audio-workspace-test").toFile()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { value ->
                pool.submit<File> {
                    AudioWorkspace(root).use { workspace ->
                        workspace.pcm.writeText("audio-$value")
                        workspace.asrPcm.writeText("asr-$value")
                        File(workspace.pcm.path + ".raw").writeText("raw-$value")
                        barrier.await(10, TimeUnit.SECONDS)
                        assertEquals("audio-$value", workspace.pcm.readText())
                        assertEquals("asr-$value", workspace.asrPcm.readText())
                        workspace.directory
                    }
                }
            }
            val directories = jobs.map { it.get(15, TimeUnit.SECONDS) }
            assertNotEquals(directories[0], directories[1])
            assertTrue(directories.none { it.exists() })
        } finally {
            pool.shutdownNow()
            root.delete()
        }
    }

    @Test fun failuresAndCancellationCleanOnlyTheOwnedDirectory() {
        val root = Files.createTempDirectory("audio-workspace-test").toFile()
        try {
            AudioWorkspace(root).use { active ->
                active.pcm.writeText("keep")
                for (failure in listOf(IOException("decode failed"), InterruptedException("cancelled"))) {
                    val failed = AudioWorkspace(root)
                    val thrown = assertFails {
                        failed.use {
                            it.pcm.writeText("partial")
                            it.asrPcm.writeText("partial agc")
                            File(it.pcm.path + ".raw").writeText("partial raw")
                            throw failure
                        }
                    }
                    assertSame(failure, thrown)
                    assertFalse(failed.directory.exists())
                    assertEquals("keep", active.pcm.readText())
                }
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.delete()
        }
    }

    @Test fun audioLivesUntilCloseAndRepeatedCloseLeavesOtherRunsAlone() {
        val root = Files.createTempDirectory("audio-workspace-test").toFile()
        try {
            val first = AudioWorkspace(root)
            first.pcm.writeText("first")
            assertEquals("first", first.pcm.readText())
            first.close()
            AudioWorkspace(root).use { second ->
                second.pcm.writeText("second")
                first.close()
                assertEquals("second", second.pcm.readText())
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.delete()
        }
    }
}
