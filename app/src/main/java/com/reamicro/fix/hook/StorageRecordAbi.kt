package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageRecords
import com.reamicro.fix.core.HostReducerGuard
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.webdav.*

internal fun WebDavDriveHook.newBaiduAuth(vararg legacy: Any?): Any =
    HostStorageRecords.baiduAuth(cls(AUTH_BAIDU_CLASS), legacy)

internal fun WebDavDriveHook.newCloudUserInfo(vararg legacy: Any?): Any =
    HostStorageRecords.userInfo(cls(CLOUD_USER_INFO_CLASS), legacy)

internal fun WebDavDriveHook.newStorageFolder(name: String, path: String, empty: Boolean, updatedAt: Long): Any =
    HostStorageRecords.folder(cls(CLOUD_FOLDER_CLASS), name, path, empty, updatedAt)

internal fun WebDavDriveHook.storageStateWithTree(state: Any, tree: List<Any>): Any =
    HostReducerGuard.reduce(state, cls(CLOUD_STORAGE_UI_STATE_CLASS), { error ->
        HookRuntime.logAlways("$LOG_PREFIX storage reducer retained original state: ${error.stackTraceToString()}")
    }) {
        val records = requireNotNull(com.reamicro.fix.core.HostStorageBindings.records)
        records.record("state", mapOf("userInfo" to records.field("state.userInfo").get(state), "treeList" to tree))
    }
internal fun WebDavDriveHook.newNativeWorkHandle(id: String, state: Any): Any {
    return com.reamicro.fix.core.HostWorkBindings.requireCurrent().handle(id, state)
}
