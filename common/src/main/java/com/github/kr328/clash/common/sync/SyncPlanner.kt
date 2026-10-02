package com.github.kr328.clash.common.sync

import java.security.MessageDigest

/**
 * 同步计划器:纯逻辑,无 Android、无网络依赖。
 *
 * 输入本机订阅集合、云端状态([VergeBackup])与上次同步快照,输出同步计划
 * (导入/推送/删本机/删云端/冲突列表)、同步后的新云端状态与新快照。
 * 语义来自共享笔记 00「同步语义(定稿)」,不得偏离。
 *
 * 快照指基线:plan() 产出的新快照假设计划会被完整执行且推送成功(「成功同步后才
 * 更新快照」由调用方保证——执行失败时丢弃产出、保留旧快照)。
 */
object SyncPlanner {
    /** 内容指纹:SHA-256 十六进制。本机侧与云端侧、快照基线统一用这一个函数。 */
    fun fingerprint(content: ByteArray): String = sha256Hex(content)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 生成同步计划。
     *
     * @param locals 本机订阅集合;键(URL/名称)重复视为调用方数据错误,快速失败
     * @param cloud 云端状态(同步目录最新备份包)
     * @param snapshot 上次同步快照;null = 首次同步
     */
    fun plan(
        locals: Collection<LocalProfile>,
        cloud: VergeBackup,
        snapshot: SyncSnapshot?,
    ): SyncPlan = compute(locals, cloud, snapshot, emptyMap())

    /**
     * @param overrides 冲突的用户选择(resolve 重算时非空;键缺选择按保留云端计)
     */
    private fun compute(
        locals: Collection<LocalProfile>,
        cloud: VergeBackup,
        snapshot: SyncSnapshot?,
        overrides: Map<SyncKey, ConflictChoice>,
    ): SyncPlan {
        locals.forEach {
            require(it.contentFingerprint == fingerprint(it.content)) {
                "本机订阅 ${it.name} 的 contentFingerprint 与内容不一致,必须用 SyncPlanner.fingerprint 计算"
            }
        }
        val localByKey = indexLocals(locals)
        val cloudByKey = indexCloud(cloud)

        val actions = ArrayList<PlanAction>()
        val conflicts = ArrayList<Conflict>()
        val newSnapshotFps = LinkedHashMap<SyncKey, String>()

        // 新云端状态从输入包拷贝起改:未参与同步的条目/文件/字段自然原样保留
        val newItems = cloud.items.toMutableList()
        val newContentFiles = LinkedHashMap(cloud.contentFiles)
        val newCurrent = Box(cloud.current)

        for (key in (localByKey.keys + cloudByKey.keys)) {
            val local = localByKey[key]
            val ref = cloudByKey[key]
            when {
                local == null && ref != null -> {
                    if (snapshot?.fingerprints?.containsKey(key) == true) {
                        // 快照里有、本机已消失 → 云端一并删除(删除传播,与云端是否改过无关)
                        actions += PlanAction.DeleteCloud(key, ref.item.name)
                        removeCloudItem(newItems, newContentFiles, newCurrent, ref)
                    } else {
                        // 云端独有(含首次同步):导入,直接用云端包内容,不重新下载
                        actions += PlanAction.Import(key, ref.item.name, ref.item.url, ref.item, ref.content)
                        newSnapshotFps[key] = ref.fingerprint
                    }
                }
                local != null && ref == null -> {
                    if (snapshot?.fingerprints?.containsKey(key) == true) {
                        // 快照里有、云端已消失 → 本机一并删除(删除传播,与本机是否改过无关)
                        actions += PlanAction.DeleteLocal(key, local.name)
                    } else {
                        // 本机独有(含首次同步):推送本机记录
                        actions += PlanAction.Push(key, local.name, local)
                        appendPushedItem(newItems, newContentFiles, key, local)
                        newSnapshotFps[key] = local.contentFingerprint
                    }
                }
                local != null && ref != null -> {
                    val baseline = snapshot?.fingerprints?.get(key)
                    when {
                        // 两端内容一致:原样,基线即当前指纹
                        local.contentFingerprint == ref.fingerprint ->
                            newSnapshotFps[key] = local.contentFingerprint

                        // 无基线(首次同步;或快照之后两端各自新增了同一条):
                        // 内容不一致取 updated 新的一方,不弹冲突;秒级并列取本机
                        baseline == null -> {
                            if (local.updatedAtSeconds >= (ref.item.updated ?: 0L)) {
                                actions += PlanAction.Push(key, local.name, local)
                                applyPushToExisting(newItems, newContentFiles, key, ref, local)
                                newSnapshotFps[key] = local.contentFingerprint
                            } else {
                                actions += PlanAction.Import(key, ref.item.name, ref.item.url, ref.item, ref.content)
                                newSnapshotFps[key] = ref.fingerprint
                            }
                        }

                        // 有基线:按变更方向判定。仅一端改过 → 取改过的一端,不弹冲突
                        local.contentFingerprint != baseline && ref.fingerprint == baseline -> {
                            actions += PlanAction.Push(key, local.name, local)
                            applyPushToExisting(newItems, newContentFiles, key, ref, local)
                            newSnapshotFps[key] = local.contentFingerprint
                        }
                        local.contentFingerprint == baseline && ref.fingerprint != baseline -> {
                            actions += PlanAction.Import(key, ref.item.name, ref.item.url, ref.item, ref.content)
                            newSnapshotFps[key] = ref.fingerprint
                        }

                        // 两端都改过同一条订阅 → 冲突,按用户选择;resolve 重算时才带选择
                        else -> when (overrides[key]) {
                            ConflictChoice.USE_LOCAL -> {
                                actions += PlanAction.Push(key, local.name, local)
                                applyPushToExisting(newItems, newContentFiles, key, ref, local)
                                newSnapshotFps[key] = local.contentFingerprint
                            }
                            ConflictChoice.USE_CLOUD -> {
                                actions += PlanAction.Import(key, ref.item.name, ref.item.url, ref.item, ref.content)
                                newSnapshotFps[key] = ref.fingerprint
                            }
                            // 未解析(初次 plan):登记冲突待选;试算产出按保留云端计
                            null -> {
                                conflicts += Conflict(
                                    key, local.name, local.updatedAtSeconds,
                                    ref.item.name, ref.item.updated,
                                )
                                newSnapshotFps[key] = ref.fingerprint
                            }
                        }
                    }
                }
                // 两端索引都不含此键不可能发生(键来自两端索引的并集)
                else -> Unit
            }
        }

        return SyncPlan(
            actions,
            conflicts,
            cloud.copy(items = newItems, contentFiles = newContentFiles, current = newCurrent.value),
            SyncSnapshot(newSnapshotFps),
        ) { choices -> compute(locals, cloud, snapshot, choices) }
    }

    /** 删除传播:从新云端状态移除条目与其内容文件;被删条目正是激活订阅时清空 current。 */
    private fun removeCloudItem(
        newItems: MutableList<VergeItem>,
        newContentFiles: LinkedHashMap<String, ByteArray>,
        current: Box,
        ref: CloudRef,
    ) {
        newItems.remove(ref.item)
        ref.item.file?.let { newContentFiles.remove(it) }
        if (ref.item.uid != null && ref.item.uid == current.value) current.value = null
    }

    /** current 指针的可变包装(删除传播时改写)。 */
    private class Box(var value: String?)

    /**
     * 本机独有订阅 → 云端包新增条目:uid 取 verge 前缀约定(R=remote,L=local),
     * 从键哈希确定性生成,避开已有 uid 与内容文件名。
     */
    private fun appendPushedItem(
        newItems: MutableList<VergeItem>,
        newContentFiles: LinkedHashMap<String, ByteArray>,
        key: SyncKey,
        local: LocalProfile,
    ) {
        val prefix = if (local.type == LocalType.URL) "R" else "L"
        val takenUids = newItems.mapNotNullTo(HashSet()) { it.uid }
        val uid = allocateUid(prefix, key, "", takenUids, newContentFiles.keys)
        val file = "$uid.yaml"
        newItems += VergeItem(
            uid = uid,
            type = if (local.type == LocalType.URL) "remote" else "local",
            name = local.name,
            file = file,
            desc = null,
            url = local.url,
            home = null,
            updated = local.updatedAtSeconds,
            extra = trafficOf(local.traffic),
            option = optionOf(local.updateIntervalMinutes),
            extraFields = emptyMap(),
        )
        newContentFiles[file] = local.content
    }

    /**
     * 两端都在、以本机为准推送:更新既有云端条目的同步字段(名称/URL/updated/流量/更新间隔),
     * 保留 uid、file、desc、home、option 其余字段(verge 的增强引用与抓取行为)与未知字段;
     * 条目缺 file 时补一个不冲突的内容文件名。
     */
    private fun applyPushToExisting(
        newItems: MutableList<VergeItem>,
        newContentFiles: LinkedHashMap<String, ByteArray>,
        key: SyncKey,
        ref: CloudRef,
        local: LocalProfile,
    ) {
        val index = newItems.indexOfFirst { it == ref.item }
        val file = ref.item.file ?: run {
            val prefix = if (local.type == LocalType.URL) "R" else "L"
            val takenUids = newItems.mapNotNullTo(HashSet()) { it.uid }
            val uid = ref.item.uid
                ?: allocateUid(prefix, key, ".x", takenUids, newContentFiles.keys)
            "$uid.yaml"
        }
        newItems[index] = ref.item.copy(
            name = local.name,
            url = local.url,
            updated = local.updatedAtSeconds,
            extra = pushedTraffic(ref.item.extra, local),
            option = ref.item.option?.copy(updateInterval = local.updateIntervalMinutes)
                ?: optionOf(local.updateIntervalMinutes),
            file = file,
        )
        newContentFiles[file] = local.content
    }

    /**
     * 推送后的流量信息:本机有值的字段覆盖,没有的字段保留云端值;
     * 云端 extra 的未知子字段原样保留(verge 端重存会丢弃未知字段,见 ADR-0001)。
     */
    private fun pushedTraffic(cloudExtra: VergeTraffic?, local: LocalProfile): VergeTraffic? {
        val t = local.traffic
        if (cloudExtra == null) return trafficOf(t)
        return cloudExtra.copy(
            upload = t?.upload ?: cloudExtra.upload,
            download = t?.download ?: cloudExtra.download,
            total = t?.total ?: cloudExtra.total,
            expire = t?.expire ?: cloudExtra.expire,
        )
    }

    private fun trafficOf(traffic: LocalTraffic?): VergeTraffic? = traffic?.let {
        VergeTraffic(it.upload, it.download, it.total, it.expire, emptyMap())
    }

    private fun optionOf(updateInterval: Long?): VergeOption? = updateInterval?.let {
        VergeOption(
            userAgent = null, withProxy = null, selfProxy = null, updateInterval = it,
            timeoutSeconds = null, dangerAcceptInvalidCerts = null, allowAutoUpdate = null,
            merge = null, script = null, rules = null, proxies = null, groups = null,
            extraFields = emptyMap(),
        )
    }

    /** 从键哈希确定性生成不与现有 uid/内容文件名冲突的 uid;[salt] 用于同键多次分配。 */
    private fun allocateUid(
        prefix: String,
        key: SyncKey,
        salt: String,
        takenUids: Set<String>,
        takenFiles: Set<String>,
    ): String {
        var n = 0
        while (true) {
            val uid = prefix + sha256Hex("${key.value}$salt#$n".toByteArray()).take(11)
            if (uid !in takenUids && "$uid.yaml" !in takenFiles) return uid
            n++
        }
    }

    // ---- 键索引 ----

    /** 云端订阅条目索引(键规则见 [SyncKey.of]);残缺条目不参与同步,原样随包保留。 */
    private fun indexCloud(cloud: VergeBackup): Map<SyncKey, CloudRef> {
        val map = LinkedHashMap<SyncKey, CloudRef>()
        for (item in cloud.items) {
            val key = SyncKey.of(item) ?: continue
            val content = item.file?.let { cloud.contentFiles[it] }
            val ref = CloudRef(item, content, fingerprint(content ?: ByteArray(0)))
            require(key !in map) { "云端包内存在重复键的订阅条目: $key" }
            map[key] = ref
        }
        return map
    }

    private fun indexLocals(locals: Collection<LocalProfile>): Map<SyncKey, LocalProfile> {
        val map = LinkedHashMap<SyncKey, LocalProfile>()
        for (local in locals) {
            val key = SyncKey.of(local.type, local.url, local.name) ?: continue
            require(key !in map) { "本机存在重复键的订阅: $key" }
            map[key] = local
        }
        return map
    }

    /** 云端订阅条目引用:条目元数据 + 包内容 + 内容指纹。 */
    private class CloudRef(
        val item: VergeItem,
        val content: ByteArray?,
        val fingerprint: String,
    )

    /**
     * 清理选择:从云端目录的备份包文件名里挑出应删除的自产旧包。
     *
     * 只考虑 `android-backup-<yyyy-MM-dd_HH-mm-ss>.zip`,按文件名内时间戳倒序保留
     * 最近 [keepCount] 个;verge 的包永不入选;时间戳解析失败的自产包为防误删也不入选。
     */
    fun selectPackagesToDelete(
        filenames: Collection<String>,
        keepCount: Int = 10,
    ): List<String> {
        val selfProduced = filenames.mapNotNull { name ->
            if (!name.startsWith(SELF_BACKUP_PREFIX)) return@mapNotNull null
            val timestamp = BackupFileName.parseTimestampSeconds(name)
            // 时间戳解析失败的文件无法判定新旧,宁可不删
            timestamp?.let { name to it }
        }
        // 文件名时间戳相同(同一秒)时按名称倒序,保证结果确定
        return selfProduced
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
            .drop(keepCount)
            .sortedBy { it.second } // 删除清单按旧→新输出,稳定可读
            .map { it.first }
    }

    private const val SELF_BACKUP_PREFIX = "android-backup-"
}
