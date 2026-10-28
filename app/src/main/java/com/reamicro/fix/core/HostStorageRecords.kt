package com.reamicro.fix.core

/** 按已核实的构造器签名选择布局，不把版本号当作 ABI。 */
object HostStorageRecords {
    private val longType = Long::class.javaPrimitiveType!!
    private val stringType = String::class.java
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val sizesFirst = arrayOf<Class<*>>(longType, longType, stringType, stringType,
        stringType, stringType, stringType, stringType)

    fun baiduAuth(type: Class<*>, legacy: Array<out Any?>): Any = construct(
        type, sizesFirst, { HostStorageArguments.b2BaiduAuth(legacy) },
        arrayOf(stringType, stringType, stringType, stringType, longType, longType, stringType, stringType),
        legacy,
    )

    fun userInfo(type: Class<*>, legacy: Array<out Any?>): Any = construct(
        type, sizesFirst, { HostStorageArguments.b2CloudUserInfo(legacy) },
        arrayOf(stringType, stringType, stringType, stringType, stringType, stringType, longType, longType),
        legacy,
    )

    fun folder(type: Class<*>, name: String, path: String, empty: Boolean, updatedAt: Long): Any = construct(
        type, arrayOf(longType, stringType, stringType, booleanType),
        { arrayOf<Any?>(updatedAt, name, path, empty) },
        arrayOf(stringType, stringType, booleanType, longType),
        arrayOf<Any?>(name, path, empty, updatedAt),
    )

    private fun construct(
        type: Class<*>,
        currentTypes: Array<Class<*>>,
        currentArgs: () -> Array<Any?>,
        legacyTypes: Array<Class<*>>,
        legacyArgs: Array<out Any?>,
    ): Any {
        val current = try {
            type.getDeclaredConstructor(*currentTypes)
        } catch (_: NoSuchMethodException) {
            null
        }
        // 构造器本身的业务异常必须暴露，不能误判成布局不兼容后再构造一次。
        return if (current != null) current.newInstance(*currentArgs())
        else type.getDeclaredConstructor(*legacyTypes).newInstance(*legacyArgs)
    }
}
