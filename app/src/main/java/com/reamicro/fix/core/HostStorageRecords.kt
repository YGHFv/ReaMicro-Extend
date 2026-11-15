package com.reamicro.fix.core

/** All constructor slots and fields come from the storage.records query. */
object HostStorageRecords {
    private fun create(key: String, type: Class<*>, values: Array<out Any?>): Any {
        val contract = requireNotNull(HostStorageBindings.records) { "Storage records contract unavailable" }
        check(type == contract.type(key)) { "Storage record owner mismatch: $key" }
        val properties = HostStorageQueries.RECORDS.getValue(key).second.split(' ')
        require(values.size == properties.size)
        return contract.record(key, properties.zip(values.toList()).toMap())
    }
    fun baiduAuth(type: Class<*>, values: Array<out Any?>): Any = create("auth", type, values)
    fun userInfo(type: Class<*>, values: Array<out Any?>): Any = create("info", type, values)
    fun localAuth(type: Class<*>, dir: Class<*>, values: Array<out Any?>): Any {
        check(dir == requireNotNull(HostStorageBindings.records).type("dir"))
        return create("localAuth", type, values)
    }
    fun dir(type: Class<*>, id: String, name: String): Any = create("dir", type, arrayOf(id, name))
    fun folder(type: Class<*>, name: String, path: String, empty: Boolean, updatedAt: Long): Any =
        create("folder", type, arrayOf<Any?>(name, path, empty, updatedAt))
    fun book(type: Class<*>, flow: Class<*>, values: Array<out Any?>): Any {
        val contract = requireNotNull(HostStorageBindings.records)
        check(flow.isAssignableFrom(contract.type("stateFlow")))
        return create("book", type, values)
    }
}
