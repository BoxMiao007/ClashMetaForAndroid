package com.github.kr328.clash.design.dialog

import android.content.Context
import com.github.kr328.clash.common.sync.SyncMode
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.remote.SyncOutcome
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 同步流程的弹窗助手:冲突选择、变更确认与结果展示。
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

/** 变更确认(删除 + 覆盖):true = 继续执行;false = 放弃本轮。 */
suspend fun Context.requestSyncConfirmChanges(message: CharSequence): Boolean =
    suspendCancellableCoroutine { ctx ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_confirm_title)
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

/**
 * 变更确认弹窗正文:按「将删除」「将被覆盖」分段列出条目。
 * 覆盖段区分两侧:keptLocal = false 表示以云端为准、本机那次改动会丢失;
 * keptLocal = true 表示以本机为准、云端那次改动会丢失。
 */
fun syncChangeMessage(context: Context, outcome: SyncOutcome): CharSequence {
    val sections = mutableListOf<CharSequence>()

    if (outcome.deletedLocal.isNotEmpty())
        sections += context.getString(R.string.sync_delete_section_local, outcome.deletedLocal.joinToString(", "))

    if (outcome.deletedCloud.isNotEmpty())
        sections += context.getString(R.string.sync_delete_section_cloud, outcome.deletedCloud.joinToString(", "))

    val overwrittenLocal = outcome.overwrites.filter { !it.keptLocal }.mapNotNull { it.name }
    val overwrittenCloud = outcome.overwrites.filter { it.keptLocal }.mapNotNull { it.name }

    if (overwrittenLocal.isNotEmpty())
        sections += context.getString(R.string.sync_overwrite_section_local, overwrittenLocal.joinToString(", "))

    if (overwrittenCloud.isNotEmpty())
        sections += context.getString(R.string.sync_overwrite_section_cloud, overwrittenCloud.joinToString(", "))

    return context.getString(R.string.sync_confirm_message, sections.joinToString("\n"))
}

/** 同步方向的展示文案资源。 */
fun syncModeLabelRes(mode: SyncMode): Int = when (mode) {
    SyncMode.BIDIRECTIONAL -> R.string.sync_mode_bidirectional
    SyncMode.CLOUD_TO_LOCAL_MIRROR -> R.string.sync_mode_cloud_to_local_mirror
    SyncMode.CLOUD_TO_LOCAL_ADD -> R.string.sync_mode_cloud_to_local_add
    SyncMode.LOCAL_TO_CLOUD_MIRROR -> R.string.sync_mode_local_to_cloud_mirror
    SyncMode.LOCAL_TO_CLOUD_ADD -> R.string.sync_mode_local_to_cloud_add
}

/**
 * 把一轮同步的结果组织成可读文本(与「设置→同步」页同一套文案)。
 * [modeLabel] 非空时首行标明本次使用的同步方向。
 */
fun syncOutcomeText(
    context: Context,
    outcome: SyncOutcome,
    modeLabel: CharSequence? = null,
): CharSequence {
    val body: CharSequence = when {
        !outcome.succeeded -> context.getString(R.string.sync_failed, outcome.message ?: "")

        outcome.imported.isEmpty() && outcome.pushed.isEmpty() &&
            outcome.deletedLocal.isEmpty() && outcome.deletedCloud.isEmpty() &&
            outcome.overwrites.isEmpty() ->
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
            outcome.overwrites.takeIf { it.isNotEmpty() }
                ?.let {
                    context.getString(
                        R.string.sync_result_overwritten,
                        it.mapNotNull { overwrite -> overwrite.name }.joinToString(", "),
                    )
                },
        ).joinToString("\n")
    }

    return if (modeLabel != null && outcome.succeeded)
        context.getString(R.string.sync_result_mode, modeLabel, body)
    else
        body
}
