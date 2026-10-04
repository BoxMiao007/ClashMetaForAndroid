package com.github.kr328.clash.design.dialog

import android.content.Context
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.remote.SyncOutcome
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 同步流程的弹窗助手:冲突选择、删除确认与结果展示。
 * 与「设置→同步」页共用同一组字符串资源,文案不会漂移。
 */

/** 冲突二选一:true = 用本地,false = 用云端;null = 用户取消(放弃本轮)。 */
suspend fun Context.requestSyncConflictChoice(local: CharSequence, cloud: CharSequence): Boolean? =
    suspendCancellableCoroutine { ctx ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_conflict_title)
            .setMessage(getString(R.string.sync_conflict_message, local, cloud))
            .setPositiveButton(R.string.sync_use_local) { _, _ -> ctx.resume(true) }
            .setNegativeButton(R.string.sync_use_cloud) { _, _ -> ctx.resume(false) }
            .show()

        dialog.setOnDismissListener {
            if (!ctx.isCompleted) ctx.resume(null)
        }
    }

/** 删除确认:true = 继续执行删除;false = 放弃本轮。 */
suspend fun Context.requestSyncDeleteConfirm(message: CharSequence): Boolean =
    suspendCancellableCoroutine { ctx ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_delete_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> ctx.resume(false) }
            .show()

        dialog.setOnDismissListener {
            if (!ctx.isCompleted) ctx.resume(false)
        }
    }

/** 展示一轮同步的结果(成功或失败)。 */
fun Context.showSyncResult(message: CharSequence) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.sync_result_title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

/** 把一轮同步的结果组织成可读文本(与「设置→同步」页同一套文案)。 */
fun syncOutcomeText(context: Context, outcome: SyncOutcome): CharSequence = when {
    !outcome.succeeded -> context.getString(R.string.sync_failed, outcome.message ?: "")

    outcome.imported.isEmpty() && outcome.pushed.isEmpty() &&
        outcome.deletedLocal.isEmpty() && outcome.deletedCloud.isEmpty() ->
        context.getText(R.string.sync_done_no_changes)

    else -> listOfNotNull(
        outcome.imported.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.sync_result_imported, it.joinToString(", ")) },
        outcome.pushed.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.sync_result_pushed, it.joinToString(", ")) },
        outcome.deletedLocal.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.sync_result_deleted_local, it.joinToString(", ")) },
        outcome.deletedCloud.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.sync_result_deleted_cloud, it.joinToString(", ")) },
    ).joinToString("\n")
}
