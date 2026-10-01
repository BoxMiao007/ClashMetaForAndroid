package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.ClickablePreference
import com.github.kr328.clash.design.preference.NullableTextAdapter
import com.github.kr328.clash.design.preference.TipsPreference
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.editableText
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.sync.SyncStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SyncSettingsDesign(
    context: Context,
    store: SyncStore,
) : Design<SyncSettingsDesign.Request>(context) {
    enum class Request {
        StartSync
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var syncAction: ClickablePreference
    private lateinit var resultTips: TipsPreference

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.webdav)

            editableText(
                value = store::webdavUrl,
                adapter = NonBlankString,
                title = R.string.webdav_url,
                placeholder = R.string.webdav_not_set,
            )

            editableText(
                value = store::webdavUsername,
                adapter = NonBlankString,
                title = R.string.webdav_username,
                placeholder = R.string.webdav_not_set,
            )

            editableText(
                value = store::webdavPassword,
                adapter = NonBlankString,
                title = R.string.webdav_password,
                placeholder = R.string.webdav_not_set,
            )

            category(R.string.sync)

            syncAction = clickable(
                title = R.string.sync_now,
                icon = R.drawable.ic_baseline_sync,
            ) {
                requests.trySend(Request.StartSync)
            }

            resultTips = tips(text = R.string.sync_result_empty)
        }

        binding.content.addView(screen.root)
    }

    /** 同步进行中禁用「立即同步」,防止并发发起。 */
    fun setSyncRunning(running: Boolean) {
        syncAction.enabled = !running
    }

    fun showResult(text: CharSequence) {
        resultTips.text = text
    }

    /**
     * 冲突选择弹窗:返回 true = 用本地,false = 用云端,null = 用户取消(放弃本轮)。
     */
    suspend fun requestConflictChoice(local: CharSequence, cloud: CharSequence): Boolean? {
        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.sync_conflict_title)
                .setMessage(context.getString(R.string.sync_conflict_message, local, cloud))
                .setPositiveButton(R.string.sync_use_local) { _, _ -> ctx.resume(true) }
                .setNegativeButton(R.string.sync_use_cloud) { _, _ -> ctx.resume(false) }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted)
                    ctx.resume(null)
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        }
    }

    /** 删除确认弹窗:返回 false(含取消/关闭)表示放弃本轮。 */
    suspend fun requestDeleteConfirm(message: CharSequence): Boolean {
        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.sync_delete_confirm_title)
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

    private companion object {
        // 空白视为未设置:展示占位文案,写入前去掉首尾空白
        val NonBlankString = object : NullableTextAdapter<String> {
            override fun from(value: String): String? {
                return value.ifBlank { null }
            }

            override fun to(text: String?): String {
                return text?.trim() ?: ""
            }
        }
    }
}
