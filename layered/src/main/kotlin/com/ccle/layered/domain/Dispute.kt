package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 분쟁 판정(ADR-0004 §4.4). 이름이 판정 뒤 disputes.status 값이다. */
enum class DisputeDecision {
    ACCEPTED,
    REJECTED,
    ;

    companion object {
        /** 요청의 accept(true = 인정, false = 기각). 없으면 400. */
        fun of(accept: Boolean?): DisputeDecision {
            if (accept == null) invalidRequest("accept가 필요하다")
            return if (accept) ACCEPTED else REJECTED
        }
    }
}

@Entity
@Table(name = "disputes")
class Dispute(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    status: String,
    var openedAt: Instant,
    resolvedAt: Instant? = null,
) {
    var status: String = status
        private set

    var resolvedAt: Instant? = resolvedAt
        private set

    /** OPEN만 판정한다(아니면 409 DISPUTE_NOT_OPEN). 주문·항목을 잠근 뒤 다시 읽고 부른다. */
    fun requireOpen() {
        if (status != "OPEN") throw MarketException(Failure.DISPUTE_NOT_OPEN, "OPEN이 아닌 분쟁: $id ($status)")
    }

    /** 판정 반영: ACCEPTED|REJECTED, resolved_at = 현재. 판정 처리의 마지막 변경이다(인정이면 환불 뒤). */
    fun resolve(decision: DisputeDecision, now: Instant) {
        check(status == "OPEN") { "OPEN이 아닌 분쟁 $id 을 판정하려 했다: $status" }
        status = decision.name
        resolvedAt = now
    }

    companion object {
        /** 분쟁 제기(§4.3): OPEN, opened_at = 현재, resolved_at NULL. */
        fun open(orderItemId: Long, now: Instant) = Dispute(orderItemId = orderItemId, status = "OPEN", openedAt = now)
    }
}

interface DisputeRepository {
    fun find(id: Long): Dispute?

    fun add(dispute: Dispute)

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 잠근 뒤 DB의 현재 값으로 다시 읽는다. */
    fun reload(dispute: Dispute)
}

@Entity
@Table(name = "seller_penalties")
class SellerPenalty(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var orderItemId: Long,
    var amount: Long,
    var settlementId: Long? = null,
    var createdAt: Instant,
) {
    companion object {
        /**
         * 판매자 귀책 패널티 1행(ADR-0004 §6): amount = floor(체결가 × 10%), settlement_id NULL, created_at = 현재.
         * 체결가 < 10원이면 0원이 되어 DB 제약에 걸린다(계약 밖 — 확인하지 않는다).
         */
        fun issue(sellerId: Long, item: OrderItem, now: Instant) =
            SellerPenalty(sellerId = sellerId, orderItemId = item.id!!, amount = mulDiv(item.price, 10, 100), createdAt = now)
    }
}

interface SellerPenaltyRepository {
    fun add(penalty: SellerPenalty)

    /** 그 판매자의 패널티 중 created_at > from 인 것의 created_at. */
    fun createdAtAfter(sellerId: Long, from: Instant): List<Instant>
}
