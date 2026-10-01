package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterCloudBackupBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.remote.CloudBackupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云端备份包历史列表。每行展示文件名与「平台 · 时间」;恢复/删除各自独立入口,
 * 点击经回调转成 Design 的 Request,由 Activity 的事件循环处理。
 */
class CloudBackupAdapter(
    private val context: Context,
    private val onRestoreClicked: (CloudBackupInfo) -> Unit,
    private val onDeleteClicked: (CloudBackupInfo) -> Unit,
) : RecyclerView.Adapter<CloudBackupAdapter.Holder>() {
    class Holder(val binding: AdapterCloudBackupBinding) : RecyclerView.ViewHolder(binding.root)

    var backups: List<CloudBackupInfo> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterCloudBackupBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = backups[position]
        val binding = holder.binding

        if (current === binding.backup)
            return

        binding.backup = current
        binding.title = current.name
        binding.subtitle = describe(current)
        binding.restore = View.OnClickListener { onRestoreClicked(current) }
        binding.delete = View.OnClickListener { onDeleteClicked(current) }
    }

    override fun getItemCount(): Int {
        return backups.size
    }

    /**
     * 副标题「平台 · 时间」。平台取文件名首个 `-` 前段:android 为本应用产,
     * windows/macos/linux 为 verge 产;时间优先文件名内时间戳,解析失败回退服务器
     * last_modified(与 clash-verge-rev 的历史展示一致)。
     */
    private fun describe(backup: CloudBackupInfo): String {
        val platform = backup.name.substringBefore('-', "").takeIf { it.isNotEmpty() }?.let {
            when (it.lowercase()) {
                "android" -> context.getString(R.string.cloud_backup_platform_cmfa)
                "windows" -> context.getString(R.string.cloud_backup_platform_verge, "Windows")
                "macos" -> context.getString(R.string.cloud_backup_platform_verge, "macOS")
                "linux" -> context.getString(R.string.cloud_backup_platform_verge, "Linux")
                else -> context.getString(R.string.cloud_backup_platform_verge, it)
            }
        }
        val time = (backup.fileNameTime ?: backup.lastModified)?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }

        return listOfNotNull(platform, time).joinToString(" · ")
    }
}
