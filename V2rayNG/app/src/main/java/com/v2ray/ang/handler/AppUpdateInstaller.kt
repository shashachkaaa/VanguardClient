package com.v2ray.ang.handler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.receiver.UpdateInstallReceiver
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Что сейчас происходит с обновлением: из этого рисуется плашка на главном экране. */
sealed class UpdateInstallState {
    data object Idle : UpdateInstallState()
    data class Downloading(val percent: Int) : UpdateInstallState()

    /** Файл скачан, дальше слово за системным установщиком. */
    data object Installing : UpdateInstallState()
    data class Failed(val message: String?) : UpdateInstallState()

    /** Нет разрешения ставить приложения из этого источника. */
    data object NeedsPermission : UpdateInstallState()
}

/**
 * Скачивание и установка новой версии прямо из приложения.
 *
 * Файл уходит в сессию системного установщика, а не в общую папку: наружу он
 * не попадает, и права на чтение чужим приложениям выдавать не нужно.
 */
object AppUpdateInstaller {

    private val _state = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = _state.asStateFlow()

    /** Разрешена ли установка из этого приложения. До Android 8 разрешение общесистемное. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек, где это разрешение выдают. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun reset() {
        _state.value = UpdateInstallState.Idle
    }

    /** Вызывается приёмником статуса: установка закончилась или сорвалась. */
    fun onInstallFinished(error: String?) {
        _state.value = if (error == null) {
            UpdateInstallState.Idle
        } else {
            UpdateInstallState.Failed(error)
        }
    }

    /**
     * Качает файл и отдаёт его системному установщику.
     *
     * @return false, если до установщика дело не дошло - тогда интерфейс предлагает
     * открыть ссылку в браузере, чтобы человек не остался ни с чем.
     */
    suspend fun downloadAndInstall(context: Context, url: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!canInstall(context)) {
                _state.value = UpdateInstallState.NeedsPermission
                return@withContext false
            }

            _state.value = UpdateInstallState.Downloading(0)

            val target = File(context.cacheDir, "ward-update.apk")
            runCatching { target.delete() }

            // Сначала напрямую, потом через локальный прокси: если сеть до GitHub
            // не доходит, соединение обычно как раз и поднято этим приложением
            val downloaded = download(url, target, httpPort = 0) ||
                    download(url, target, httpPort = SettingsManager.getHttpPort())

            if (!downloaded) {
                _state.value = UpdateInstallState.Failed(null)
                return@withContext false
            }

            _state.value = UpdateInstallState.Installing
            val started = runCatching { startInstall(context, target) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to start install session", it) }
                .getOrDefault(false)

            if (!started) {
                _state.value = UpdateInstallState.Failed(null)
            }
            started
        }

    private fun download(url: String, target: File, httpPort: Int): Boolean =
        HttpUtil.downloadToFile(
            request = UrlContentRequest(url = url, timeout = 30000, httpPort = httpPort),
            targetFile = target,
            onProgress = { percent -> _state.value = UpdateInstallState.Downloading(percent) }
        )

    private fun startInstall(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() <= 0L) return false

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite("ward-update", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            // Изменяемый PendingIntent: система дописывает в него статус установки
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val statusIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                flags
            )
            session.commit(statusIntent.intentSender)
        }
        return true
    }
}
