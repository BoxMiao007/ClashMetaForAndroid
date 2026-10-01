package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

/**
 * WebDAV 同步配置(地址/账号/密码),存独立 prefs 文件,跨进程读写。
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
}
