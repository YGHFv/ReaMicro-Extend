package com.reamicro.fix.association.provider

import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostMembers
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Public local extension contract. Missing semantic queries return null; no old short-name fallback. */
class ExternalHostSymbols internal constructor(private val loader: ClassLoader) {
    private val members = HostMembers()
    val revision: Int get() = 1
    fun type(logicalName: String): Class<*>? = descriptor("class:$logicalName")?.let { members.describedClass(it, loader) }
    fun method(logicalOwner: String, logicalName: String): Method? = descriptor("method:$logicalOwner#$logicalName")
        ?.let { members.describedMethod(it, loader) }
    fun field(logicalOwner: String, logicalName: String): Field? = descriptor("field:$logicalOwner#$logicalName")
        ?.let { members.describedField(it, loader) }
    fun descriptor(logicalKey: String): String? = HostAutoScanner.installedDescriptor(logicalKey)
}
