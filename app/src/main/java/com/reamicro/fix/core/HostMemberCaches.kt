package com.reamicro.fix.core

import com.reamicro.fix.reload.ModuleResources
import java.util.WeakHashMap

/** 只弱引用查询期间创建的缓存；长期绑定和共享缓存统一随模块 generation 退场。 */
internal object HostMemberCaches {
    private val instances = WeakHashMap<HostMembers, Unit>()
    private var retired = false

    init {
        ModuleResources.cleanup {
            val caches = synchronized(instances) {
                retired = true
                instances.keys.toList().also { instances.clear() }
            }
            caches.forEach(HostMembers::retire)
        }
    }

    fun track(members: HostMembers) {
        val accepted = synchronized(instances) {
            if (retired) false else {
                instances[members] = Unit
                true
            }
        }
        if (!accepted) members.retire()
    }
}
