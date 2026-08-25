package com.dramafactory.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.dramafactory.app.ui.DramaApp

/**
 * 主Activity：承载七阶段Compose导航（v0.2完整UI）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    DramaApp()
                }
            }
        }
    }
}
