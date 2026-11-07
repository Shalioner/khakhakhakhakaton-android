package ru.neurosteno.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    /** Файл из «Поделиться»: и при запуске, и когда приложение уже открыто (onNewIntent, launchMode singleTop). */
    private val shared = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // После пересоздания activity (savedInstanceState != null) не запускаем «Поделиться» повторно.
        if (savedInstanceState == null) shared.value = sharedUri(intent)
        setContent { NeuroTheme { App(shared.value) { shared.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUri(intent)?.let { shared.value = it }
    }

    private fun sharedUri(i: Intent?): Uri? = if (i?.action == Intent.ACTION_SEND) {
        @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
    } else null
}

@Composable
fun App(shared: Uri?, onSharedConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val state by ProcessingRepository.asFlow().collectAsState()
    var openEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var historyVersion by remember { mutableStateOf(0) }

    fun startProcessing(uri: Uri, name: String = displayName(ctx, uri)) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        ProcessingService.start(ctx, uri, name)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { startProcessing(it) } }
    LaunchedEffect(shared) { shared?.let { startProcessing(it); onSharedConsumed() } }

    // Готовая расшифровка уже в истории — открываем её, сервис возвращаем в Idle.
    LaunchedEffect(state) {
        val s = state
        if (s is ProcState.Done) {
            openEntry = s.entryId
            historyVersion++
            ProcessingRepository.state.value = ProcState.Idle
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = when {
                state is ProcState.Running -> "run"
                state is ProcState.Failed -> "fail"
                openEntry != null -> "entry:$openEntry"
                else -> "home"
            },
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "screen",
        ) { key ->
            when {
                key == "run" -> (state as? ProcState.Running)?.let { ProcessingScreen(it) { ProcessingService.cancel(ctx) } }
                key == "fail" -> (state as? ProcState.Failed)?.let {
                    FailedScreen(it) { ProcessingRepository.state.value = ProcState.Idle }
                }
                key.startsWith("entry:") -> {
                    BackHandler { openEntry = null }
                    TranscriptScreen(
                        entryId = key.removePrefix("entry:"),
                        onBack = { openEntry = null },
                        onDeleted = { openEntry = null; historyVersion++ },
                    )
                }
                else -> HomeScreen(
                    historyVersion = historyVersion,
                    onOpen = { openEntry = it.id },
                    onPick = { picker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) },
                    onRecorded = { f, name -> startProcessing(Uri.fromFile(f), name) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Главный экран: список записей + запись

@Composable
fun HomeScreen(
    historyVersion: Int,
    onOpen: (HistoryEntry) -> Unit,
    onPick: () -> Unit,
    onRecorded: (java.io.File, String) -> Unit,
) {
    val ctx = LocalContext.current
    val entries = remember(historyVersion) { HistoryStore.list(ctx) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recMs by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }

    fun startRec() {
        val dir = java.io.File(ctx.filesDir, "recordings").apply { mkdirs() }
        val f = java.io.File(dir, "record_${System.currentTimeMillis()}.wav")
        levels.clear()
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
            recMs = r.recordedMs
            levels.add(sqrt(r.level.coerceIn(0f, 1f))) // корень — тихая речь тоже видна
            if (levels.size > 120) levels.removeAt(0)
            if (!r.isRunning) { // запись прервалась сама (ошибка микрофона)
                r.stop(); recorder = null
                Toast.makeText(ctx, r.error ?: "Запись остановлена", Toast.LENGTH_LONG).show()
                break
            }
            delay(80)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPick, enabled = recorder == null) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Открыть файл")
            }
        }
        Text(
            "Записи",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
        )
        Text(
            "Расшифровка и говорящие — прямо на телефоне, без интернета",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
        )
        if (entries.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Нет записей", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Нажмите красную кнопку, чтобы записать разговор, или откройте аудиофайл",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(entries, key = { it.id }) { e -> HistoryRow(e) { onOpen(e) } }
            }
        }
        RecordPanel(
            recording = recorder != null,
            elapsedMs = recMs,
            levels = levels,
            onRecord = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) startRec()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStop = {
                recorder?.let { r ->
                    recorder = null
                    val name = "Запись " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date())
                    onRecorded(r.stop(), name)
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp)) {
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp, end = 20.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${friendlyDate(e.createdAt)} · ${plural(e.speakers, "говорящий", "говорящих", "говорящих")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    fmtMs(e.durationMs, tenths = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

/** Нижняя панель: большая круглая кнопка записи; во время записи — таймер, волна и «стоп». */
@Composable
private fun RecordPanel(
    recording: Boolean,
    elapsedMs: Long,
    levels: List<Float>,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    val red = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 18.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recording) {
                Text("Новая запись", style = MaterialTheme.typography.titleMedium)
                Text(
                    fmtMs(elapsedMs),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Waveform(levels, red, Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp))
                Spacer(Modifier.height(16.dp))
            }
            val inner by animateFloatAsState(if (recording) 0.48f else 0.84f, tween(220), label = "rec")
            val corner by animateFloatAsState(if (recording) 0.22f else 0.5f, tween(220), label = "corner")
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = if (recording) onStop else onRecord)
                    .semantics { contentDescription = if (recording) "Остановить запись" else "Начать запись" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(76.dp)) {
                    val side = size.minDimension * inner
                    drawRoundRect(
                        color = red,
                        topLeft = Offset((size.width - side) / 2, (size.height - side) / 2),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(side * corner, side * corner),
                    )
                }
            }
            Text(
                if (recording) "Остановить и расшифровать" else "Записать разговор",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Волна уровней микрофона: свежие значения справа, вертикальная линия «сейчас». */
@Composable
private fun Waveform(levels: List<Float>, color: Color, modifier: Modifier) {
    val idle = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val n = 60
        val bar = size.width / n
        val shown = levels.takeLast(n)
        val offset = n - shown.size
        for (i in 0 until n) {
            val v = if (i >= offset) shown[i - offset] else 0f
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtLeast(2f)
            drawRoundRect(
                color = if (i >= offset) color else idle,
                topLeft = Offset(i * bar + bar * 0.2f, (size.height - h) / 2),
                size = Size(bar * 0.6f, h),
                cornerRadius = CornerRadius(bar * 0.3f, bar * 0.3f),
            )
        }
    }
}

// ---------------------------------------------------------------- Обработка

private val stageWeight = mapOf(
    Stage.DECODE to 0.05f, Stage.VAD to 0.05f, Stage.ASR to 0.38f, Stage.DIARIZATION to 0.38f,
    Stage.ALIGN to 0.04f, Stage.LLM to 0.10f,
)

@Composable
fun ProcessingScreen(s: ProcState.Running, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val order = Stage.entries.filter { it != Stage.DONE }
    val parallel = setOf(Stage.ASR, Stage.DIARIZATION)
    val maxStarted = s.progress.keys.maxOfOrNull { it.ordinal } ?: 0
    fun done(st: Stage): Boolean {
        val p = s.progress[st]
        return p != null && p >= 0.999f || (maxStarted > st.ordinal && (st !in parallel || maxStarted > Stage.DIARIZATION.ordinal))
    }
    val overall = order.sumOf { st ->
        val w = stageWeight[st] ?: 0f
        (w * if (done(st)) 1f else (s.progress[st] ?: 0f)).toDouble()
    }.toFloat().coerceIn(0f, 1f)
    val shown by animateFloatAsState(overall, tween(400), label = "overall")

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.6f))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { shown },
                modifier = Modifier.size(148.dp),
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(20.dp))
        Text(s.fileName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            "Прошло ${fmtMs(now - s.startedAt, tenths = false)} · на устройстве, без интернета",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                order.forEachIndexed { i, st ->
                    val isDone = done(st)
                    val active = s.progress[st] != null && !isDone
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            when {
                                isDone -> Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF2E9E5B), modifier = Modifier.size(22.dp))
                                active -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                else -> Box(Modifier.size(14.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            st.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isDone || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) Text(
                            "${((s.progress[st] ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i < order.lastIndex) HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Отменить", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun FailedScreen(s: ProcState.Failed, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Не удалось обработать", style = MaterialTheme.typography.titleLarge)
        Text(s.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(s.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("К записям", style = MaterialTheme.typography.labelLarge) }
    }
}

// ---------------------------------------------------------------- Утилиты

fun displayName(ctx: android.content.Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"

fun friendlyDate(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val ru = Locale("ru")
    return when {
        c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
            "Сегодня, " + SimpleDateFormat("HH:mm", ru).format(Date(ms))
        c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 ->
            "Вчера, " + SimpleDateFormat("HH:mm", ru).format(Date(ms))
        else -> SimpleDateFormat("d MMM yyyy", ru).format(Date(ms))
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}
