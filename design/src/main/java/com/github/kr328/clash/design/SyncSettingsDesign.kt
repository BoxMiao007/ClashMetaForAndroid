package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.common.sync.SyncMode
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.dialog.requestSyncConfirmChanges
import com.github.kr328.clash.design.preference.ClickablePreference
import com.github.kr328.clash.design.preference.NullableTextAdapter
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.SelectableListPreference
import com.github.kr328.clash.design.preference.TipsPreference
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.editableText
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.selectableList
import com.github.kr328.clash.design.preference.switch
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.sync.SyncStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SyncSettingsDesign(
    context: Context,
    private val store: SyncStore,
) : Design<SyncSettingsDesign.Request>(context) {
    enum class Request {
        StartSync, AutoSyncChanged, CustomInterval, OpenCloudHistory, ModeChanged
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var syncAction: ClickablePreference
    private lateinit var resultTips: TipsPreference
    private lateinit var intervalAction: SelectableListPreference<Int>
    private lateinit var modeTips: TipsPreference

    // 「自定义」间隔入口被点中时置位,listener 里据此弹输入框而不是直接重排
    private var pendingCustomInterval = false

    /**
     * 定时间隔的选择值:预设分钟数,或「自定义」入口哨兵(不落库)。
     * 存储里是非预设值(自定义)时也归一到哨兵,保证 selectableList 的索引合法。
     */
    private var intervalChoice: Int
        get() = store.periodicSyncIntervalMinutes.takeIf { it in INTERVAL_PRESETS }
            ?: INTERVAL_CUSTOM
        set(value) {
            pendingCustomInterval = value == INTERVAL_CUSTOM

            if (!pendingCustomInterval)
                store.periodicSyncIntervalMinutes = value
        }

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

            // 同步方向:手动与自动同步都按它执行;选中后只刷新下方说明文字
            selectableList(
                value = store::syncMode,
                values = MODE_VALUES,
                valuesText = MODE_TEXT,
                title = R.string.sync_mode,
            ) {
                listener = OnChangedListener { requests.trySend(Request.ModeChanged) }
            }

            modeTips = tips(text = R.string.sync_mode_tips_bidirectional)

            syncAction = clickable(
                title = R.string.sync_now,
                icon = R.drawable.ic_baseline_sync,
            ) {
                clicked {
                    requests.trySend(Request.StartSync)
                }
            }

            resultTips = tips(text = R.string.sync_result_empty)

            clickable(
                title = R.string.cloud_backup_history,
                icon = R.drawable.ic_baseline_view_list,
            ) {
                clicked {
                    requests.trySend(Request.OpenCloudHistory)
                }
            }

            category(R.string.sync_auto)

            switch(
                value = store::startupSyncEnabled,
                title = R.string.sync_startup_sync,
            ) {
                listener = OnChangedListener { requests.trySend(Request.AutoSyncChanged) }
            }

            switch(
                value = store::periodicSyncEnabled,
                title = R.string.sync_periodic_sync,
            ) {
                listener = OnChangedListener {
                    intervalAction.enabled = store.periodicSyncEnabled

                    requests.trySend(Request.AutoSyncChanged)
                }
            }

            intervalAction = selectableList(
                value = this@SyncSettingsDesign::intervalChoice,
                values = INTERVAL_VALUES,
                valuesText = arrayOf(
                    R.string.sync_interval_30m,
                    R.string.sync_interval_1h,
                    R.string.sync_interval_6h,
                    R.string.sync_interval_12h,
                    R.string.sync_interval_24h,
                    R.string.sync_interval_custom,
                ),
                title = R.string.sync_interval,
            ) {
                listener = OnChangedListener {
                    if (pendingCustomInterval) {
                        pendingCustomInterval = false

                        requests.trySend(Request.CustomInterval)
                    } else {
                        requests.trySend(Request.AutoSyncChanged)
                    }
                }
            }

            launch(Dispatchers.Main) {
                intervalAction.enabled = withContext(Dispatchers.IO) {
                    store.periodicSyncEnabled
                }

                refreshModeTips()
            }
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

    /**
     * 自定义间隔输入弹窗(分钟);返回 true 表示已写入存储,需要重排定时。
     * 输入非法时弹窗回退为当前值,等于未变更。
     */
    suspend fun requestCustomInterval(): Boolean {
        val text = context.requestModelTextInput(
            initial = store.periodicSyncIntervalMinutes.toString(),
            title = context.getString(R.string.sync_interval),
            hint = context.getString(R.string.sync_interval_hint),
            validator = { (it.toIntOrNull() ?: 0) > 0 },
        )

        val minutes = text.toIntOrNull()?.takeIf { it > 0 } ?: run {
            // 取消输入:存储未变,选择值回显到现状,不落在「自定义」入口上
            val index = INTERVAL_VALUES.indexOf(store.periodicSyncIntervalMinutes)

            intervalAction.selected = if (index >= 0) index else INTERVAL_VALUES.lastIndex

            return false
        }

        store.periodicSyncIntervalMinutes = minutes

        // 刷新摘要:写回预设值时显示预设名,自定义值落在「自定义」入口上
        val index = INTERVAL_VALUES.indexOf(minutes)

        intervalAction.selected = if (index >= 0) index else INTERVAL_VALUES.lastIndex

        return true
    }

    /** 删除/覆盖确认弹窗:返回 false(含取消/关闭)表示放弃本轮。 */
    suspend fun requestConfirmChanges(message: CharSequence): Boolean {
        return context.requestSyncConfirmChanges(message)
    }

    /**
     * 按当前方向刷新说明文字。读取跨进程偏好走 IO,避免在主线程做 IPC。
     */
    suspend fun refreshModeTips() {
        applyModeTips(withContext(Dispatchers.IO) { store.syncMode })
    }

    private fun applyModeTips(mode: SyncMode) {
        val description = context.getString(
            when (mode) {
                SyncMode.BIDIRECTIONAL -> R.string.sync_mode_tips_bidirectional
                SyncMode.CLOUD_TO_LOCAL_MIRROR -> R.string.sync_mode_tips_cloud_to_local_mirror
                SyncMode.CLOUD_TO_LOCAL_ADD -> R.string.sync_mode_tips_cloud_to_local_add
                SyncMode.LOCAL_TO_CLOUD_MIRROR -> R.string.sync_mode_tips_local_to_cloud_mirror
                SyncMode.LOCAL_TO_CLOUD_ADD -> R.string.sync_mode_tips_local_to_cloud_add
            }
        )
        val warning = if (mode.deletesCounterpart)
            "\n" + context.getString(R.string.sync_mode_tips_mirror_warning)
        else ""

        modeTips.text = description + warning
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

        // 同步方向:枚举顺序即下拉顺序,文案与 [SyncMode] 一一对应
        val MODE_VALUES: Array<SyncMode> = SyncMode.entries.toTypedArray()
        val MODE_TEXT = arrayOf(
            R.string.sync_mode_bidirectional,
            R.string.sync_mode_cloud_to_local_mirror,
            R.string.sync_mode_cloud_to_local_add,
            R.string.sync_mode_local_to_cloud_mirror,
            R.string.sync_mode_local_to_cloud_add,
        )

        // 定时间隔预设(分钟);0 是「自定义」入口的哨兵值,不落库
        val INTERVAL_PRESETS = arrayOf(30, 60, 360, 720, 1440)
        const val INTERVAL_CUSTOM = 0
        val INTERVAL_VALUES = INTERVAL_PRESETS + INTERVAL_CUSTOM
    }
}
