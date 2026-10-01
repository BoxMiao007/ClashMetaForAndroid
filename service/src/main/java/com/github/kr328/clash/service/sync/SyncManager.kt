package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.sync.Conflict
import com.github.kr328.clash.common.sync.ConflictChoice
import com.github.kr328.clash.common.sync.LocalProfile
import com.github.kr328.clash.common.sync.LocalTraffic
import com.github.kr328.clash.common.sync.LocalType
import com.github.kr328.clash.common.sync.PlanAction
import com.github.kr328.clash.common.sync.SyncKey
import com.github.kr328.clash.common.sync.SyncPlanner
import com.github.kr328.clash.common.sync.SyncPlan
import com.github.kr328.clash.common.sync.VergeBackup
import com.github.kr328.clash.common.sync.VergeBackupCodec
import com.github.kr328.clash.service.ProfileManager
import com.github.kr328.clash.service.ProfileReceiver
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.ISyncManager
import com.github.kr328.clash.service.remote.SYNC_KEY_KIND_NAME
import com.github.kr328.clash.service.remote.SYNC_KEY_KIND_URL
import com.github.kr328.clash.service.remote.SyncChoice
import com.github.kr328.clash.service.remote.SyncConflict
import com.github.kr328.clash.service.remote.SyncOutcome
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.service.webdav.CloudBackup
import com.github.kr328.clash.service.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手动同步引擎(remote/ISyncManager 的 service 进程实现)。
 *
 * 流程:读本机 imported 订阅与云端最新备份包 → SyncPlanner 生成计划 →
 * 无冲突无删除直接执行;有冲突(PHASE_CONFLICTS)或含删除(PHASE_CONFIRM_DELETIONS)
 * 时挂起待续计划,经 resolve 按用户选择继续。所有动作完成后才持久化新快照,
 * 失败/取消保留旧快照(共享笔记 00 定稿语义)。
 *
 * providers 往返:CMFA 的 imported/<uuid>/providers/ 在 verge 备份包里没有对应结构,
 * 推送时以 "cmfa-providers/<云端内容文件名>/<provider 文件名>" 注入 extraFiles
 * (编解码层未知文件保真,verge 恢复时原样落盘、不受影响);导入时按同一约定读回。
 *
 * 并发:同一时刻只允许一轮执行(busy);停在等用户输入时不算执行中,
 * 再次 start 会覆盖旧的待续计划(逃生门)。
 */
class SyncManager(
    private val context: Context,
    private val profileManager: ProfileManager,
) : ISyncManager {
    private val busy = AtomicBoolean(false)
    private val stateMutex = Mutex()
    private var pending: PendingRound? = null

    private class PendingRound(
        val plan: SyncPlan,
        // 本机键 → 订阅 uuid:执行 DeleteLocal 与收集 providers 用
        val localUuids: Map<SyncKey, UUID>,
    )

    override suspend fun start(): SyncOutcome {
        if (!busy.compareAndSet(false, true)) return busyOutcome()

        try {
            val store = SyncStore(context)
            if (store.webdavUrl.isBlank()) return failed("未配置 WebDAV 服务器地址")

            val client = newClient(store)
            val cloud = fetchCloudState(client)
            val (locals, localUuids) = readLocals()
            val snapshot = SnapshotStore(context).load()

            val plan = SyncPlanner.plan(locals, cloud, snapshot)

            return when {
                plan.conflicts.isNotEmpty() -> {
                    stateMutex.withLock { pending = PendingRound(plan, localUuids) }

                    outcome(phase = SyncOutcome.PHASE_CONFLICTS, plan = plan, conflicts = plan.conflicts)
                }
                plan.containsDeletions() -> {
                    stateMutex.withLock { pending = PendingRound(plan, localUuids) }

                    outcome(phase = SyncOutcome.PHASE_CONFIRM_DELETIONS, plan = plan)
                }
                else -> execute(plan, localUuids, client)
            }
        } catch (e: Exception) {
            Log.w("Sync start failed: $e", e)

            return failed(e)
        } finally {
            busy.set(false)
        }
    }

    override suspend fun resolve(choices: List<SyncChoice>): SyncOutcome {
        if (!busy.compareAndSet(false, true)) return busyOutcome()

        try {
            val round = stateMutex.withLock {
                pending.also { pending = null }
            } ?: return failed("没有待继续的同步")

            val plan = round.plan.resolve(
                choices.associate {
                    SyncKey(
                        if (it.keyKind == SYNC_KEY_KIND_URL) SyncKey.Kind.URL else SyncKey.Kind.NAME,
                        it.keyValue,
                    ) to if (it.useLocal) ConflictChoice.USE_LOCAL else ConflictChoice.USE_CLOUD
                }
            )

            return execute(plan, round.localUuids, newClient(SyncStore(context)))
        } catch (e: Exception) {
            Log.w("Sync resolve failed: $e", e)

            return failed(e)
        } finally {
            busy.set(false)
        }
    }

    // ---- 执行 ----

    private suspend fun execute(
        plan: SyncPlan,
        localUuids: Map<SyncKey, UUID>,
        client: WebDavClient,
    ): SyncOutcome {
        val imported = mutableListOf<String>()
        val deletedLocal = mutableListOf<String>()

        for (action in plan.actions) {
            when (action) {
                is PlanAction.Import -> {
                    importOffline(plan, action)

                    imported += displayName(action)
                }
                is PlanAction.DeleteLocal -> {
                    // 走既有删除语义(取消排期 + 删表删目录 + 广播),不自建删除逻辑
                    localUuids[action.key]?.let { profileManager.delete(it) }

                    deletedLocal += displayName(action)
                }
                else -> Unit
            }
        }

        val pushes = plan.actions.filterIsInstance<PlanAction.Push>()
        val deletedCloudActions = plan.actions.filterIsInstance<PlanAction.DeleteCloud>()

        if (pushes.isNotEmpty() || deletedCloudActions.isNotEmpty()) {
            uploadCloudState(plan, pushes, localUuids, client)
        }

        // 整轮成功才写快照;之前任何一步抛异常都会跳过这里
        SnapshotStore(context).store(plan.newSnapshot)

        return SyncOutcome(
            phase = SyncOutcome.PHASE_DONE,
            succeeded = true,
            imported = imported,
            pushed = pushes.map(::displayName),
            deletedLocal = deletedLocal,
            deletedCloud = deletedCloudActions.map(::displayName),
        )
    }

    /**
     * 离线导入:云端包内容直接落盘(imported/<新uuid>/config.yaml + providers),
     * 写 imported 表行(不联网下载、不走 pending 流程),排期自动更新并广播。
     */
    private suspend fun importOffline(plan: SyncPlan, action: PlanAction.Import) {
        val uuid = generateProfileUUID()
        val dir = context.importedDir.resolve(uuid.toString())

        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
            dir.resolve("config.yaml").writeBytes(action.content ?: ByteArray(0))

            val providersDir = dir.resolve("providers")
            providersDir.mkdirs()

            for ((name, data) in providersOf(plan, action)) {
                require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) {
                    "非法的 provider 文件名: $name"
                }

                providersDir.resolve(name).writeBytes(data)
            }
        }

        val item = action.cloudItem
        val row = Imported(
            uuid = uuid,
            name = action.name ?: action.key.value,
            type = if (action.key.kind == SyncKey.Kind.URL) Profile.Type.Url else Profile.Type.File,
            source = action.url ?: "",
            interval = item.option?.updateInterval?.takeIf { it > 0 }
                ?.let { TimeUnit.MINUTES.toMillis(it) } ?: 0,
            upload = item.extra?.upload ?: 0,
            download = item.extra?.download ?: 0,
            total = item.extra?.total ?: 0,
            expire = item.extra?.expire ?: 0,
            createdAt = System.currentTimeMillis(),
        )

        ImportedDao().insert(row)

        ProfileReceiver.scheduleNext(context, row)
        context.sendProfileChanged(uuid)
    }

    /**
     * 推送:providers 注入后编码上传 android-backup 包,成功后按文件名时间戳
     * 清理自产旧包(仅留最近 10 个,verge 的包不动)。
     */
    private suspend fun uploadCloudState(
        plan: SyncPlan,
        pushes: List<PlanAction.Push>,
        localUuids: Map<SyncKey, UUID>,
        client: WebDavClient,
    ) {
        val extraFiles = LinkedHashMap(plan.newCloudState.extraFiles)

        for (push in pushes) {
            val cloudFile = findCloudFile(plan.newCloudState, push.key) ?: continue
            val prefix = PROVIDERS_PREFIX + cloudFile + "/"
            val uuid = localUuids[push.key] ?: continue
            val providersDir = context.importedDir.resolve(uuid.toString()).resolve("providers")

            val files = withContext(Dispatchers.IO) {
                // 先清旧再注入,订阅 providers 变更后云端不留陈旧文件
                extraFiles.keys.removeAll { it.startsWith(prefix) }

                providersDir.listFiles()?.filter { it.isFile }
            } ?: continue

            for (file in files) {
                extraFiles[prefix + file.name] = withContext(Dispatchers.IO) { file.readBytes() }
            }
        }

        // 删除传播后条目已不在 newCloudState.items,清掉其遗留 providers,防云端垃圾累积
        val liveFiles = plan.newCloudState.items.mapNotNullTo(HashSet()) { it.file }
        extraFiles.keys.removeAll { key ->
            key.startsWith(PROVIDERS_PREFIX) &&
                key.removePrefix(PROVIDERS_PREFIX).substringBefore('/') !in liveFiles
        }

        client.ensureDirectory()
        client.uploadBackup(
            VergeBackupCodec.backupFileName(System.currentTimeMillis()),
            VergeBackupCodec.encode(plan.newCloudState.copy(extraFiles = extraFiles)),
        )

        for (victim in SyncPlanner.selectPackagesToDelete(client.listBackups().map(CloudBackup::name))) {
            client.deleteBackup(victim)
        }
    }

    // ---- 本机集合与云端状态 ----

    /**
     * 本机同步集合:仅 imported 表(pending 草稿不参与),External 类型跳过。
     * 指纹取 config.yaml 字节,updatedAt 取目录最大修改时间(与订阅列表展示一致)。
     */
    private suspend fun readLocals(): Pair<List<LocalProfile>, Map<SyncKey, UUID>> {
        val locals = mutableListOf<LocalProfile>()
        val uuids = LinkedHashMap<SyncKey, UUID>()

        for (uuid in ImportedDao().queryAllUUIDs()) {
            val imported = ImportedDao().queryByUUID(uuid) ?: continue
            if (imported.type == Profile.Type.External) continue

            val dir = context.importedDir.resolve(uuid.toString())
            val content = withContext(Dispatchers.IO) {
                dir.resolve("config.yaml").takeIf { it.isFile }?.readBytes()
            } ?: continue

            val type = when (imported.type) {
                Profile.Type.Url -> LocalType.URL
                Profile.Type.File -> LocalType.FILE
                Profile.Type.External -> continue
            }
            val url = imported.source.takeIf { type == LocalType.URL && it.isNotBlank() }
            // 键的推导与 SyncPlanner.indexLocals 一致;URL 型缺地址则不参与同步
            val key = when (type) {
                LocalType.URL -> url?.let { SyncKey(SyncKey.Kind.URL, it) } ?: continue
                LocalType.FILE -> SyncKey(SyncKey.Kind.NAME, imported.name)
            }

            locals += LocalProfile(
                name = imported.name,
                type = type,
                url = url,
                contentFingerprint = SyncPlanner.fingerprint(content),
                updatedAtSeconds = (dir.directoryLastModified ?: 0L) / 1000,
                updateIntervalMinutes = imported.interval
                    .takeIf { it > 0 }?.let { TimeUnit.MILLISECONDS.toMinutes(it) },
                traffic = LocalTraffic(
                    upload = imported.upload.takeIf { it > 0 },
                    download = imported.download.takeIf { it > 0 },
                    total = imported.total.takeIf { it > 0 },
                    expire = imported.expire.takeIf { it > 0 },
                ),
                content = content,
            )
            uuids[key] = uuid
        }

        return locals to uuids
    }

    /** 云端状态 = 同步目录最新备份包;目录为空(首次使用)视为空云端。 */
    private suspend fun fetchCloudState(client: WebDavClient): VergeBackup {
        client.ensureDirectory()

        val latest = client.listBackups().firstOrNull()
            ?: return VergeBackup(null, emptyList(), emptyMap(), emptyMap(), emptyMap())

        return VergeBackupCodec.decode(client.fetchBackup(latest.name))
    }

    private fun newClient(store: SyncStore): WebDavClient =
        WebDavClient(store.webdavUrl, store.webdavUsername, store.webdavPassword)

    // ---- providers 与云端条目的对应关系 ----

    /** 导入的 providers:云端 extraFiles 中该条目内容文件名对应的前缀下全部文件。 */
    private fun providersOf(plan: SyncPlan, action: PlanAction.Import): Map<String, ByteArray> {
        val file = action.cloudItem.file ?: return emptyMap()
        val prefix = PROVIDERS_PREFIX + file + "/"

        return plan.newCloudState.extraFiles
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    /** 推送目标条目在云端包内的内容文件名(与 SyncPlanner 的键索引规则一致)。 */
    private fun findCloudFile(backup: VergeBackup, key: SyncKey): String? =
        backup.items.firstOrNull { item ->
            when (key.kind) {
                SyncKey.Kind.URL -> item.type == "remote" && item.url == key.value
                SyncKey.Kind.NAME -> item.type == "local" && item.name == key.value
            }
        }?.file

    // ---- 答复构造 ----

    private fun outcome(
        phase: Int,
        plan: SyncPlan,
        conflicts: List<Conflict> = emptyList(),
    ): SyncOutcome = SyncOutcome(
        phase = phase,
        // 待续阶段名称列表为将要发生的内容,PHASE_DONE 时为实际结果
        imported = plan.actions.filterIsInstance<PlanAction.Import>().map(::displayName),
        pushed = plan.actions.filterIsInstance<PlanAction.Push>().map(::displayName),
        deletedLocal = plan.actions.filterIsInstance<PlanAction.DeleteLocal>().map(::displayName),
        deletedCloud = plan.actions.filterIsInstance<PlanAction.DeleteCloud>().map(::displayName),
        conflicts = conflicts.map {
            SyncConflict(
                keyKind = if (it.key.kind == SyncKey.Kind.URL) SYNC_KEY_KIND_URL else SYNC_KEY_KIND_NAME,
                keyValue = it.key.value,
                localName = it.localName,
                cloudName = it.cloudName,
                localUpdatedAt = it.localUpdatedAtSeconds,
                cloudUpdatedAt = it.cloudUpdatedAtSeconds,
            )
        },
    )

    private fun displayName(action: PlanAction): String = action.name ?: action.key.value

    private fun busyOutcome(): SyncOutcome =
        failed("已有一轮同步正在进行")

    private fun failed(e: Exception): SyncOutcome = failed(e.message ?: e.toString())

    private fun failed(message: String): SyncOutcome = SyncOutcome(
        phase = SyncOutcome.PHASE_DONE,
        succeeded = false,
        message = message,
    )

    private fun SyncPlan.containsDeletions(): Boolean =
        actions.any { it is PlanAction.DeleteLocal || it is PlanAction.DeleteCloud }

    companion object {
        /**
         * providers 在备份包内的根前缀:包内 profiles/ 只允许单级文件(verge 格式),
         * providers 以额外前缀随包携带;verge 恢复时原样落盘,不受影响。
         */
        private const val PROVIDERS_PREFIX = "cmfa-providers/"
    }
}
