package com.ccle.layered.service

import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.SettlementItem
import com.ccle.layered.domain.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** 정산 조회 결과 1행: 정산과 그 항목(orderItemId 오름차순). */
class SettlementDetail(val settlement: Settlement, val items: List<SettlementItem>)

/**
 * `GET /me/settlements`(ADR-0004 §10.3): 본인 정산, period_start 오름차순, 각 정산의 항목은 orderItemId 오름차순.
 * 정산과 항목을 한 스냅샷에서 읽는다(OrderReader와 같은 이유). 사용자 확인은 부르는 쪽이 트랜잭션 밖에서 한다.
 */
@Service
class SettlementQueryService(tx: TransactionTemplate, private val settlements: SettlementRepository) {

    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    fun list(sellerId: Long): List<SettlementDetail> = snapshotTx.execute {
        val found = settlements.listOf(sellerId)
        val items = if (found.isEmpty()) emptyMap() else settlements.itemsOf(found.map { it.id!! }).groupBy { it.settlementId }
        found.map { SettlementDetail(it, items[it.id].orEmpty()) }
    }!!
}
