package ru.neurosteno.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.neurosteno.audio.AudioDecodeException
import ru.neurosteno.core.JsonExporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ProcState {
    data object Idle : ProcState
    /**
     * progress — доля по каждой начатой стадии отдельно: ASR и диаризация идут параллельно, и одна общая
     * «текущая стадия» прыгала между ними (прогресс-бар дёргался — замечено на Pixel 6a).
     */
    data class Running(val fileName: String, val progress: Map<Stage, Float>, val startedAt: Long) : ProcState
    data class Done(val fileName: String, val result: PipelineResult, val entryId: String) : ProcState
    data class Failed(val fileName: String, val message: String) : ProcState
}

/** Общее состояние обработки: сервис пишет, UI читает. */
object ProcessingRepository {
    val state = MutableStateFlow<ProcState>(ProcState.Idle)
    val cancelled = AtomicBoolean(false)
    fun asFlow(): StateFlow<ProcState> = state
}

/** Foreground Service: обработка длинных файлов не умирает при сворачивании приложения. */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { ProcessingRepository.cancelled.set(true); return START_NOT_STICKY }
        }
        val uri = intent?.data ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: "audio"
        startForegroundCompat(buildNotification("Обработка: $name"))
        if (job?.isActive == true) return START_NOT_STICKY
        ProcessingRepository.cancelled.set(false)
        val started = System.currentTimeMillis()
        val progress = java.util.concurrent.ConcurrentHashMap<Stage, Float>()
        progress[Stage.DECODE] = 0f
        ProcessingRepository.state.value = ProcState.Running(name, progress.toMap(), started)
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neurosteno:processing").apply { acquire(3 * 60 * 60 * 1000L) }
        job = scope.launch {
            try {
                var lastEmit = 0L
                Pipeline(applicationContext, llmFactory = LlmProvider.factory).run(uri, { stage, f ->
                    val prev = progress[stage]
                    progress[stage] = maxOf(f, prev ?: 0f) // прогресс стадии не откатывается назад
                    val now = System.currentTimeMillis()
                    // не чаще 10 раз/с, кроме старта новой стадии и завершения
                    if (prev == null || f >= 1f || now - lastEmit >= 100) {
                        lastEmit = now
                        ProcessingRepository.state.value = ProcState.Running(name, progress.toMap(), started)
                    }
                }) { ProcessingRepository.cancelled.get() }.use { result ->
                    if (ProcessingRepository.cancelled.get()) throw InterruptedException("cancelled")
                    saveLast(applicationContext, name, result)
                    val entry = HistoryStore.add(applicationContext, name, uri.toString(), result)
                    ProcessingRepository.state.value = ProcState.Done(name, result, entry.id)
                }
            } catch (e: InterruptedException) {
                ProcessingRepository.state.value = ProcState.Idle
            } catch (e: AudioDecodeException) {
                Log.w(Pipeline.TAG, "decode failed: ${e.message}", e.cause ?: e)
                ProcessingRepository.state.value = ProcState.Failed(name, e.message ?: "Ошибка декодирования")
            } catch (e: OutOfMemoryError) {
                Log.e(Pipeline.TAG, "OOM", e)
                ProcessingRepository.state.value = ProcState.Failed(name, "Недостаточно памяти для обработки этого файла")
            } catch (e: Throwable) {
                Log.e(Pipeline.TAG, "pipeline failed", e)
                ProcessingRepository.state.value = ProcState.Failed(name, "Ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (wl.isHeld) wl.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProcessingRepository.cancelled.set(true)
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обработка", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Нейро-стенографист")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "ru.neurosteno.CANCEL"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "processing"
        private const val NOTIF_ID = 1

        fun start(context: Context, uri: Uri, name: String) {
            val i = Intent(context, ProcessingService::class.java).setData(uri).putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            ProcessingRepository.cancelled.set(true)
        }

        fun lastJsonFile(context: Context) = File(context.filesDir, "last_transcript.json")

        fun saveLast(context: Context, name: String, r: PipelineResult) {
            lastJsonFile(context).writeText(JsonExporter.toJson(r.transcript))
        }
    }
}
