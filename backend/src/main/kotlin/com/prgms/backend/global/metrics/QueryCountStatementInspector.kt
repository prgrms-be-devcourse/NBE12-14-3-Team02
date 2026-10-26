package com.prgms.backend.global.metrics

import org.hibernate.resource.jdbc.spi.StatementInspector

class QueryCountStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        QueryCountHolder.increment(sql)
        return sql
    }
}
