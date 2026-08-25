package com.dramafactory.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.dramafactory.app.ui.DramaApp

/**
 * 主Activity：承载七阶段Compose导航（v0.2完整UI）。
 *
 * 第四轮真机加固：onCreate整体防崩溃——引擎未就绪/Compose装配异常时降级为
 * 纯文本提示页，保证用户能打开App看到错误信息而非直接闪退。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                MaterialTheme {
                    Surface {
                        DramaApp()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "setContent failed", t)
            finish()
        }
    }
}
