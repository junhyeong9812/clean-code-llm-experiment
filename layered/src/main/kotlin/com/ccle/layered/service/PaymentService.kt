package com.ccle.layered.service

import com.ccle.layered.domain.DuplicateIdempotencyKeyException
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderAmounts
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.OrderRequest
import com.ccle.layered.domain.OrderSnapshot
import com.ccle.layered.domain.PayableTrades
import com.ccle.layered.domain.PgOutcome
import com.ccle.layered.domain.PaymentGateway
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.PointRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** 결제 결과. created = 새 주문(201), 아니면 같은 멱등 키의 기존 주문(200). */
class PaymentResult(val created: Boolean, val order: OrderSnapshot)

/**
 * 주문 결제(§5.1). 순서: 요청 검증(400) → [트랜잭션 1] 구매자 잠금 → 멱등 키 조회 → 체결 확인(404·403·409) → 금액 → 포인트 규칙(422)
 * → 주문·항목 생성 + 포인트 예약 → 커밋 → PG 승인 요청(트랜잭션 밖) → [트랜잭션 2] 결과 반영.
 * 주문 행은 PG 호출 전에 커밋한다. PG가 응답하지 않아도 주문이 남아야 하기 때문이다.
 */
@Service
class PaymentService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val orders: OrderRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val points: PointRepository,
    private val pgTransactions: PgTransactionRepository,
    private val pointBook: PointBook,
    private val outcomes: PaymentOutcomes,
    private val pgCaller: PgCaller,
    private val pg: PaymentGateway,
    private val reader: OrderReader,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    fun pay(userId: Long, idempotencyKey: String?, tradeIds: List<Long>?, pointToUse: Long?): PaymentResult {
        val request = OrderRequest.of(idempotencyKey, tradeIds, pointToUse)
        val now = clock.now()
        val created = try {
            tx.execute { createOrder(userId, request, now) }!!
        } catch (e: DuplicateIdempotencyKeyException) {
            // 다른 구매자가 같은 키로 동시에 주문을 만든 경우만 다시 한다(같은 구매자의 요청은 구매자 행 잠금으로 줄을 서므로 여기 오지 않는다)
            log.info("주문 생성 충돌, 멱등 키로 다시 조회: {} ({})", request.idempotencyKey, e.cause.toString())
            tx.execute { createOrder(userId, request, now) }!!
        }
        val orderId = created.first
        val cardAmount = created.second
        if (cardAmount == null) {
            // 같은 키의 기존 주문. 이긴 요청이 아직 PG를 기다리는 중(PAYING)이면 결과를 쓸 때까지 기다린다(ADR-0003 §9의 7)
            return PaymentResult(created = false, order = waitWhilePaying(orderId))
        }

        // PG 호출 중에는 트랜잭션·잠금을 잡지 않는다
        val result = pgCaller.approve(request.idempotencyKey, cardAmount)
        val reflected = try {
            tx.execute {
                pgTransactions.add(PgTransaction.approval(orderId, cardAmount, request.idempotencyKey, result, requestedAt = now, now = now))
                when (result.outcome) {
                    PgOutcome.APPROVED -> outcomes.approve(orderId, paidAt = now, now = now)
                    PgOutcome.DECLINED -> outcomes.decline(orderId, now)
                    PgOutcome.UNKNOWN -> orders.find(orderId)!!.markUnknown()
                }
            }
            true
        } catch (e: Exception) {
            log.error("결제 결과 반영 실패({} {}), UNKNOWN 기록을 다시 시도한다", orderId, result.outcome, e)
            false
        }
        if (!reflected) recordUnknown(orderId, now)

        // 결과(PAID·FAILED·UNKNOWN)가 영속된 것을 다시 읽어 확인한 뒤에만 201. 확인되지 않으면 계약 밖 오류로 끝낸다
        // (주문은 PAYING으로 남고 다음 기동 때 UNKNOWN으로 바뀐다, ADR-0003 §9의 7)
        val snapshot = reader.read(orderId)
        if (snapshot.order.isPaying()) {
            throw IllegalStateException("주문 $orderId 의 결제 결과를 기록하지 못했다 — PAYING으로 남고 다음 기동 때 UNKNOWN으로 바뀐다")
        }
        return PaymentResult(created = true, order = snapshot)
    }

    /**
     * 트랜잭션 1. 구매자 행을 잠가 같은 구매자의 결제를 줄 세운다(같은 키 동시 요청, 포인트 동시 사용).
     * 반환: (주문 id, 새 주문이면 카드 결제액 / 같은 키의 기존 주문이면 null).
     */
    private fun createOrder(userId: Long, request: OrderRequest, now: Instant): Pair<Long, Long?> {
        users.lock(userId)

        val existing = orders.findByIdempotencyKey(request.idempotencyKey)
        if (existing != null) {
            existing.requireSameRequest(userId, items.tradeIdsOf(existing.id!!), request)
            return existing.id!! to null
        }

        val payable = PayableTrades.of(request.tradeIds, trades.findAll(request.tradeIds), userId, now)
        payable.requireNoLiveItems(items.countLive(request.tradeIds))

        val amounts = OrderAmounts(payable.trades, request.point)
        amounts.requirePointRule { points.usable(userId, now) }

        val order = amounts.order(userId, request.idempotencyKey, now)
        orders.add(order)
        amounts.items(order.id!!).forEach(items::add)
        if (request.point > 0) pointBook.reserve(userId, order.id!!, request.point, now)
        return order.id!! to amounts.cardAmount
    }

    /**
     * 결과 반영이 실패하면 응답 없음(UNKNOWN)으로라도 남긴다. 확정은 pg-inquiry가 한다.
     * 단 반영 트랜잭션이 DB에는 커밋됐는데 예외만 받았을 수 있으므로, 잠근 뒤 다시 읽고 abandonPayment가 쓸지 정한다.
     * 이것도 실패하면 PAYING으로 남고 다음 기동 때 전환된다.
     */
    private fun recordUnknown(orderId: Long, now: Instant) {
        try {
            tx.execute {
                val order = orders.find(orderId)!!
                users.lock(order.buyerId)
                orders.reload(order)
                order.abandonPayment({ pgTransactions.approveCount(orderId) }, requestedAt = now)?.let(pgTransactions::add)
            }
        } catch (again: Exception) {
            log.error("UNKNOWN 기록도 실패: 주문 {}", orderId, again)
        }
    }

    /**
     * 같은 키의 진 요청: 주문이 PAYING인 동안 기다렸다가 그 주문의 현재 상태를 돌려준다(ADR-0003 §9의 7).
     * DB 잠금은 잡지 않고 50ms마다 다시 읽는다(매번 새 스냅샷 트랜잭션이라 커밋된 값을 읽는다).
     * 상한 근거: 이긴 요청은 주문을 커밋한 뒤 PG를 한 번 부르고(연결 타임아웃 + 응답 타임아웃 = PG 타임아웃 × 2가 최대),
     * 바로 결과 반영 트랜잭션과 실패 시 재기록 트랜잭션을 돈다. DB 작업 몇 건이므로 여유 10초면 충분하다.
     * 상한을 넘거나 인터럽트되면 PAYING을 돌려주지 않고 계약 밖 오류로 끝낸다(이긴 요청이 결과를 쓰지 못한 버그·장애).
     */
    private fun waitWhilePaying(orderId: Long): OrderSnapshot {
        // 경과 시간 측정용(업무 시각 아님)
        val deadline = System.nanoTime() + pg.timeout.multipliedBy(2).plusSeconds(10).toNanos()
        while (true) {
            val snapshot = reader.read(orderId)
            if (!snapshot.order.isPaying()) return snapshot
            if (System.nanoTime() > deadline) {
                throw IllegalStateException("주문 $orderId 가 대기 상한을 넘어서도 PAYING이다")
            }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("주문 $orderId 의 결과를 기다리다 인터럽트됐다", e)
            }
        }
    }
}
