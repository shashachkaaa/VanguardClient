package com.v2ray.ang.ui.base

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.util.MyContextWrapper

abstract class BaseComponentActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(MyContextWrapper.wrap(newBase ?: return, SettingsManager.getLocale()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme(recordBackdrop = recordGlassBackdrop) {
                ScreenContent()
            }
        }
    }

    /**
     * Писать ли экран в слой для стекла. Экранам с внешней поверхностью - камера,
     * видео - это ломает вывод: их картинку рисует не приложение, и в слой она не попадает.
     */
    protected open val recordGlassBackdrop: Boolean = true

    @Composable
    protected abstract fun ScreenContent()
}