package com.github.kr328.clash.service.sync

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.sync.SyncKey
import com.github.kr328.clash.common.sync.SyncSnapshot
import com.github.kr328.clash.service.remote.toKeyKindInt
import com.github.kr328.clash.service.remote.toSyncKeyKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 「上次同步快照」持久化(filesDir/sync/snapshot.json,org.json,无新增依赖)。
 *
 * 快照只在整轮同步成功后由 SyncManager 写入;失败/取消时旧文件原样保留。
 * 读取失败(文件损坏)按无快照处理——后续同步把差异按新增补齐而非删除,方向安全。
 */
class SnapshotStore(context: Context) {
    private val directory = context.filesDir.resolve("sync")
    private val file = directory.resolve("snapshot.json")

    suspend fun load(): SyncSnapshot? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null

        try {
            val keys = JSONObject(file.readText()).getJSONArray("keys")
            val fingerprints = LinkedHashMap<SyncKey, String>()

            for (i in 0 until keys.length()) {
                val item = keys.getJSONObject(i)
                val kind = item.getInt("kind").toSyncKeyKind()

                fingerprints[SyncKey(kind, item.getString("value"))] = item.getString("fingerprint")
            }

            SyncSnapshot(fingerprints)
        } catch (e: Exception) {
            Log.w("Load sync snapshot failed: $e", e)

            null
        }
    }

    suspend fun store(snapshot: SyncSnapshot): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()

        val keys = JSONArray()
        for ((key, fingerprint) in snapshot.fingerprints) {
            keys.put(
                JSONObject()
                    .put("kind", key.kind.toKeyKindInt())
                    .put("value", key.value)
                    .put("fingerprint", fingerprint)
            )
        }

        // 先写临时文件再原子替换,避免中途失败留下半份快照
        val tmp = directory.resolve("snapshot.json.tmp")
        tmp.writeText(JSONObject().put("keys", keys).toString())

        if (!tmp.renameTo(file)) {
            tmp.delete()

            throw IOException("写入同步快照失败: $file")
        }
    }
}
