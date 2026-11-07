package ru.neurosteno.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuardedEditorCancellationTest {
    private val transcript = Transcript(listOf(Utterance("speaker_1", 0, 500, "привет")), 500)

    private class Backend(val generate: (stop: () -> Boolean) -> String) : LlmBackend {
        var calls = 0
        override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String {
            calls++
            return generate(shouldStop)
        }
        override fun close() {}
    }

    @Test fun cancellationBeforeEditingNeverCallsModel() {
        val backend = Backend { error("Model must not be called") }
        for (input in listOf(transcript, transcript.copy(utterances = emptyList()),
            transcript.copy(utterances = listOf(transcript.utterances.single().copy(text = "Привет."))))) {
            assertFailsWith<InterruptedException> {
                GuardedEditor(backend).edit(input, 1000, isCancelled = { true })
            }
        }
        assertEquals(0, backend.calls)
    }

    @Test fun cancellationInsideLastGenerationDiscardsResult() {
        var cancelled = false
        val backend = Backend { stop ->
            cancelled = true
            assertTrue(stop())
            "Привет."
        }
        assertFailsWith<InterruptedException> {
            GuardedEditor(backend).edit(transcript, 1000, isCancelled = { cancelled })
        }
        assertEquals(1, backend.calls)
    }

    @Test fun interruptionFromModelPropagates() {
        val backend = Backend { throw InterruptedException("generation interrupted") }
        val error = assertFailsWith<InterruptedException> {
            GuardedEditor(backend).edit(transcript, 1000)
        }
        assertEquals("generation interrupted", error.message)
    }

    @Test fun expiredBudgetReturnsOriginalText() {
        var now = 0L
        val backend = Backend { stop ->
            now = 1001
            assertTrue(stop())
            ""
        }
        val (result, _) = GuardedEditor(backend) { now }.edit(transcript, 1000)
        assertEquals(transcript, result)
    }

    @Test fun modelFailureReturnsOriginalText() {
        val backend = Backend { throw IllegalStateException("model failed") }
        assertEquals(transcript, GuardedEditor(backend).edit(transcript, 1000).first)
    }
}
