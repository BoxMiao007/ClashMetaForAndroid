package com.github.kr328.clash.service.remote

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/** [SyncOutcome.conflicts] 与 [SyncChoice] 里 SyncKey 的种类,对齐 common/sync 的 SyncKey.Kind。 */
const val SYNC_KEY_KIND_URL = 0
const val SYNC_KEY_KIND_NAME = 1

/** 单条订阅的冲突信息(计划器 Conflict 的跨进程形态)。 */
@Serializable
data class SyncConflict(
    val keyKind: Int,
    val keyValue: String,
    val localName: String,
    val cloudName: String?,
    // 秒级 Unix 时间戳
    val localUpdatedAt: Long,
    val cloudUpdatedAt: Long?,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SyncConflict> {
        override fun createFromParcel(parcel: Parcel): SyncConflict {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<SyncConflict?> = arrayOfNulls(size)
    }
}

/** 用户对一条冲突的选择(true = 用本地,false = 用云端)。 */
@Serializable
data class SyncChoice(
    val keyKind: Int,
    val keyValue: String,
    val useLocal: Boolean,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SyncChoice> {
        override fun createFromParcel(parcel: Parcel): SyncChoice {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<SyncChoice?> = arrayOfNulls(size)
    }
}

/**
 * 一次同步请求的答复。
 *
 * [phase] 决定后续动作:
 * - [PHASE_DONE]:本轮已结束,看 [succeeded] 与 [message](失败原因,可直接展示);
 * - [PHASE_CONFLICTS]:存在待选冲突,用户逐条选择后经 ISyncManager.resolve 继续执行;
 * - [PHASE_CONFIRM_DELETIONS]:计划包含删除动作,需用户确认后经 ISyncManager.resolve 继续。
 *
 * 各名称列表在 PHASE_DONE 时为实际发生的结果,在两个待续阶段为将要发生的内容。
 */
@Serializable
data class SyncOutcome(
    val phase: Int,
    val succeeded: Boolean = false,
    val message: String? = null,
    val imported: List<String> = emptyList(),
    val pushed: List<String> = emptyList(),
    val deletedLocal: List<String> = emptyList(),
    val deletedCloud: List<String> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SyncOutcome> {
        const val PHASE_DONE = 0
        const val PHASE_CONFLICTS = 1
        const val PHASE_CONFIRM_DELETIONS = 2

        override fun createFromParcel(parcel: Parcel): SyncOutcome {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<SyncOutcome?> = arrayOfNulls(size)
    }
}
