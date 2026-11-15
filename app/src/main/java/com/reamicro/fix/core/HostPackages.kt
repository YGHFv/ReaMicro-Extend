package com.reamicro.fix.core

internal object HostPackages {
    val all: Set<String> = setOf(
        "app.zhendong.reamicro",
        "app.zhendong.reamicro.fix",
    )

    fun contains(packageName: String?): Boolean = packageName in all
}
