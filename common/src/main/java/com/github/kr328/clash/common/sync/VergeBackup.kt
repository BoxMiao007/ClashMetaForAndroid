package com.github.kr328.clash.common.sync

/**
 * verge 备份包描述的订阅集合,纯领域模型,不依赖 service 模块的 Profile。
 *
 * 已知字段建模为类型化属性;其余数据——verge 的增强文件条目(merge/script/rules/
 * proxies/groups 及其 option 引用)、selected 等未建模字段、任何未知字段——统一放进
 * 各层 [extraFields] / [VergeBackup.extraFiles],保证解析→编码往返后原样保留
 * (verge 重存会丢弃未知字段,所以推送前必须保住它们,见 ADR-0001)。
 *
 * 字段事实来源:clash-verge-rev v2.5.7 `config/profiles.rs` 的 IProfiles / PrfItem。
 */
data class VergeBackup(
    // profiles.yaml 的 current(当前激活订阅的 uid;激活状态不参与同步,仅随包保留)
    val current: String?,
    // profiles.yaml 的 items,包含 remote/local 订阅与 merge/script/rules/proxies/groups 增强条目
    val items: List<VergeItem>,
    // 订阅内容文件,键为 zip 内 profiles/ 下的单级文件名(与 VergeItem.file 对应),内容为原始字节
    val contentFiles: Map<String, ByteArray>,
    // zip 根部除设置文件(verge.yaml/config.yaml/dns_config.yaml)与索引外的其他未知文件
    val extraFiles: Map<String, ByteArray>,
    // profiles.yaml 顶层未知字段,原样保留
    val extraFields: Map<String, Any?>,
)

data class VergeItem(
    val uid: String?,
    // remote | local | merge | script | rules | proxies | groups
    val type: String?,
    val name: String?,
    // 相对 profiles/ 的单级文件名,惯例 <uid>.yaml
    val file: String?,
    val desc: String?,
    // remote 订阅地址
    val url: String?,
    // 订阅主页(profile-web-page-url 响应头)
    val home: String?,
    // 秒级 Unix 时间戳
    val updated: Long?,
    // 流量信息(profiles.yaml 的 extra 字段,来自 subscription-userinfo 响应头)
    val extra: VergeTraffic?,
    val option: VergeOption?,
    // 未知字段与未建模字段(selected 节点选择等)原样保留
    val extraFields: Map<String, Any?>,
)

data class VergeTraffic(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    // 秒级 Unix 时间戳,0 表示不限期
    val expire: Long?,
    // 未知字段原样保留
    val extraFields: Map<String, Any?>,
)

data class VergeOption(
    val userAgent: String?,
    val withProxy: Boolean?,
    val selfProxy: Boolean?,
    // 自动更新间隔,分钟
    val updateInterval: Long?,
    val timeoutSeconds: Long?,
    val dangerAcceptInvalidCerts: Boolean?,
    val allowAutoUpdate: Boolean?,
    // 增强文件条目的 uid 引用
    val merge: String?,
    val script: String?,
    val rules: String?,
    val proxies: String?,
    val groups: String?,
    // 未知字段原样保留
    val extraFields: Map<String, Any?>,
)
