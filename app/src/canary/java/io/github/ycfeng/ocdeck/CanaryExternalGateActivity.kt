package io.github.ycfeng.ocdeck

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class CanaryExternalGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
