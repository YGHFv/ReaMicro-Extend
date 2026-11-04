package com.reamicro.fix.core

internal interface CatalogSql {
    fun execute(sql: String, args: List<Any>)
    fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>>
}

internal data class CatalogStoredRow(val id: Long, val values: List<Any>)
internal data class CatalogRowChanges(
    val removed: List<Long>,
    val changed: List<CatalogStoredRow>,
    val added: List<List<Any>>,
) {
    val count: Int get() = removed.size + changed.size + added.size
}

internal object CatalogTableSync {
    enum class Table(val sqlName: String, val columns: List<String>, val numeric: Set<Int>) {
        ITEM_REF("book_item_ref", listOf("book_id", "index", "spine_index", "id_ref", "href", "properties", "size"), setOf(0, 1, 2, 6)),
        CHAPTER("book_chapter", listOf("book_id", "play_order", "title", "href", "anchor", "epubcfi", "parent_order"), setOf(0, 1, 6)),
    }

    fun plan(existing: List<CatalogStoredRow>, expected: List<List<Any>>): CatalogRowChanges {
        require(expected.isNotEmpty()) { "Refusing an empty catalog" }
        require(expected.map { it[1] }.distinct().size == expected.size) { "Duplicate catalog order" }
        val remaining = existing.groupBy { it.values[1] }.toMutableMap()
        val removed = mutableListOf<Long>()
        val changed = mutableListOf<CatalogStoredRow>()
        val added = mutableListOf<List<Any>>()
        for (values in expected) {
            val matches = remaining.remove(values[1]).orEmpty()
            val old = matches.firstOrNull()
            removed.addAll(matches.drop(1).map { it.id })
            if (old == null) added.add(values)
            else if (old.values != values) changed.add(CatalogStoredRow(old.id, values))
        }
        removed.addAll(remaining.values.flatten().map { it.id })
        return CatalogRowChanges(removed, changed, added)
    }

    /** 两张表由调用者放进同一个 Room 事务；不再清空目录或更换未变条目的主键。 */
    fun sync(sql: CatalogSql, table: Table, bookId: Long, expected: List<List<Any>>): Int {
        require(bookId > 0 && expected.all { it.size == table.columns.size && it[0] == bookId })
        val columns = table.columns.joinToString(",") { "`$it`" }
        val numeric = table.numeric.mapTo(mutableSetOf(0)) { it + 1 }
        val existing = sql.query("SELECT id,$columns FROM `${table.sqlName}` WHERE book_id=?", listOf(bookId), numeric)
            .map { CatalogStoredRow(it[0] as Long, it.drop(1)) }
        val changes = plan(existing, expected)
        changes.removed.forEach { id ->
            sql.execute("DELETE FROM `${table.sqlName}` WHERE id=? AND book_id=?", listOf(id, bookId))
        }
        val assignments = table.columns.joinToString(",") { "`$it`=?" }
        changes.changed.forEach { row ->
            sql.execute("UPDATE `${table.sqlName}` SET $assignments WHERE id=? AND book_id=?", row.values + listOf(row.id, bookId))
        }
        val slots = table.columns.joinToString(",") { "?" }
        changes.added.forEach { row ->
            sql.execute("INSERT INTO `${table.sqlName}` ($columns) VALUES ($slots)", row)
        }
        return changes.count
    }
}
