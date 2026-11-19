package com.github.kr328.clash.common.sync

/**
 * 同步核心的中性本机模型与计划类型。
 *
 * 本文件只定义数据形态;合并/删除/冲突判定在 [SyncPlanner]。
 * 刻意不依赖 service 模块的 Room/Profile 类型:计划器是纯逻辑,输入输出全部可测。
 *
 * 术语见领域术语表:同步 = 本机订阅集合与云端状态([VergeBackup])之间的同步,
 * 方向由 [SyncMode] 决定(双向并集合并,或单向覆盖/只增);上次同步快照 = 上次成功
 * 同步时两端订阅集合在本机的留底。
 */

/** 本机订阅类型:URL = 远程订阅(按 url 匹配);FILE = 本地文件(按名称匹配)。 */
enum class LocalType { URL, FILE }

/** 本机订阅的流量信息(来自 subscription-userinfo);秒级时间戳,与 verge 的 extra 字段一致。 */
data class LocalTraffic(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    // 秒级 Unix 时间戳,0 表示不限期
    val expire: Long?,
)

/**
 * 同步视角的本机订阅。
 *
 * [contentFingerprint] 必须等于 [SyncPlanner.fingerprint] 对 [content] 的结果:
 * 计划器用它和云端包内容指纹、快照指纹做三方比对,入参不一致会在 plan() 里快速失败。
 */
class LocalProfile(
    val name: String,
    val type: LocalType,
    // type == URL 时为订阅地址,参与匹配;type == FILE 时应为 null
    val url: String?,
    val contentFingerprint: String,
    // 秒级 Unix 时间戳(与 verge 的 updated 字段同粒度),首次同步取新者时使用
    val updatedAtSeconds: Long,
    // 自动更新间隔,分钟;null 表示未设置
    val updateIntervalMinutes: Long?,
    val traffic: LocalTraffic?,
    // 订阅内容原始字节(推送时写入云端包,本票不做真实上传)
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LocalProfile &&
        name == other.name &&
        type == other.type &&
        url == other.url &&
        contentFingerprint == other.contentFingerprint &&
        updatedAtSeconds == other.updatedAtSeconds &&
        updateIntervalMinutes == other.updateIntervalMinutes &&
        traffic == other.traffic

    override fun hashCode(): Int = listOf(
        name, type, url, contentFingerprint, updatedAtSeconds, updateIntervalMinutes, traffic,
    ).hashCode()
}

/** 订阅的同步键:远程订阅按 URL,文件型按名称(来自共享笔记 00 的匹配规则)。 */
data class SyncKey(val kind: Kind, val value: String) {
    enum class Kind { URL, NAME }

    companion object {
        /**
         * 本机订阅的同步键:URL 型取订阅地址、FILE 型取名称(键规则的唯一实现)。
         * URL 型缺地址返回 null——该订阅不参与同步,由调用方跳过。
         */
        fun of(type: LocalType, url: String?, name: String): SyncKey? = when (type) {
            LocalType.URL -> url?.let { SyncKey(Kind.URL, it) }
            LocalType.FILE -> SyncKey(Kind.NAME, name)
        }

        /**
         * 云端条目(verge)的同步键:remote 取 URL、local 取名称(与 [of] 同一规则)。
         * merge/script 等增强条目或名称/地址缺失的残缺条目返回 null——不参与同步,
         * 原样随包保留。
         */
        fun of(item: VergeItem): SyncKey? = when (item.type) {
            "remote" -> item.url?.let { SyncKey(Kind.URL, it) }
            "local" -> item.name?.let { SyncKey(Kind.NAME, it) }
            else -> null
        }
    }
}

/**
 * 上次成功同步的快照:键 → 同步后两端一致的内容指纹。
 * 下次同步据此区分 新增/删除/修改;本票只定义数据形态,持久化由后续票完成。
 */
data class SyncSnapshot(val fingerprints: Map<SyncKey, String>)

/** 单条订阅的同步动作;冲突不在此列,见 [SyncPlan.conflicts]。 */
sealed class PlanAction {
    abstract val key: SyncKey
    // 展示用名称(导入/删云端取云端侧,推送/删本机取本机侧)
    abstract val name: String?

    /**
     * 导入:以云端为准写入本机。
     * [content] 直接取自云端备份包,离线导入,不重新联网下载;缺失时为 null。
     * [existing] 非空表示本机已有同键订阅:执行时就地改写该订阅(保留 uuid)而不是新建,
     * 避免同一订阅在列表里出现重复;双向与单向模式都适用。
     */
    class Import(
        override val key: SyncKey,
        override val name: String?,
        // type == URL 时的订阅地址
        val url: String?,
        // 云端条目完整元数据(updated/extra/option 等),供建本机订阅取值
        val cloudItem: VergeItem,
        val content: ByteArray?,
        val existing: LocalProfile? = null,
    ) : PlanAction() {
        override fun equals(other: Any?) = other is Import &&
            key == other.key && name == other.name && url == other.url &&
            cloudItem == other.cloudItem && content.contentEquals(other.content) &&
            existing == other.existing

        override fun hashCode() = listOf(key, name, url, cloudItem, existing).hashCode()
    }

    /** 推送:本机有云端没有(或冲突后用户选了本地);本机记录已并入 [SyncPlan.newCloudState]。 */
    data class Push(
        override val key: SyncKey,
        override val name: String?,
        val local: LocalProfile,
    ) : PlanAction()

    /** 删本机:快照里存在、云端已消失,删除传播到本机。 */
    data class DeleteLocal(
        override val key: SyncKey,
        override val name: String?,
    ) : PlanAction()

    /** 删云端:快照里存在、本机已消失,删除传播到云端(新云端状态中已移除该条目)。 */
    data class DeleteCloud(
        override val key: SyncKey,
        override val name: String?,
    ) : PlanAction()
}

/**
 * 单向同步中「将被覆盖」的一条订阅:两端都存在但内容不同,执行后取来源端版本,
 * 对端那次改动丢失。与 [Conflict] 互斥:单向模式以来源端为准,不产生冲突。
 */
data class Overwrite(
    val key: SyncKey,
    // 执行后保留的名称(来源端)
    val name: String?,
    // true = 保留本机版本覆盖云端;false = 保留云端版本覆盖本机
    val keptLocal: Boolean,
)

/** 冲突:上次同步后两端都改过同一条订阅,待用户选「用本地/用云端」。 */
data class Conflict(
    val key: SyncKey,
    val localName: String,
    val localUpdatedAtSeconds: Long,
    // 云端侧展示信息;verge 条目允许缺 name/updated
    val cloudName: String?,
    val cloudUpdatedAtSeconds: Long?,
)

/** 用户对冲突的选择。 */
enum class ConflictChoice { USE_LOCAL, USE_CLOUD }

/**
 * 一次 plan() 的产出:可执行动作、待选冲突、将被覆盖清单、同步后的新云端状态与新快照。
 *
 * [refusal] 非空表示本轮不可执行(来源端为空等保护性拒绝):此时没有任何动作,
 * 调用方直接把原因展示给用户,两端都不动。
 *
 * 存在冲突时,本计划不可直接执行:[newCloudState]/[newSnapshot] 按冲突全部
 * 「保留云端」试算,必须先经 [resolve] 按用户选择得出最终计划。
 */
class SyncPlan internal constructor(
    val mode: SyncMode,
    val actions: List<PlanAction>,
    val conflicts: List<Conflict>,
    val overwrites: List<Overwrite>,
    val refusal: String?,
    val newCloudState: VergeBackup,
    val newSnapshot: SyncSnapshot,
    private val recompute: (Map<SyncKey, ConflictChoice>) -> SyncPlan,
) {
    /** 是否需要在执行前向用户确认(有删除或有覆盖)。 */
    val needsConfirmation: Boolean
        get() = actions.any { it is PlanAction.DeleteLocal || it is PlanAction.DeleteCloud } ||
            overwrites.isNotEmpty()

    /** 按用户选择解析冲突,得出可执行计划;每个冲突都必须给出选择。 */
    fun resolve(choices: Map<SyncKey, ConflictChoice>): SyncPlan {
        val missing = conflicts.filter { it.key !in choices }
        require(missing.isEmpty()) { "冲突未给出选择: ${missing.map { it.key }}" }
        return recompute(choices)
    }
}
