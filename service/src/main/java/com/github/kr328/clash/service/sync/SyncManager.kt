package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.sync.Conflict
import com.github.kr328.clash.common.sync.ConflictChoice
import com.github.kr328.clash.common.sync.LocalProfile
import com.github.kr328.clash.common.sync.LocalTraffic
import com.github.kr328.clash.common.sync.LocalType
import com.github.kr328.clash.common.sync.Overwrite
import com.github.kr328.clash.common.sync.PlanAction
import com.github.kr328.clash.common.sync.SyncKey
import com.github.kr328.clash.common.sync.SyncMode
import com.github.kr328.clash.common.sync.SyncPlanner
import com.github.kr328.clash.common.sync.SyncPlan
import com.github.kr328.clash.common.sync.SyncSnapshot
import com.github.kr328.clash.common.sync.VergeBackup
import com.github.kr328.clash.common.sync.VergeBackupCodec
import com.github.kr328.clash.common.sync.VergeItem
import com.github.kr328.clash.service.ProfileManager
import com.github.kr328.clash.service.ProfileReceiver
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.ISyncManager
import com.github.kr328.clash.service.remote.CloudBackupInfo
import com.github.kr328.clash.service.remote.SyncChoice
import com.github.kr328.clash.service.remote.SyncConflict
import com.github.kr328.clash.service.remote.SyncOutcome
import com.github.kr328.clash.service.remote.SyncOverwrite
import com.github.kr328.clash.service.remote.toKeyKindInt
import com.github.kr328.clash.service.remote.toSyncKeyKind
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手动同步引擎(remote/ISyncManager 的 service 进程实现)。
 *
 * 同步流程:读本机 imported 订阅与云端最新备份包 → 按设置里的同步方向
 * ([com.github.kr328.clash.common.sync.SyncMode])交给 SyncPlanner 生成计划 →
 * 无需确认直接执行;有冲突(PHASE_CONFLICTS,仅双向)或含删除/覆盖
 * (PHASE_CONFIRM_CHANGES)时挂起待续计划,经 resolve 按用户选择继续。
 * 来源端为空的保护性拒绝直接以失败答复返回,两端都不动。
 * 所有动作完成后才持久化新快照,失败/取消保留旧快照(共享笔记 00 定稿语义)。
 *
 * 另实现云端历史管理(票 #7):列表 / 恢复(整体替换本机订阅并重建快照基线,
 * 不经过合并计划)/ 删除单包;恢复与同步共用同一 busy 互斥。
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
            val cloudState = fetchCloudState(client)
            val (locals, localUuids) = readLocals()
            val snapshot = SnapshotStore(context).load()

            val plan = SyncPlanner.plan(
                locals = locals,
                cloud = cloudState.backup,
                snapshot = snapshot,
                mode = store.syncMode,
                cloudBackupExists = cloudState.exists,
            )

            // 保护性拒绝(来源端为空等):原样展示原因,两端都不动
            plan.refusal?.let { return failed(it) }

            return when {
                plan.conflicts.isNotEmpty() -> {
                    stateMutex.withLock { pending = PendingRound(plan, localUuids) }

                    outcome(phase = SyncOutcome.PHASE_CONFLICTS, plan = plan, conflicts = plan.conflicts)
                }
                plan.needsConfirmation -> {
                    stateMutex.withLock { pending = PendingRound(plan, localUuids) }

                    outcome(phase = SyncOutcome.PHASE_CONFIRM_CHANGES, plan = plan)
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
                    SyncKey(it.keyKind.toSyncKeyKind(), it.keyValue) to
                        if (it.useLocal) ConflictChoice.USE_LOCAL else ConflictChoice.USE_CLOUD
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

    // ---- 云端历史管理(票 #7) ----

    override suspend fun listCloudBackups(): List<CloudBackupInfo> {
        val store = SyncStore(context)
        if (store.webdavUrl.isBlank()) throw IllegalStateException("未配置 WebDAV 服务器地址")

        val client = newClient(store)
        client.ensureDirectory()

        return client.listBackups().map {
            CloudBackupInfo(
                name = it.name,
                fileNameTime = it.fileTimeMillis,
                lastModified = it.lastModifiedMillis,
            )
        }
    }

    override suspend fun restoreBackup(name: String): SyncOutcome {
        if (!busy.compareAndSet(false, true)) return busyOutcome()

        try {
            val store = SyncStore(context)
            if (store.webdavUrl.isBlank()) return failed("未配置 WebDAV 服务器地址")

            val backup = VergeBackupCodec.decode(newClient(store).fetchBackup(name))

            // 1) 整体替换:清空现有 imported 订阅,逐个走既有删除语义(取消排期+删目录+删行)
            for (uuid in ImportedDao().queryAllUUIDs()) {
                profileManager.delete(uuid)
            }

            // 2) 按云端包条目逐个离线导入(与同步导入同一实现);增强条目与残缺条目
            //    不落为本机订阅,原样留在包里
            val restored = mutableListOf<String>()
            val fingerprints = LinkedHashMap<SyncKey, String>()

            for (item in backup.items) {
                val key = SyncKey.of(item) ?: continue

                // 包内重复键与同步(plan 对重复键快速失败)同口径,拒绝含糊导入
                require(key !in fingerprints) { "云端包内存在重复键的订阅条目: $key" }

                val content = item.file?.let { backup.contentFiles[it] }

                importOffline(
                    key = key,
                    name = item.name,
                    url = item.url,
                    item = item,
                    content = content,
                    providers = providersOf(backup, item.file),
                )

                restored += item.name ?: key.value
                // 快照基线按实际落盘内容记指纹(与 readLocals 读回 config.yaml 的口径一致)
                fingerprints[key] = SyncPlanner.fingerprint(content ?: ByteArray(0))
            }

            // 3) 恢复不是同步:不经过 SyncPlanner.plan,直接把基线重建为「本机 = 该包」;
            //    只有导入全部成功才走到这里,失败时旧快照原样保留
            SnapshotStore(context).store(SyncSnapshot(fingerprints))

            return SyncOutcome(
                phase = SyncOutcome.PHASE_DONE,
                succeeded = true,
                imported = restored,
            )
        } catch (e: Exception) {
            Log.w("Sync restore failed: $e", e)

            return failed(e)
        } finally {
            busy.set(false)
        }
    }

    override suspend fun deleteCloudBackup(name: String) {
        val store = SyncStore(context)
        if (store.webdavUrl.isBlank()) throw IllegalStateException("未配置 WebDAV 服务器地址")

        newClient(store).deleteBackup(name)
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
                    val providers = providersOf(plan.newCloudState, action.cloudItem.file)
                    val existingUuid = action.existing?.let { localUuids[action.key] }

                    if (existingUuid != null) {
                        // 本机已有同键订阅:就地改写,保留 uuid、激活状态与自动更新排期
                        overwriteInPlace(
                            uuid = existingUuid,
                            key = action.key,
                            name = action.name,
                            url = action.url,
                            item = action.cloudItem,
                            content = action.content,
                            providers = providers,
                        )
                    } else {
                        importOffline(
                            key = action.key,
                            name = action.name,
                            url = action.url,
                            item = action.cloudItem,
                            content = action.content,
                            providers = providers,
                        )
                    }

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
            overwrites = overwritesOf(plan.overwrites),
        )
    }

    /**
     * 离线导入:云端包内容直接落盘(imported/<新uuid>/config.yaml + providers),
     * 写 imported 表行(不联网下载、不走 pending 流程),排期自动更新并广播。
     * 同步导入与历史恢复共用;[content]/[providers] 必须取自云端包原始内容。
     */
    private suspend fun importOffline(
        key: SyncKey,
        name: String?,
        url: String?,
        item: VergeItem,
        content: ByteArray?,
        providers: Map<String, ByteArray>,
    ) {
        val uuid = generateProfileUUID()
        val dir = context.importedDir.resolve(uuid.toString())

        writeProfileFiles(dir, content, providers)

        val row = Imported(
            uuid = uuid,
            name = name ?: key.value,
            type = if (key.kind == SyncKey.Kind.URL) Profile.Type.Url else Profile.Type.File,
            source = url ?: "",
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
     * 就地改写本机已有订阅(单向覆盖或双向取云端时使用):
     * 用云端包内容替换 config.yaml 与 providers 目录,保留 uuid、创建时间与 age 密钥,
     * 更新名称/来源/更新间隔/流量。激活状态因此保持,不需要重新选中订阅。
     *
     * 注意:若该订阅存在未提交的编辑草稿(pending),草稿里的名称等展示值仍优先,
     * 同步本就不处理草稿(与 readLocals 只读 imported 同口径)。
     */
    private suspend fun overwriteInPlace(
        uuid: UUID,
        key: SyncKey,
        name: String?,
        url: String?,
        item: VergeItem,
        content: ByteArray?,
        providers: Map<String, ByteArray>,
    ) {
        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw IllegalStateException("要覆盖的订阅已不存在: $key")

        writeProfileFiles(context.importedDir.resolve(uuid.toString()), content, providers)

        val row = imported.copy(
            name = name ?: imported.name,
            type = if (key.kind == SyncKey.Kind.URL) Profile.Type.Url else Profile.Type.File,
            source = url ?: "",
            interval = item.option?.updateInterval?.takeIf { it > 0 }
                ?.let { TimeUnit.MINUTES.toMillis(it) } ?: 0,
            upload = item.extra?.upload ?: 0,
            download = item.extra?.download ?: 0,
            total = item.extra?.total ?: 0,
            expire = item.extra?.expire ?: 0,
        )

        ImportedDao().update(row)

        ProfileReceiver.scheduleNext(context, row)
        context.sendProfileChanged(uuid)
    }

    /** 落盘一条订阅的内容与 providers(先清旧目录,保证 providers 不残留陈旧文件)。 */
    private suspend fun writeProfileFiles(
        dir: File,
        content: ByteArray?,
        providers: Map<String, ByteArray>,
    ) {
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
            dir.resolve("config.yaml").writeBytes(content ?: ByteArray(0))

            val providersDir = dir.resolve("providers")
            providersDir.mkdirs()

            for ((fileName, data) in providers) {
                require(fileName.isNotEmpty() && fileName != "." && fileName != ".." && '/' !in fileName) {
                    "非法的 provider 文件名: $fileName"
                }

                providersDir.resolve(fileName).writeBytes(data)
            }
        }
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
            val key = SyncKey.of(type, url, imported.name) ?: continue

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

    /**
     * 云端状态 = 同步目录最新备份包。
     * [CloudState.exists] 区分「目录里没有任何备份包」与「有备份包但为空」:
     * 单向模式在来源端为空时必须拒绝执行,而不是把另一端清空。
     */
    private class CloudState(val backup: VergeBackup, val exists: Boolean)

    private suspend fun fetchCloudState(client: WebDavClient): CloudState {
        client.ensureDirectory()

        val latest = client.listBackups().firstOrNull()
            ?: return CloudState(
                VergeBackup(null, emptyList(), emptyMap(), emptyMap(), emptyMap()),
                exists = false,
            )

        return CloudState(VergeBackupCodec.decode(client.fetchBackup(latest.name)), exists = true)
    }

    private fun newClient(store: SyncStore): WebDavClient =
        WebDavClient(store.webdavUrl, store.webdavUsername, store.webdavPassword, context.cacheDir)

    // ---- providers 与云端条目的对应关系 ----

    /**
     * 条目的 providers:备份包 extraFiles 中该条目内容文件名对应前缀下的全部文件
     * (同步导入取计划的新云端状态,历史恢复取备份包本身)。
     */
    private fun providersOf(backup: VergeBackup, file: String?): Map<String, ByteArray> {
        file ?: return emptyMap()
        val prefix = PROVIDERS_PREFIX + file + "/"

        return backup.extraFiles
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    /** 推送目标条目在云端包内的内容文件名(条目键规则见 [SyncKey.of])。 */
    private fun findCloudFile(backup: VergeBackup, key: SyncKey): String? =
        backup.items.firstOrNull { SyncKey.of(it) == key }?.file

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
                keyKind = it.key.kind.toKeyKindInt(),
                keyValue = it.key.value,
                localName = it.localName,
                cloudName = it.cloudName,
                localUpdatedAt = it.localUpdatedAtSeconds,
                cloudUpdatedAt = it.cloudUpdatedAtSeconds,
            )
        },
        overwrites = overwritesOf(plan.overwrites),
    )

    /** 覆盖清单 → 跨进程形态(确认弹窗与结果提示共用同一份映射)。 */
    private fun overwritesOf(overwrites: List<Overwrite>): List<SyncOverwrite> = overwrites.map {
        SyncOverwrite(
            keyKind = it.key.kind.toKeyKindInt(),
            keyValue = it.key.value,
            name = it.name,
            keptLocal = it.keptLocal,
        )
    }

    private fun displayName(action: PlanAction): String = action.name ?: action.key.value

    private fun busyOutcome(): SyncOutcome =
        failed("已有一轮同步正在进行")

    private fun failed(e: Exception): SyncOutcome = failed(e.message ?: e.toString())

    private fun failed(message: String): SyncOutcome = SyncOutcome(
        phase = SyncOutcome.PHASE_DONE,
        succeeded = false,
        message = message,
    )

    companion object {
        /**
         * providers 在备份包内的根前缀:包内 profiles/ 只允许单级文件(verge 格式),
         * providers 以额外前缀随包携带;verge 恢复时原样落盘,不受影响。
         */
        private const val PROVIDERS_PREFIX = "cmfa-providers/"
    }
}
