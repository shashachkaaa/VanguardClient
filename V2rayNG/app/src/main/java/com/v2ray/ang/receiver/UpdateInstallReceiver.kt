package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AppUpdateInstaller
import com.v2ray.ang.util.LogUtil

/**
 * Ответ системного установщика на нашу сессию обновления.
 *
 * Сначала он просит показать пользователю окно подтверждения, и только потом
 * приходит окончательный результат.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java
                )
                if (confirm == null) {
                    AppUpdateInstaller.onInstallFinished("no confirmation intent")
                    return
                }
                // Из приёмника активность запускается только новой задачей
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    LogUtil.e(AppConfig.TAG, "Failed to show install confirmation", it)
                    AppUpdateInstaller.onInstallFinished(it.message)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> AppUpdateInstaller.onInstallFinished(null)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                LogUtil.i(AppConfig.TAG, "Update install finished with status $status: $message")
                AppUpdateInstaller.onInstallFinished(message ?: "status $status")
            }
        }
    }
}
