package com.easysstun

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class EasyssTunApplication : Application() {

    /** 进程级单例：下载跨 Activity 重建存活，UI 只通过 [UpdateManager.state] 观察。 */
    internal val updateManager: UpdateManager by lazy { UpdateManager.create(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = AppState.update(true)
            override fun onStop(owner: LifecycleOwner) = AppState.update(false)
        })
        // 进程启动即开始采集 logcat，日志页才能回溯本次进程的全部日志。
        LogStore.start(this)
    }
}