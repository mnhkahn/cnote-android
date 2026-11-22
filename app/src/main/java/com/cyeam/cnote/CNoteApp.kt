package com.cyeam.cnote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CNoteApp:Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val preferences by lazy { getSharedPreferences("preferences",MODE_PRIVATE) }
    internal var lastWorkspace: Int
        get() = when (val saved = preferences.getInt("last_workspace", 0)) {
            4, 5 -> saved
            else -> 0
        }
        set(value) { preferences.edit().putInt("last_workspace", value).apply() }
    val analytics by lazy { Analytics(this) }
    val updater by lazy { AppUpdater(this,scope) }
    // Legacy host is retained for migration regression tests; production uses shared.
    val repository by lazy { Repository(this,scope,analytics).also { repo->scope.launch { repo.start() } } }
    val shared by lazy { SharedRuntime(this) }
    override fun onCreate() {
        super.onCreate()
        scope.launch { runCatching { analytics.initialize() } }
    }
}
