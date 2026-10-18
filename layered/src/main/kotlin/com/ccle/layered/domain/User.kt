package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

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

    /** 등급 적립률(ADR-0002 §5.2, 천분율): BRONZE 1% · SILVER 1.5% · GOLD 2% · VIP 3%. 결제 요청 시각의 등급(1단계 전제, ADR-0003 §10). */
    fun earnRatePermille(): Long = when (grade) {
        "BRONZE" -> 10L
        "SILVER" -> 15L
        "GOLD" -> 20L
        "VIP" -> 30L
        else -> throw IllegalStateException("알 수 없는 등급: $grade")
    }
}

interface UserRepository {
    fun find(id: Long): User?

    /** 사용자 행 잠금(`SELECT … FOR UPDATE`). 결제·포인트 잔액을 바꾸는 처리는 구매자 잠금 뒤에 한다(같은 구매자의 결제를 줄 세운다). */
    fun lock(id: Long)
}
