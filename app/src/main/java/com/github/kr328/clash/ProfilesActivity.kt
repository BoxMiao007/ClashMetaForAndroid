package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.dialog.requestSyncConflictChoice
import com.github.kr328.clash.design.dialog.requestSyncDeleteConfirm
import com.github.kr328.clash.design.dialog.showSyncResult
import com.github.kr328.clash.design.dialog.syncOutcomeText
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.SyncChoice
import com.github.kr328.clash.service.remote.SyncOutcome
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.withSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        ProfilesDesign.Request.Sync ->
                            runSync(design)
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    /**
     * 配置页右上角的快捷同步:与「设置→同步」同一套两段式流程,
     * 冲突选择、删除确认与结果弹窗均在本页就地处理。
     */
    private suspend fun runSync(design: ProfilesDesign) {
        try {
            var outcome = withSync { start() }

            loop@ while (outcome.phase != SyncOutcome.PHASE_DONE) {
                when (outcome.phase) {
                    SyncOutcome.PHASE_CONFLICTS -> {
                        val choices = mutableListOf<SyncChoice>()

                        for (conflict in outcome.conflicts) {
                            val useLocal = requestSyncConflictChoice(
                                local = syncConflictSummary(conflict.localName, conflict.localUpdatedAt),
                                cloud = syncConflictSummary(conflict.cloudName, conflict.cloudUpdatedAt),
                                // 用户取消:放弃本轮;引擎保留待续计划,下次同步自动覆盖
                            ) ?: break@loop

                            choices += SyncChoice(
                                keyKind = conflict.keyKind,
                                keyValue = conflict.keyValue,
                                useLocal = useLocal,
                            )
                        }

                        outcome = withSync { resolve(choices) }
                    }
                    SyncOutcome.PHASE_CONFIRM_DELETIONS -> {
                        if (!requestSyncDeleteConfirm(syncDeleteMessage(outcome))) break@loop

                        outcome = withSync { resolve(emptyList()) }
                    }
                }
            }

            showSyncResult(
                message = if (outcome.phase == SyncOutcome.PHASE_DONE)
                    syncOutcomeText(this, outcome)
                else
                    getText(R.string.sync_cancelled),
            )
        } catch (e: Exception) {
            showSyncResult(message = e.message ?: e.toString())
        } finally {
            design.finishSync()
        }
    }

    private fun syncConflictSummary(name: String?, updatedAt: Long?): CharSequence {
        val time = updatedAt?.takeIf { it > 0 }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it * 1000))
        }

        return listOfNotNull(name, time).joinToString(" · ").ifEmpty { "—" }
    }

    private fun syncDeleteMessage(outcome: SyncOutcome): CharSequence {
        val sections = mutableListOf<CharSequence>()

        if (outcome.deletedLocal.isNotEmpty())
            sections += getString(R.string.sync_delete_section_local, outcome.deletedLocal.joinToString(", "))

        if (outcome.deletedCloud.isNotEmpty())
            sections += getString(R.string.sync_delete_section_cloud, outcome.deletedCloud.joinToString(", "))

        return getString(R.string.sync_delete_confirm_message, sections.joinToString("\n"))
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}