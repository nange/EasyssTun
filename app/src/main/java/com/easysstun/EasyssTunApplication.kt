package com.easysstun

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class EasyssTunApplication : Application() {

    /**
     * Process-scoped updater: the download survives Activity recreation and
     * the UI observes its state through [UpdateManager.state].
     */
    internal val updateManager: UpdateManager by lazy { UpdateManager.create(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = AppState.update(true)
            override fun onStop(owner: LifecycleOwner) = AppState.update(false)
        })
        // Capture logcat for the whole process lifetime so the log viewer can
        // page through all logs since the process started.
        LogStore.start(this)
    }
}