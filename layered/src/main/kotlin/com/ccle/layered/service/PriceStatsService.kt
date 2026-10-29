package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.PriceStatDaily
import com.ccle.layered.domain.PriceStatRepository
import com.ccle.layered.domain.PriceStatsDay
import com.ccle.layered.domain.PriceStatsRange
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** A8 시세 조회(ADR-0007 §11.2). 헤더 불필요. */
@Service
class PriceStatsService(private val sizes: ProductSizeRepository, private val stats: PriceStatRepository) {

    /** 일별 시세: 400(기간) → 없는 사이즈 404. date 오름차순. */
    fun priceStats(productSizeId: Long, from: String?, to: String?): List<PriceStatDaily> {
        val range = PriceStatsRange.of(from, to)
        if (!sizes.exists(productSizeId)) notFound("없는 사이즈: $productSizeId")
        return stats.listOf(productSizeId, range)
    }

    /** 최근 체결: 그 사이즈의 PAID 체결 최근 20건. */
    fun recentTrades(productSizeId: Long): List<Trade> {
        if (!sizes.exists(productSizeId)) notFound("없는 사이즈: $productSizeId")
        return stats.recentPaidTrades(productSizeId, RECENT_TRADES)
    }

    private companion object {
        const val RECENT_TRADES = 20
    }
}

/**
 * price-stats(ADR-0007 §11.1): 어제(KST) PAID 체결이 있고 (사이즈, 어제) 행이 없는 사이즈(product_sizes id 순). 잠금 없음.
 * 사이즈 1개 = 트랜잭션 하나, 그 날 가격을 읽어 1행. processed = 쓴 행 수.
 */
@Component
class PriceStatsBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val stats: PriceStatRepository,
) {
    fun run(now: Instant): BatchResult {
        val day = PriceStatsDay.of(now)
        return targets.forEachIsolated("price-stats", BatchTarget.PRICE_STATS_SIZES, now) { sizeId ->
            tx.execute {
                val stat = PriceStatDaily.of(sizeId, day, stats.paidPricesBetween(sizeId, day.from, day.to), now)
                    ?: return@execute false
                stats.add(stat)
                true
            }!!
        }
    }
}
