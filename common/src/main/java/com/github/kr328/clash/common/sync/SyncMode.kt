package com.github.kr328.clash.common.sync

/**
 * 同步方向(模式):决定本轮同步以哪一端为准,以及是否删除对端独有的条目。
 *
 * 双向是既有语义,保持 v1 的全部行为;四个单向模式「以来源端为准、不产生冲突」,
 * 差别只在方向(云端→本机 / 本机→云端)与是否删除(覆盖 / 只增)。
 * 只增模式是「两端口径互不影响」的实现:只补来源端的新增与更新,从不删对端任何条目。
 *
 * 存档值用枚举名(见 [ofStorage]):新增模式只能追加,不能改名或调序,否则会改变旧存档含义。
 */
enum class SyncMode {
    /** 双向同步:并集合并 + 删除传播 + 冲突待用户选择(既有行为)。 */
    BIDIRECTIONAL,

    /** 云端→本机(覆盖):执行后本机完全等于云端状态,本机独有条目被删除。 */
    CLOUD_TO_LOCAL_MIRROR,

    /** 云端→本机(只增):只把云端新增/更新导入本机,本机独有条目保留。 */
    CLOUD_TO_LOCAL_ADD,

    /** 本机→云端(覆盖):执行后云端完全等于本机,云端独有的订阅条目被删除。 */
    LOCAL_TO_CLOUD_MIRROR,

    /** 本机→云端(只增):只把本机新增/更新推送云端,云端独有条目保留。 */
    LOCAL_TO_CLOUD_ADD,
    ;

    /** 是否单向模式:单向不看上次同步快照做比对,也不会产生冲突。 */
    val oneWay: Boolean
        get() = this != BIDIRECTIONAL

    /** 来源端是否本机;双向模式下无意义。 */
    val sourceIsLocal: Boolean
        get() = this == LOCAL_TO_CLOUD_MIRROR || this == LOCAL_TO_CLOUD_ADD

    /** 是否删除对端独有条目(仅覆盖模式)。 */
    val deletesCounterpart: Boolean
        get() = this == CLOUD_TO_LOCAL_MIRROR || this == LOCAL_TO_CLOUD_MIRROR

    companion object {
        /** 存档值 → 模式:未知值(旧版本降级、手改偏好)回退双向,行为安全可预期。 */
        fun ofStorage(value: String?): SyncMode =
            entries.firstOrNull { it.name == value } ?: BIDIRECTIONAL
    }
}
