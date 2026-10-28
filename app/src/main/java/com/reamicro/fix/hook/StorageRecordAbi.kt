package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageRecords
import com.reamicro.fix.core.HostReducerGuard
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.hook.webdav.*

internal fun WebDavDriveHook.newBaiduAuth(vararg legacy: Any?): Any =
    HostStorageRecords.baiduAuth(cls(AUTH_BAIDU_CLASS), legacy)

internal fun WebDavDriveHook.newCloudUserInfo(vararg legacy: Any?): Any =
    HostStorageRecords.userInfo(cls(CLOUD_USER_INFO_CLASS), legacy)

internal fun WebDavDriveHook.newStorageFolder(name: String, path: String, empty: Boolean, updatedAt: Long): Any =
    HostStorageRecords.folder(cls(CLOUD_FOLDER_CLASS), name, path, empty, updatedAt)

internal fun WebDavDriveHook.storageStateWithTree(state: Any, tree: List<Any>): Any =
    HostReducerGuard.reduce(state, cls(CLOUD_STORAGE_UI_STATE_CLASS), { error ->
        XposedBridge.logAlways("$LOG_PREFIX storage reducer retained original state: ${error.stackTraceToString()}")
    }) {
        val constructor = try {
            cls(CLOUD_STORAGE_UI_STATE_CLASS).getDeclaredConstructor(cls(CLOUD_USER_INFO_CLASS), List::class.java)
        } catch (_: NoSuchMethodException) {
            null
        }
        if (constructor != null) {
            constructor.newInstance(state.javaClass.hostField("userInfo").get(state), tree)
        } else {
            val info = state.javaClass.hostMethodsNamed("getUserInfo").single { it.parameterCount == 0 }.invoke(state)
            cls(CLOUD_STORAGE_UI_STATE_CLASS).hostMethodsNamed("copy").single { it.parameterCount == 2 }
                .invoke(state, info, tree)
        }
    }

internal fun WebDavDriveHook.newNativeWorkHandle(id: String, state: Any): Any {
    val constructor = cls(WORK_HANDLE_CLASS).declaredConstructors.single {
        it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isInstance(state)
    }
    return constructor.newInstance(id, state)
}
