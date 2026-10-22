package com.ccle.god

import com.ccle.god.entity.Bid
import com.ccle.god.entity.BidEvent
import com.ccle.god.entity.BuyerWarning
import com.ccle.god.entity.Dispute
import com.ccle.god.entity.Inspection
import com.ccle.god.entity.OrderItem
import com.ccle.god.entity.PgTransaction
import com.ccle.god.entity.PointAllocation
import com.ccle.god.entity.PointBalance
import com.ccle.god.entity.PointLedgerEntry
import com.ccle.god.entity.ProductSize
import com.ccle.god.entity.PurchaseOrder
import com.ccle.god.entity.Refund
import com.ccle.god.entity.SellerPenalty
import com.ccle.god.entity.Trade
import com.ccle.god.entity.User
import com.ccle.god.web.ApiException
import com.ccle.god.web.BidCancelled
import com.ccle.god.web.BidPlaced
import com.ccle.god.web.BidView
import com.ccle.god.web.BookView
import com.ccle.god.web.DisputeOpened
import com.ccle.god.web.DisputeView
import com.ccle.god.web.ItemView
import com.ccle.god.web.OrderItemView
import com.ccle.god.web.OrderView
import com.ccle.god.web.PaymentResult
import com.ccle.god.web.PointsView
import com.ccle.god.web.RefundView
import com.ccle.god.web.TradeView
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 거래소의 절차·규칙·저장 전부(ADR-0003 1단계: 입찰·체결·결제·포인트·배치, ADR-0004 2단계: 주문 항목 이후 절차).
 *
 * 컨트롤러는 요청을 파싱해 여기 메서드를 부르고 결과를 JSON으로 바꾸기만 한다.
 * 행 읽기·쓰기는 EntityManager로 하고, 잠금·호가 조회·배치처럼 SQL이 자연스러운 곳은 네이티브 SQL을 쓴다.
 * 시각은 FrozenClock에서만 읽는다. 요청 하나는 처음에 읽은 시각 하나로 처리한다.
 * 오류는 ApiException(HTTP 상태 + code)으로 던진다. 판정 순서: 401 → 400 → 멱등 키 → 404 → 403 → 409 → 422.
 */
@Service
class Marketplace(
    private val em: EntityManager,
    private val clock: FrozenClock,
    private val tx: TransactionTemplate,
    private val pg: PgClient,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(Marketplace::class.java)

    /** 한 스냅샷에서 여러 행을 읽는 조회용(orderView). */
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    // ---- 공통 ----

    /** `X-User-Id` 헤더를 확인해 사용자 id를 돌려준다. 없음·숫자 아님·없는 사용자 → 401 UNKNOWN_USER. */
    fun requireUser(header: String?): Long {
        val id = header?.toLongOrNull()
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNKNOWN_USER", "X-User-Id 헤더가 없거나 숫자가 아니다")
        em.find(User::class.java, id)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNKNOWN_USER", "없는 사용자: $id")
        return id
    }

    private fun badRequest(message: String): Nothing =
        throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)

    private fun notFound(message: String): Nothing =
        throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

    // ---- 시계 (§7) ----

    fun clockNow(): Instant = clock.now()

    /** 같은 시각·뒤 시각만 허용한다. 앞 시각이면 FrozenClock이 409 CLOCK_BACKWARD. */
    fun moveClock(target: Instant): Instant {
        clock.moveTo(target)
        return clock.now()
    }

    // ---- 입찰 등록·즉시·취소·조회, 체결 (§3) ----

    /**
     * 입찰 등록(§3.1). 일반 입찰은 교차하면 같은 트랜잭션에서 체결하고, 즉시 입찰은 반대편 최우선 입찰의 가격으로 만들어 바로 체결한다.
     * 숫자·문자열 형식은 컨트롤러가 확인했고, 여기서는 값이 있는지·범위 안인지부터 본다.
     */
    fun placeBid(
        userId: Long,
        productSizeId: Long?,
        side: String?,
        price: Long?,
        validDays: Long?,
        instant: Boolean?,
    ): BidPlaced {
        if (productSizeId == null) badRequest("productSizeId가 필요하다")
        if (side == null) badRequest("side가 필요하다")
        if (side != "BUY" && side != "SELL") badRequest("side는 BUY 또는 SELL: $side")
        val isInstant = instant == true
        if (isInstant) {
            if (price != null) badRequest("즉시 입찰에는 price를 줄 수 없다")
            if (validDays != null) badRequest("즉시 입찰에는 validDays를 줄 수 없다")
        } else {
            if (price == null) badRequest("price가 필요하다")
            if (price <= 0) badRequest("price는 0보다 커야 한다: $price")
            if (validDays == null) badRequest("validDays가 필요하다")
            if (validDays != 30L && validDays != 60L && validDays != 90L) badRequest("validDays는 30·60·90: $validDays")
        }

        val now = clock.now()
        return tx.execute {
            lockProductSize(productSizeId)

            // 제한은 새 입찰에만 건다. 이미 걸려 있는 입찰의 체결은 막지 않는다
            val user = em.find(User::class.java, userId)
            if (side == "BUY" && user.purchaseRestrictedUntil?.isAfter(now) == true) {
                throw ApiException(HttpStatus.FORBIDDEN, "PURCHASE_RESTRICTED", "구매 제한 중: ${user.purchaseRestrictedUntil}까지")
            }
            if (side == "SELL" && user.saleRestrictedUntil?.isAfter(now) == true) {
                throw ApiException(HttpStatus.FORBIDDEN, "SALE_RESTRICTED", "판매 제한 중: ${user.saleRestrictedUntil}까지")
            }

            // 즉시 입찰은 가격 조건 없이 최우선 상대를 찾고, 일반 입찰은 교차하는 상대만 찾는다
            val counter = findBestCounterBid(productSizeId, side, userId, if (isInstant) null else price, now)
            if (isInstant && counter == null) {
                throw ApiException(HttpStatus.CONFLICT, "NO_COUNTER_BID", "체결 가능한 상대 입찰이 없다")
            }

            val days = if (isInstant) 30L else validDays!!
            val bid = Bid(
                userId = userId,
                productSizeId = productSizeId,
                side = side,
                price = if (isInstant) counter!!.price else price!!,
                status = "ACTIVE",
                expiresAt = now.plus(Duration.ofDays(days)),
                createdAt = now,
            )
            em.persist(bid)
            em.persist(BidEvent(bidId = bid.id!!, fromStatus = null, toStatus = "ACTIVE", tradeId = null, createdAt = now))

            if (counter == null) {
                BidPlaced(bid.id!!, "ACTIVE", null, null)
            } else {
                val trade = matchBids(bid, counter, now)
                BidPlaced(bid.id!!, "MATCHED", trade.id, trade.price)
            }
        }!!
    }

    /** 입찰 취소(§3.2). 등록자만, ACTIVE만. */
    fun cancelBid(userId: Long, bidId: Long): BidCancelled {
        val now = clock.now()
        return tx.execute {
            val found = em.find(Bid::class.java, bidId) ?: notFound("없는 입찰: $bidId")
            if (found.userId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 입찰: $bidId")

            // 상태는 사이즈를 잠근 뒤 다시 읽는다(잠금을 기다리는 사이에 체결됐을 수 있다)
            lockProductSize(found.productSizeId)
            em.refresh(found)
            if (found.status != "ACTIVE") {
                throw ApiException(HttpStatus.CONFLICT, "BID_NOT_ACTIVE", "ACTIVE가 아닌 입찰: ${found.status}")
            }
            found.status = "CANCELLED"
            em.persist(BidEvent(bidId = bidId, fromStatus = "ACTIVE", toStatus = "CANCELLED", tradeId = null, createdAt = now))
            BidCancelled(bidId, "CANCELLED")
        }!!
    }

    /** 입찰 조회(§3.4). 등록자만. tradeId = 살아 있는 체결. */
    fun getBid(userId: Long, bidId: Long): BidView {
        val bid = em.find(Bid::class.java, bidId) ?: notFound("없는 입찰: $bidId")
        if (bid.userId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 입찰: $bidId")
        val liveTradeId = em.createNativeQuery(
            "SELECT id FROM trades WHERE (buy_bid_id = :bid OR sell_bid_id = :bid) AND status <> 'PAYMENT_EXPIRED'",
        ).setParameter("bid", bidId).resultList.firstOrNull() as Number?
        return BidView(
            bidId = bid.id!!,
            userId = bid.userId,
            productSizeId = bid.productSizeId,
            side = bid.side,
            price = bid.price,
            status = bid.status,
            createdAt = bid.createdAt,
            expiresAt = bid.expiresAt,
            tradeId = liveTradeId?.toLong(),
        )
    }

    /** 호가(§3.4): 체결 가능한(ACTIVE, 만료 시각 전) 입찰의 최고 구매가·최저 판매가. */
    fun getBook(productSizeId: Long): BookView {
        em.find(ProductSize::class.java, productSizeId) ?: notFound("없는 사이즈: $productSizeId")
        val row = em.createNativeQuery(
            """
            SELECT max(price) FILTER (WHERE side = 'BUY'), min(price) FILTER (WHERE side = 'SELL')
              FROM bids
             WHERE product_size_id = :size AND status = 'ACTIVE' AND expires_at > :now
            """,
        ).setParameter("size", productSizeId).setParameter("now", clock.now()).singleResult as Array<*>
        return BookView(bestBuy = (row[0] as Number?)?.toLong(), bestSell = (row[1] as Number?)?.toLong())
    }

    /**
     * 사이즈 행을 잠근다(§3.3 사이즈 단위 직렬화). 그 사이즈의 입찰 상태를 바꾸는 처리는 모두 이 잠금 뒤에 하고,
     * 잠금은 커밋까지 유지된다. 그래서 같은 사이즈 안에서는 bid_events.id 순서가 실제 처리 순서다. 없는 사이즈면 404.
     */
    private fun lockProductSize(productSizeId: Long) {
        val rows = em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR UPDATE")
            .setParameter("id", productSizeId)
            .resultList
        if (rows.isEmpty()) notFound("없는 사이즈: $productSizeId")
    }

    /**
     * 반대편 최우선 체결 가능 입찰(§3.3). 체결 가능 = ACTIVE이고 expires_at > 현재.
     * 순서: 가격(구매는 높은 순, 판매는 낮은 순) → created_at → id. 자기 입찰은 건너뛴다.
     * limitPrice가 있으면 교차하는 것만(새 구매가 ≥ 판매가, 새 판매가 ≤ 구매가), 없으면(즉시 입찰) 가격 조건 없이.
     */
    private fun findBestCounterBid(productSizeId: Long, side: String, userId: Long, limitPrice: Long?, now: Instant): Bid? {
        val counterSide = if (side == "BUY") "SELL" else "BUY"
        val crossing = when {
            limitPrice == null -> ""
            side == "BUY" -> " AND price <= :limit"
            else -> " AND price >= :limit"
        }
        val order = if (side == "BUY") "price ASC" else "price DESC"
        val query = em.createNativeQuery(
            "SELECT * FROM bids" +
                " WHERE product_size_id = :size AND side = :side AND status = 'ACTIVE' AND expires_at > :now" +
                " AND user_id <> :user" + crossing +
                " ORDER BY $order, created_at ASC, id ASC LIMIT 1",
            Bid::class.java,
        )
            .setParameter("size", productSizeId)
            .setParameter("side", counterSide)
            .setParameter("now", now)
            .setParameter("user", userId)
        if (limitPrice != null) query.setParameter("limit", limitPrice)
        return query.resultList.firstOrNull() as Bid?
    }

    /**
     * 두 입찰을 체결한다(§3.3). 사이즈를 잠근 트랜잭션 안에서 부른다.
     * 체결가 = (created_at, id)가 앞선 입찰의 가격. 결제 기한 = 현재 + 30분.
     * 두 입찰 MATCHED, bid_events는 구매 입찰 행 먼저.
     */
    private fun matchBids(a: Bid, b: Bid, now: Instant): Trade {
        val buy = if (a.side == "BUY") a else b
        val sell = if (a.side == "BUY") b else a
        val aFirst = a.createdAt.isBefore(b.createdAt) || (a.createdAt == b.createdAt && a.id!! < b.id!!)
        val trade = Trade(
            buyBidId = buy.id!!,
            sellBidId = sell.id!!,
            buyerId = buy.userId,
            sellerId = sell.userId,
            productSizeId = buy.productSizeId,
            price = if (aFirst) a.price else b.price,
            status = "AWAITING_PAYMENT",
            matchedAt = now,
            paymentDueAt = now.plus(Duration.ofMinutes(30)),
        )
        em.persist(trade)
        buy.status = "MATCHED"
        sell.status = "MATCHED"
        em.persist(BidEvent(bidId = buy.id!!, fromStatus = "ACTIVE", toStatus = "MATCHED", tradeId = trade.id, createdAt = now))
        em.persist(BidEvent(bidId = sell.id!!, fromStatus = "ACTIVE", toStatus = "MATCHED", tradeId = trade.id, createdAt = now))
        return trade
    }

    // ---- 체결 조회 (§4) ----

    /** 내 체결 목록. role = buyer|seller(필수), status는 체결 상태 중 하나(선택). tradeId 오름차순. */
    fun listTrades(userId: Long, role: String?, status: String?): List<TradeView> {
        if (role == null) badRequest("role이 필요하다")
        if (role != "buyer" && role != "seller") badRequest("role은 buyer 또는 seller: $role")
        if (status != null && status != "AWAITING_PAYMENT" && status != "PAID" && status != "PAYMENT_EXPIRED") {
            badRequest("알 수 없는 체결 상태: $status")
        }
        val who = if (role == "buyer") "t.buyerId" else "t.sellerId"
        val jpql = "SELECT t FROM Trade t WHERE $who = :user" +
            (if (status != null) " AND t.status = :status" else "") +
            " ORDER BY t.id"
        val query = em.createQuery(jpql, Trade::class.java).setParameter("user", userId)
        if (status != null) query.setParameter("status", status)
        return query.resultList.map {
            TradeView(
                tradeId = it.id!!,
                productSizeId = it.productSizeId,
                price = it.price,
                status = it.status,
                matchedAt = it.matchedAt,
                paymentDueAt = it.paymentDueAt,
            )
        }
    }

    // ---- 결제 (§5.1)·주문 조회 (§5.3) ----

    /**
     * 주문 결제(§5.1). 순서: 요청 검증(400) → [트랜잭션 1] 멱등 키 조회 → 체결 확인(404·403·409) → 금액 → 포인트 규칙(422)
     * → 주문·항목 생성 + 포인트 예약 → 커밋 → PG 승인 요청(트랜잭션 밖) → [트랜잭션 2] 결과 반영.
     * 주문 행은 PG 호출 전에 커밋한다. PG가 응답하지 않아도 주문이 남아야 하기 때문이다.
     */
    fun payOrder(userId: Long, idempotencyKey: String?, tradeIds: List<Long>?, pointToUse: Long?): PaymentResult {
        if (idempotencyKey == null) badRequest("Idempotency-Key 헤더가 필요하다")
        if (idempotencyKey.length !in 1..64) badRequest("Idempotency-Key는 1~64자: ${idempotencyKey.length}자")
        if (tradeIds == null || tradeIds.isEmpty()) badRequest("tradeIds가 1개 이상 필요하다")
        if (tradeIds.toSet().size != tradeIds.size) badRequest("tradeIds에 중복이 있다")
        val point = pointToUse ?: 0L
        if (point < 0) badRequest("pointToUse는 0 이상: $point")

        val now = clock.now()
        val created = try {
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        } catch (e: PersistenceException) {
            // 다른 구매자가 같은 키로 동시에 주문을 만들어 orders_idempotency_key_key에 걸린 경우만 다시 한다
            // (같은 구매자의 요청은 사용자 행 잠금으로 줄을 서므로 여기 오지 않는다). 다시 하면 멱등 키 조회에서 판정된다.
            // 주문 INSERT는 persist 시점(IDENTITY)에 실행되고 UNIQUE는 즉시 검사되므로 위반은 커밋이 아니라 여기서 올라온다.
            // 그 밖의 PersistenceException은 다시 하지 않고 그대로 올린다
            if (!isIdempotencyKeyConflict(e)) throw e
            log.info("주문 생성 충돌, 멱등 키로 다시 조회: {} ({})", idempotencyKey, e.toString())
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        }
        val orderId = created.first
        val cardAmount = created.second
        if (cardAmount == null) {
            // 같은 키의 기존 주문. 이긴 요청이 아직 PG를 기다리는 중(PAYING)이면 결과를 쓸 때까지 기다린다(ADR-0003 §9의 7)
            return PaymentResult(created = false, order = waitWhilePaying(orderId))
        }

        // PG 호출 중에는 트랜잭션·잠금을 잡지 않는다
        val (status, pgTxId) = pgResult(callPg("승인 $idempotencyKey") { pg.postApproval(idempotencyKey, cardAmount) }, inquiry = false)
        val reflected = try {
            tx.execute {
                em.persist(
                    PgTransaction(
                        orderId = orderId,
                        kind = "APPROVE",
                        amount = cardAmount,
                        status = status,
                        pgTxId = pgTxId,
                        idempotencyKey = idempotencyKey,
                        requestedAt = now,
                        respondedAt = if (status == "UNKNOWN") null else now,
                    ),
                )
                when (status) {
                    "APPROVED" -> approveOrder(orderId, paidAt = now, now = now)
                    "DECLINED" -> declineOrder(orderId, now)
                    else -> em.find(PurchaseOrder::class.java, orderId).status = "UNKNOWN"
                }
            }
            true
        } catch (e: Exception) {
            log.error("결제 결과 반영 실패({} {}), UNKNOWN 기록을 다시 시도한다", orderId, status, e)
            false
        }
        if (!reflected) {
            // 결과 반영이 실패하면 응답 없음(UNKNOWN)으로라도 남긴다. 확정은 pg-inquiry가 한다.
            // 단 반영 트랜잭션이 DB에는 커밋됐는데 예외만 받았을 수 있으므로, 잠근 뒤 다시 읽어 아직 PAYING이고
            // APPROVE 행이 없을 때만 쓴다(결과가 정해진 뒤 1행, §5.1). 이것도 실패하면 PAYING으로 남고 다음 기동 때 전환된다
            try {
                tx.execute {
                    val order = em.find(PurchaseOrder::class.java, orderId)
                    lockUser(order.buyerId)
                    em.refresh(order)
                    if (order.status == "PAYING" && approveRowCount(orderId) == 0L) {
                        em.persist(
                            PgTransaction(
                                orderId = orderId,
                                kind = "APPROVE",
                                amount = cardAmount,
                                status = "UNKNOWN",
                                pgTxId = null,
                                idempotencyKey = idempotencyKey,
                                requestedAt = now,
                                respondedAt = null,
                            ),
                        )
                        order.status = "UNKNOWN"
                    }
                }
            } catch (again: Exception) {
                log.error("UNKNOWN 기록도 실패: 주문 {}", orderId, again)
            }
        }
        // 결과(PAID·FAILED·UNKNOWN)가 영속된 것을 다시 읽어 확인한 뒤에만 201. 확인되지 않으면 계약 밖 오류로 끝낸다
        // (주문은 PAYING으로 남고 다음 기동 때 UNKNOWN으로 바뀐다, ADR-0003 §9의 7)
        val view = orderView(orderId)
        if (view.status == "PAYING") {
            throw IllegalStateException("주문 $orderId 의 결제 결과를 기록하지 못했다 — PAYING으로 남고 다음 기동 때 UNKNOWN으로 바뀐다")
        }
        return PaymentResult(created = true, order = view)
    }

    private fun isIdempotencyKeyConflict(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is ConstraintViolationException && it.constraintName == "orders_idempotency_key_key" }

    private fun approveRowCount(orderId: Long): Long =
        em.createQuery("SELECT count(p) FROM PgTransaction p WHERE p.orderId = :order AND p.kind = 'APPROVE'", Long::class.javaObjectType)
            .setParameter("order", orderId)
            .singleResult

    /**
     * 같은 키의 진 요청: 주문이 PAYING인 동안 기다렸다가 그 주문의 현재 상태를 돌려준다(ADR-0003 §9의 7).
     * DB 잠금은 잡지 않고 50ms마다 다시 읽는다(트랜잭션 밖 조회는 매번 커밋된 값을 읽는다).
     * 상한 근거: 이긴 요청은 주문을 커밋한 뒤 PG를 한 번 부르고(연결 타임아웃 + 응답 타임아웃 = PG 타임아웃 × 2가 최대),
     * 바로 결과 반영 트랜잭션과 실패 시 재기록 트랜잭션을 돈다. DB 작업 몇 건이므로 여유 10초면 충분하다.
     * 상한을 넘거나 인터럽트되면 PAYING을 돌려주지 않고 계약 밖 오류로 끝낸다(이긴 요청이 결과를 쓰지 못한 버그·장애).
     */
    private fun waitWhilePaying(orderId: Long): OrderView {
        // 경과 시간 측정용(업무 시각 아님)
        val deadline = System.nanoTime() + pg.timeout.multipliedBy(2).plusSeconds(10).toNanos()
        while (true) {
            val view = orderView(orderId)
            if (view.status != "PAYING") return view
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

    /** PG 호출. 어떤 예외든 응답 없음(null)으로 본다(ADR-0003 §9의 7). 인터럽트는 플래그를 되살린다. */
    private fun callPg(what: String, call: () -> PgResponse): PgResponse? =
        try {
            call()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("PG 응답 없음({}): 인터럽트", what)
            null
        } catch (e: Exception) {
            log.warn("PG 응답 없음({}): {}", what, e.toString())
            null
        }

    /**
     * PG 응답 해석(ADR-0003 §5.1·§6·§7) → (APPROVED|DECLINED|UNKNOWN, pg_tx_id).
     * 응답 없음·200이 아닌 코드·읽을 수 없는 본문·알 수 없는 status는 UNKNOWN. 재조회의 404(승인 요청이 기록되지 않음)는 DECLINED.
     * pg_tx_id는 승인일 때만 PG 값.
     */
    private fun pgResult(response: PgResponse?, inquiry: Boolean): Pair<String, String?> {
        if (response == null) return "UNKNOWN" to null
        if (inquiry && response.status == 404) return "DECLINED" to null
        if (response.status != 200) {
            log.warn("PG 응답 없음으로 본다: HTTP {}", response.status)
            return "UNKNOWN" to null
        }
        val node = try {
            json.readTree(response.body)
        } catch (e: Exception) {
            log.warn("PG 응답 본문을 읽을 수 없다: {}", response.body)
            return "UNKNOWN" to null
        }
        val pgTxId = node?.path("pgTxId")?.takeIf { it.isTextual }?.asText()
        return when (node?.path("status")?.asText()) {
            "APPROVED" -> if (pgTxId != null) "APPROVED" to pgTxId else {
                log.warn("PG 승인 응답에 pgTxId가 없다: {}", response.body)
                "UNKNOWN" to null
            }
            "DECLINED" -> "DECLINED" to null
            else -> {
                log.warn("PG가 알 수 없는 상태를 돌려줌: {}", response.body)
                "UNKNOWN" to null
            }
        }
    }

    /**
     * 결제 트랜잭션 1. 구매자 행을 잠가 같은 구매자의 결제를 줄 세운다(같은 키 동시 요청, 포인트 동시 사용).
     * 반환: (주문 id, 새 주문이면 카드 결제액 / 같은 키의 기존 주문이면 null).
     */
    private fun createOrder(userId: Long, key: String, tradeIds: List<Long>, point: Long, now: Instant): Pair<Long, Long?> {
        lockUser(userId)

        // 멱등: 같은 구매자·같은 tradeIds 집합·같은 pointToUse면 그 주문, 아니면 409
        val existing = em.createQuery("SELECT o FROM PurchaseOrder o WHERE o.idempotencyKey = :key", PurchaseOrder::class.java)
            .setParameter("key", key)
            .resultList.firstOrNull()
        if (existing != null) {
            val existingTradeIds = em.createQuery("SELECT i.tradeId FROM OrderItem i WHERE i.orderId = :order", Long::class.javaObjectType)
                .setParameter("order", existing.id)
                .resultList.toSet()
            if (existing.buyerId != userId || existingTradeIds != tradeIds.toSet() || existing.pointUsed != point) {
                throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 멱등 키에 다른 구매자 또는 다른 요청: $key")
            }
            return existing.id!! to null
        }

        // 체결: 하나라도 없으면 404, 모두 있고 하나라도 남의 것이면 403, 하나라도 결제 불가면 409
        val trades = em.createQuery("SELECT t FROM Trade t WHERE t.id IN :ids ORDER BY t.id", Trade::class.java)
            .setParameter("ids", tradeIds)
            .resultList
        if (trades.size != tradeIds.size) notFound("없는 체결이 있다: $tradeIds")
        if (trades.any { it.buyerId != userId }) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남이 구매자인 체결이 있다")
        for (t in trades) {
            if (t.status != "AWAITING_PAYMENT" || !now.isBefore(t.paymentDueAt)) {
                throw ApiException(HttpStatus.CONFLICT, "TRADE_NOT_PAYABLE", "결제할 수 없는 체결: ${t.id} (${t.status}, 기한 ${t.paymentDueAt})")
            }
        }
        val liveItems = em.createQuery(
            "SELECT count(i) FROM OrderItem i WHERE i.tradeId IN :ids AND i.status <> 'PAYMENT_FAILED'",
            Long::class.javaObjectType,
        ).setParameter("ids", tradeIds).singleResult
        if (liveItems > 0) {
            throw ApiException(HttpStatus.CONFLICT, "TRADE_NOT_PAYABLE", "결제 중이거나 결제된 체결이 있다")
        }

        // 금액(ADR-0002 §5.1·§9.2). 항목은 tradeId 오름차순 = order_items.id 순서.
        // 곱셈의 중간값은 mulDiv로 넘치지 않게 계산하고, 합계는 addExact로 넘치면 오류가 드러나게 한다
        val fees = trades.map { maxOf(mulDiv(it.price, 3, 100), 1_000L) }
        val itemTotal = trades.fold(0L) { sum, t -> Math.addExact(sum, t.price) }
        val feeTotal = fees.fold(0L) { sum, fee -> Math.addExact(sum, fee) }
        val shippingFee = if (itemTotal >= 100_000) 0L else 3_000L
        val total = Math.addExact(Math.addExact(itemTotal, feeTotal), shippingFee)

        // 포인트 규칙(§5.2): 0이거나, 1,000 이상·10의 배수·총액 절반 이하·사용 가능액 이하
        if (point != 0L) {
            if (point < 1_000) pointRule("1,000P 이상만 쓸 수 있다: $point")
            if (point % 10 != 0L) pointRule("10P 단위로만 쓸 수 있다: $point")
            if (point > total / 2) pointRule("주문 총액의 50%(${total / 2})까지만 쓸 수 있다: $point")
            val usable = usablePoints(userId, now)
            if (point > usable) pointRule("사용 가능액($usable)을 넘는다: $point")
        }
        val cardAmount = total - point

        // 항목 몫: 기준액 b_i = 체결가 + 수수료 (+ 배송비, 첫 항목만). 카드 몫 = floor(카드 × b_i / 총액),
        // 남은 원은 앞 항목부터 1원씩(기준액을 넘지 않게). 포인트 몫 = b_i − 카드 몫
        val bases = trades.indices.map { i -> trades[i].price + fees[i] + (if (i == 0) shippingFee else 0L) } // 각각 ≤ total
        val cardShares = bases.map { mulDiv(cardAmount, it, total) }.toMutableList()
        var left = cardAmount - cardShares.sum()
        while (left > 0) {
            for (i in cardShares.indices) {
                if (left > 0 && cardShares[i] < bases[i]) {
                    cardShares[i] += 1
                    left -= 1
                }
            }
        }

        val order = PurchaseOrder(
            buyerId = userId,
            status = "PAYING",
            itemTotal = itemTotal,
            buyerFeeTotal = feeTotal,
            shippingFee = shippingFee,
            pointUsed = point,
            cardAmount = cardAmount,
            idempotencyKey = key,
            createdAt = now,
        )
        em.persist(order)
        for (i in trades.indices) {
            em.persist(
                OrderItem(
                    orderId = order.id!!,
                    tradeId = trades[i].id!!,
                    price = trades[i].price,
                    buyerFee = fees[i],
                    cardShare = cardShares[i],
                    pointShare = bases[i] - cardShares[i],
                    shippingShare = if (i == 0) shippingFee else 0L,
                    status = "AWAITING_PAYMENT",
                ),
            )
        }
        if (point > 0) reservePoints(userId, order.id!!, point, now)
        return order.id!! to cardAmount
    }

    /** floor(a × b ÷ c). 금액은 모두 0 이상이다. 중간 곱은 BigInteger로 계산하고, 결과가 Long을 넘으면 ArithmeticException. */
    private fun mulDiv(a: Long, b: Long, c: Long): Long =
        BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).longValueExact()

    private fun pointRule(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "POINT_RULE_VIOLATION", message)

    /** 구매자 행 잠금. 결제·포인트 잔액을 바꾸는 처리는 이 잠금 뒤에 한다. */
    private fun lockUser(userId: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE").setParameter("id", userId).resultList
    }

    /** 사용자 행 여러 개를 id 오름차순으로 잠근다(ADR-0004 §9.1 — 구매자·판매자가 서로 바뀐 두 처리가 교착하지 않게). */
    private fun lockUsers(vararg userIds: Long) {
        userIds.distinct().sorted().forEach { lockUser(it) }
    }

    /**
     * 적립률의 등급(ADR-0004 §8.1). 주문 생성 날짜(KST) 이전 effective_from의 최신 이력 → 이력이 모두 그 날짜 뒤면 BRONZE
     * → 이력이 없으면 users.grade. 승인이 늦어도(pg-inquiry) 주문 생성 날짜로 정한다.
     */
    private fun gradeOnOrderDate(order: PurchaseOrder): String =
        em.createNativeQuery(
            """
            SELECT coalesce(
                       (SELECT h.grade FROM user_grade_history h
                         WHERE h.user_id = u.id AND h.effective_from <= :orderDate
                         ORDER BY h.effective_from DESC LIMIT 1),
                       (SELECT 'BRONZE' FROM user_grade_history h WHERE h.user_id = u.id LIMIT 1),
                       u.grade)
              FROM users u
             WHERE u.id = :user
            """,
        ).setParameter("orderDate", LocalDate.ofInstant(order.createdAt, ZoneOffset.ofHours(9)))
            .setParameter("user", order.buyerId)
            .singleResult as String

    /**
     * 승인 반영(§5.1 표). 주문·항목 PAID(paid_at), 체결 PAID, USE CONFIRMED, 항목별 EARN PENDING(적립 > 0만), 잔액 pending 증가.
     * 적립 = floor(floor(카드 몫 × 체결가 ÷ 기준액) × 등급 적립률). paidAt은 PG가 승인한 시각(재조회 승인이면 요청 시각).
     */
    private fun approveOrder(orderId: Long, paidAt: Instant, now: Instant) {
        val order = em.find(PurchaseOrder::class.java, orderId)
        lockUser(order.buyerId)
        order.status = "PAID"
        order.paidAt = paidAt

        // 등급 적립률(ADR-0002 §5.2): BRONZE 1% · SILVER 1.5% · GOLD 2% · VIP 3%
        val grade = gradeOnOrderDate(order)
        val ratePermille = when (grade) {
            "BRONZE" -> 10L
            "SILVER" -> 15L
            "GOLD" -> 20L
            "VIP" -> 30L
            else -> throw IllegalStateException("알 수 없는 등급: $grade")
        }
        var pendingAdded = 0L
        for (item in orderItems(orderId)) {
            item.status = "PAID"
            item.paidAt = paidAt
            em.find(Trade::class.java, item.tradeId).status = "PAID"
            val base = Math.addExact(Math.addExact(item.price, item.buyerFee), item.shippingShare)
            val earn = mulDiv(mulDiv(item.cardShare, item.price, base), ratePermille, 1000)
            if (earn > 0) {
                em.persist(
                    PointLedgerEntry(
                        userId = order.buyerId,
                        kind = "EARN",
                        status = "PENDING",
                        amount = earn,
                        remaining = earn,
                        expiresAt = null,
                        orderItemId = item.id,
                        orderId = null,
                        createdAt = paidAt,
                    ),
                )
                pendingAdded = Math.addExact(pendingAdded, earn)
            }
        }
        useEntryOf(orderId)?.status = "CONFIRMED"
        if (pendingAdded > 0) changeBalance(order.buyerId, availableDelta = 0, pendingDelta = pendingAdded, now = now)
    }

    /** 거절 반영(§5.1 표). 주문 FAILED, 항목 PAYMENT_FAILED, 체결은 그대로, 포인트 예약 해제. */
    private fun declineOrder(orderId: Long, now: Instant) {
        val order = em.find(PurchaseOrder::class.java, orderId)
        lockUser(order.buyerId)
        order.status = "FAILED"
        for (item in orderItems(orderId)) item.status = "PAYMENT_FAILED"
        val use = useEntryOf(orderId) ?: return
        releasePoints(use, now)
    }

    private fun orderItems(orderId: Long): List<OrderItem> =
        em.createQuery("SELECT i FROM OrderItem i WHERE i.orderId = :order ORDER BY i.id", OrderItem::class.java)
            .setParameter("order", orderId)
            .resultList

    /** 주문 조회(§5.3). 구매자만. */
    fun getOrder(userId: Long, orderId: Long): OrderView {
        val order = em.find(PurchaseOrder::class.java, orderId) ?: notFound("없는 주문: $orderId")
        if (order.buyerId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 주문: $orderId")
        return orderView(orderId)
    }

    /**
     * §5.1 응답 모양. 항목은 order_items.id 순서(= tradeId 오름차순).
     * 주문과 항목을 한 스냅샷에서 읽는다(읽기 전용 REPEATABLE READ 트랜잭션). READ COMMITTED는 문장마다 스냅샷이 바뀌어
     * 그 사이 pg-inquiry 승인이 커밋되면 {주문 UNKNOWN, 항목 PAID} 같은 조합을 읽을 수 있다.
     * 트랜잭션 밖에서 부른다(결제 결과 확인·같은 키 대기·주문 조회).
     */
    private fun orderView(orderId: Long): OrderView = snapshotTx.execute { readOrderView(orderId) }!!

    private fun readOrderView(orderId: Long): OrderView {
        val order = em.find(PurchaseOrder::class.java, orderId)
        return OrderView(
            orderId = order.id!!,
            status = order.status,
            itemTotal = order.itemTotal,
            buyerFeeTotal = order.buyerFeeTotal,
            shippingFee = order.shippingFee,
            pointUsed = order.pointUsed,
            cardAmount = order.cardAmount,
            items = orderItems(orderId).map {
                OrderItemView(
                    orderItemId = it.id!!,
                    tradeId = it.tradeId,
                    status = it.status,
                    price = it.price,
                    buyerFee = it.buyerFee,
                    cardShare = it.cardShare,
                    pointShare = it.pointShare,
                    shippingShare = it.shippingShare,
                )
            },
        )
    }

    // ---- 주문 항목 조회·판매자·센터 절차 (ADR-0004 §2·§3) ----

    /** `GET /order-items/{id}`(§2.2). 그 체결의 구매자 또는 판매자만. 항목과 환불 행을 한 스냅샷에서 읽는다(orderView와 같은 이유). */
    fun getOrderItem(userId: Long, itemId: Long): ItemView = snapshotTx.execute {
        val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
        val trade = em.find(Trade::class.java, item.tradeId)
        if (userId != trade.buyerId && userId != trade.sellerId) {
            throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "구매자도 판매자도 아닌 주문 항목: $itemId")
        }
        itemView(item)
    }!!

    /** 발송 등록(§3). 그 체결의 판매자만, PAID이고 현재 < paid_at + 3일. 상태 위반이 기한 위반보다 먼저다(§2.1). */
    fun shipItem(userId: Long, itemId: Long): ItemView {
        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            if (em.find(Trade::class.java, item.tradeId).sellerId != userId) {
                throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "판매자가 아닌 주문 항목: $itemId")
            }
            requireItemStatus(item, "PAID")
            val deadline = item.paidAt!!.plus(Duration.ofDays(3))
            if (!now.isBefore(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "발송 기한($deadline)이 지났다: $itemId")
            }
            item.status = "SHIPPED_TO_CENTER"
            item.shippedAt = now
            itemView(item)
        }!!
    }

    /** 입고(§3). SHIPPED_TO_CENTER → INSPECTING. 입고 시각 열은 없다(inspected_at은 검수 때 쓴다). */
    fun receiveItem(itemId: Long): ItemView =
        tx.execute {
            val item = lockOrderOf(itemId)
            requireItemStatus(item, "SHIPPED_TO_CENTER")
            item.status = "INSPECTING"
            itemView(item)
        }!!

    /**
     * 검수(§3). result는 PASS|FAIL, FAIL이면 reason이 필요하다(앞뒤 공백을 뺀 값이 비면 400). reason은 받은 그대로 저장한다.
     * PASS: inspections PASS(reason은 무시하고 NULL) + 항목 DELIVERING, inspected_at = 현재.
     * FAIL: §6 판매자 귀책(환불 reason = 검수 reason) + inspections FAIL, inspected_at = cancelled_at = 현재.
     * 카드 취소가 실패하면 409 PG_UNAVAILABLE이고 검수 행도 쓰지 않는다(§2.3).
     */
    fun inspectItem(itemId: Long, result: String?, reason: String?): ItemView {
        if (result == null) badRequest("result가 필요하다")
        if (result != "PASS" && result != "FAIL") badRequest("result는 PASS 또는 FAIL: $result")
        if (result == "FAIL" && reason.isNullOrBlank()) badRequest("불합격에는 reason이 필요하다")

        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            requireItemStatus(item, "INSPECTING")
            if (result == "FAIL") {
                sellerFault(item, reason!!, now)
                em.persist(Inspection(orderItemId = itemId, result = "FAIL", reason = reason, inspectedAt = now))
            } else {
                em.persist(Inspection(orderItemId = itemId, result = "PASS", reason = null, inspectedAt = now))
                item.status = "DELIVERING"
            }
            item.inspectedAt = now
            itemView(item)
        }!!
    }

    /** 배송 완료(§3). DELIVERING → DELIVERED, delivered_at = 현재. */
    fun deliverItem(itemId: Long): ItemView {
        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            requireItemStatus(item, "DELIVERING")
            item.status = "DELIVERED"
            item.deliveredAt = now
            itemView(item)
        }!!
    }

    /**
     * 항목을 바꾸는 처리의 1단계(§2.3·§9.1): 그 항목의 주문 행, 이어서 항목 행을 잠그고, 잠근 뒤의 항목을 돌려준다.
     * 같은 주문의 항목 변경은 이 잠금으로 줄을 서므로, 잠금을 기다리는 사이에 바뀐 상태를 다시 읽어 확인한다. 없는 항목이면 404.
     * 항목 행을 사용자 행보다 먼저 잠가 잠금 순서를 주문 → 항목 → 사용자로 고정한다(항목을 잠근 채 판매자 행에 FK를 거는 정산과 교착하지 않게).
     */
    private fun lockOrderOf(itemId: Long): OrderItem {
        val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
        em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE").setParameter("id", item.orderId).resultList
        em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR UPDATE").setParameter("id", itemId).resultList
        em.refresh(item)
        return item
    }

    private fun requireItemStatus(item: OrderItem, expected: String) {
        if (item.status != expected) {
            throw ApiException(HttpStatus.CONFLICT, "ITEM_STATE_CONFLICT", "${expected}가 아닌 주문 항목: ${item.id} (${item.status})")
        }
    }

    private fun itemView(item: OrderItem): ItemView {
        val refund = em.createQuery("SELECT r FROM Refund r WHERE r.orderItemId = :item", Refund::class.java)
            .setParameter("item", item.id)
            .resultList.firstOrNull()
        return ItemView(
            orderItemId = item.id!!,
            orderId = item.orderId,
            tradeId = item.tradeId,
            status = item.status,
            price = item.price,
            buyerFee = item.buyerFee,
            cardShare = item.cardShare,
            pointShare = item.pointShare,
            shippingShare = item.shippingShare,
            paidAt = item.paidAt,
            shippedAt = item.shippedAt,
            inspectedAt = item.inspectedAt,
            deliveredAt = item.deliveredAt,
            confirmedAt = item.confirmedAt,
            cancelledAt = item.cancelledAt,
            refund = refund?.let { RefundView(it.cardAmount, it.pointAmount, it.buyerFeeAmount, it.shippingAmount, it.fault) },
        )
    }

    private fun requireBuyer(item: OrderItem, userId: Long) {
        if (em.find(PurchaseOrder::class.java, item.orderId).buyerId != userId) {
            throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "구매자가 아닌 주문 항목: ${item.id}")
        }
    }

    // ---- 구매자 절차 (ADR-0004 §4) ----

    /** 구매 확정(§4.1). 구매자만, DELIVERED만. confirmed_at = min(현재, delivered_at + 7일)(7일 뒤의 확정은 자동 확정과 같다) → §8.2. */
    fun confirmOrderItem(userId: Long, itemId: Long): ItemView {
        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            requireBuyer(item, userId)
            requireItemStatus(item, "DELIVERED")
            val lastDay = item.deliveredAt!!.plus(Duration.ofDays(7))
            confirmItem(item, confirmedAt = if (now.isBefore(lastDay)) now else lastDay, now = now)
            itemView(item)
        }!!
    }

    /** 확정 반영(§4.1·auto-confirm 공용): 항목 CONFIRMED + 포인트 확정. 주문·항목을 잠근 트랜잭션 안에서 부르고, 구매자 행을 잠근다. */
    private fun confirmItem(item: OrderItem, confirmedAt: Instant, now: Instant) {
        val buyerId = em.find(PurchaseOrder::class.java, item.orderId).buyerId
        lockUser(buyerId)
        item.status = "CONFIRMED"
        item.confirmedAt = confirmedAt
        confirmEarn(item, buyerId, confirmedAt, now)
    }

    /** 구매자 취소(§4.2). 구매자만, PAID만(발송 기한이 지나도 배치 전이면 PAID라 취소된다) → §7 환불(fault BUYER). */
    fun cancelOrderItem(userId: Long, itemId: Long): ItemView {
        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            requireBuyer(item, userId)
            requireItemStatus(item, "PAID")
            refundItem(item, fault = "BUYER", reason = "구매자 취소", now = now)
            itemView(item)
        }!!
    }

    /** 분쟁 제기(§4.3). 구매자만, CONFIRMED이고 현재 ≤ confirmed_at + 14일. 상태 위반이 기한 위반보다 먼저다. */
    fun openDispute(userId: Long, itemId: Long): DisputeOpened {
        val now = clock.now()
        return tx.execute {
            val item = lockOrderOf(itemId)
            requireBuyer(item, userId)
            requireItemStatus(item, "CONFIRMED")
            val deadline = item.confirmedAt!!.plus(Duration.ofDays(14))
            if (now.isAfter(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "분쟁 기간($deadline)이 지났다: $itemId")
            }
            val dispute = Dispute(orderItemId = itemId, status = "OPEN", openedAt = now)
            em.persist(dispute)
            item.status = "DISPUTED"
            DisputeOpened(dispute.id!!, itemId, dispute.status, dispute.openedAt)
        }!!
    }

    /**
     * 분쟁 판정(§4.4). OPEN만. 기각: REJECTED, 항목 CONFIRMED(confirmed_at 그대로).
     * 인정: §7 환불(fault DISPUTE, 전액, cancelled_at = resolved_at) + §8.4 회수, 패널티 없음. 카드 취소가 실패하면 409 PG_UNAVAILABLE, 분쟁은 OPEN 그대로.
     * 잠금 순서: 분쟁 조회 → 주문 → 항목 → 분쟁을 다시 읽어 상태 확인.
     */
    fun resolveDispute(disputeId: Long, accept: Boolean?): DisputeView {
        if (accept == null) badRequest("accept가 필요하다")
        val now = clock.now()
        return tx.execute {
            val dispute = em.find(Dispute::class.java, disputeId) ?: notFound("없는 분쟁: $disputeId")
            val item = lockOrderOf(dispute.orderItemId)
            em.refresh(dispute)
            if (dispute.status != "OPEN") {
                throw ApiException(HttpStatus.CONFLICT, "DISPUTE_NOT_OPEN", "OPEN이 아닌 분쟁: $disputeId (${dispute.status})")
            }
            if (accept) {
                refundItem(item, fault = "DISPUTE", reason = "분쟁 인정", now = now)
            } else {
                item.status = "CONFIRMED"
            }
            dispute.status = if (accept) "ACCEPTED" else "REJECTED"
            dispute.resolvedAt = now
            DisputeView(dispute.id!!, dispute.orderItemId, dispute.status, dispute.openedAt, dispute.resolvedAt)
        }!!
    }

    // ---- 판매자 귀책 (ADR-0004 §6) ----

    /**
     * 판매자 귀책(검수 불합격·발송 기한 초과 공용). 주문·항목을 잠그고 상태를 확인한 트랜잭션 안에서, 아직 아무것도 쓰지 않은 상태로 부른다.
     * §7 환불(fault SELLER, 전액 — 판매자 행도 잠근다) → 패널티 1행(floor(체결가 × 10%)) → 판매 제한:
     * 최근 30일 안이면서 마지막 제한을 건 시각(sale_restricted_until − 30일) 뒤의 패널티가 3건 이상이면 현재 + 30일.
     */
    private fun sellerFault(item: OrderItem, reason: String, now: Instant) {
        refundItem(item, fault = "SELLER", reason = reason, now = now)
        val sellerId = em.find(Trade::class.java, item.tradeId).sellerId
        em.persist(SellerPenalty(sellerId = sellerId, orderItemId = item.id!!, amount = mulDiv(item.price, 10, 100), createdAt = now))
        val seller = em.find(User::class.java, sellerId)
        em.refresh(seller)
        val lastRestrictedAt = seller.saleRestrictedUntil?.minus(Duration.ofDays(30))
        val recentPenalties = em.createQuery(
            "SELECT p.createdAt FROM SellerPenalty p WHERE p.sellerId = :seller AND p.createdAt > :from",
            Instant::class.java,
        ).setParameter("seller", sellerId).setParameter("from", now.minus(Duration.ofDays(30))).resultList
            .count { lastRestrictedAt == null || it.isAfter(lastRestrictedAt) }
        if (recentPenalties >= 3) seller.saleRestrictedUntil = now.plus(Duration.ofDays(30))
    }

    // ---- 환불 (ADR-0004 §7) ----

    /**
     * 항목 환불(§7). 주문·항목을 잠그고 상태를 확인한 트랜잭션 안에서, 아직 아무것도 쓰지 않은 상태로 부른다(§2.3의 3·4단계).
     * 순서: 금액 → 카드 취소(PG) → 사용자 잠금(구매자, 판매자 귀책이면 판매자도, id 오름차순) → 항목 상태·cancelled_at,
     * refunds 1행, pg_transactions CANCEL 1행, 적립 예정 취소(분쟁 인정이면 회수), RESTORE, 주문 상태.
     * 카드 취소가 실패하면 409 PG_UNAVAILABLE을 던지고, 그때까지 쓴 것이 없으므로 롤백 뒤 DB는 그대로다.
     * PG는 주문 잠금을 쥔 채 부른다: 같은 주문의 다른 항목 환불이 끼어들어 금액(마지막 환불)을 바꾸지 못하게.
     * 사용자 잠금은 PG 뒤에 잡아, PG를 기다리는 동안 같은 사용자의 결제·입찰을 막지 않는다.
     *
     * 금액(§7.1): s = 배송비(첫 항목 = id가 가장 작은 항목에 귀속), s_c = floor(s × c_1 / b_1), s_p = s − s_c.
     * 구매자 취소는 그 항목의 몫에서 (첫 항목이면) 배송비 부분을 빼고, 판매자 귀책·분쟁 인정은 몫 전부(첫 항목이면 배송비 s 포함).
     * 이 환불로 주문의 모든 항목이 환불되고 배송비가 아직 환불되지 않았으면 s_c·s_p를 더하고 배송비 s를 싣는다.
     */
    private fun refundItem(item: OrderItem, fault: String, reason: String, now: Instant) {
        val order = em.find(PurchaseOrder::class.java, item.orderId)
        val items = orderItems(item.orderId)
        val first = items.first()
        val isFirst = first.id == item.id
        val shipping = order.shippingFee
        val shipCard = mulDiv(shipping, first.cardShare, first.price + first.buyerFee + first.shippingShare)
        val shipPoint = shipping - shipCard
        val keepsShipping = isFirst && fault == "BUYER"
        var card = item.cardShare - (if (keepsShipping) shipCard else 0L)
        var point = item.pointShare - (if (keepsShipping) shipPoint else 0L)
        var shippingAmount = if (isFirst && !keepsShipping) shipping else 0L
        val last = items.count { it.id != item.id && it.status in refundedStatuses } == items.size - 1
        if (last && shippingAmount == 0L && shipping > 0 && shippingRefunded(item.orderId) == 0L) {
            card += shipCard
            point += shipPoint
            shippingAmount = shipping
        }

        val pgTxId = if (card > 0) cancelCard(order, item.id!!, card) else null

        if (fault == "SELLER") lockUsers(order.buyerId, em.find(Trade::class.java, item.tradeId).sellerId) else lockUser(order.buyerId)
        item.status = when (fault) {
            "BUYER" -> "CANCELLED_BY_BUYER"
            "SELLER" -> "CANCELLED_SELLER_FAULT"
            else -> "REFUNDED_AFTER_CONFIRM"
        }
        item.cancelledAt = now
        em.persist(
            Refund(
                orderItemId = item.id!!,
                cardAmount = card,
                pointAmount = point,
                buyerFeeAmount = item.buyerFee,
                shippingAmount = shippingAmount,
                reason = reason,
                fault = fault,
                createdAt = now,
            ),
        )
        if (pgTxId != null) {
            em.persist(
                PgTransaction(
                    orderId = order.id!!,
                    kind = "CANCEL",
                    amount = card,
                    status = "APPROVED",
                    pgTxId = pgTxId,
                    idempotencyKey = "cancel-${item.id}",
                    requestedAt = now,
                    respondedAt = now,
                ),
            )
        }
        // 회수(§8.4)가 복구(RESTORE)보다 먼저다
        if (fault == "DISPUTE") revokeEarn(item, now) else cancelPendingEarn(item, now)
        if (point > 0) restorePoints(order, item, point, now)
        order.status = if (last) "CANCELLED" else "PARTIALLY_CANCELLED"
    }

    private val refundedStatuses = setOf("CANCELLED_BY_BUYER", "CANCELLED_SELLER_FAULT", "REFUNDED_AFTER_CONFIRM")

    /** 그 주문의 환불 행에 이미 실린 배송비 합. */
    private fun shippingRefunded(orderId: Long): Long =
        em.createQuery(
            "SELECT coalesce(sum(r.shippingAmount), 0) FROM Refund r WHERE r.orderItemId IN (SELECT i.id FROM OrderItem i WHERE i.orderId = :order)",
            Long::class.javaObjectType,
        ).setParameter("order", orderId).singleResult

    /**
     * 카드 취소(§7.2): `cancel-<항목 id>` 키(항목당 하나, 사유와 무관)로 주문 승인 키와 금액을 보낸다. 반환 = PG의 pgTxId.
     * 응답 없음(타임아웃·연결 실패·예외)·200이 아님·APPROVED가 아님·pgTxId 없음·응답 금액이 요청 금액과 다름 → 409 PG_UNAVAILABLE.
     */
    private fun cancelCard(order: PurchaseOrder, itemId: Long, amount: Long): String {
        val key = "cancel-$itemId"
        val response = callPg("취소 $key") { pg.postCancellation(key, order.idempotencyKey, amount) }
        val node = if (response?.status == 200) {
            try {
                json.readTree(response.body)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val pgTxId = node?.path("pgTxId")?.takeIf { it.isTextual }?.asText()
        val respondedAmount = node?.path("amount")?.takeIf { it.isIntegralNumber }?.asLong()
        if (node?.path("status")?.asText() != "APPROVED" || pgTxId == null || respondedAmount != amount) {
            log.warn("카드 취소 실패({}, 요청 {}): {}", key, amount, response)
            throw ApiException(HttpStatus.CONFLICT, "PG_UNAVAILABLE", "카드 취소를 PG가 처리하지 못했다: $key")
        }
        return pgTxId
    }

    // ---- 포인트 (§5.2) ----

    /** 사용 가능액 = EARN·AVAILABLE·만료 전 묶음의 remaining 합(예약분은 이미 remaining에서 빠져 있다). */
    private fun usablePoints(userId: Long, now: Instant): Long {
        val sum = em.createNativeQuery(
            "SELECT coalesce(sum(remaining), 0) FROM point_ledger" +
                " WHERE user_id = :user AND kind = 'EARN' AND status = 'AVAILABLE' AND expires_at > :now",
        ).setParameter("user", userId).setParameter("now", now).singleResult as Number
        return sum.toLong()
    }

    /**
     * 예약(§5.2·ADR-0002 §9.6): USE RESERVED 1행, 사용 가능 묶음을 (expires_at, id) 순으로 차감해 배분을 쓰고
     * remaining과 point_balances.available을 줄인다. 구매자 행을 잠근 트랜잭션 안에서 부른다.
     */
    private fun reservePoints(userId: Long, orderId: Long, amount: Long, now: Instant) {
        val use = PointLedgerEntry(
            userId = userId,
            kind = "USE",
            status = "RESERVED",
            amount = amount,
            remaining = null,
            expiresAt = null,
            orderItemId = null,
            orderId = orderId,
            createdAt = now,
        )
        em.persist(use)
        val bundles = em.createNativeQuery(
            "SELECT * FROM point_ledger" +
                " WHERE user_id = :user AND kind = 'EARN' AND status = 'AVAILABLE' AND expires_at > :now AND remaining > 0" +
                " ORDER BY expires_at, id",
            PointLedgerEntry::class.java,
        ).setParameter("user", userId).setParameter("now", now).resultList
        var need = amount
        for (row in bundles) {
            if (need == 0L) break
            val bundle = row as PointLedgerEntry
            val take = minOf(bundle.remaining!!, need)
            bundle.remaining = bundle.remaining!! - take
            need -= take
            em.persist(PointAllocation(entryId = use.id!!, earnEntryId = bundle.id!!, amount = take))
        }
        // 사용 가능액 확인을 같은 잠금 안에서 했으므로 모자랄 수 없다
        check(need == 0L) { "포인트 부족: user $userId, 부족 $need" }
        changeBalance(userId, availableDelta = -amount, pendingDelta = 0, now = now)
    }

    /** 해제(§5.2): USE RELEASED, 그 USE의 배분 행을 지우고 같은 묶음의 remaining·available을 되돌린다. */
    private fun releasePoints(use: PointLedgerEntry, now: Instant) {
        use.status = "RELEASED"
        val allocations = em.createQuery("SELECT a FROM PointAllocation a WHERE a.entryId = :use", PointAllocation::class.java)
            .setParameter("use", use.id)
            .resultList
        for (a in allocations) {
            val bundle = em.find(PointLedgerEntry::class.java, a.earnEntryId)
            bundle.remaining = bundle.remaining!! + a.amount
            em.remove(a)
        }
        changeBalance(use.userId, availableDelta = use.amount, pendingDelta = 0, now = now)
    }

    private fun useEntryOf(orderId: Long): PointLedgerEntry? =
        em.createQuery("SELECT l FROM PointLedgerEntry l WHERE l.orderId = :order AND l.kind = 'USE'", PointLedgerEntry::class.java)
            .setParameter("order", orderId)
            .resultList.firstOrNull()

    private fun earnOf(itemId: Long): PointLedgerEntry? =
        em.createQuery("SELECT l FROM PointLedgerEntry l WHERE l.orderItemId = :item AND l.kind = 'EARN'", PointLedgerEntry::class.java)
            .setParameter("item", itemId)
            .resultList.firstOrNull()

    /** 적립 확정(§8.2): 그 항목의 EARN이 PENDING이면 AVAILABLE, expires_at = confirmed_at + 365일, pending → available. 적립 행이 없으면 그대로. */
    private fun confirmEarn(item: OrderItem, buyerId: Long, confirmedAt: Instant, now: Instant) {
        val earn = earnOf(item.id!!) ?: return
        if (earn.status != "PENDING") return
        earn.status = "AVAILABLE"
        earn.expiresAt = confirmedAt.plus(Duration.ofDays(365))
        changeBalance(buyerId, availableDelta = earn.amount, pendingDelta = -earn.amount, now = now)
    }

    /** 적립 예정 취소(§7.3): 그 항목의 EARN이 PENDING이면 CANCELLED, pending 감소. remaining은 그대로(= amount). */
    private fun cancelPendingEarn(item: OrderItem, now: Instant) {
        val earn = earnOf(item.id!!) ?: return
        if (earn.status != "PENDING") return
        earn.status = "CANCELLED"
        changeBalance(earn.userId, availableDelta = 0, pendingDelta = -earn.amount, now = now)
    }

    /** 회수(§8.4): 그 항목의 EARN 묶음에 남은 양 전부를 REVOKE(배분 1행), remaining 0, available 감소. 이미 쓴 부분은 그대로. EARN은 AVAILABLE. */
    private fun revokeEarn(item: OrderItem, now: Instant) {
        val earn = earnOf(item.id!!) ?: return
        val left = earn.remaining!!
        if (earn.status != "AVAILABLE" || left == 0L) return
        val revoke = PointLedgerEntry(
            userId = earn.userId,
            kind = "REVOKE",
            status = "DONE",
            amount = left,
            orderItemId = item.id,
            orderId = item.orderId,
            createdAt = now,
        )
        em.persist(revoke)
        em.persist(PointAllocation(entryId = revoke.id!!, earnEntryId = earn.id!!, amount = left))
        earn.remaining = 0
        changeBalance(earn.userId, availableDelta = -left, pendingDelta = 0, now = now)
    }

    /**
     * 복구(§7.3): RESTORE 1행 + 배분. 되돌릴 묶음 = 그 주문의 CONFIRMED USE가 빠져나간 묶음, 만료가 늦은 묶음부터(같으면 id 큰 것부터),
     * 묶음마다 "그 USE가 가져간 양 − 이 주문의 앞선 RESTORE가 이미 되돌린 양"까지. 되돌린 묶음이 이미 만료됐으면(expires_at ≤ 현재)
     * RESTORE 뒤에 같은 시각으로 되돌린 양만큼 EXPIRE 1행 + 배분을 쓴다(remaining은 복구 전 값, 원래 남은 양은 point-expiry가 처리).
     * 구매자 행을 잠근 트랜잭션 안에서 부른다.
     */
    private fun restorePoints(order: PurchaseOrder, item: OrderItem, amount: Long, now: Instant) {
        val restore = PointLedgerEntry(
            userId = order.buyerId,
            kind = "RESTORE",
            status = "DONE",
            amount = amount,
            orderItemId = item.id,
            orderId = order.id,
            createdAt = now,
        )
        em.persist(restore)
        val rows = em.createNativeQuery(
            """
            SELECT u.earn_entry_id, u.used - coalesce(r.back, 0)
              FROM (SELECT a.earn_entry_id, sum(a.amount) AS used
                      FROM point_allocations a JOIN point_ledger l ON l.id = a.entry_id
                     WHERE l.order_id = :order AND l.kind = 'USE' AND l.status = 'CONFIRMED'
                     GROUP BY a.earn_entry_id) u
              LEFT JOIN (SELECT a.earn_entry_id, sum(a.amount) AS back
                           FROM point_allocations a JOIN point_ledger l ON l.id = a.entry_id
                          WHERE l.order_id = :order AND l.kind = 'RESTORE'
                          GROUP BY a.earn_entry_id) r ON r.earn_entry_id = u.earn_entry_id
              JOIN point_ledger e ON e.id = u.earn_entry_id
             ORDER BY e.expires_at DESC, e.id DESC
            """,
        ).setParameter("order", order.id).resultList
        var need = amount
        val expired = mutableListOf<Pair<PointLedgerEntry, Long>>()
        for (row in rows) {
            if (need == 0L) break
            val cols = row as Array<*>
            val take = minOf((cols[1] as Number).toLong(), need)
            if (take <= 0) continue
            val bundle = em.find(PointLedgerEntry::class.java, (cols[0] as Number).toLong())
            bundle.remaining = bundle.remaining!! + take
            need -= take
            em.persist(PointAllocation(entryId = restore.id!!, earnEntryId = bundle.id!!, amount = take))
            if (!bundle.expiresAt!!.isAfter(now)) expired += bundle to take
        }
        // 환불 포인트는 그 주문이 쓴 포인트를 넘지 않으므로(§7.1) 모자랄 수 없다
        check(need == 0L) { "복구 대상 부족: order ${order.id}, 부족 $need" }
        for ((bundle, take) in expired) {
            val expire = PointLedgerEntry(userId = order.buyerId, kind = "EXPIRE", status = "DONE", amount = take, createdAt = now)
            em.persist(expire)
            em.persist(PointAllocation(entryId = expire.id!!, earnEntryId = bundle.id!!, amount = take))
            bundle.remaining = bundle.remaining!! - take
        }
        changeBalance(order.buyerId, availableDelta = amount - expired.sumOf { it.second }, pendingDelta = 0, now = now)
    }

    /** point_balances 캐시 갱신. 행이 없는 사용자는 처음 바뀔 때 만든다. updated_at = 바꾼 시각. */
    private fun changeBalance(userId: Long, availableDelta: Long, pendingDelta: Long, now: Instant) {
        val balance = em.find(PointBalance::class.java, userId)
        if (balance == null) {
            em.persist(PointBalance(userId = userId, available = availableDelta, pending = pendingDelta, updatedAt = now))
        } else {
            balance.available = Math.addExact(balance.available, availableDelta)
            balance.pending = Math.addExact(balance.pending, pendingDelta)
            balance.updatedAt = now
        }
    }

    /** `GET /me/points`: available·pending = 캐시(행 없으면 0), usable = 사용 가능액(만료 묶음 제외). */
    fun getPoints(userId: Long): PointsView {
        val balance = em.find(PointBalance::class.java, userId)
        return PointsView(
            available = balance?.available ?: 0L,
            pending = balance?.pending ?: 0L,
            usable = usablePoints(userId, clock.now()),
        )
    }

    // ---- 배치: bid-expiry·payment-due·pg-inquiry (§7) ----

    /** `POST /admin/batches/{name}`: 현재 시각 기준 1회. 반환 = 상태가 바뀐 주 대상 수. 알 수 없는 이름은 400. */
    fun runBatch(name: String): Int {
        val now = clock.now()
        val started = System.nanoTime() // 소요 시간 로그용(업무 시각 아님)
        val processed = when (name) {
            "bid-expiry" -> expireBids(now)
            "payment-due" -> expireUnpaidTrades(now)
            "pg-inquiry" -> inquireUnknownOrders(now)
            else -> badRequest("알 수 없는 배치: $name")
        }
        log.info("배치 {} (현재 {}): processed={}, {}ms", name, now, processed, (System.nanoTime() - started) / 1_000_000)
        return processed
    }

    /**
     * 배치 대상 id를 id 순 키셋 페이지(500개씩)로 읽어 하나씩 처리한다. 반환 = handle이 true를 돌려준 수.
     * 배치를 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다.
     * condition은 별칭 t로 쓴 WHERE 조건, params는 그 조건의 이름 붙은 파라미터.
     */
    private fun forEachTarget(table: String, condition: String, params: Map<String, Any>, handle: (Long) -> Boolean): Int {
        val upTo = (em.createNativeQuery("SELECT coalesce(max(id), 0) FROM $table").singleResult as Number).toLong()
        var after = 0L
        var processed = 0
        while (true) {
            val query = em.createNativeQuery(
                "SELECT t.id FROM $table t WHERE ($condition) AND t.id > :after AND t.id <= :upTo ORDER BY t.id LIMIT 500",
            ).setParameter("after", after).setParameter("upTo", upTo)
            params.forEach { (name, value) -> query.setParameter(name, value) }
            val ids = query.resultList.map { (it as Number).toLong() }
            if (ids.isEmpty()) return processed
            for (id in ids) {
                if (handle(id)) processed++
            }
            after = ids.last()
        }
    }

    /** bid-expiry: ACTIVE이고 expires_at ≤ 현재인 입찰을 id 순으로 EXPIRED. 입찰마다 그 사이즈를 잠근 트랜잭션 하나. */
    private fun expireBids(now: Instant): Int =
        forEachTarget("bids", "t.status = 'ACTIVE' AND t.expires_at <= :now", mapOf("now" to now)) { bidId ->
            tx.execute {
                val bid = em.find(Bid::class.java, bidId)
                lockProductSize(bid.productSizeId)
                em.refresh(bid)
                // 잠금을 기다리는 사이에 취소·체결됐으면 건너뛴다
                if (bid.status != "ACTIVE" || bid.expiresAt.isAfter(now)) {
                    false
                } else {
                    bid.status = "EXPIRED"
                    em.persist(BidEvent(bidId = bidId, fromStatus = "ACTIVE", toStatus = "EXPIRED", tradeId = null, createdAt = now))
                    true
                }
            }!!
        }

    /**
     * payment-due: AWAITING_PAYMENT이고 payment_due_at ≤ 현재인 체결 중 결과가 정해지지 않은 주문(UNKNOWN·PAYING)이 없는 것을
     * id 순으로 만료한다. 체결 1건 = 그 사이즈를 잠근 트랜잭션 하나(§3.3).
     * UNKNOWN·PAYING 주문의 항목은 모두 AWAITING_PAYMENT이므로(verify order_status_vs_items) 항목 조건
     * status <> 'PAYMENT_FAILED'를 붙여도 뜻은 같고, 부분 인덱스 order_items_live_trade_key를 탄다.
     */
    private fun expireUnpaidTrades(now: Instant): Int =
        forEachTarget(
            "trades",
            "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= :now" +
                " AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id" +
                "                 WHERE i.trade_id = t.id AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING'))",
            mapOf("now" to now),
        ) { tradeId -> tx.execute { expireUnpaidTrade(tradeId, now) }!! }

    /**
     * 체결 1건 만료(§7). 잠금 순서: 사이즈 → 구매자. 구매자 행을 잠그면 그 구매자의 결제(구매자 잠금 안에서 PAYING 주문을 만든다)와
     * 줄을 서므로, 잠근 뒤 다시 확인한 결과가 결제와 엇갈리지 않는다.
     */
    private fun expireUnpaidTrade(tradeId: Long, now: Instant): Boolean {
        val trade = em.find(Trade::class.java, tradeId)
        lockProductSize(trade.productSizeId)
        lockUser(trade.buyerId)
        em.refresh(trade)
        val undecidedOrders = em.createNativeQuery(
            "SELECT count(*) FROM order_items i JOIN orders o ON o.id = i.order_id" +
                " WHERE i.trade_id = :trade AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING')",
        ).setParameter("trade", tradeId).singleResult as Number
        if (trade.status != "AWAITING_PAYMENT" || trade.paymentDueAt.isAfter(now) || undecidedOrders.toLong() > 0) return false

        // 만료를 바로 DB에 반영한다: 아래 재매칭이 같은 판매 입찰로 새 체결을 넣을 때 부분 UNIQUE(trades_live_sell_bid_key)에 걸리지 않게
        trade.status = "PAYMENT_EXPIRED"
        em.flush()

        // 구매 경고 1행. 최근 30일 안이면서 마지막 제한이 걸린 시각(restricted_until − 7일) 뒤의 경고가 3건 이상이면 7일 구매 제한
        em.persist(BuyerWarning(userId = trade.buyerId, tradeId = tradeId, createdAt = now))
        val buyer = em.find(User::class.java, trade.buyerId)
        em.refresh(buyer)
        val lastRestrictedAt = buyer.purchaseRestrictedUntil?.minus(Duration.ofDays(7))
        val recentWarnings = em.createQuery(
            "SELECT w.createdAt FROM BuyerWarning w WHERE w.userId = :user AND w.createdAt > :from",
            Instant::class.java,
        ).setParameter("user", trade.buyerId).setParameter("from", now.minus(Duration.ofDays(30))).resultList
            .count { lastRestrictedAt == null || it.isAfter(lastRestrictedAt) }
        if (recentWarnings >= 3) buyer.purchaseRestrictedUntil = now.plus(Duration.ofDays(7))

        // 구매 입찰 MATCHED → CANCELLED (판매 입찰 이벤트보다 먼저)
        val buy = em.find(Bid::class.java, trade.buyBidId)
        buy.status = "CANCELLED"
        em.persist(BidEvent(bidId = buy.id!!, fromStatus = "MATCHED", toStatus = "CANCELLED", tradeId = tradeId, createdAt = now))

        // 판매 입찰: 유효기간이 지났으면 EXPIRED, 아니면 ACTIVE로 복구(원래 created_at 유지)한 뒤 새 입찰처럼 바로 재매칭
        val sell = em.find(Bid::class.java, trade.sellBidId)
        if (!sell.expiresAt.isAfter(now)) {
            sell.status = "EXPIRED"
            em.persist(BidEvent(bidId = sell.id!!, fromStatus = "MATCHED", toStatus = "EXPIRED", tradeId = tradeId, createdAt = now))
        } else {
            sell.status = "ACTIVE"
            em.persist(BidEvent(bidId = sell.id!!, fromStatus = "MATCHED", toStatus = "ACTIVE", tradeId = tradeId, createdAt = now))
            val counter = findBestCounterBid(sell.productSizeId, "SELL", sell.userId, sell.price, now)
            if (counter != null) matchBids(sell, counter, now)
        }
        return true
    }

    /**
     * pg-inquiry: UNKNOWN 주문을 id 순으로 PG에 다시 묻는다. PG 호출 중에는 트랜잭션·잠금을 잡지 않는다.
     * 승인 → §5.1 승인 처리(paid_at = APPROVE 행의 requested_at), 404·거절 → 거절 처리, 응답 없음 → 행 없이 UNKNOWN 유지.
     */
    private fun inquireUnknownOrders(now: Instant): Int =
        forEachTarget("orders", "t.status = 'UNKNOWN'", emptyMap()) { orderId ->
            val key = em.find(PurchaseOrder::class.java, orderId).idempotencyKey
            val (status, pgTxId) = pgResult(callPg("재조회 $key") { pg.getApproval(key) }, inquiry = true)
            if (status == "UNKNOWN") {
                false
            } else {
                tx.execute {
                    val order = em.find(PurchaseOrder::class.java, orderId)
                    lockUser(order.buyerId)
                    em.refresh(order)
                    // 다른 재조회가 먼저 확정했으면 건너뛴다
                    if (order.status != "UNKNOWN") return@execute false
                    em.persist(
                        PgTransaction(
                            orderId = orderId,
                            kind = "INQUIRY",
                            amount = order.cardAmount,
                            status = status,
                            pgTxId = pgTxId,
                            idempotencyKey = key,
                            requestedAt = now,
                            respondedAt = now,
                        ),
                    )
                    if (status == "APPROVED") {
                        val requestedAt = em.createQuery(
                            "SELECT p.requestedAt FROM PgTransaction p WHERE p.orderId = :order AND p.kind = 'APPROVE'",
                            Instant::class.java,
                        ).setParameter("order", orderId).singleResult
                        approveOrder(orderId, paidAt = requestedAt, now = now)
                    } else {
                        declineOrder(orderId, now)
                    }
                    true
                }!!
            }
        }

    // ---- 기동 (ADR-0003 §9의 7) ----

    /**
     * 기동 시 전환: 남아 있는 PAYING 주문(직전 프로세스가 PG 호출 전후에 죽어 결과를 쓰지 못한 것)을 UNKNOWN으로 바꾸고
     * 주문마다 APPROVE UNKNOWN 1행(requested_at = 주문 created_at)을 쓴다. 이후 확정은 pg-inquiry가 한다.
     * 앱이 요청을 받기 전에 부른다(StartupRecovery). 앱 인스턴스는 하나라고 전제한다.
     */
    fun convertStalePayingOrders() {
        val converted = forEachTarget("orders", "t.status = 'PAYING'", emptyMap()) { orderId ->
            tx.execute {
                val order = em.find(PurchaseOrder::class.java, orderId)
                lockUser(order.buyerId)
                em.refresh(order)
                // 결제 결과 반영과 같은 원리: 잠근 뒤 다시 읽어 아직 PAYING이고 APPROVE 행이 없을 때만 쓴다
                if (order.status != "PAYING" || approveRowCount(orderId) > 0) return@execute false
                em.persist(
                    PgTransaction(
                        orderId = orderId,
                        kind = "APPROVE",
                        amount = order.cardAmount,
                        status = "UNKNOWN",
                        pgTxId = null,
                        idempotencyKey = order.idempotencyKey,
                        requestedAt = order.createdAt,
                        respondedAt = null,
                    ),
                )
                order.status = "UNKNOWN"
                true
            }!!
        }
        if (converted > 0) log.warn("기동 시 PAYING 주문 {}건을 UNKNOWN으로 바꿨다(확정은 pg-inquiry)", converted)
    }
}
