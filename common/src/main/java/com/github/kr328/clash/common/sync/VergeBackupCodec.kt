package com.github.kr328.clash.common.sync

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * clash-verge-rev 备份包(zip:profiles.yaml 订阅索引 + profiles/ 下的 *.yaml 内容文件)编解码。
 *
 * - 解码:索引解析为 [VergeBackup] 领域模型;内容文件保留原始字节不做 YAML 解析。
 *   verge 的设置文件(verge.yaml/config.yaml/dns_config.yaml)被丢弃,不进入模型。
 * - 编码:重建索引 + 内容文件,输出 verge 可正常恢复的 android-backup 包;不含任何
 *   设置文件,未知条目与未知字段经各层 extraFields/extraFiles 原样带回。
 *
 * YAML 用 snakeyaml 的通用 Map/List 树读写:不建 schema,天然保留未知结构;
 * 选择它而不是 kaml,是为避免给 common 引入 kotlinx-serialization 编译器插件。
 *
 * 格式事实来源:clash-verge-rev v2.5.7(src-tauri/src/core/backup.rs、config/profiles.rs)。
 */
object VergeBackupCodec {
    private const val PROFILES_INDEX = "profiles.yaml"
    private const val PROFILES_DIR = "profiles/"
    private const val PROFILES_HEADER = "# Profiles Config for Clash Verge\n"

    // verge 的设置文件:解码时丢弃、编码时拒绝写入(设计要求:生成的包不含设置文件)
    private val SETTINGS_FILES = setOf("verge.yaml", "config.yaml", "dns_config.yaml")

    // 重复键直接失败,不静默取后者
    private val loaderOptions = LoaderOptions().apply { isAllowDuplicateKeys = false }

    private val dumperOptions = DumperOptions().apply {
        // 中文等非 ASCII 字符原样输出,不转义成 \uXXXX
        isAllowUnicode = true
    }

    /**
     * 备份包文件名,约定 android-backup-<时间戳>.zip;时间戳与 verge 一致取
     * [zone] 时区的本地时间,格式 yyyy-MM-dd_HH-mm-ss(verge 历史按文件名内
     * 时间戳排序,失败才回退服务器 last_modified)。
     */
    fun backupFileName(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        format.timeZone = zone
        return "android-backup-${format.format(Date(epochMillis))}.zip"
    }

    /** 解码备份包;结构非法时抛 [IllegalArgumentException] 快速失败。 */
    fun decode(zip: ByteArray): VergeBackup {
        var index: ByteArray? = null
        val contentFiles = LinkedHashMap<String, ByteArray>()
        val extraFiles = LinkedHashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name
                try {
                    if (entry.isDirectory || name.endsWith("/")) continue
                    val data = stream.readBytes()
                    when {
                        name == PROFILES_INDEX -> {
                            check(index == null) { "备份包内有重复的 $PROFILES_INDEX" }
                            index = data
                        }
                        name.startsWith(PROFILES_DIR) -> {
                            val file = name.removePrefix(PROFILES_DIR)
                            require(file.isNotEmpty() && '/' !in file) {
                                "备份包内 profiles/ 存在不支持的多级路径: $name"
                            }
                            check(file !in contentFiles) { "备份包内有重复的内容文件: $name" }
                            contentFiles[file] = data
                        }
                        name in SETTINGS_FILES -> Unit
                        else -> {
                            check(name !in extraFiles) { "备份包内有重复的文件: $name" }
                            extraFiles[name] = data
                        }
                    }
                } finally {
                    stream.closeEntry()
                }
            }
        }

        val indexData = index ?: throw IllegalArgumentException("备份包缺少 $PROFILES_INDEX")
        return parseIndex(indexData, contentFiles, extraFiles)
    }

    /**
     * 编码为备份包 zip 字节;只写 profiles.yaml、contentFiles 与 extraFiles,
     * 从不写 verge 设置文件。
     */
    fun encode(backup: VergeBackup): ByteArray {
        require(backup.extraFiles.keys.none { it == PROFILES_INDEX }) {
            "extraFiles 不能包含 $PROFILES_INDEX"
        }
        val illegal = backup.extraFiles.keys.intersect(SETTINGS_FILES)
        require(illegal.isEmpty()) { "extraFiles 不能包含 verge 设置文件: $illegal" }

        val root = LinkedHashMap<String, Any?>()
        backup.current?.let { root["current"] = it }
        root["items"] = backup.items.map(::itemToMap)
        root.putAll(backup.extraFields)

        val body = Yaml(dumperOptions).dump(root)
        val index = (PROFILES_HEADER + body).toByteArray(StandardCharsets.UTF_8)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PROFILES_INDEX))
            zip.write(index)
            zip.closeEntry()

            for ((name, data) in backup.contentFiles) {
                require(name.isNotEmpty() && '/' !in name) {
                    "内容文件名必须是单级文件名: $name"
                }
                zip.putNextEntry(ZipEntry(PROFILES_DIR + name))
                zip.write(data)
                zip.closeEntry()
            }

            for ((name, data) in backup.extraFiles) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---- profiles.yaml 解析 ----

    private fun parseIndex(
        data: ByteArray,
        contentFiles: Map<String, ByteArray>,
        extraFiles: Map<String, ByteArray>,
    ): VergeBackup {
        val loaded: Any? = Yaml(loaderOptions).load(String(data, StandardCharsets.UTF_8))
        if (loaded == null) {
            return VergeBackup(null, emptyList(), contentFiles, extraFiles, emptyMap())
        }
        require(loaded is Map<*, *>) { "profiles.yaml 顶层必须是映射" }

        var current: String? = null
        var items: List<VergeItem> = emptyList()
        val extraFields = LinkedHashMap<String, Any?>()

        for ((key, value) in loaded) {
            when (key) {
                "current" -> current = value as? String
                "items" -> items = parseItems(value)
                else -> extraFields[key.toString()] = value
            }
        }

        return VergeBackup(current, items, contentFiles, extraFiles, extraFields)
    }

    private fun parseItems(value: Any?): List<VergeItem> {
        if (value == null) return emptyList()
        require(value is List<*>) { "profiles.yaml 的 items 必须是列表" }
        return value.map { item ->
            require(item is Map<*, *>) { "profiles.yaml 的 items 元素必须是映射" }
            parseItem(item)
        }
    }

    private fun parseItem(map: Map<*, *>): VergeItem {
        var uid: String? = null
        var type: String? = null
        var name: String? = null
        var file: String? = null
        var desc: String? = null
        var url: String? = null
        var home: String? = null
        var updated: Long? = null
        var extra: VergeTraffic? = null
        var option: VergeOption? = null
        val extraFields = LinkedHashMap<String, Any?>()

        for ((key, value) in map) {
            when (key) {
                "uid" -> uid = value as? String
                "type" -> type = value as? String
                "name" -> name = value as? String
                "file" -> file = value as? String
                "desc" -> desc = value as? String
                "url" -> url = value as? String
                "home" -> home = value as? String
                "updated" -> updated = (value as? Number)?.toLong()
                "extra" -> extra = parseTraffic(value)
                "option" -> option = parseOption(value)
                else -> extraFields[key.toString()] = value
            }
        }

        return VergeItem(uid, type, name, file, desc, url, home, updated, extra, option, extraFields)
    }

    private fun parseTraffic(value: Any?): VergeTraffic? {
        if (value == null) return null
        require(value is Map<*, *>) { "profiles.yaml 的 extra 必须是映射" }

        var upload: Long? = null
        var download: Long? = null
        var total: Long? = null
        var expire: Long? = null
        val extraFields = LinkedHashMap<String, Any?>()

        for ((key, v) in value) {
            when (key) {
                "upload" -> upload = (v as? Number)?.toLong()
                "download" -> download = (v as? Number)?.toLong()
                "total" -> total = (v as? Number)?.toLong()
                "expire" -> expire = (v as? Number)?.toLong()
                else -> extraFields[key.toString()] = v
            }
        }

        return VergeTraffic(upload, download, total, expire, extraFields)
    }

    private fun parseOption(value: Any?): VergeOption? {
        if (value == null) return null
        require(value is Map<*, *>) { "profiles.yaml 的 option 必须是映射" }

        var userAgent: String? = null
        var withProxy: Boolean? = null
        var selfProxy: Boolean? = null
        var updateInterval: Long? = null
        var timeoutSeconds: Long? = null
        var dangerAcceptInvalidCerts: Boolean? = null
        var allowAutoUpdate: Boolean? = null
        var merge: String? = null
        var script: String? = null
        var rules: String? = null
        var proxies: String? = null
        var groups: String? = null
        val extraFields = LinkedHashMap<String, Any?>()

        for ((key, v) in value) {
            when (key) {
                "user_agent" -> userAgent = v as? String
                "with_proxy" -> withProxy = v as? Boolean
                "self_proxy" -> selfProxy = v as? Boolean
                "update_interval" -> updateInterval = (v as? Number)?.toLong()
                "timeout_seconds" -> timeoutSeconds = (v as? Number)?.toLong()
                "danger_accept_invalid_certs" -> dangerAcceptInvalidCerts = v as? Boolean
                "allow_auto_update" -> allowAutoUpdate = v as? Boolean
                "merge" -> merge = v as? String
                "script" -> script = v as? String
                "rules" -> rules = v as? String
                "proxies" -> proxies = v as? String
                "groups" -> groups = v as? String
                else -> extraFields[key.toString()] = v
            }
        }

        return VergeOption(
            userAgent, withProxy, selfProxy, updateInterval, timeoutSeconds,
            dangerAcceptInvalidCerts, allowAutoUpdate, merge, script, rules, proxies, groups,
            extraFields,
        )
    }

    // ---- profiles.yaml 编码 ----

    private fun itemToMap(item: VergeItem): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        item.uid?.let { map["uid"] = it }
        item.type?.let { map["type"] = it }
        item.name?.let { map["name"] = it }
        item.file?.let { map["file"] = it }
        item.desc?.let { map["desc"] = it }
        item.url?.let { map["url"] = it }
        item.home?.let { map["home"] = it }
        item.updated?.let { map["updated"] = it }
        item.extra?.let { map["extra"] = trafficToMap(it) }
        item.option?.let { map["option"] = optionToMap(it) }
        map.putAll(item.extraFields)
        return map
    }

    private fun trafficToMap(traffic: VergeTraffic): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        traffic.upload?.let { map["upload"] = it }
        traffic.download?.let { map["download"] = it }
        traffic.total?.let { map["total"] = it }
        traffic.expire?.let { map["expire"] = it }
        map.putAll(traffic.extraFields)
        return map
    }

    private fun optionToMap(option: VergeOption): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        option.userAgent?.let { map["user_agent"] = it }
        option.withProxy?.let { map["with_proxy"] = it }
        option.selfProxy?.let { map["self_proxy"] = it }
        option.updateInterval?.let { map["update_interval"] = it }
        option.timeoutSeconds?.let { map["timeout_seconds"] = it }
        option.dangerAcceptInvalidCerts?.let { map["danger_accept_invalid_certs"] = it }
        option.allowAutoUpdate?.let { map["allow_auto_update"] = it }
        option.merge?.let { map["merge"] = it }
        option.script?.let { map["script"] = it }
        option.rules?.let { map["rules"] = it }
        option.proxies?.let { map["proxies"] = it }
        option.groups?.let { map["groups"] = it }
        map.putAll(option.extraFields)
        return map
    }
}
