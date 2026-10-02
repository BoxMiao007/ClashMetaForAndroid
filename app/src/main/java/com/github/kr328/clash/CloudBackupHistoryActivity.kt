package com.github.kr328.clash

import com.github.kr328.clash.design.CloudBackupHistoryDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.remote.CloudBackupInfo
import com.github.kr328.clash.service.remote.SyncOutcome
import com.github.kr328.clash.util.withSync
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * 云端备份包历史(票 #7):浏览/恢复/删除。全部云端操作经 service 进程的
 * ISyncManager 完成,本进程不直连 WebDAV。
 */
class CloudBackupHistoryActivity : BaseActivity<CloudBackupHistoryDesign>() {
    override suspend fun main() {
        val design = CloudBackupHistoryDesign(this)

        setContentDesign(design)

        launch {
            design.setWorking(true)
            refresh(design)
            design.setWorking(false)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    // 恢复/删除进行中忽略新请求,防并发打断
                    if (design.working) return@onReceive

                    when (it) {
                        is CloudBackupHistoryDesign.Request.Restore -> restore(design, it.backup)
                        is CloudBackupHistoryDesign.Request.Delete -> delete(design, it.backup)
                    }
                }
            }
        }
    }

    private suspend fun refresh(design: CloudBackupHistoryDesign) {
        try {
            design.patchBackups(withSync { listCloudBackups() })
        } catch (e: Exception) {
            design.showError(e.message ?: e.toString())
        }
    }

    private suspend fun restore(design: CloudBackupHistoryDesign, backup: CloudBackupInfo) {
        if (!design.requestRestoreConfirm(backup)) return

        design.setWorking(true)

        try {
            val outcome = withSync { restoreBackup(backup.name) }

            design.showToast(restoreResultText(outcome), ToastDuration.Long)

            refresh(design)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        } finally {
            design.setWorking(false)
        }
    }

    private suspend fun delete(design: CloudBackupHistoryDesign, backup: CloudBackupInfo) {
        if (!design.requestDeleteConfirm(backup)) return

        design.setWorking(true)

        try {
            withSync { deleteCloudBackup(backup.name) }

            design.showToast(R.string.cloud_backup_deleted, ToastDuration.Long)

            refresh(design)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        } finally {
            design.setWorking(false)
        }
    }

    private fun restoreResultText(outcome: SyncOutcome): CharSequence = when {
        outcome.phase != SyncOutcome.PHASE_DONE -> getText(R.string.sync_cancelled)

        !outcome.succeeded -> getString(R.string.sync_failed, outcome.message ?: "")

        outcome.imported.isEmpty() -> getText(R.string.cloud_backup_restore_done)

        else -> getString(
            R.string.cloud_backup_restore_done_names,
            outcome.imported.joinToString(", "),
        )
    }
}
