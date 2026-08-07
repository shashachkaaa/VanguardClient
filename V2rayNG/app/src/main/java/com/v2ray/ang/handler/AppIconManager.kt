package com.v2ray.ang.handler

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil

/**
 * Вариант значка приложения.
 *
 * @param alias Класс-псевдоним из манифеста; включённый псевдоним и есть тот значок,
 * который показывает лаунчер.
 */
data class AppIconOption(
    val id: String,
    val alias: String,
    @DrawableRes val previewRes: Int,
    @StringRes val titleRes: Int
)

/**
 * Смена значка приложения.
 *
 * Значок нельзя поменять «на лету»: система знает только те иконки, что объявлены
 * в манифесте. Поэтому в манифесте лежат псевдонимы одной и той же activity, и
 * ровно один из них включён - его иконку и подпись видит лаунчер.
 */
object AppIconManager {

    private const val PACKAGE = "com.v2ray.ang.ui.main.MainActivity"

    val options = listOf(
        AppIconOption(
            id = "default",
            alias = "${PACKAGE}Default",
            previewRes = R.mipmap.ic_launcher,
            titleRes = R.string.app_icon_default
        ),
        AppIconOption(
            id = "indigo",
            alias = "${PACKAGE}Indigo",
            previewRes = R.mipmap.ic_launcher_indigo,
            titleRes = R.string.accent_indigo
        ),
        AppIconOption(
            id = "purple",
            alias = "${PACKAGE}Purple",
            previewRes = R.mipmap.ic_launcher_purple,
            titleRes = R.string.accent_purple
        ),
        AppIconOption(
            id = "teal",
            alias = "${PACKAGE}Teal",
            previewRes = R.mipmap.ic_launcher_teal,
            titleRes = R.string.accent_teal
        ),
        AppIconOption(
            id = "light",
            alias = "${PACKAGE}Light",
            previewRes = R.mipmap.ic_launcher_light,
            titleRes = R.string.app_icon_light
        )
    )

    fun find(id: String?): AppIconOption =
        options.firstOrNull { it.id == id } ?: options.first()

    /** Что выбрано сейчас. Храним у себя: спрашивать систему дороже и не всегда точно. */
    fun current(): AppIconOption =
        find(MmkvManager.decodeSettingsString(AppConfig.PREF_APP_ICON))

    /**
     * Включает нужный псевдоним и гасит остальные.
     *
     * Порядок важен: сначала включаем новый, потом выключаем старые - иначе на
     * мгновение не останется ни одной точки входа, и лаунчер успевает убрать
     * приложение из списка.
     */
    fun apply(context: Context, option: AppIconOption) {
        if (current().id == option.id) return

        val manager = context.packageManager
        runCatching {
            manager.setComponentEnabledSetting(
                ComponentName(context, option.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            options.filter { it.id != option.id }.forEach {
                manager.setComponentEnabledSetting(
                    ComponentName(context, it.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            MmkvManager.encodeSettings(AppConfig.PREF_APP_ICON, option.id)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to switch app icon", it)
        }
    }
}
