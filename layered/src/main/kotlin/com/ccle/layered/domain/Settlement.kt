package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 정산 주(ADR-0004 §10.1). 마감 = 월요일 00:00 KST, 주 = [마감 − 7일, 마감), period_start = 그 주 월요일, period_end = period_start + 6일.
 * cutoffDate = 마감일(그 주 다음 월요일).
 */
class SettlementWeek(val cutoffDate: LocalDate) {
    /** 마감 시각. 대상 경계는 모두 이 시각 배타. */
    val cutoff: Instant = cutoffDate.atStartOfDay(KST).toInstant()
    val periodStart: LocalDate = cutoffDate.minusDays(7)
    val periodEnd: LocalDate = periodStart.plusDays(6)

    /** 지급 시각 = period_start + 9일 00:00 KST(그 주 다음 수요일). 정산 행을 만들 때 미리 쓴다. */
    val paidAt: Instant = periodStart.plusDays(9).atStartOfDay(KST).toInstant()

    companion object {
        /**
         * 정산할 주, 오래된 주부터: 마감 ≤ 현재이고 마감이 지금까지 만든 정산의 마지막 마감(max(period_start) + 7일)보다 뒤인 주.
         * 정산 행이 하나도 없으면(lastPeriodStart null) 현재 직전 마감 1주만.
         */
        fun pending(lastPeriodStart: LocalDate?, now: Instant): List<SettlementWeek> {
            val latest = LocalDate.ofInstant(now, KST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeks = mutableListOf<SettlementWeek>()
            var cutoff = lastPeriodStart?.plusDays(14) ?: latest
            while (!cutoff.isAfter(latest)) {
                weeks += SettlementWeek(cutoff)
                cutoff = cutoff.plusDays(7)
            }
            return weeks
        }
    }
}

/**
 * 정산 대상 항목 1개(§10.2): 그 주의 대상 조건을 만족한 항목의 현재 상태와 체결가, 카테고리 수수료(floor(체결가 × sale_fee_rate) — DB가 계산한 값).
 */
class SettlementTarget(val orderItemId: Long, val sellerId: Long, val itemStatus: String, val price: Long, val saleFee: Long) {

    /** 정산 항목 행의 값: CONFIRMED → INCLUDED(체결가, 수수료), DISPUTED → HELD(0, 0), REFUNDED_AFTER_CONFIRM → CLAWBACK(−체결가, −수수료). */
    fun line(): SettlementLine = when (itemStatus) {
        "CONFIRMED" -> SettlementLine(orderItemId, "INCLUDED", price, saleFee)
        "DISPUTED" -> SettlementLine(orderItemId, "HELD", 0L, 0L)
        else -> {
            check(itemStatus == "REFUNDED_AFTER_CONFIRM") { "정산 대상이 될 수 없는 항목 상태: $orderItemId ($itemStatus)" }
            SettlementLine(orderItemId, "CLAWBACK", -price, -saleFee)
        }
    }
}

/** 정산 항목 행 1개의 값(settlement_id를 정하기 전). */
class SettlementLine(val orderItemId: Long, val status: String, val amount: Long, val fee: Long)

/**
 * 판매자 1명의 한 주(§10.2): 정산 항목·붙일 패널티·carry_in(직전 정산의 음수 carry_out, 없으면 0).
 * 정산 행은 대상·미정산 패널티·음수 이월 중 하나라도 있는 판매자에게만 만든다(그래서 이 값이 있으면 행을 만든다).
 */
class SellerStatement(
    val sellerId: Long,
    val lines: List<SettlementLine>,
    val penalties: List<SellerPenalty>,
    val carryIn: Long,
) {
    /**
     * 정산 행: gross = Σamount, fee_total = Σfee, penalty_total = Σ패널티, net = gross − fee_total − penalty_total + carry_in,
     * payout = max(net, 0), carry_out = min(net, 0), payout > 0이면 PAID·paid_at = 그 주 지급 시각, 아니면 CARRIED·paid_at NULL.
     */
    fun settlement(week: SettlementWeek): Settlement {
        val gross = lines.fold(0L) { sum, l -> Math.addExact(sum, l.amount) }
        val feeTotal = lines.fold(0L) { sum, l -> Math.addExact(sum, l.fee) }
        val penaltyTotal = penalties.fold(0L) { sum, p -> Math.addExact(sum, p.amount) }
        val net = Math.addExact(Math.subtractExact(Math.subtractExact(gross, feeTotal), penaltyTotal), carryIn)
        val payout = maxOf(net, 0L)
        return Settlement(
            sellerId = sellerId,
            periodStart = week.periodStart,
            periodEnd = week.periodEnd,
            gross = gross,
            feeTotal = feeTotal,
            penaltyTotal = penaltyTotal,
            carryIn = carryIn,
            net = net,
            payout = payout,
            carryOut = minOf(net, 0L),
            status = if (payout > 0) "PAID" else "CARRIED",
            paidAt = if (payout > 0) week.paidAt else null,
        )
    }

    /** 저장한 정산 행(id 있음)에 딸린 항목 행들, 대상 항목 id 순. */
    fun items(settlement: Settlement): List<SettlementItem> = lines.map {
        SettlementItem(settlementId = settlement.id!!, orderItemId = it.orderItemId, amount = it.amount, fee = it.fee, status = it.status)
    }

    /** 패널티를 저장한 정산 행에 붙인다(settlement_id 갱신). */
    fun attachPenalties(settlement: Settlement) {
        penalties.forEach { it.attachTo(settlement) }
    }

    companion object {
        /**
         * 한 주의 판매자별 값, 판매자 id 오름차순. targets는 항목 id 순, penalties는 패널티 id 순이고 판매자별로 그 순서를 지킨다.
         * carries = 직전 정산의 carry_out이 음수인 판매자 → 그 값.
         */
        fun of(targets: List<SettlementTarget>, penalties: List<SellerPenalty>, carries: Map<Long, Long>): List<SellerStatement> {
            val lines = targets.groupBy({ it.sellerId }, { it.line() })
            val penaltiesBySeller = penalties.groupBy { it.sellerId }
            return (lines.keys + penaltiesBySeller.keys + carries.keys).sorted().map { sellerId ->
                SellerStatement(
                    sellerId = sellerId,
                    lines = lines[sellerId].orEmpty(),
                    penalties = penaltiesBySeller[sellerId].orEmpty(),
                    carryIn = carries[sellerId] ?: 0L,
                )
            }
        }
    }
}

/** 판매자 주간 정산 행(ADR-0004 §10). 만든 뒤 바꾸지 않는다. */
@Entity
@Table(name = "settlements")
class Settlement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var periodStart: LocalDate,
    var periodEnd: LocalDate,
    var gross: Long,
    var feeTotal: Long,
    var penaltyTotal: Long,
    var carryIn: Long,
    var net: Long,
    var payout: Long,
    var carryOut: Long,
    var status: String,
    var paidAt: Instant? = null,
)

/** 정산 항목 행(INCLUDED·HELD·CLAWBACK). 만든 뒤 바꾸지 않는다. */
@Entity
@Table(name = "settlement_items")
class SettlementItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var settlementId: Long,
    var orderItemId: Long,
    var amount: Long,
    var fee: Long,
    var status: String,
)

interface SettlementRepository {
    /** 지금까지 만든 정산의 max(period_start). 없으면 null. */
    fun lastPeriodStart(): LocalDate?

    /** 그 주의 대상 항목 행을 id 순으로 잠근다(§9.1 — order_items만). */
    fun lockTargets(cutoff: Instant)

    /** 잠근 뒤 새 문장으로 대상 조건을 다시 평가한 결과, 항목 id 순. */
    fun targets(cutoff: Instant): List<SettlementTarget>

    /** 판매자별 직전 정산(period_start가 가장 늦은 것)의 carry_out 중 음수인 것: 판매자 id → carry_out. */
    fun negativeCarries(): Map<Long, Long>

    fun add(settlement: Settlement)

    fun addItem(item: SettlementItem)

    /** 그 판매자의 정산, period_start 오름차순. */
    fun listOf(sellerId: Long): List<Settlement>

    /** 그 정산들의 항목, orderItemId 오름차순(같으면 id 순). */
    fun itemsOf(settlementIds: List<Long>): List<SettlementItem>
}
