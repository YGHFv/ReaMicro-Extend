package com.reamicro.fix.core

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Test

class HostStorageRecordsTest {
    class CurrentUser(val total: Long, val used: Long, val id: String, val name: String,
        val avatar: String, val drive: String, val identity: String, val level: String?)
    class LegacyUser(val id: String, val name: String, val avatar: String, val drive: String,
        val identity: String, val level: String?, val total: Long, val used: Long)
    class CurrentAuth(val total: Long, val used: Long, val token: String, val time: String,
        val sort: String, val name: String, val library: String, val backup: String)
    class LegacyAuth(val token: String, val time: String, val sort: String, val name: String,
        val total: Long, val used: Long, val library: String, val backup: String)
    class CurrentFolder(val updated: Long, val name: String, val path: String, val empty: Boolean)
    class LegacyFolder(val name: String, val path: String, val empty: Boolean, val updated: Long)
    class FailingFolder(updated: Long, name: String, path: String, empty: Boolean) {
        init { error("constructor failure") }
    }

    @Test fun currentAndLegacyUserPreserveNullableLevelAndSizes() {
        val args = arrayOf<Any?>("id", "name", "avatar", "drive", "identity", null, 17L, 23L)
        val current = HostStorageRecords.userInfo(CurrentUser::class.java, args) as CurrentUser
        val legacy = HostStorageRecords.userInfo(LegacyUser::class.java, args) as LegacyUser
        assertEquals(17L, current.total)
        assertEquals(23L, current.used)
        assertEquals(null, current.level)
        assertEquals(listOf(legacy.id, legacy.name, legacy.avatar, legacy.drive, legacy.identity, legacy.level),
            listOf(current.id, current.name, current.avatar, current.drive, current.identity, current.level))
        assertEquals(legacy.total, current.total)
        assertEquals(legacy.used, current.used)
    }

    @Test fun currentAndLegacyAuthKeepDirectoriesDistinct() {
        val args = arrayOf<Any?>("token", "time", "sort", "name", 17L, 23L, "/library", "/backup")
        val current = HostStorageRecords.baiduAuth(CurrentAuth::class.java, args) as CurrentAuth
        val legacy = HostStorageRecords.baiduAuth(LegacyAuth::class.java, args) as LegacyAuth
        assertEquals(17L, current.total)
        assertEquals(23L, current.used)
        assertEquals(listOf(legacy.token, legacy.time, legacy.sort, legacy.name, legacy.library, legacy.backup),
            listOf(current.token, current.time, current.sort, current.name, current.library, current.backup))
    }

    @Test fun currentAndLegacyFoldersKeepTimestamp() {
        val current = HostStorageRecords.folder(CurrentFolder::class.java, "name", "/path", true, 97L) as CurrentFolder
        val legacy = HostStorageRecords.folder(LegacyFolder::class.java, "name", "/path", true, 97L) as LegacyFolder
        assertEquals(listOf(legacy.name, legacy.path, legacy.empty, legacy.updated),
            listOf(current.name, current.path, current.empty, current.updated))
        assertEquals(97L, current.updated)
    }

    @Test(expected = NoSuchMethodException::class)
    fun unknownShapeFailsClosed() {
        HostStorageRecords.folder(String::class.java, "name", "/path", true, 97L)
    }

    @Test(expected = InvocationTargetException::class)
    fun constructorFailureIsNotRetriedAsLegacy() {
        HostStorageRecords.folder(FailingFolder::class.java, "name", "/path", true, 97L)
    }
}
