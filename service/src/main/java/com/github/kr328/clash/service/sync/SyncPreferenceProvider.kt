package com.github.kr328.clash.service.sync

import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.service.BaseService
import com.github.kr328.clash.service.TunService
import rikka.preference.MultiProcessPreference
import rikka.preference.PreferenceProvider

/**
 * WebDAV 同步凭证的跨进程偏好,独立 prefs 文件 [FILE_NAME]。
 *
 * 不与 PreferenceProvider 共用 service.xml:凭证需要整体排除出 Android 自动备份
 * (full_backup_content),排除只能按文件粒度声明,独立文件才不必动既有包含条目。
 * 读写分发与 PreferenceProvider 一致:引擎所在的 service 进程直连 SharedPreferences,
 * app 进程经 MultiProcessPreference 走本 provider(声明在 :background 进程)。
 */
class SyncPreferenceProvider : PreferenceProvider() {
    override fun onCreatePreference(context: Context): SharedPreferences {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val FILE_NAME = "sync"

        fun createSharedPreferencesFromContext(context: Context): SharedPreferences {
            return when (context) {
                is BaseService, is TunService ->
                    context.getSharedPreferences(
                        FILE_NAME,
                        Context.MODE_PRIVATE
                    )
                else ->
                    MultiProcessPreference(
                        context,
                        Authorities.SYNC_SETTINGS_PROVIDER
                    )
            }
        }
    }
}
