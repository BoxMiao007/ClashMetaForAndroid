package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.CloudBackupAdapter
import com.github.kr328.clash.design.databinding.DesignCloudBackupHistoryBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.remote.CloudBackupInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class CloudBackupHistoryDesign(context: Context) :
    Design<CloudBackupHistoryDesign.Request>(context) {
    sealed class Request {
        data class Restore(val backup: CloudBackupInfo) : Request()
        data class Delete(val backup: CloudBackupInfo) : Request()
    }

    private val binding = DesignCloudBackupHistoryBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = CloudBackupAdapter(
        context,
        onRestoreClicked = { requests.trySend(Request.Restore(it)) },
        onDeleteClicked = { requests.trySend(Request.Delete(it)) },
    )

    /** 恢复/删除进行中:供 Activity 拦截重复请求,同时驱动加载指示。 */
    var working: Boolean = false
        private set

    override val root: View
        get() = binding.root

    suspend fun patchBackups(backups: List<CloudBackupInfo>) {
        adapter.apply {
            patchDataSet(this::backups, backups, id = { it.name })
        }

        withContext(Dispatchers.Main) {
            binding.emptyView.text = context.getText(R.string.cloud_backup_empty)
            binding.emptyView.visibility =
                if (backups.isEmpty() && !working) View.VISIBLE else View.GONE
        }
    }

    /** 列表加载失败时把原因显示在空态位置。 */
    suspend fun showError(message: CharSequence) {
        withContext(Dispatchers.Main) {
            binding.emptyView.text = message
            binding.emptyView.visibility = View.VISIBLE
        }
    }

    fun setWorking(value: Boolean) {
        working = value

        launch(Dispatchers.Main) {
            binding.loadingView.visibility = if (value) View.VISIBLE else View.GONE
        }
    }

    /** 恢复确认弹窗:明确警告将整体替换本机全部订阅;返回 false(含取消)表示放弃。 */
    suspend fun requestRestoreConfirm(backup: CloudBackupInfo): Boolean {
        return requestConfirm(
            title = R.string.cloud_backup_restore_confirm_title,
            message = context.getString(
                R.string.cloud_backup_restore_confirm_message,
                backup.name,
            ),
        )
    }

    /** 删除确认弹窗;返回 false(含取消)表示放弃。 */
    suspend fun requestDeleteConfirm(backup: CloudBackupInfo): Boolean {
        return requestConfirm(
            title = R.string.cloud_backup_delete_confirm_title,
            message = context.getString(
                R.string.cloud_backup_delete_confirm_message,
                backup.name,
            ),
        )
    }

    private suspend fun requestConfirm(title: Int, message: CharSequence): Boolean {
        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted)
                    ctx.resume(false)
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }
}
