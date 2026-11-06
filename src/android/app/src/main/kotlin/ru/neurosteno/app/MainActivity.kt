package ru.neurosteno.app

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import ru.neurosteno.core.Summarizer
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.Utterance

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // После пересоздания activity (savedInstanceState != null) не запускаем «Поделиться» повторно.
        val shared: Uri? = if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                App(shared)
            }
        }
    }
}

private val speakerColors = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF546E7A),
)

fun speakerColor(id: String): Color {
    val n = id.removePrefix("speaker_").toIntOrNull() ?: 1
    return speakerColors[(n - 1).mod(speakerColors.size)]
}

fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d.%d".format(s / 60, s % 60, ms % 1000 / 100)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(shared: Uri?) {
    val ctx = LocalContext.current
    val state by ProcessingRepository.asFlow().collectAsState()
    var audioUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun startProcessing(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        audioUri = uri.toString()
        ProcessingService.start(ctx, uri, displayName(ctx, uri))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::startProcessing) }
    LaunchedEffect(shared) { shared?.let(::startProcessing) }

    Scaffold(topBar = { TopAppBar(title = { Text("Нейро-стенографист") }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                is ProcState.Idle -> ImportScreen(
                    onPick = { picker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) },
                    onRecorded = { f -> startProcessing(Uri.fromFile(f)) },
                )
                is ProcState.Running -> ProgressScreen(s) { ProcessingService.cancel(ctx) }
                is ProcState.Done -> TranscriptScreen(s, audioUri?.let(Uri::parse)) {
                    ProcessingRepository.state.value = ProcState.Idle
                }
                is ProcState.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Не удалось обработать «${s.fileName}»", style = MaterialTheme.typography.titleMedium)
                    Text(s.message)
                    Button(onClick = { ProcessingRepository.state.value = ProcState.Idle }) { Text("Выбрать другой файл") }
                }
            }
        }
    }
}

@Composable
fun ImportScreen(onPick: () -> Unit, onRecorded: (java.io.File) -> Unit) {
    val ctx = LocalContext.current
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recMs by remember { mutableLongStateOf(0L) }
    var recLevel by remember { mutableStateOf(0f) }
    fun startRec() {
        val f = java.io.File(ctx.cacheDir, "record_${System.currentTimeMillis()}.wav")
        recorder = runCatching { WavRecorder(f).also { it.start() } }
            .onFailure { Toast.makeText(ctx, "Микрофон недоступен: ${it.message}", Toast.LENGTH_LONG).show() }
            .getOrNull()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else Toast.makeText(ctx, "Нет разрешения на микрофон", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(recorder) {
        while (recorder != null) {
            val r = recorder!!
            recMs = r.recordedMs; recLevel = r.level
            if (!r.isRunning) { // запись прервалась сама (ошибка микрофона)
                r.stop(); recorder = null
                Toast.makeText(ctx, r.error ?: "Запись остановлена", Toast.LENGTH_LONG).show()
                break
            }
            delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }
    recorder?.let { r ->
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("● Идёт запись", color = Color(0xFFE53935), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(fmtMs(recMs), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { recLevel.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Button(onClick = { recorder = null; onRecorded(r.stop()) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Остановить и расшифровать")
            }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.AudioFile, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Расшифровка разговора", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Распознавание речи, определение говорящих и тайминги реплик. Всё выполняется на устройстве, без интернета.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выбрать аудиофайл") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) startRec()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Записать с микрофона") }
        Spacer(Modifier.height(8.dp))
        Text("wav, mp3, m4a, ogg, opus, flac, amr", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProgressScreen(s: ProcState.Running, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.fileName, style = MaterialTheme.typography.titleMedium)
        Text("Прошло: ${fmtMs(now - s.startedAt)}", style = MaterialTheme.typography.bodySmall)
        val order = Stage.entries.filter { it != Stage.DONE }
        // ASR и диаризация идут параллельно — показываем текущую активную стадию.
        order.forEach { st ->
            val passed = st.ordinal < s.stage.ordinal
            val active = st == s.stage
            Column {
                Text(
                    (if (passed) "✓ " else if (active) "▶ " else "• ") + st.title,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
                if (active) LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("Отменить") }
    }
}

@Composable
fun TranscriptScreen(s: ProcState.Done, audio: Uri?, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val t = s.result.transcript
    val player = remember(audio) { audio?.let { runCatching { MediaPlayer.create(ctx, it) }.getOrNull() } }
    DisposableEffect(player) { onDispose { player?.release() } }
    var playingIdx by remember { mutableStateOf(-1) }
    var stopAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(playingIdx) {
        while (playingIdx >= 0 && player != null) {
            if (!player.isPlaying || player.currentPosition >= stopAt) { player.pause(); playingIdx = -1 }
            delay(100)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(JsonExporter.toJson(t).toByteArray(Charsets.UTF_8)) }
            }.onSuccess { Toast.makeText(ctx, "JSON сохранён", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Ошибка сохранения: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val speakers = t.utterances.map { it.speakerId }.distinct()
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<String?>(null) }
    var summarizing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(s.fileName, style = MaterialTheme.typography.titleMedium)
            Text(
                "Длительность ${fmtMs(t.durationMs)} · реплик ${t.utterances.size} · говорящих ${speakers.size} · " +
                    "обработка ${fmtMs(s.result.totalMs)} (RTF %.2f)".format(s.result.rtf),
                style = MaterialTheme.typography.bodySmall,
            )
            s.result.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { exporter.launch(s.fileName.substringBeforeLast('.') + ".json") }) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Экспорт JSON")
                }
                OutlinedButton(enabled = !summarizing && t.utterances.isNotEmpty(), onClick = {
                    summarizing = true
                    scope.launch {
                        summary = withContext(Dispatchers.Default) {
                            runCatching {
                                LlmProvider.factory.create(ctx)?.use { Summarizer(it).summarize(t) } ?: "Локальная LLM недоступна"
                            }.getOrElse { "Ошибка LLM: ${it.message}" }
                        }
                        summarizing = false
                    }
                }) { Text(if (summarizing) "Протокол…" else "Протокол") }
                OutlinedButton(onClick = onNew) { Text("Новый") }
                if (playingIdx >= 0) IconButton(onClick = { player?.pause(); playingIdx = -1 }) { Icon(Icons.Default.Stop, "Стоп") }
            }
        }
        summary?.let {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Протокол (локальная LLM, может содержать неточности)", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (t.utterances.isEmpty()) {
            Text("Речь не обнаружена.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            itemsIndexed(t.utterances) { i, u ->
                UtteranceCard(u, playing = i == playingIdx) {
                    player?.let {
                        it.seekTo(u.startMs.toInt()); it.start()
                        stopAt = u.endMs; playingIdx = i
                    }
                }
            }
        }
    }
}

@Composable
fun UtteranceCard(u: Utterance, playing: Boolean, onClick: () -> Unit) {
    val c = speakerColor(u.speakerId)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (playing) c.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(u.speakerId.replace("speaker_", "Говорящий "), color = c, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${fmtMs(u.startMs)} – ${fmtMs(u.endMs)}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(u.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun displayName(ctx: android.content.Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"
