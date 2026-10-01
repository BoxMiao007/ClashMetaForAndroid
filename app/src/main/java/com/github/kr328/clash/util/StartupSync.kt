package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.sync.SyncStore
import com.github.kr328.clash.service.sync.runAutoRound

/**
 * 启动时同步:app 进程启动(MainApplication.onCreate)时 fire-and-forget 触发,
 * 每进程生命周期至多一轮。仅当开关开启且已配置服务器地址才执行;
 * 经 AIDL 调 service 进程引擎(服务未连接时在此挂起等待,不阻塞启动),
 * 全程无 UI,失败仅记日志(语义见 runAutoRound)。
 */
suspend fun startupSync(context: Context) {
    try {
        val store = SyncStore(context)

        if (!store.startupSyncEnabled || store.webdavUrl.isBlank())
            return

        withSync { runAutoRound("startup") }
    } catch (e: Exception) {
        Log.w("Startup sync failed: $e", e)
    }
}
