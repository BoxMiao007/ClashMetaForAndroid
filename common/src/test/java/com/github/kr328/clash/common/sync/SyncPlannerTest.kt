package com.github.kr328.clash.common.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步核心外部行为测试:给定(本机集合、云端包、快照)断言(计划、新云端包、新快照),
 * 不测内部调用关系。语义依据共享笔记 00 与 issue #4 验收标准。
 */
class SyncPlannerTest {
    // ---- 夹具 ----

    private fun localUrl(
        name: String,
        url: String,
        content: ByteArray,
        updatedAt: Long,
        interval: Long? = null,
        traffic: LocalTraffic? = null,
    ) = LocalProfile(
        name = name,
        type = LocalType.URL,
        url = url,
        contentFingerprint = SyncPlanner.fingerprint(content),
        updatedAtSeconds = updatedAt,
        updateIntervalMinutes = interval,
        traffic = traffic,
        content = content,
    )

    private fun cloudRemoteItem(
        uid: String,
        name: String,
        url: String,
        file: String,
        updated: Long,
        interval: Long? = null,
        traffic: VergeTraffic? = null,
    ) = VergeItem(
        uid = uid,
        type = "remote",
        name = name,
        file = file,
        desc = null,
        url = url,
        home = null,
        updated = updated,
        extra = traffic,
        option = interval?.let {
            VergeOption(
                userAgent = null, withProxy = null, selfProxy = null, updateInterval = it,
                timeoutSeconds = null, dangerAcceptInvalidCerts = null, allowAutoUpdate = null,
                merge = null, script = null, rules = null, proxies = null, groups = null,
                extraFields = emptyMap(),
            )
        },
        extraFields = emptyMap(),
    )

    private fun cloudOf(
        vararg items: VergeItem,
        contentFiles: Map<String, ByteArray> = emptyMap(),
        current: String? = null,
    ) = VergeBackup(current, items.toList(), contentFiles, emptyMap(), emptyMap())

    private fun snapshotOf(vararg pairs: Pair<SyncKey, String>) =
        SyncSnapshot(linkedMapOf(*pairs))

    private fun urlKey(url: String) = SyncKey(SyncKey.Kind.URL, url)

    private fun nameKey(name: String) = SyncKey(SyncKey.Kind.NAME, name)

    // ---- 用例 ----

    @Test
    fun `首次同步 - 云端独有的订阅产出导入计划并携带云端内容`() {
        val contentA = "proxies: []\n".toByteArray()
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 100,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentA))

        val plan = SyncPlanner.plan(emptyList(), cloud, null)

        val import = plan.actions.single() as PlanAction.Import
        assertEquals(urlKey("https://example.com/a"), import.key)
        assertEquals("订阅A", import.name)
        assertEquals("https://example.com/a", import.url)
        assertArrayEquals(contentA, import.content)
        assertEquals(itemA, import.cloudItem)
        assertTrue(plan.conflicts.isEmpty())

        // 云端状态不变;快照记下云端内容指纹作为下次同步基线
        assertEquals(listOf(itemA), plan.newCloudState.items)
        assertEquals(
            mapOf(urlKey("https://example.com/a") to SyncPlanner.fingerprint(contentA)),
            plan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `首次同步 - 本机独有的订阅产出推送计划并并入新云端状态`() {
        val contentB = "proxies:\n  - name: 日本 01\n".toByteArray()
        val local = localUrl(
            name = "订阅B", url = "https://example.com/b", content = contentB,
            updatedAt = 200, interval = 1440,
            traffic = LocalTraffic(upload = 1, download = 2, total = 3, expire = 0),
        )

        val plan = SyncPlanner.plan(listOf(local), cloudOf(), null)

        val push = plan.actions.single() as PlanAction.Push
        assertEquals(urlKey("https://example.com/b"), push.key)
        assertEquals("订阅B", push.name)
        assertEquals(local, push.local)
        assertTrue(plan.conflicts.isEmpty())

        // 新云端状态新增对应条目:type/uid 前缀符合 verge 约定,元数据取自本机记录
        val pushed = plan.newCloudState.items.single()
        assertEquals("remote", pushed.type)
        assertEquals("订阅B", pushed.name)
        assertEquals("https://example.com/b", pushed.url)
        assertEquals(200L, pushed.updated)
        assertEquals(1440L, pushed.option!!.updateInterval)
        assertEquals(1L, pushed.extra!!.upload)
        assertEquals("R", pushed.uid!!.first().toString())
        val pushedFile = pushed.file!!
        assertArrayEquals(contentB, plan.newCloudState.contentFiles[pushedFile])

        // 快照基线 = 推送后的两端一致内容(即本机内容)
        assertEquals(
            mapOf(urlKey("https://example.com/b") to SyncPlanner.fingerprint(contentB)),
            plan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `首次同步 - 本机文件型订阅推送为 verge local 条目`() {
        val content = "mixed-port: 7890\n".toByteArray()
        val local = LocalProfile(
            name = "本地配置", type = LocalType.FILE, url = null,
            contentFingerprint = SyncPlanner.fingerprint(content),
            updatedAtSeconds = 300, updateIntervalMinutes = null, traffic = null,
            content = content,
        )

        val plan = SyncPlanner.plan(listOf(local), cloudOf(), null)

        assertTrue(plan.actions.single() is PlanAction.Push)
        val pushed = plan.newCloudState.items.single()
        assertEquals("local", pushed.type)
        assertEquals("本地配置", pushed.name)
        assertEquals("L", pushed.uid!!.first().toString())
        assertEquals(null, pushed.url)
        assertArrayEquals(content, plan.newCloudState.contentFiles[pushed.file!!])
    }

    @Test
    fun `清理选择 - 自产包保留最近10个且verge的包永不入选`() {
        val android = (1..12).map { "android-backup-2026-01-%02d_00-00-00.zip".format(it) }
        val verge = listOf(
            "windows-backup-2026-01-13_00-00-00.zip",
            "linux-backup-2026-01-14_00-00-00.zip",
            "macos-backup-2026-01-15_00-00-00.zip",
        )

        val deletions = SyncPlanner.selectPackagesToDelete(verge + android)

        // 最旧的 2 个自产包入选,最近 10 个保留,verge 的包一个不动
        assertEquals(
            listOf("android-backup-2026-01-01_00-00-00.zip", "android-backup-2026-01-02_00-00-00.zip"),
            deletions,
        )
    }

    @Test
    fun `清理选择 - 不足10个不删且时间戳解析失败的自产包防误删不入选`() {
        val android = (1..10).map { "android-backup-2026-02-%02d_00-00-00.zip".format(it) }
        assertTrue(SyncPlanner.selectPackagesToDelete(android).isEmpty())

        val weird = listOf(
            "android-backup-not-a-timestamp.zip", // 前缀对但时间戳非法
            "android-backup-2026-03-01.zip", // 缺字段
            "android-backup-2026-03-02_00-00-00.zip.bak", // 后缀不对
        )
        assertTrue(SyncPlanner.selectPackagesToDelete(weird).isEmpty())
    }

    @Test
    fun `首次同步 - 两端都有且内容不一致时取更新时间新的一方且不弹冲突`() {
        val contentA1 = "version: 1\n".toByteArray()
        val contentA2 = "version: 2\n".toByteArray()
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 300,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentA2))

        // 云端 updated 较新 → 导入云端,不弹冲突
        val localOlder = localUrl("订阅A", "https://example.com/a", contentA1, updatedAt = 200)
        val importPlan = SyncPlanner.plan(listOf(localOlder), cloud, null)
        assertTrue(importPlan.actions.single() is PlanAction.Import)
        assertTrue(importPlan.conflicts.isEmpty())
        assertEquals(
            mapOf(urlKey("https://example.com/a") to SyncPlanner.fingerprint(contentA2)),
            importPlan.newSnapshot.fingerprints,
        )

        // 本机 updated 较新 → 推送本机
        val localNewer = localUrl("订阅A", "https://example.com/a", contentA1, updatedAt = 400)
        val pushPlan = SyncPlanner.plan(listOf(localNewer), cloud, null)
        assertTrue(pushPlan.actions.single() is PlanAction.Push)
        assertTrue(pushPlan.conflicts.isEmpty())
        assertArrayEquals(
            contentA1,
            pushPlan.newCloudState.contentFiles[pushPlan.newCloudState.items.single().file],
        )

        // 两端内容一致(即便 updated 有差)→ 原样,不产出动作
        val localSame = localUrl("订阅A", "https://example.com/a", contentA2, updatedAt = 200)
        val noopPlan = SyncPlanner.plan(listOf(localSame), cloud, null)
        assertTrue(noopPlan.actions.isEmpty())
        assertEquals(
            mapOf(urlKey("https://example.com/a") to SyncPlanner.fingerprint(contentA2)),
            noopPlan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `快照在 - 两端都没改则原样且快照不变`() {
        val contentA = "version: 1\n".toByteArray()
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 100,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentA))
        val local = localUrl("订阅A", "https://example.com/a", contentA, updatedAt = 100)
        val key = urlKey("https://example.com/a")
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentA))

        val plan = SyncPlanner.plan(listOf(local), cloud, snapshot)

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(snapshot, plan.newSnapshot)
        assertEquals(cloud, plan.newCloudState)
    }

    @Test
    fun `快照在 - 仅一端改过则取改过的一端且不弹冲突`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentCloud = "version: 2\n".toByteArray()
        val contentLocal = "version: 3\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentCloud))
        val local = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        // 只有云端改过(本机保持基线内容)→ 导入云端内容
        val localBase = localUrl("订阅A", "https://example.com/a", contentBase, updatedAt = 100)
        val importPlan = SyncPlanner.plan(listOf(localBase), cloud, snapshot)
        val import = importPlan.actions.single() as PlanAction.Import
        assertEquals(key, import.key)
        assertArrayEquals(contentCloud, import.content)
        assertTrue(importPlan.conflicts.isEmpty())
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentCloud)),
            importPlan.newSnapshot.fingerprints,
        )

        // 只有本机改过(云端保持基线内容)→ 推送本机内容到云端既有条目
        val localChanged = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val pushPlan = SyncPlanner.plan(
            listOf(localChanged),
            cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentBase)),
            snapshot,
        )
        val push = pushPlan.actions.single() as PlanAction.Push
        assertEquals(key, push.key)
        assertTrue(pushPlan.conflicts.isEmpty())
        // 云端既有条目被原地更新:uid/file 保留,内容与元数据取本机
        val updated = pushPlan.newCloudState.items.single()
        assertEquals("Raaaaaaaaaaa", updated.uid)
        assertEquals("Raaaaaaaaaaa.yaml", updated.file)
        assertEquals(300L, updated.updated)
        assertArrayEquals(contentLocal, pushPlan.newCloudState.contentFiles["Raaaaaaaaaaa.yaml"])
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentLocal)),
            pushPlan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `删除传播 - 云端已消失的订阅标记删本机且移出快照`() {
        val contentA = "version: 1\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val local = localUrl("订阅A", "https://example.com/a", contentA, updatedAt = 100)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentA))

        val plan = SyncPlanner.plan(listOf(local), cloudOf(), snapshot)

        val delete = plan.actions.single() as PlanAction.DeleteLocal
        assertEquals(key, delete.key)
        assertEquals("订阅A", delete.name)
        // 本机删除不触碰云端;快照基线同步收缩
        assertTrue(plan.newCloudState.items.isEmpty())
        assertTrue(plan.newSnapshot.fingerprints.isEmpty())
    }

    @Test
    fun `删除传播 - 本机已消失的订阅标记删云端并从新云端状态移除`() {
        val contentA = "version: 1\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 100,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentA), current = "Raaaaaaaaaaa")
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentA))

        val plan = SyncPlanner.plan(emptyList(), cloud, snapshot)

        val delete = plan.actions.single() as PlanAction.DeleteCloud
        assertEquals(key, delete.key)
        assertEquals("订阅A", delete.name)
        // 条目与内容文件一并移除;被删条目正是 current 时清空激活指针
        assertTrue(plan.newCloudState.items.isEmpty())
        assertTrue(plan.newCloudState.contentFiles.isEmpty())
        assertEquals(null, plan.newCloudState.current)
        assertTrue(plan.newSnapshot.fingerprints.isEmpty())
    }

    @Test
    fun `删除传播 - 两端都没了则无动作`() {
        val contentA = "version: 1\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentA))

        val plan = SyncPlanner.plan(emptyList(), cloudOf(), snapshot)

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.newSnapshot.fingerprints.isEmpty())
    }

    @Test
    fun `冲突 - 快照后两端都改过则标记待选且试算结果按保留云端计`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentCloud = "version: 2\n".toByteArray()
        val contentLocal = "version: 3\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentCloud))
        val local = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        val plan = SyncPlanner.plan(listOf(local), cloud, snapshot)

        // 冲突列表给出键与两边各是什么
        val conflict = plan.conflicts.single()
        assertEquals(key, conflict.key)
        assertEquals("订阅A", conflict.localName)
        assertEquals(300L, conflict.localUpdatedAtSeconds)
        assertEquals("订阅A", conflict.cloudName)
        assertEquals(200L, conflict.cloudUpdatedAtSeconds)
        assertTrue(plan.actions.isEmpty())
        // 试算产出(未 resolve 不可直接执行):云端保持原样,基线按云端内容计
        assertEquals(cloud, plan.newCloudState)
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentCloud)),
            plan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `冲突 - 选用本地则解析为推送本机`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentCloud = "version: 2\n".toByteArray()
        val contentLocal = "version: 3\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentCloud))
        val local = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        val resolved = SyncPlanner.plan(listOf(local), cloud, snapshot)
            .resolve(mapOf(key to ConflictChoice.USE_LOCAL))

        // 冲突变更为推送;云端既有条目更新为本机内容;基线取本机指纹
        val push = resolved.actions.single() as PlanAction.Push
        assertEquals(key, push.key)
        assertTrue(resolved.conflicts.isEmpty())
        assertArrayEquals(
            contentLocal,
            resolved.newCloudState.contentFiles["Raaaaaaaaaaa.yaml"],
        )
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentLocal)),
            resolved.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `冲突 - 选用云端则解析为导入云端且云端不动`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentCloud = "version: 2\n".toByteArray()
        val contentLocal = "version: 3\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentCloud))
        val local = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        val resolved = SyncPlanner.plan(listOf(local), cloud, snapshot)
            .resolve(mapOf(key to ConflictChoice.USE_CLOUD))

        // 冲突变更为导入(本机侧改为云端内容);云端保持原样;基线取云端指纹
        val import = resolved.actions.single() as PlanAction.Import
        assertEquals(key, import.key)
        assertArrayEquals(contentCloud, import.content)
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(cloud, resolved.newCloudState)
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentCloud)),
            resolved.newSnapshot.fingerprints,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `冲突 - 有冲突未给出选择时 resolve 快速失败`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentCloud = "version: 2\n".toByteArray()
        val contentLocal = "version: 3\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentCloud))
        val local = localUrl("订阅A", "https://example.com/a", contentLocal, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        SyncPlanner.plan(listOf(local), cloud, snapshot).resolve(emptyMap())
    }

    @Test
    fun `冲突 - 两端改成了相同内容则不弹冲突原样记录基线`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentNew = "version: 2\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 200,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentNew))
        val local = localUrl("订阅A", "https://example.com/a", contentNew, updatedAt = 300)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        val plan = SyncPlanner.plan(listOf(local), cloud, snapshot)

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(
            mapOf(key to SyncPlanner.fingerprint(contentNew)),
            plan.newSnapshot.fingerprints,
        )
    }

    @Test
    fun `快照在但该键无基线 - 快照后两端各自新增同一条取更新时间新的一方`() {
        val contentA1 = "version: 1\n".toByteArray()
        val contentA2 = "version: 2\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 500,
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentA2))
        // 本机 updated 较旧,云端较新 → 导入云端,不弹冲突(与首次同步同规则)
        val local = localUrl("订阅A", "https://example.com/a", contentA1, updatedAt = 100)
        val snapshot = snapshotOf(urlKey("https://example.com/other") to SyncPlanner.fingerprint(contentA1))

        val plan = SyncPlanner.plan(listOf(local), cloud, snapshot)

        assertTrue(plan.actions.single() is PlanAction.Import)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `新云端状态 - 未参与同步的增强条目未知字段与未知文件原样保留`() {
        val contentA = "version: 1\n".toByteArray()
        val mergeContent = "prepend-rules: []\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 100,
        ).copy(extraFields = linkedMapOf("selected" to listOf("节点"))) // 未建模字段
        val mergeItem = VergeItem(
            uid = "maaaaaaaaaaa", type = "merge", name = "全局扩展配置",
            file = "maaaaaaaaaaa.yaml", desc = null, url = null, home = null, updated = null,
            extra = null, option = null, extraFields = emptyMap(),
        )
        val cloud = VergeBackup(
            current = "Raaaaaaaaaaa",
            items = listOf(itemA, mergeItem),
            contentFiles = linkedMapOf(
                "Raaaaaaaaaaa.yaml" to contentA,
                "maaaaaaaaaaa.yaml" to mergeContent,
            ),
            extraFiles = linkedMapOf("future-root-file.yaml" to "future: true\n".toByteArray()),
            extraFields = linkedMapOf("some_future_root_field" to mapOf("nested" to 1)),
        )
        // 本机推送一条新订阅 + 快照记录订阅A(云端已删,触发删云端落地)
        val contentB = "version: 2\n".toByteArray()
        val localB = localUrl("订阅B", "https://example.com/b", contentB, updatedAt = 200)
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentA))

        val plan = SyncPlanner.plan(listOf(localB), cloud, snapshot)

        // 删云端动作已落地:订阅A 的条目与内容文件被移除,current 清空
        assertTrue(plan.actions.contains(PlanAction.DeleteCloud(key, "订阅A")))
        assertTrue(plan.newCloudState.items.none { it.url == "https://example.com/a" })
        assertTrue("Raaaaaaaaaaa.yaml" !in plan.newCloudState.contentFiles)

        // 推送动作已落地:新条目与内容文件进入新云端状态
        val pushed = plan.newCloudState.items.single { it.url == "https://example.com/b" }
        assertArrayEquals(contentB, plan.newCloudState.contentFiles[pushed.file])

        // 未参与同步的一切原样保留:增强条目及其内容、未知字段、未知文件
        assertEquals(mergeItem, plan.newCloudState.items.single { it.type == "merge" })
        assertArrayEquals(mergeContent, plan.newCloudState.contentFiles["maaaaaaaaaaa.yaml"])
        assertArrayEquals("future: true\n".toByteArray(), plan.newCloudState.extraFiles["future-root-file.yaml"])
        assertEquals(mapOf("nested" to 1), plan.newCloudState.extraFields["some_future_root_field"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `本机集合存在重复键时快速失败`() {
        val content = "version: 1\n".toByteArray()
        SyncPlanner.plan(
            listOf(
                localUrl("订阅A", "https://example.com/a", content, updatedAt = 100),
                localUrl("订阅A2", "https://example.com/a", content, updatedAt = 200),
            ),
            cloudOf(),
            null,
        )
    }

    @Test
    fun `推送更新 - 云端条目 extra 内未知子字段原样保留`() {
        val contentBase = "version: 1\n".toByteArray()
        val contentLocal = "version: 2\n".toByteArray()
        val key = urlKey("https://example.com/a")
        val itemA = cloudRemoteItem(
            uid = "Raaaaaaaaaaa", name = "订阅A", url = "https://example.com/a",
            file = "Raaaaaaaaaaa.yaml", updated = 100,
        ).copy(
            extra = VergeTraffic(
                upload = 1, download = 2, total = 3, expire = 0,
                extraFields = linkedMapOf("unknown_extra_field" to "kept"),
            ),
        )
        val cloud = cloudOf(itemA, contentFiles = mapOf("Raaaaaaaaaaa.yaml" to contentBase))
        // 本机流量信息只带 upload:upload 被本机值覆盖,其余字段与未知子字段保留云端值
        val local = localUrl(
            "订阅A", "https://example.com/a", contentLocal, updatedAt = 300,
            traffic = LocalTraffic(upload = 9, download = null, total = null, expire = null),
        )
        val snapshot = snapshotOf(key to SyncPlanner.fingerprint(contentBase))

        val plan = SyncPlanner.plan(listOf(local), cloud, snapshot)

        assertTrue(plan.actions.single() is PlanAction.Push)
        val extra = plan.newCloudState.items.single().extra!!
        assertEquals(9L, extra.upload)
        assertEquals(2L, extra.download)
        assertEquals("kept", extra.extraFields["unknown_extra_field"])
    }
}
