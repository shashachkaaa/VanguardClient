package com.v2ray.ang.handler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Предупреждения по подписке: трафик на исходе или подходит срок.
 *
 * Данные приходят с обновлением подписки, поэтому и проверка живёт здесь же -
 * отдельно ничего опрашивать не нужно.
 */
object SubscriptionAlerts {

    private const val CHANNEL_ID = "ward_subscription_alerts"
    private const val NOTIFICATION_ID_BASE = 9100

    /** Доля израсходованного трафика, после которой пора предупреждать. */
    private const val TRAFFIC_THRESHOLD = 0.9

    /** За сколько дней до конца срока предупреждать. */
    private const val EXPIRE_SOON_DAYS = 3L

    /**
     * Что уже показывали по этой подписке. Без этой отметки предупреждение
     * повторялось бы после каждого обновления, то есть по несколько раз в день.
     */
    private fun stateKey(guid: String) = "sub_alert_$guid"

    fun check(guid: String, subscription: SubscriptionItem) {
        try {
            val alert = buildAlert(subscription) ?: run {
                // Лимит обновили или продлили срок - снимаем отметку,
                // чтобы в следующий раз предупреждение снова сработало
                MmkvManager.encodeSettings(stateKey(guid), "")
                return
            }

            if (MmkvManager.decodeSettingsString(stateKey(guid)) == alert.tag) return
            MmkvManager.encodeSettings(stateKey(guid), alert.tag)

            notify(guid, subscription, alert.text)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check subscription alerts", e)
        }
    }

    private class Alert(val tag: String, val text: String)

    private fun buildAlert(subscription: SubscriptionItem): Alert? {
        val context = AngApplication.application

        val expireMillis = subscription.trafficExpire.let {
            when {
                it <= 0L -> 0L
                // Сервер отдаёт срок в секундах, но встречается и в миллисекундах
                it > 9999999999L -> it
                else -> it * 1000
            }
        }
        if (expireMillis > 0L) {
            val left = expireMillis - System.currentTimeMillis()
            val daysLeft = TimeUnit.MILLISECONDS.toDays(left)
            if (left <= 0L) {
                return Alert("expired", context.getString(R.string.sub_alert_expired))
            }
            if (daysLeft <= EXPIRE_SOON_DAYS) {
                return Alert(
                    "expire_$daysLeft",
                    context.getString(R.string.sub_alert_expires_soon, daysLeft + 1)
                )
            }
        }

        val total = subscription.trafficTotal
        if (total > 0L) {
            val used = subscription.trafficUpload + subscription.trafficDownload
            val fraction = used.toDouble() / total
            if (fraction >= 1.0) {
                return Alert("traffic_over", context.getString(R.string.sub_alert_traffic_over))
            }
            if (fraction >= TRAFFIC_THRESHOLD) {
                val percent = ((1 - fraction) * 100).toInt().coerceAtLeast(1)
                return Alert(
                    "traffic_$percent",
                    context.getString(R.string.sub_alert_traffic_low, percent)
                )
            }
        }

        return null
    }

    private fun notify(guid: String, subscription: SubscriptionItem, text: String) {
        val context: Context = AngApplication.application
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sub_alert_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(subscription.remarks.ifBlank {
                context.getString(R.string.app_name)
            })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // У каждой подписки своё уведомление: иначе они затирали бы друг друга
        runCatching {
            manager.notify(NOTIFICATION_ID_BASE + guid.hashCode().and(0xFF), notification)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to post subscription alert", it)
        }
    }
}
