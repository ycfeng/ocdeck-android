package io.github.ycfeng.ocdeck

import android.app.Application
import io.github.ycfeng.ocdeck.app.AppContainer

class OpenCodeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
