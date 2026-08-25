// MainActivity占位：MVP骨架不含完整UI，仅保证工程可编译入口
package com.agentteam.app

import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI层（会话屏/DAG图/团队面板/日志流）按PRD F05-F08在后续迭代实现
    }
}
