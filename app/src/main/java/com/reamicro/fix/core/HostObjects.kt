package com.reamicro.fix.core

/**
 * 按 ClassLoader 缓存宿主 `object` 单例访问器。
 *
 * 单例的 `INSTANCE` 字段与静态方法形态在进程内不会变化，缓存一次即可；
 * 同时避免每次解析 CFI / 测量文本都重新反射。
 */
object HostObjects {
    private val cache = HashMap<Pair<ClassLoader, String>, HostObject>()

    @JvmStatic
    fun get(classLoader: ClassLoader, logicalName: String): HostObject =
        synchronized(cache) {
            cache.getOrPut(classLoader to logicalName) { HostObject.of(classLoader, logicalName) }
        }

    /** 单例不可用（宿主结构变化）时返回 null，调用方按缺失能力降级。 */
    @JvmStatic
    fun getOrNull(classLoader: ClassLoader, logicalName: String): HostObject? =
        runCatching { get(classLoader, logicalName) }.getOrNull()
}
