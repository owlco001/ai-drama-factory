package com.dramafactory.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * UI占位Activity —— MVP核心层为管线引擎；七阶段Compose界面（F01-F05/F10）留待下一迭代。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "「AI短剧工厂」\n管线引擎已就绪（core-engine）\nUI占位——七阶段导航下一迭代"
            setPadding(48, 96, 48, 0); textSize = 18f
        }
        setContentView(tv)
    }
}
