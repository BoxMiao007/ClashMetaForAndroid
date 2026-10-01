package com.github.kr328.clash.service.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 定时同步排期(照 ProfileReceiver 的 AlarmManager 模式,不引入 WorkManager)。
 *
 * 到点在本进程(:background)直调同步引擎执行一轮,完成后排下一个 alarm;
 * 开关/间隔变更与开机、时间变化时经 [schedule] 立即重排或取消。
 * 自动轮失败静默记日志(语义见 [runAutoRound])。
 */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 网络同步可能超过普通广播的执行窗口,goAsync 拉长进程存活时间
            ACTION_SYNC_SCHEDULE -> {
                val result = goAsync()

                Global.launch {
                    try {
                        run(context.applicationContext)
                    } finally {
                        result.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                Global.launch { schedule(context.applicationContext) }
        }
    }

    companion object {
        private const val ACTION_SYNC_SCHEDULE =
            "com.github.kr328.clash.intent.action.SYNC_SCHEDULE"

        // 自定义间隔下限(分钟),与订阅自动更新一致
        private const val MIN_INTERVAL_MINUTES = 15

        /** 按当前配置重排;开关关闭或未配置服务器地址时仅取消排期。 */
        fun schedule(context: Context) {
            val store = SyncStore(context)
            val alarm = context.getSystemService<AlarmManager>()
            val pending = pendingIntentOf(context)

            alarm?.cancel(pending)

            if (!store.periodicSyncEnabled || store.webdavUrl.isBlank())
                return

            val interval = TimeUnit.MINUTES.toMillis(
                store.periodicSyncIntervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
            )

            // RTC 非唤醒:设备休眠时顺延到唤醒后触发,与订阅自动更新行为一致
            alarm?.set(AlarmManager.RTC, System.currentTimeMillis() + interval, pending)
        }

        private suspend fun run(context: Context) {
            try {
                val store = SyncStore(context)

                if (!store.periodicSyncEnabled || store.webdavUrl.isBlank())
                    return

                SyncEngine.obtain(context).runAutoRound("periodic")
            } catch (e: Exception) {
                Log.w("Periodic sync crashed: $e", e)
            } finally {
                // 无论成败都排下一个周期
                schedule(context)
            }
        }

        private fun pendingIntentOf(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_SYNC_SCHEDULE).setComponent(SyncReceiver::class.componentName),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
            )
    }
}
