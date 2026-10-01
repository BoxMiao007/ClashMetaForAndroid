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
}
