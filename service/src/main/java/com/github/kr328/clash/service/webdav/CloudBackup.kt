package com.github.kr328.clash.service.webdav

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 云端备份包条目(云端历史列表的一行)。
 *
 * @property name 文件名(不含路径),如 linux-backup-2026-10-01_18-58-24.zip
 * @property fileTimeMillis 文件名内时间戳(epoch 毫秒);文件名不含合法时间戳时为 null
 * @property lastModifiedMillis 服务器 last_modified(epoch 毫秒);缺失或无法解析时为 null
 */
data class CloudBackup(
    val name: String,
    val fileTimeMillis: Long?,
    val lastModifiedMillis: Long?,
) {
    /**
     * 排序与展示用时间:优先文件名内时间戳,解析失败回退服务器 last_modified,
     * 与 clash-verge-rev 的历史排序心智一致。
     */
    val effectiveTimeMillis: Long?
        get() = fileTimeMillis ?: lastModifiedMillis

    companion object {
        // 与 clash-verge-rev 一致:从文件名任意位置提取 YYYY-MM-DD_HH-mm-ss
        private val FILE_NAME_TIME_REGEX = Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""")

        /**
         * 从文件名解析时间戳。取值非法(如 2026-13-45_99-99-99)时返回 null,
         * 由调用方按设计回退服务器 last_modified,不视为错误。
         */
        fun parseFileNameTime(name: String): Long? {
            val value = FILE_NAME_TIME_REGEX.find(name)?.value ?: return null
            val format = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
                isLenient = false
            }
            return try {
                format.parse(value)?.time
            } catch (e: ParseException) {
                null
            }
        }

        /**
         * 解析服务器 last_modified(HTTP 日期,RFC 1123 格式)。无法解析时返回 null,
         * 不使整个列表失败。
         */
        internal fun parseServerDate(value: String): Long? {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return try {
                format.parse(value.trim())?.time
            } catch (e: ParseException) {
                null
            }
        }
    }
}
