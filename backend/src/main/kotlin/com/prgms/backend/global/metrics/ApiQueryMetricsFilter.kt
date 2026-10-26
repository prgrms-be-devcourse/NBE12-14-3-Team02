package com.prgms.backend.global.metrics

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

/**
 * API 요청 한 건당 실행된 SQL 개수를 엔드포인트(메서드 + 경로 패턴)별로 기록한다.
 *
 * - api.db.queries: 요청당 총 쿼리 수 분포 (평균·최대로 N+1 탐지)
 * - api.db.statements: 쿼리 종류(select/insert/update/delete)별 누적 실행 횟수
 */
@Component
class ApiQueryMetricsFilter(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        QueryCountHolder.start()
        try {
            filterChain.doFilter(request, response)
        } finally {
            record(request, QueryCountHolder.finish())
        }
    }

    private fun record(
        request: HttpServletRequest,
        counts: Map<QueryType, Int>,
    ) {
        // 웹 슬라이스 테스트처럼 MeterRegistry가 없는 환경에서는 기록하지 않는다.
        val meterRegistry = meterRegistryProvider.ifAvailable ?: return

        // 패턴이 없으면 경로 변수가 그대로 태그가 되어 시계열이 폭증하므로 UNKNOWN으로 묶는다.
        val uri = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "UNKNOWN"
        val method = request.method

        meterRegistry
            .summary("api.db.queries", "method", method, "uri", uri)
            .record(counts.values.sum().toDouble())

        counts.forEach { (type, count) ->
            meterRegistry
                .counter("api.db.statements", "method", method, "uri", uri, "type", type.name.lowercase())
                .increment(count.toDouble())
        }
    }

    private companion object {
        const val API_PREFIX = "/api/"
    }
}
