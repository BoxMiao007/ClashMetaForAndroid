package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.ProfileManager
import com.github.kr328.clash.service.remote.ISyncManager
import com.github.kr328.clash.service.remote.SyncOutcome

/**
 * 自动触发(启动时/定时)的一轮同步语义(共享笔记 00 定稿):
 * - PHASE_DONE:结束,成功失败都静默,失败仅记日志;
 * - PHASE_CONFIRM_DELETIONS:删除传播是定稿语义、云端历史包可回滚,自动轮不等用户,
 *   以空选择(确认删除)继续执行;
 * - PHASE_CONFLICTS:冲突必须由人选,自动轮作废本轮,留给手动同步。
 */
suspend fun ISyncManager.runAutoRound(trigger: String) {
    var outcome = start()

    while (outcome.phase == SyncOutcome.PHASE_CONFIRM_DELETIONS) {
        outcome = resolve(emptyList())
    }

    when {
        !outcome.succeeded -> Log.w("$trigger sync failed: ${outcome.message}")
        outcome.phase == SyncOutcome.PHASE_CONFLICTS ->
            Log.i("$trigger sync: conflicts detected, left for manual sync")
    }
}

/**
 * service 进程内唯一的同步引擎实例。
 *
 * 手动同步经 RemoteService 的 AIDL 暴露,定时同步由 SyncReceiver 在本进程直调
 * (不走 AIDL);两者共用同一实例,busy 防并发才对所有触发方式生效,
 * 且 RemoteService 销毁后定时轮到点仍可直接执行。
 */
object SyncEngine {
    @Volatile
    private var instance: SyncManager? = null

    @Synchronized
    fun obtain(context: Context): SyncManager {
        instance?.let { return it }

        val app = context.applicationContext

        return SyncManager(app, ProfileManager(app)).also { instance = it }
    }
}
