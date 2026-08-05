package com.v2ray.ang.util

import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig

/**
 * Сведения об устройстве в одном месте.
 *
 * Главное здесь - HWID: он уходит панели в заголовке x-hwid и показывается в настройках.
 * Считать его в двух местах нельзя, иначе на экране будет одно, а на сервере другое.
 */
object DeviceInfo {

    const val UNKNOWN_HWID = "unknown_hwid"

    /** Идентификатор устройства для подписок (заголовок x-hwid). */
    fun hwid(): String = try {
        Settings.Secure.getString(
            AngApplication.application.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UNKNOWN_HWID
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get HWID", e)
        UNKNOWN_HWID
    }

    /** Версия Android, например «14». */
    val osVersion: String get() = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    /** Уровень API. */
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    /** Производитель и модель одной строкой. */
    val model: String
        get() {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return when {
                model.isBlank() -> manufacturer.ifBlank { "Android" }
                model.startsWith(manufacturer, ignoreCase = true) -> model
                manufacturer.isBlank() -> model
                else -> "$manufacturer $model"
            }
        }

    /** Основная архитектура сборки, под которую работает приложение. */
    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
}
