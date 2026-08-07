package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.LogFileInfo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Ловит необработанные исключения и складывает их на диск: в бете иначе о падении
 * узнаёшь только из сообщения «оно закрылось».
 *
 * Обработчик ставится в каждом процессе приложения, поэтому в отчёт пишется имя
 * процесса - падение ядра в :RunSoLibV2RayDaemon выглядит совсем не так, как
 * падение интерфейса.
 */
object CrashReportManager {

    /** Каталог (внутри filesDir) с отчётами о сбоях. */
    private const val CRASH_DIR = "crash"

    /** Сколько отчётов держим: старые вытесняются, диск не набивается. */
    private const val MAX_REPORTS = 10

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val readableStamp = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US)

    /** Второй сбой во время записи отчёта не должен уводить нас в рекурсию. */
    @Volatile
    private var handling = false

    fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR)

    /**
     * Ставит обработчик поверх системного. Системный вызывается в любом случае:
     * процесс должен умереть так же, как умирал бы без нас.
     *
     * @param context Контекст приложения.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!handling) {
                handling = true
                runCatching { write(context, thread, error) }
            }
            // Без системного обработчика процесс останется висеть живым трупом
            previous?.uncaughtException(thread, error) ?: exitProcess(2)
        }
    }

    /**
     * Отчёты на диске, свежие сверху.
     *
     * @param context Контекст.
     * @return Список файлов; пустой, если падений не было.
     */
    fun listReports(context: Context): List<LogFileInfo> {
        val files = crashDir(context).listFiles()?.filter { it.isFile } ?: return emptyList()
        return files
            .map { LogFileInfo(it.name, it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    /**
     * Самый свежий отчёт, который ещё не показывали.
     *
     * @param context Контекст.
     * @return Отчёт или null, если новых падений нет.
     */
    fun unseenReport(context: Context): LogFileInfo? {
        val latest = listReports(context).firstOrNull() ?: return null
        val seen = MmkvManager.decodeSettingsString(AppConfig.PREF_CRASH_SEEN_REPORT)
        return latest.takeIf { it.name != seen }
    }

    /** Помечает отчёт показанным, чтобы плашка не возвращалась при каждом запуске. */
    fun markSeen(name: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_CRASH_SEEN_REPORT, name)
    }

    /**
     * Удаляет все отчёты.
     *
     * @param context Контекст.
     */
    fun clearReports(context: Context) {
        crashDir(context).listFiles()?.forEach { it.delete() }
        MmkvManager.encodeSettings(AppConfig.PREF_CRASH_SEEN_REPORT, "")
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists() && !dir.mkdirs()) return

        val now = System.currentTimeMillis()
        File(dir, "crash_${fileStamp.format(Date(now))}.txt")
            .writeText(buildReport(context, thread, error, now))

        // Вытесняем старые уже после записи: если места нет, свежий отчёт важнее
        listReports(context).drop(MAX_REPORTS).forEach { File(it.path).delete() }
    }

    private fun buildReport(
        context: Context,
        thread: Thread,
        error: Throwable,
        millis: Long
    ): String = buildString {
        appendLine("Время: ${readableStamp.format(Date(millis))}")
        appendLine("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Сборка: ${BuildConfig.DISTRIBUTION}, ${BuildConfig.GIT_COMMIT}")
        appendLine("Процесс: ${processName(context)}")
        appendLine("Поток: ${thread.name}")
        appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        append(stackTrace(error))
    }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    /**
     * Имя процесса. До API 28 системного способа нет, поэтому читаем cmdline -
     * без него отчёты из разных процессов не отличить.
     */
    private fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName()
        }
        return runCatching {
            // Строка заканчивается нулевым байтом, а он тоже отсекается по коду
            File("/proc/self/cmdline").readText().trim { it <= ' ' }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: context.packageName
    }
}
