package ru.neurosteno.app

import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.Summarizer
import ru.neurosteno.core.Utterance

@Composable
fun TranscriptScreen(entryId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val entry = remember(entryId) { HistoryStore.get(ctx, entryId) }
    val transcript = remember(entry) { entry?.let { HistoryStore.transcript(ctx, it) } }
    if (entry == null || transcript == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            BackButton(onBack)
            Text("Запись не найдена", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        }
        return
    }
    val utts = transcript.utterances

    // ---- Плеер
    val player = remember(entry.audioUri) {
        entry.audioUri?.let { u -> runCatching { MediaPlayer.create(ctx, Uri.parse(u)) }.getOrNull() }
    }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    // Длительность — один раз: обращение к уже освобождённому MediaPlayer бросает IllegalStateException
    // (так приложение падало при удалении записи, пока экран доигрывал анимацию ухода).
    val durMs = remember(player) {
        (runCatching { player?.duration?.toLong() }.getOrNull()?.takeIf { it > 0 } ?: entry.durationMs).coerceAtLeast(1)
    }
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(player, playing) {
        while (player != null && playing && !released) {
            val p = runCatching { player.currentPosition.toLong() to player.isPlaying }.getOrNull() ?: break
            posMs = p.first
            if (!p.second) playing = false
            delay(50)
        }
    }
    fun seek(ms: Long) {
        if (released) return
        runCatching { player?.seekTo(ms.coerceIn(0, durMs).toInt()) }
        posMs = ms.coerceIn(0, durMs)
    }
    fun toggle() {
        val p = player ?: return
        if (released) return
        runCatching { if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true } }
    }
    val currentIdx = utts.indexOfLast { it.startMs <= posMs }.takeIf { it >= 0 && posMs <= utts[it].endMs + 400 } ?: -1

    // Автопрокрутка к текущей реплике при воспроизведении
    val listState = rememberLazyListState()
    LaunchedEffect(currentIdx, playing) {
        if (playing && currentIdx >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (currentIdx + 1 !in visible.dropLast(1)) listState.animateScrollToItem(currentIdx + 1, -120)
        }
    }

    // ---- Экспорт, протокол, удаление
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(JsonExporter.toJson(transcript).toByteArray(Charsets.UTF_8)) }
            }.onSuccess { Toast.makeText(ctx, "JSON сохранён", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Ошибка сохранения: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<String?>(null) }
    var summarizing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val speakers = utts.map { it.speakerId }.distinct()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Верхняя панель
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            IconButton(
                enabled = !summarizing && utts.isNotEmpty(),
                onClick = {
                    summarizing = true
                    scope.launch {
                        summary = withContext(Dispatchers.Default) {
                            runCatching {
                                LlmProvider.factory.create(ctx)?.use { Summarizer(it).summarize(transcript) } ?: "Локальная LLM недоступна"
                            }.getOrElse { "Ошибка LLM: ${it.message}" }
                        }
                        summarizing = false
                    }
                },
            ) {
                if (summarizing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.AutoAwesome, "Протокол встречи", tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = { exporter.launch(entry.name + ".json") }) {
                Icon(Icons.Outlined.FileDownload, "Экспорт JSON", tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${friendlyDate(entry.createdAt)} · ${fmtMs(entry.durationMs, tenths = false)} · " +
                            "${plural(utts.size, "реплика", "реплики", "реплик")} · расшифровано за ${fmtMs(entry.processingMs, tenths = false)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // Легенда говорящих
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        speakers.forEach { id ->
                            val c = speakerColor(id)
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                Spacer(Modifier.width(6.dp))
                                Text(speakerName(id), style = MaterialTheme.typography.labelMedium, color = c)
                            }
                        }
                    }
                    entry.warnings.forEach {
                        Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    summary?.let {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Протокол · локальная LLM, возможны неточности", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (utts.isEmpty()) Text("Речь не обнаружена", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(utts, key = { i, _ -> i }) { i, u ->
                UtteranceRow(u, current = i == currentIdx) {
                    if (player != null) { seek(u.startMs); if (!playing) toggle() }
                }
            }
        }

        PlayerBar(
            available = player != null,
            playing = playing,
            posMs = if (dragging) (dragFrac * durMs).toLong() else posMs,
            durMs = durMs,
            onToggle = ::toggle,
            onBack10 = { seek(posMs - 10_000) },
            onFwd10 = { seek(posMs + 10_000) },
            onDrag = { dragging = true; dragFrac = it },
            onDragEnd = { dragging = false; seek((dragFrac * durMs).toLong()) },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить запись?") },
        text = { Text("Расшифровка «${entry.name}» будет удалена с устройства.") },
        confirmButton = {
            TextButton(onClick = {
                // Плеер не освобождаем здесь: это сделает DisposableEffect, когда экран действительно уйдёт.
                runCatching { if (player?.isPlaying == true) player.pause() }
                playing = false; released = true
                HistoryStore.delete(ctx, entry); confirmDelete = false; onDeleted()
            }) {
                Text("Удалить", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        Text("Записи", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun UtteranceRow(u: Utterance, current: Boolean, onClick: () -> Unit) {
    val c = speakerColor(u.speakerId)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) c.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(Modifier.padding(top = 3.dp).width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(speakerName(u.speakerId), style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${fmtMs(u.startMs, tenths = false)} – ${fmtMs(u.endMs, tenths = false)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(u.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Нижний плеер: ползунок, время, −10 / ▶ / +10. */
@Composable
private fun PlayerBar(
    available: Boolean,
    playing: Boolean,
    posMs: Long,
    durMs: Long,
    onToggle: () -> Unit,
    onBack10: () -> Unit,
    onFwd10: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            if (!available) {
                Text(
                    "Исходный звук недоступен — расшифровка сохранена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                return@Column
            }
            Slider(
                value = (posMs.toFloat() / durMs).coerceIn(0f, 1f),
                onValueChange = onDrag,
                onValueChangeFinished = onDragEnd,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.height(28.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtMs(posMs, tenths = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("−" + fmtMs(durMs - posMs, tenths = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack10) { Icon(Icons.Filled.Replay10, "Назад 10 секунд", Modifier.size(30.dp)) }
                Spacer(Modifier.width(28.dp))
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Пауза" else "Воспроизвести",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = onFwd10) { Icon(Icons.Filled.Forward10, "Вперёд 10 секунд", Modifier.size(30.dp)) }
            }
        }
    }
}
