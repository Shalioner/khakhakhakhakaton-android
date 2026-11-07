package ru.neurosteno.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {

    private fun w(t: String, s: Long, e: Long, c: Float? = null) = Word(t, s, e, c)

    @Test
    fun alignmentByMaxOverlap() {
        val turns = listOf(SpeakerTurn(7, 0, 1000), SpeakerTurn(3, 1000, 2000))
        val lw = Alignment.assign(listOf(w("а", 100, 400), w("б", 900, 1300), w("в", 1500, 1800)), turns)
        assertEquals(listOf(7, 3, 3), lw.map { it.speakerIdx })
    }

    @Test
    fun smoothingRemovesIsolatedWordButKeepsBackchannel() {
        val turns = listOf(
            SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 1000, 1200), SpeakerTurn(0, 1200, 3000),
            SpeakerTurn(1, 3000, 3400), SpeakerTurn(0, 3400, 5000),
        )
        val words = listOf(
            w("раз", 100, 900), w("два", 1000, 1200), w("три", 1300, 2900),
            w("угу", 3000, 3400), w("четыре", 3500, 4500),
        )
        val lw = Alignment.assign(words, turns)
        assertEquals(listOf(0, 0, 0, 1, 0), lw.map { it.speakerIdx })
    }

    @Test
    fun noTurnsMeansOneSpeaker() {
        val t = TranscriptBuilder.build(listOf(w("привет", 0, 500), w("мир", 600, 900)), emptyList(), 1000)
        assertEquals(1, t.utterances.size)
        assertEquals("speaker_1", t.utterances[0].speakerId)
        assertEquals("привет мир", t.utterances[0].text)
    }

    @Test
    fun pauseSplitsUtterance() {
        val turns = listOf(SpeakerTurn(0, 0, 10_000))
        val t = TranscriptBuilder.build(listOf(w("а", 0, 300), w("б", 2000, 2300)), turns, 10_000)
        assertEquals(2, t.utterances.size)
    }

    @Test
    fun relabelByFirstAppearance() {
        val turns = listOf(SpeakerTurn(5, 0, 1000), SpeakerTurn(2, 1000, 2000), SpeakerTurn(5, 2000, 3000))
        val words = listOf(w("один", 0, 900), w("два", 1100, 1900), w("три", 2100, 2900))
        val t = TranscriptBuilder.build(words, turns, 3000, cfg = PipelineConfig(minSpeakerTotalMs = 0))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), t.utterances.map { it.speakerId })
        assertTrue(ContractValidator.validate(t).isEmpty())
    }

    @Test
    fun nonSpeechSingleLowConfTokenDropped() {
        val u = RawUtterance(0, listOf(w("мяу", 0, 200, 0.2f)))
        assertTrue(Segmenter.isNonSpeech(u, PipelineConfig()))
        assertTrue(!Segmenter.isNonSpeech(RawUtterance(0, listOf(w("да", 0, 200, 0.9f))), PipelineConfig()))
    }

    @Test
    fun tinySpeakerAbsorbed() {
        val utts = listOf(
            RawUtterance(0, listOf(w("длинная", 0, 5000))),
            RawUtterance(1, listOf(w("шум", 5100, 5300))),
            RawUtterance(0, listOf(w("речь", 5400, 9000))),
        )
        val out = SpeakerRelabel.absorbTinySpeakers(utts, 1500, 1000)
        assertEquals(1, out.size)
        assertEquals(0, out[0].speakerIdx)
    }

    @Test
    fun centroidMerge() {
        val m = SpeakerRelabel.mergeByCentroids(
            mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0.99f, 0.05f), 2 to floatArrayOf(0f, 1f)), 0.9f,
        )
        assertEquals(m[0], m[1])
        assertTrue(m[0] != m[2])
    }

    @Test
    fun wordGuard() {
        assertTrue(WordGuard.sameWords("привет как дела", "Привет! Как дела?"))
        assertTrue(WordGuard.sameWords("ёлка", "Елка."))
        assertTrue(!WordGuard.sameWords("привет как дела", "Привет, как твои дела?"))
        assertEquals("привет как дела", WordGuard.accept("привет как дела", "Привет, как твои дела?"))
        assertEquals("Привет, как дела?", WordGuard.accept("привет как дела", "Привет, как дела?"))
    }

    @Test
    fun jsonContractShape() {
        val t = Transcript(listOf(Utterance("speaker_1", 1250, 4380, "Добрый \"день\"!")), 5000)
        val json = JsonExporter.toJson(t, pretty = false)
        assertEquals("{\"transcript\":[{\"speaker_id\":\"speaker_1\",\"start_ms\":1250,\"end_ms\":4380,\"text\":\"Добрый \\\"день\\\"!\"}]}", json)
    }

    @Test
    fun validatorCatchesErrors() {
        val t = Transcript(listOf(Utterance("speaker_2", 100, 50, " ")), 40)
        assertTrue(ContractValidator.validate(t).size >= 4)
    }

    @Test
    fun wer() {
        assertEquals(0.0, Metrics.wer("Привет, мир!", "привет мир"))
        assertEquals(0.5, Metrics.wer("привет мир", "привет"))
    }

    @Test
    fun wordsClampedToDuration() {
        val t = TranscriptBuilder.build(listOf(w("конец", 900, 1200)), emptyList(), 1000)
        assertEquals(1000, t.utterances[0].endMs)
        assertTrue(ContractValidator.validate(t).isEmpty())
    }
}

class GuardedEditorTest {
    private class FakeLlm(val f: (String) -> String) : LlmBackend {
        override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean) = f(user)
        override fun close() {}
    }

    @Test
    fun acceptsPunctuationRejectsWordChanges() {
        val t = Transcript(listOf(
            Utterance("speaker_1", 0, 1000, "привет как дела"),
            Utterance("speaker_2", 1000, 2000, "хорошо спасибо"),
        ), 2000)
        val llm = FakeLlm { if (it.startsWith("привет")) "Привет, как дела?" else "Отлично, спасибо!" }
        val (out, st) = GuardedEditor(llm).edit(t, budgetMs = 10_000)
        assertEquals("Привет, как дела?", out.utterances[0].text)
        assertEquals("хорошо спасибо", out.utterances[1].text)
        assertEquals(1, st.rejected)
        assertEquals(t.utterances.map { Triple(it.speakerId, it.startMs, it.endMs) }, out.utterances.map { Triple(it.speakerId, it.startMs, it.endMs) })
    }

    @Test
    fun budgetExceededKeepsAsrText() {
        var now = 0L
        val t = Transcript(List(3) { Utterance("speaker_1", it * 1000L, it * 1000L + 500, "раз два") }, 3000)
        val llm = FakeLlm { now += 600; "Раз, два." }
        val (out, st) = GuardedEditor(llm) { now }.edit(t, budgetMs = 1000)
        assertEquals(2, st.accepted)
        assertEquals(1, st.skipped)
        assertEquals("раз два", out.utterances[2].text)
    }
}

class LlmGatingTest {
    private val ed = GuardedEditor(object : LlmBackend {
        override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean) = error("must not be called")
        override fun close() {}
    })

    @Test
    fun cleanTextSkipsLlm() {
        assertTrue(!ed.needsEdit("Привет, как дела?"))
        assertTrue(ed.needsEdit("привет как дела"))
        assertTrue(ed.needsEdit("Привет как дела"))
        val t = Transcript(listOf(Utterance("speaker_1", 0, 100, "Готово.")), 100)
        val (_, st) = ed.edit(t, 1000)
        assertEquals(1, st.alreadyClean)
    }

    @Test
    fun summarizerPromptTruncates() {
        val t = Transcript(List(500) { Utterance("speaker_1", it * 10L, it * 10L + 5, "фраза номер $it") }, 6000)
        val p = Summarizer(object : LlmBackend {
            override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean) = ""
            override fun close() {}
        }).prompt(t, 500)
        assertTrue(p.length <= 520 && p.endsWith("…\n"))
    }
}

class VadRefineTest {
    private fun w(t: String, s: Long, e: Long) = Word(t, s, e)

    @Test
    fun endExtendedToVadButNotIntoNeighbour() {
        val utts = listOf(
            RawUtterance(0, listOf(w("здравствуйте", 1000, 1120))),
            RawUtterance(1, listOf(w("да", 1900, 2000))),
        )
        val vad = listOf(SpeechSegment(900, 1800), SpeechSegment(1850, 2500))
        val b = Segmenter.refineWithVad(utts, vad, PipelineConfig())
        assertEquals(900L to 1800L, b[0])
        assertEquals(1850L to 2500L, b[1])
        // перекрытие: следующая реплика начинается раньше конца VAD — конец обрезается по соседу
        val b2 = Segmenter.refineWithVad(
            listOf(RawUtterance(0, listOf(w("а", 0, 100))), RawUtterance(1, listOf(w("б", 300, 400)))),
            listOf(SpeechSegment(0, 900)), PipelineConfig(),
        )
        assertEquals(0L to 300L, b2[0])
    }
}

class PunctTransferTest {
    private fun ws(vararg t: String) = t.mapIndexed { i, s -> Word(s, i * 100L, i * 100L + 90) }

    @Test
    fun transfersCaseAndPunctuation() {
        val out = PunctTransfer.transfer(ws("привет", "как", "дела"), "Привет, как дела?")
        assertEquals(listOf("Привет,", "как", "дела?"), out.map { it.text })
        assertEquals(listOf(0L, 100L, 200L), out.map { it.startMs })
    }

    @Test
    fun partialSecondPassDoesNotTurnTheEndOfAStatementIntoAQuestion() {
        val words = ws("тогда", "соберёмся", "в", "подвале", "там", "сухо", "и", "тепло")
        val expected = words.toMutableList().apply { this[0] = first().copy(text = "Тогда") }
        assertEquals(expected, PunctTransfer.transfer(words, "Сухо и тепло?"))
    }

    @Test
    fun keepsQuestionAfterAlignedSentenceBoundary() {
        val out = PunctTransfer.transfer(ws("идём", "сухо", "и", "тепло"), "Идём. Сухо и тепло?")
        assertEquals(listOf("Идём.", "Сухо", "и", "тепло?"), out.map { it.text })
    }

    @Test
    fun numbersStayWordsWithTrailingPunct() {
        val out = PunctTransfer.transfer(
            ws("сейчас", "живёт", "двадцать", "семь", "котов", "три", "тысячи"),
            "Сейчас живёт 27 котов. 3 000.",
        )
        assertEquals(listOf("Сейчас", "живёт", "двадцать", "семь", "котов.", "Три", "тысячи."), out.map { it.text })
    }

    @Test
    fun neverChangesWords() {
        val src = ws("мама", "мыла", "раму")
        val out = PunctTransfer.transfer(src, "Папа мыл раму!")
        assertEquals(src.map { WordGuard.normalizeWord(it.text) }, out.map { WordGuard.normalizeWord(it.text) })
        assertEquals("раму!", out[2].text)
    }
}

class AbsorbGapTest {
    @Test
    fun tinySpeakerDoesNotGlueLongPauses() {
        val w = { t: String, s: Long, e: Long -> Word(t, s, e) }
        val utts = listOf(
            RawUtterance(0, listOf(w("раз", 0, 3000))),
            RawUtterance(1, listOf(w("кхм", 3100, 3300))),
            RawUtterance(0, listOf(w("два", 3400, 6000))),
            RawUtterance(0, listOf(w("три", 20_000, 23_000))), // через 14 с — отдельная реплика
        )
        val out = SpeakerRelabel.absorbTinySpeakers(utts, 1500, 1000)
        assertEquals(2, out.size)
        assertEquals(20_000, out[1].startMs)
    }
}
