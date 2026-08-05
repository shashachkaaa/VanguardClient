package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Приём ссылок извне: из «Поделиться» и по своей схеме.
 *
 * Поддерживается `ward://add/<ссылка>`, где ссылка - либо адрес подписки, либо ключ
 * сервера (vless, vmess, trojan, ss, hysteria2 и прочие). Ссылку можно передать и
 * параметром: `ward://add?url=<ссылка>`. Старые `v2rayng://install-config|install-sub`
 * продолжают работать.
 */
class UrlSchemeActivity : BaseComponentActivity() {

    companion object {
        private const val HOST_ADD = "add"
        private const val HOST_INSTALL_CONFIG = "install-config"
        private const val HOST_INSTALL_SUB = "install-sub"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val handled = handleIntent()
            // Главный экран открываем только после импорта, иначе список
            // успеет отрисоваться до того, как в нём что-то появится
            if (!handled) openMainAndFinish()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            openMainAndFinish()
        }
    }

    /** @return true, если запущен импорт и экран закроется сам по его завершении. */
    private fun handleIntent(): Boolean {
        val uri = intent.data

        if (intent.action == Intent.ACTION_SEND) {
            if ("text/plain" != intent.type) return false
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
            return importAsync(shared, null)
        }

        if (intent.action != Intent.ACTION_VIEW) return false

        return when (uri?.host?.lowercase()) {
            HOST_ADD -> {
                val payload = addPayload(intent.dataString, uri)
                if (payload.isNullOrBlank()) {
                    toastError(R.string.toast_failure)
                    false
                } else {
                    importAsync(payload, uri.fragment)
                }
            }

            HOST_INSTALL_CONFIG, HOST_INSTALL_SUB -> {
                val shareUrl = uri.getQueryParameter("url").orEmpty()
                if (shareUrl.isBlank()) false else importAsync(decodePercent(shareUrl), uri.fragment)
            }

            else -> {
                toastError(R.string.toast_failure)
                false
            }
        }
    }

    /**
     * Достаёт полезную нагрузку из `ward://add/...`.
     *
     * Берём её из сырой строки, а не через [Uri.getPath]: внутри лежит целый адрес со
     * своей схемой, запросом и якорем, и разбор по частям его развалит.
     */
    private fun addPayload(raw: String?, uri: Uri): String? {
        // Значение параметра система раскодирует сама, второй раз этого делать нельзя
        uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { return it }

        if (raw.isNullOrBlank()) return null
        val prefix = "${uri.scheme}://${uri.host}/"
        if (!raw.startsWith(prefix, ignoreCase = true)) return null

        return decodePercent(raw.substring(prefix.length)).takeIf { it.isNotBlank() }
    }

    /**
     * Раскодирование процентов без потери плюсов: в vmess-ссылках base64 содержит «+»,
     * а обычный декодер превратил бы его в пробел и сломал конфиг.
     */
    private fun decodePercent(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to decode url scheme payload", e)
        value
    }

    private fun importAsync(payload: String, fragment: String?): Boolean {
        val url = if (Uri.parse(payload).fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            "$payload#$fragment"
        } else {
            payload
        }
        LogUtil.i(AppConfig.TAG, "Importing from url scheme: $url")

        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = try {
                AngConfigManager.importBatchConfig(url, "", false)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import from url scheme", e)
                0 to 0
            }
            withContext(Dispatchers.Main) {
                if (count + countSub > 0) {
                    toast(R.string.import_subscription_success)
                } else {
                    toast(R.string.import_subscription_failure)
                }
                openMainAndFinish(refresh = count + countSub > 0)
            }
        }
        return true
    }

    private fun openMainAndFinish(refresh: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REFRESH_GROUPS, refresh)
        )
        finish()
    }

    @Composable
    override fun ScreenContent() {
    }
}
