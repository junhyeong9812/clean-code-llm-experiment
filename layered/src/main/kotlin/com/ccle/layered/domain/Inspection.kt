package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 검수 요청의 값(ADR-0004 §3). result는 PASS|FAIL, FAIL이면 reason이 필요하다(앞뒤 공백을 뺀 값이 비면 400). reason은 받은 그대로 둔다. */
class InspectionRequest private constructor(val passed: Boolean, val reason: String?) {
    companion object {
        fun of(result: String?, reason: String?): InspectionRequest {
            if (result == null) invalidRequest("result가 필요하다")
            if (result != "PASS" && result != "FAIL") invalidRequest("result는 PASS 또는 FAIL: $result")
            if (result == "FAIL" && reason.isNullOrBlank()) invalidRequest("불합격에는 reason이 필요하다")
            return InspectionRequest(result == "PASS", reason)
        }
    }
}

@Entity
@Table(name = "inspections")
class Inspection(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var result: String,
    var reason: String? = null,
    var inspectedAt: Instant,
)

interface InspectionRepository {
    fun add(inspection: Inspection)
}

@Entity
@Table(name = "refunds")
class Refund(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var cardAmount: Long,
    var pointAmount: Long,
    var buyerFeeAmount: Long,
    var shippingAmount: Long,
    var reason: String,
    var fault: String,
    var createdAt: Instant,
)

interface RefundRepository {
    /** 그 항목의 환불 행(항목당 최대 1행), 없으면 null. */
    fun findFor(orderItemId: Long): Refund?

    fun add(refund: Refund)

    /** 그 주문의 환불 행에 이미 실린 배송비 합. */
    fun shippingRefunded(orderId: Long): Long
}
