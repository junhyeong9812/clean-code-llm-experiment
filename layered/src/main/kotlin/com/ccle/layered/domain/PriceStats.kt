package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// A8 시세·통계(ADR-0007 §11.1·§11.2).

/** price-stats의 대상 날짜(§11.1): d = 오늘(KST) − 1일, 그 날의 KST 구간 [from, to). */
class PriceStatsDay private constructor(val date: LocalDate) {
    val from: Instant = date.atStartOfDay(KST).toInstant()
    val to: Instant = date.plusDays(1).atStartOfDay(KST).toInstant()

    companion object {
        fun of(now: Instant): PriceStatsDay = PriceStatsDay(LocalDate.ofInstant(now, KST).minusDays(1))
    }
}

/** 시세 조회 기간(§11.2): from·to 필수(양끝 포함), from ≤ to, 일수(to − from + 1) ≤ 92. 아니면 400. */
class PriceStatsRange private constructor(val from: LocalDate, val to: LocalDate) {
    companion object {
        private const val MAX_DAYS = 92

        fun of(from: String?, to: String?): PriceStatsRange {
            val fromDate = dateInput("from", from) ?: invalidRequest("from이 필요하다")
            val toDate = dateInput("to", to) ?: invalidRequest("to가 필요하다")
            if (fromDate.isAfter(toDate)) invalidRequest("from이 to보다 늦다: $fromDate > $toDate")
            if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_DAYS) invalidRequest("기간은 92일 이하: $fromDate ~ $toDate")
            return PriceStatsRange(fromDate, toDate)
        }
    }
}

/** 일별 시세 행(§3 price_stats_daily). UNIQUE(product_size_id, stat_date). */
@Entity
@Table(name = "price_stats_daily")
class PriceStatDaily(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var productSizeId: Long,
    var statDate: LocalDate,
    var tradeCount: Int,
    var minPrice: Long,
    var maxPrice: Long,
    var medianPrice: Long,
    var createdAt: Instant,
) {
    companion object {
        /**
         * 그 날 PAID 체결 가격(오름차순)의 집계(§11.1): 건수·최저·최고·중앙값 = floor((n − 1) / 2)번째(0부터 — 짝수면 아래 중앙값).
         * 가격이 없으면 null(쓰지 않는다).
         */
        fun of(productSizeId: Long, day: PriceStatsDay, ascendingPrices: List<Long>, now: Instant): PriceStatDaily? {
            if (ascendingPrices.isEmpty()) return null
            return PriceStatDaily(
                productSizeId = productSizeId,
                statDate = day.date,
                tradeCount = ascendingPrices.size,
                minPrice = ascendingPrices.first(),
                maxPrice = ascendingPrices.last(),
                medianPrice = ascendingPrices[(ascendingPrices.size - 1) / 2],
                createdAt = now,
            )
        }
    }
}

interface PriceStatRepository {
    fun add(stat: PriceStatDaily)

    /** 그 사이즈의 PAID 체결 중 matched_at ∈ [from, to)의 가격, 오름차순. */
    fun paidPricesBetween(productSizeId: Long, from: Instant, to: Instant): List<Long>

    /** 그 사이즈의 일별 시세, stat_date ∈ [from, to](양끝 포함) 오름차순. */
    fun listOf(productSizeId: Long, range: PriceStatsRange): List<PriceStatDaily>

    /** 그 사이즈의 PAID 체결 최근 limit건, matched_at 내림차순·같으면 id 내림차순. */
    fun recentPaidTrades(productSizeId: Long, limit: Int): List<Trade>
}
