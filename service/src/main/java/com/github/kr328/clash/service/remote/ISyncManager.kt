package com.github.kr328.clash.service.remote

import com.github.kr328.kaidl.BinderInterface

/**
 * 手动同步引擎的跨进程接口(实现在 service 进程,见 service/sync/SyncManager)。
 *
 * 两段式流程:
 * 1. [start]:读取本机订阅与云端状态,生成同步计划。无冲突且无删除时直接执行完
 *    并返回 [SyncOutcome.PHASE_DONE];有冲突返回 [SyncOutcome.PHASE_CONFLICTS],
 *    计划含删除动作返回 [SyncOutcome.PHASE_CONFIRM_DELETIONS],两种情况都把
 *    待续计划留在引擎里,等待 [resolve]。
 * 2. [resolve]:按用户对冲突/删除确认的选择继续执行,返回 [SyncOutcome.PHASE_DONE]。
 *
 * 同一时刻只允许一轮同步:并发请求以「正在进行中」失败答复。
 */
@BinderInterface
interface ISyncManager {
    /** 发起一轮同步。 */
    suspend fun start(): SyncOutcome

    /**
     * 就 [start] 留下的待续计划继续执行;每个冲突都必须给出选择。
     * 删除确认场景传空列表即可。
     */
    suspend fun resolve(choices: List<SyncChoice>): SyncOutcome

    /**
     * 列出云端同步目录的全部备份包,时间倒序(文件名内时间戳优先,回退服务器 last_modified)。
     * 失败抛异常,由调用方提示。
     */
    suspend fun listCloudBackups(): List<CloudBackupInfo>

    /**
     * 用指定的云端备份包整体替换本机订阅:下载解码 → 清空现有 imported 订阅 →
     * 按包内订阅条目离线导入(含 providers)→ 快照重建为「本机 = 该包」的基线。
     * 恢复不是同步,不经过合并计划;与同步共用同一 busy 互斥,并发以「正在进行中」失败答复。
     */
    suspend fun restoreBackup(name: String): SyncOutcome

    /** 删除单个云端备份包,不影响其他包;失败抛异常,由调用方提示。 */
    suspend fun deleteCloudBackup(name: String)
}
