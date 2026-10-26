package com.prgms.backend.global.metrics

/**
 * 현재 요청 스레드에서 실행된 SQL 개수를 종류별로 센다.
 */
object QueryCountHolder {
    private val counts = ThreadLocal<MutableMap<QueryType, Int>>()

    fun start() {
        counts.set(mutableMapOf())
    }

    fun increment(sql: String) {
        val current = counts.get() ?: return
        val type = QueryType.of(sql)
        current[type] = (current[type] ?: 0) + 1
    }

    fun finish(): Map<QueryType, Int> {
        val result = counts.get().orEmpty()
        counts.remove()
        return result
    }
}

enum class QueryType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    OTHER,
    ;

    companion object {
        fun of(sql: String): QueryType {
            val head = sql.trimStart().take(6).uppercase()
            return entries.firstOrNull { it != OTHER && head.startsWith(it.name) } ?: OTHER
        }
    }
}
