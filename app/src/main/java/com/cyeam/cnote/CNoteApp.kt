package com.cyeam.cnote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CNoteApp:Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val analytics by lazy { Analytics(this) }
    val updater by lazy { AppUpdater(this,scope) }
    val repository by lazy { Repository(this,scope,analytics) }
    override fun onCreate() {
        super.onCreate()
        scope.launch { runCatching { analytics.initialize() } };scope.launch { repository.start() }
    }
}
