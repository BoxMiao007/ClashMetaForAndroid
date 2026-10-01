package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.SyncSettingsDesign
import com.github.kr328.clash.service.remote.SyncChoice
import com.github.kr328.clash.service.remote.SyncConflict
import com.github.kr328.clash.service.remote.SyncOutcome
import com.github.kr328.clash.service.sync.SyncReceiver
import com.github.kr328.clash.service.sync.SyncStore
import com.github.kr328.clash.util.withSync
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncSettingsActivity : BaseActivity<SyncSettingsDesign>() {
    override suspend fun main() {
        val design = SyncSettingsDesign(this, SyncStore(this))

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SyncSettingsDesign.Request.StartSync -> runSync(design)

                        // 开关/间隔变更后立即重排或取消定时
                        SyncSettingsDesign.Request.AutoSyncChanged ->
                            SyncReceiver.schedule(this@SyncSettingsActivity)

                        SyncSettingsDesign.Request.CustomInterval ->
                            if (design.requestCustomInterval())
                                SyncReceiver.schedule(this@SyncSettingsActivity)
                    }
                }
            }
        }
    }

    private suspend fun runSync(design: SyncSettingsDesign) {
        design.setSyncRunning(true)
        design.showResult(getText(R.string.sync_running))

        try {
            var outcome = withSync { start() }

            loop@ while (outcome.phase != SyncOutcome.PHASE_DONE) {
                when (outcome.phase) {
                    SyncOutcome.PHASE_CONFLICTS -> {
                        val choices = mutableListOf<SyncChoice>()

                        for (conflict in outcome.conflicts) {
                            val useLocal = design.requestConflictChoice(
                                local = conflictSummary(conflict.localName, conflict.localUpdatedAt),
                                cloud = conflictSummary(conflict.cloudName, conflict.cloudUpdatedAt),
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
                        if (!design.requestDeleteConfirm(deleteMessage(outcome))) break@loop

                        outcome = withSync { resolve(emptyList()) }
                    }
                }
            }

            design.showResult(resultText(outcome))
        } catch (e: Exception) {
            design.showResult(e.message ?: e.toString())
        } finally {
            design.setSyncRunning(false)
        }
    }

    private fun resultText(outcome: SyncOutcome): CharSequence = when {
        outcome.phase != SyncOutcome.PHASE_DONE -> getText(R.string.sync_cancelled)

        !outcome.succeeded -> getString(R.string.sync_failed, outcome.message ?: "")

        outcome.imported.isEmpty() && outcome.pushed.isEmpty() &&
            outcome.deletedLocal.isEmpty() && outcome.deletedCloud.isEmpty() ->
            getText(R.string.sync_done_no_changes)

        else -> listOfNotNull(
            outcome.imported.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.sync_result_imported, it.joinToString(", ")) },
            outcome.pushed.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.sync_result_pushed, it.joinToString(", ")) },
            outcome.deletedLocal.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.sync_result_deleted_local, it.joinToString(", ")) },
            outcome.deletedCloud.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.sync_result_deleted_cloud, it.joinToString(", ")) },
        ).joinToString("\n")
    }

    private fun conflictSummary(name: String?, updatedAt: Long?): CharSequence {
        val time = updatedAt?.takeIf { it > 0 }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it * 1000))
        }

        return listOfNotNull(name, time).joinToString(" · ").ifEmpty { "—" }
    }

    private fun deleteMessage(outcome: SyncOutcome): CharSequence {
        val sections = mutableListOf<CharSequence>()

        if (outcome.deletedLocal.isNotEmpty())
            sections += getString(R.string.sync_delete_section_local, outcome.deletedLocal.joinToString(", "))

        if (outcome.deletedCloud.isNotEmpty())
            sections += getString(R.string.sync_delete_section_cloud, outcome.deletedCloud.joinToString(", "))

        return getString(R.string.sync_delete_confirm_message, sections.joinToString("\n"))
    }
}
