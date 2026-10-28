package com.reamicro.fix.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HostStorageArgumentsTest {
    @Test
    fun baiduAuthKeepsTokenSortNicknameAndDirectories() {
        val before = arrayOf<Any?>("token", "time", "1", "nickname", 11L, 29L, "/library", "/backup")
        assertArrayEquals(
            arrayOf<Any?>(11L, 29L, "token", "time", "1", "nickname", "/library", "/backup"),
            HostStorageArguments.b2BaiduAuth(before),
        )
        assertArrayEquals(arrayOf<Any?>("token", "time", "1", "nickname", 11L, 29L, "/library", "/backup"), before)
    }

    @Test
    fun cloudInfoKeepsNullableLevelAndIndependentSizes() {
        val before = arrayOf<Any?>("id", "name", "avatar", "driveId", "identity", null, 13L, 31L)
        assertArrayEquals(
            arrayOf<Any?>(13L, 31L, "id", "name", "avatar", "driveId", "identity", null),
            HostStorageArguments.b2CloudUserInfo(before),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAlreadyReorderedAuth() {
        HostStorageArguments.b2BaiduAuth(arrayOf<Any?>(1L, 2L, "token", "time", "1", "nick", "/", "/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownCloudRecordShape() {
        HostStorageArguments.b2CloudUserInfo(arrayOf<Any?>("id", "name", "avatar", "driveId", "identity", null, 13, 31L))
    }
}
