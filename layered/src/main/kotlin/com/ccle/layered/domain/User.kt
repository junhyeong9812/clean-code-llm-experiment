package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// 도메인 객체 = JPA 엔티티(1단계가 쓰는 테이블의 행). 스키마는 docker/schema/tables.sql 이 정본이고 앱은 validate만 한다.
// 연관(@ManyToOne 등) 없이 외래 키는 id 값 그대로 둔다. 상태·종류 같은 enum 값은 DB와 같은 문자열로 둔다.
// 상태 전이·금액 계산·검증은 이 클래스들의 메서드로 둔다. 상태 필드는 private set이라 메서드로만 바뀐다
// (@Id가 필드에 붙으므로 JPA는 필드로 읽고 쓴다).

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var email: String,
    var name: String,
    var grade: String,
    purchaseRestrictedUntil: Instant? = null,
    saleRestrictedUntil: Instant? = null,
    var createdAt: Instant,
) {
    var purchaseRestrictedUntil: Instant? = purchaseRestrictedUntil
        private set

    var saleRestrictedUntil: Instant? = saleRestrictedUntil
        private set

    /** 새 입찰 제한(§3.1): 구매는 purchase_restricted_until > 현재이면 403, 판매는 sale_restricted_until. 이미 걸린 입찰의 체결은 막지 않는다. */
    fun requireCanBid(side: String, now: Instant) {
        if (side == "BUY" && purchaseRestrictedUntil?.isAfter(now) == true) {
            throw MarketException(Failure.PURCHASE_RESTRICTED, "구매 제한 중: ${purchaseRestrictedUntil}까지")
        }
        if (side == "SELL" && saleRestrictedUntil?.isAfter(now) == true) {
            throw MarketException(Failure.SALE_RESTRICTED, "판매 제한 중: ${saleRestrictedUntil}까지")
        }
    }

    /**
     * 구매 경고에 따른 제한(§7): 최근 30일 안(created_at > 현재 − 30일)이면서 마지막 제한이 걸린 시각(purchase_restricted_until − 7일)
     * 뒤의 경고가 3건 이상이면 purchase_restricted_until = 현재 + 7일. 제한을 건 경고는 다음 계산에서 빠지므로 횟수는 다시 센다.
     * recentWarningTimes = created_at > warningWindowStart(now)인 경고의 created_at(방금 쓴 경고 포함).
     */
    fun restrictIfWarned(recentWarningTimes: List<Instant>, now: Instant) {
        val lastRestrictedAt = purchaseRestrictedUntil?.minus(PURCHASE_RESTRICTION)
        val counted = recentWarningTimes.count { lastRestrictedAt == null || it.isAfter(lastRestrictedAt) }
        if (counted >= WARNINGS_TO_RESTRICT) purchaseRestrictedUntil = now.plus(PURCHASE_RESTRICTION)
    }

    /**
     * 판매자 패널티에 따른 판매 제한(ADR-0004 §6): 최근 30일 안(created_at > 현재 − 30일)이면서 마지막 제한이 걸린 시각
     * (sale_restricted_until − 30일) 뒤의 패널티가 3건 이상이면 sale_restricted_until = 현재 + 30일. 구매 경고와 같은 해석이다.
     * recentPenaltyTimes = created_at > penaltyWindowStart(now)인 패널티의 created_at(방금 쓴 패널티 포함).
     */
    fun restrictIfPenalized(recentPenaltyTimes: List<Instant>, now: Instant) {
        val lastRestrictedAt = saleRestrictedUntil?.minus(SALE_RESTRICTION)
        val counted = recentPenaltyTimes.count { lastRestrictedAt == null || it.isAfter(lastRestrictedAt) }
        if (counted >= PENALTIES_TO_RESTRICT) saleRestrictedUntil = now.plus(SALE_RESTRICTION)
    }

    companion object {
        private val WARNING_WINDOW: Duration = Duration.ofDays(30)
        private val PURCHASE_RESTRICTION: Duration = Duration.ofDays(7)
        private const val WARNINGS_TO_RESTRICT = 3
        private val PENALTY_WINDOW: Duration = Duration.ofDays(30)
        private val SALE_RESTRICTION: Duration = Duration.ofDays(30)
        private const val PENALTIES_TO_RESTRICT = 3

        /** 제한 판정에 세는 경고의 시작(이 시각 초과). */
        fun warningWindowStart(now: Instant): Instant = now.minus(WARNING_WINDOW)

        /** 판매 제한 판정에 세는 패널티의 시작(이 시각 초과). */
        fun penaltyWindowStart(now: Instant): Instant = now.minus(PENALTY_WINDOW)

        /** 등급 적립률(ADR-0002 §5.2, 천분율): BRONZE 1% · SILVER 1.5% · GOLD 2% · VIP 3%. 등급은 주문 생성 날짜의 등급(ADR-0004 §8.1). */
        fun earnRatePermille(grade: String): Long = when (grade) {
            "BRONZE" -> 10L
            "SILVER" -> 15L
            "GOLD" -> 20L
            "VIP" -> 30L
            else -> throw IllegalStateException("알 수 없는 등급: $grade")
        }
    }
}

interface UserRepository {
    fun find(id: Long): User?

    /** 사용자 행 잠금(`SELECT … FOR UPDATE`). 결제·포인트 잔액을 바꾸는 처리는 구매자 잠금 뒤에 한다(같은 구매자의 결제를 줄 세운다). */
    fun lock(id: Long)

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 DB의 현재 값으로 다시 읽는다. */
    fun reload(user: User)

    /**
     * 그 날짜의 등급(ADR-0004 §8.1): `effective_from ≤ date`인 최신 등급 이력 → 이력이 모두 그 날짜 뒤면 BRONZE(첫 이력 이전 등급)
     * → 이력이 없으면 users.grade.
     */
    fun gradeOn(userId: Long, date: LocalDate): String
}
