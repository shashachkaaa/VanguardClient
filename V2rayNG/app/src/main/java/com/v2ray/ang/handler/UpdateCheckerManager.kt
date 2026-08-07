package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    /** Как часто ходить на GitHub при запуске приложения. */
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    /**
     * Тихая проверка для фона и запуска приложения: настройку пре-релизов читает
     * сама, ошибки не показывает - сеть может быть недоступна, и это не повод
     * тревожить человека.
     *
     * @param force Проверить, не глядя на время прошлой проверки.
     * @return Результат, если обновление есть; иначе null.
     */
    suspend fun checkQuietly(force: Boolean = false): CheckUpdateResult? {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_CHECK_UPDATE, true)) {
            return null
        }

        // По времени ходить рано - но если в прошлый раз обновление нашлось,
        // отдаём его из памяти. Иначе уведомление о версии приходило, а плашка
        // в приложении появлялась только через несколько часов
        val lastCheck = MmkvManager.decodeSettingsLong(AppConfig.PREF_UPDATE_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            return pendingUpdate()
        }

        return try {
            val preRelease =
                MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
            val result = checkForUpdate(preRelease)
            MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_LAST_CHECK, System.currentTimeMillis())
            rememberPending(result)
            result.takeIf { it.hasUpdate }
        } catch (e: Exception) {
            LogUtil.i(AppConfig.TAG, "Background update check failed: ${e.message}")
            // Сеть могла не ответить, а найденное раньше обновление никуда не делось
            pendingUpdate()
        }
    }

    /** Запоминает найденную версию, чтобы показать её без похода в сеть. */
    private fun rememberPending(result: CheckUpdateResult) {
        val value = if (result.hasUpdate) {
            "${result.latestVersion.orEmpty()}|${result.downloadUrl.orEmpty()}"
        } else {
            ""
        }
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_PENDING, value)
    }

    /**
     * Обновление, найденное прошлой проверкой. Версию сверяем с установленной:
     * после обновления запись остаётся, а показывать её уже нечего.
     */
    private fun pendingUpdate(): CheckUpdateResult? {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_UPDATE_PENDING).orEmpty()
        val version = stored.substringBefore('|').takeIf { it.isNotBlank() } ?: return null
        if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return null

        return CheckUpdateResult(
            hasUpdate = true,
            latestVersion = version,
            downloadUrl = stored.substringAfter('|').takeIf { it.isNotBlank() }
        )
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.toVersionParts()
        val v2 = version2.toVersionParts()

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrElse(i) { 0 }
            val num2 = v2.getOrElse(i) { 0 }
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    /**
     * «0.9.1-beta2» -> [0, 9, 1]. Суффиксы в сравнении не участвуют, но и ронять
     * его не должны: раньше на таком теге проверка обновлений падала с разбором числа.
     */
    private fun String.toVersionParts(): List<Int> =
        substringBefore('-').substringBefore('+').split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val assetsByAbi = release.assets.filter {
            (it.name.contains(abi, true))
        }

        val asset = if (BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)) {
            assetsByAbi.firstOrNull { it.name.contains(fDroid) }
        } else {
            assetsByAbi.firstOrNull { !it.name.contains(fDroid) }
        }

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
