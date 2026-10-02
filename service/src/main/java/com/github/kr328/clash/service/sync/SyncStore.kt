package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

/**
 * WebDAV 同步配置(地址/账号/密码与自动触发开关),存独立 prefs 文件,跨进程读写。
 *
 * url 为空视为未配置同步。
 */
class SyncStore(context: Context) {
    private val store = Store(
        SyncPreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var webdavUrl: String by store.string(
        key = "webdav_url",
        defaultValue = ""
    )

    var webdavUsername: String by store.string(
        key = "webdav_username",
        defaultValue = ""
    )

    var webdavPassword: String by store.string(
        key = "webdav_password",
        defaultValue = ""
    )

    // 自动触发(共享笔记 00 定稿:两个开关默认关;间隔分钟数,预设 30/60/360/720/1440,可自定义)

    var startupSyncEnabled: Boolean by store.boolean(
        key = "startup_sync_enabled",
        defaultValue = false
    )

    var periodicSyncEnabled: Boolean by store.boolean(
        key = "periodic_sync_enabled",
        defaultValue = false
    )

    var periodicSyncIntervalMinutes: Int by store.int(
        key = "periodic_sync_interval_minutes",
        defaultValue = 60
    )
}
