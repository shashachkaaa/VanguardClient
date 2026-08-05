package com.v2ray.ang.handler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений в фоне и уведомление о новой версии.
 *
 * Раз в сутки задача ходит на GitHub; если там версия свежее установленной,
 * приходит уведомление. Об одной и той же версии напоминаем один раз.
 */
object AppUpdateNotifier {

    private const val CHANNEL_ID = "ward_app_updates"
    private const val NOTIFICATION_ID = 9200
    private const val WORK_NAME = "ward_update_check"

    /** Ставит суточную проверку. Повторный вызов существующую не сбивает. */
    fun schedule(context: Context = AngApplication.application) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(2, TimeUnit.HOURS)
            .build()

        // Через RemoteWorkManager, как и обновление подписок: задачи выполняются
        // в отдельном процессе, и обычный WorkManager из интерфейса поднял бы
        // второй экземпляр планировщика
        RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context = AngApplication.application) {
        RemoteWorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Показывает уведомление о версии. По нажатию открывается файл сборки под
     * архитектуру устройства - его подобрал сам механизм проверки.
     */
    fun notifyUpdate(context: Context, version: String, downloadUrl: String?) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_UPDATE_NOTIFIED_VERSION) == version) {
            return
        }
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_NOTIFIED_VERSION, version)

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val target = downloadUrl ?: AppConfig.APP_URL
        val intent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(target)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.update_available_title, version))
            .setContentText(context.getString(R.string.update_available_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to post update notification", it) }
    }

    class UpdateCheckWorker(
        private val context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val update = UpdateCheckerManager.checkQuietly(force = true) ?: return Result.success()
            notifyUpdate(context, update.latestVersion.orEmpty(), update.downloadUrl)
            return Result.success()
        }
    }
}
