package ru.neurosteno.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.Utterance

class HistoryEditTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun renamePreservesExistingMetaAndSurvivesReload() {
        val file = folder.newFile("1.meta.json")
        file.writeText("""{"name":"Встреча","createdAt":1,"durationMs":500,"other":true}""")
        assertTrue(HistoryStore.readMeta("1", file).speakerNames.isEmpty())
        HistoryStore.renameSpeaker(file, "1", "speaker_1", " Анна ")
        HistoryStore.renameSpeaker(file, "1", "speaker_2", "Борис")
        assertEquals(mapOf("speaker_1" to "Анна", "speaker_2" to "Борис"), HistoryStore.readMeta("1", file).speakerNames)
        assertTrue(JSONObject(file.readText()).getBoolean("other"))
    }

    @Test fun editChangesOnlyTextAndRejectsBlankInput() {
        val file = folder.newFile("1.json")
        val first = Utterance("speaker_1", 0, 100, "Привет")
        val second = Utterance("speaker_2", 150, 450, "Ответ")
        file.writeText(JsonExporter.toJson(Transcript(listOf(first, second), 500)))
        HistoryStore.editText(file, 500, 1, " Ответ \"да\" ")
        assertThrows(IllegalArgumentException::class.java) { HistoryStore.editText(file, 500, 1, " ") }
        val expected = Transcript(listOf(first, second.copy(text = "Ответ \"да\"")), 500)
        assertEquals(expected, HistoryStore.readTranscript(file, 500))
        assertEquals(JsonExporter.toJson(expected), file.readText())
    }
}
