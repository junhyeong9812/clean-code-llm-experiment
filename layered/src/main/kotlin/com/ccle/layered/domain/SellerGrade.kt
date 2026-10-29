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

// A2 판매자 등급·수수료 리베이트(ADR-0007 §5). 구매자 등급(User.kt·GradePeriod)과 이름·문턱·기간이 다른 별개 규칙이다(§5.1).

/** 판매자 등급 산정 기준월(§5.1·§5.2): 현재 시각이 속한 달의 1일(KST). 기준 금액 구간 = period_start ∈ [D − 3개월, D). */
class SellerGradeMonth private constructor(val effectiveFrom: LocalDate) {
    val basisFrom: LocalDate get() = effectiveFrom.minusMonths(3)

    companion object {
        fun of(now: Instant): SellerGradeMonth = SellerGradeMonth(LocalDate.ofInstant(now, KST).withDayOfMonth(1))
    }
}

/**
 * 지난달 M(현재 달 1일 − 1개월)과 그 달의 닫힘(§5.3·§6.4). 닫힘 = M에 속하는 마지막 월요일 L에 대해
 * 지금까지 만든 정산의 마지막 마감(max(period_start) + 7일, ADR-0004 §10.1)이 L + 7일 이상.
 */
class ClosedMonth private constructor(val month: LocalDate) {
    val next: LocalDate get() = month.plusMonths(1)

    companion object {
        fun lastMonthOf(now: Instant): LocalDate = LocalDate.ofInstant(now, KST).withDayOfMonth(1).minusMonths(1)

        /** 지난달이 닫혔으면 그 달, 아니면 null. lastPeriodStart = 지금까지 만든 정산의 max(period_start)(없으면 null). */
        fun of(now: Instant, lastPeriodStart: LocalDate?): ClosedMonth? {
            val m = lastMonthOf(now)
            val lastMonday = m.plusMonths(1).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (lastPeriodStart == null) return null
            return if (lastPeriodStart.plusDays(7).isBefore(lastMonday.plusDays(7))) null else ClosedMonth(m)
        }
    }
}

@Entity
@Table(name = "seller_grades")
class SellerGrade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var grade: String,
    var effectiveFrom: LocalDate,
    var basisAmount: Long,
    var restricted: Boolean,
    var createdAt: Instant,
) {
    companion object {
        /** 등급(§5.1, 기준 금액): MASTER ≥ 8,000,000 · PRO ≥ 3,000,000 · PLUS ≥ 1,000,000 · 그 밖 STANDARD. */
        fun gradeOf(basis: Long): String = when {
            basis >= 8_000_000 -> "MASTER"
            basis >= 3_000_000 -> "PRO"
            basis >= 1_000_000 -> "PLUS"
            else -> "STANDARD"
        }

        /**
         * 그 달의 등급 행(§5.2). 판매 제한 중(저장된 sale_restricted_until > 현재)이면 restricted·STANDARD(§5.1).
         * 매달 쓴다(지난달과 같아도), created_at = 현재.
         */
        fun assess(seller: User, month: SellerGradeMonth, basis: Long, now: Instant): SellerGrade {
            val restricted = seller.saleRestrictedUntil?.isAfter(now) == true
            return SellerGrade(
                sellerId = seller.id!!,
                grade = if (restricted) "STANDARD" else gradeOf(basis),
                effectiveFrom = month.effectiveFrom,
                basisAmount = basis,
                restricted = restricted,
                createdAt = now,
            )
        }
    }
}

@Entity
@Table(name = "fee_rebates")
class FeeRebate(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var month: LocalDate,
    var grade: String,
    var feeBase: Long,
    var ratePermille: Int,
    var amount: Long,
    var status: String,
    var paidAt: Instant? = null,
    var createdAt: Instant,
) {
    companion object {
        /** 리베이트율(§5.3, 천분율): STANDARD 0 · PLUS 50 · PRO 100 · MASTER 150. */
        fun permilleOf(grade: String): Int = when (grade) {
            "STANDARD" -> 0
            "PLUS" -> 50
            "PRO" -> 100
            "MASTER" -> 150
            else -> throw IllegalStateException("알 수 없는 판매자 등급: $grade")
        }

        /**
         * 달 M의 리베이트 1행(§5.3): 율 = 그 달 등급 행의 율, amount = floor(max(fee_base, 0) × 율 / 1000).
         * amount > 0이면 PAID(paid_at = 현재), 0이면 ZERO(paid_at NULL).
         */
        fun settle(grade: SellerGrade, month: LocalDate, feeBase: Long, now: Instant): FeeRebate {
            val rate = permilleOf(grade.grade)
            val amount = mulDiv(maxOf(feeBase, 0L), rate.toLong(), 1000)
            return FeeRebate(
                sellerId = grade.sellerId,
                month = month,
                grade = grade.grade,
                feeBase = feeBase,
                ratePermille = rate,
                amount = amount,
                status = if (amount > 0) "PAID" else "ZERO",
                paidAt = if (amount > 0) now else null,
                createdAt = now,
            )
        }
    }
}

interface SellerGradeRepository {
    fun add(grade: SellerGrade)

    /** 그 판매자의 effective_from = 그 달 행 수. */
    fun countAt(sellerId: Long, effectiveFrom: LocalDate): Long

    /** 그 판매자의 effective_from = 그 달 행(없으면 null). */
    fun findAt(sellerId: Long, effectiveFrom: LocalDate): SellerGrade?

    /** 기준 금액(§5.1): 그 판매자의 INCLUDED settlement_items amount 합, 소속 정산 period_start ∈ [from, to). */
    fun basisAmount(sellerId: Long, from: LocalDate, to: LocalDate): Long

    /** 그 판매자의 등급 행, effective_from 오름차순. */
    fun listOf(sellerId: Long): List<SellerGrade>
}

interface FeeRebateRepository {
    fun add(rebate: FeeRebate)

    fun countAt(sellerId: Long, month: LocalDate): Long

    /** fee_base(§5.3): 그 판매자 정산 중 period_start ∈ [month, next)의 fee_total 합. */
    fun feeBase(sellerId: Long, month: LocalDate, next: LocalDate): Long

    /** 그 판매자의 리베이트, month 오름차순. */
    fun listOf(sellerId: Long): List<FeeRebate>
}
