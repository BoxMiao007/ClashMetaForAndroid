package com.github.kr328.clash.common.sync

import java.util.Calendar

/**
 * 备份包文件名内时间戳的解析(唯一实现):SyncPlanner 的清理选择与
 * service/webdav 的云端历史列表共用,防止两套格式判定各自漂移。
 *
 * 文件名约定 `<平台>-backup-<yyyy-MM-dd_HH-mm-ss>.zip`(android-backup-、
 * linux-backup- 等);时间戳取最后一个 "-backup-" 之后、去掉可选 .zip 后缀的
 * 剩余部分,须恰好是 6 个数字段(- 与 _ 分隔),任一段非数字即失败返回 null。
 * 文件名生成见 [VergeBackupCodec.backupFileName]。
 */
object BackupFileName {
    private const val BACKUP_MARKER = "-backup-"
    private const val ZIP_SUFFIX = ".zip"

    /** 解析文件名内时间戳为秒级 Unix 时间;解析失败返回 null,由调用方自行回退。 */
    fun parseTimestampSeconds(filename: String): Long? {
        val base = filename.removeSuffix(ZIP_SUFFIX).substringAfterLast(BACKUP_MARKER)
        val parts = base.split('_', '-')
        if (parts.size != 6) return null
        val numbers = parts.map { it.toLongOrNull() }
        if (numbers.any { it == null }) return null
        val (year, month, day, hour, minute) = numbers.take(5).map { it!!.toInt() }
        val second = numbers[5]!!.toInt()
        // 与 verge 一致用本地时区:文件名时间戳是生成端的本地时间
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis / 1000
    }
}
