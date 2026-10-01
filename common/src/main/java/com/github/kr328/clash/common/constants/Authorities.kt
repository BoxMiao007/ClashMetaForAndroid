package com.github.kr328.clash.common.constants

import com.github.kr328.clash.common.util.packageName

object Authorities {
    val STATUS_PROVIDER = "$packageName.status"
    val SETTINGS_PROVIDER = "$packageName.settings"
    val SYNC_SETTINGS_PROVIDER = "$packageName.sync_settings"
    val FILES_PROVIDER = "$packageName.files"
}