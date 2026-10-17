package com.ccle.god

import com.ccle.god.entity.Bid
import com.ccle.god.entity.BidEvent
import com.ccle.god.entity.BuyerWarning
import com.ccle.god.entity.OrderItem
import com.ccle.god.entity.PgTransaction
import com.ccle.god.entity.PointAllocation
import com.ccle.god.entity.PointBalance
import com.ccle.god.entity.PointLedgerEntry
import com.ccle.god.entity.ProductSize
import com.ccle.god.entity.PurchaseOrder
import com.ccle.god.entity.Trade
import com.ccle.god.entity.User
import com.ccle.god.web.ApiException
import com.ccle.god.web.BidCancelled
import com.ccle.god.web.BidPlaced
import com.ccle.god.web.BidView
import com.ccle.god.web.BookView
import com.ccle.god.web.OrderItemView
import com.ccle.god.web.OrderView
import com.ccle.god.web.PaymentResult
import com.ccle.god.web.PointsView
import com.ccle.god.web.TradeView
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * 거래소의 절차·규칙·저장 전부(ADR-0003 1단계: 입찰·체결·결제·포인트·배치).
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
) {
    private val log = LoggerFactory.getLogger(Marketplace::class.java)

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
            // 다른 구매자가 같은 키로 동시에 주문을 만들어 orders_idempotency_key_key에 걸린 경우.
            // (같은 구매자의 요청은 사용자 행 잠금으로 줄을 서므로 여기 오지 않는다.) 다시 하면 멱등 키 조회에서 판정된다.
            log.info("주문 생성 충돌, 멱등 키로 다시 조회: {} ({})", idempotencyKey, e.toString())
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        }
        val orderId = created.first
        val cardAmount = created.second
            ?: return PaymentResult(created = false, order = orderView(orderId))

        // PG 호출 중에는 트랜잭션·잠금을 잡지 않는다
        val reply = pg.approve(idempotencyKey, cardAmount)
        tx.execute {
            val status = when (reply?.status) {
                "APPROVED" -> "APPROVED"
                "DECLINED" -> "DECLINED"
                null -> "UNKNOWN"
                else -> {
                    log.warn("PG가 알 수 없는 상태를 돌려줌: {} — 응답 없음으로 본다", reply)
                    "UNKNOWN"
                }
            }
            em.persist(
                PgTransaction(
                    orderId = orderId,
                    kind = "APPROVE",
                    amount = cardAmount,
                    status = status,
                    pgTxId = if (status == "APPROVED") reply!!.pgTxId else null,
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
        return PaymentResult(created = true, order = orderView(orderId))
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

        // 금액(ADR-0002 §5.1·§9.2). 항목은 tradeId 오름차순 = order_items.id 순서
        val fees = trades.map { maxOf(it.price * 3 / 100, 1_000L) }
        val itemTotal = trades.sumOf { it.price }
        val feeTotal = fees.sum()
        val shippingFee = if (itemTotal >= 100_000) 0L else 3_000L
        val total = itemTotal + feeTotal + shippingFee

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
        val bases = trades.indices.map { i -> trades[i].price + fees[i] + (if (i == 0) shippingFee else 0L) }
        val cardShares = bases.map { Math.multiplyExact(cardAmount, it) / total }.toMutableList()
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

    private fun pointRule(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "POINT_RULE_VIOLATION", message)

    /** 구매자 행 잠금. 결제·포인트 잔액을 바꾸는 처리는 이 잠금 뒤에 한다. */
    private fun lockUser(userId: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE").setParameter("id", userId).resultList
    }

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
        val grade = em.find(User::class.java, order.buyerId).grade
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
            val base = item.price + item.buyerFee + item.shippingShare
            val earn = Math.multiplyExact(item.cardShare, item.price) / base * ratePermille / 1000
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
                pendingAdded += earn
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

    /** §5.1 응답 모양. 항목은 order_items.id 순서(= tradeId 오름차순). */
    private fun orderView(orderId: Long): OrderView {
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

    /** point_balances 캐시 갱신. 행이 없는 사용자는 처음 바뀔 때 만든다. updated_at = 바꾼 시각. */
    private fun changeBalance(userId: Long, availableDelta: Long, pendingDelta: Long, now: Instant) {
        val balance = em.find(PointBalance::class.java, userId)
        if (balance == null) {
            em.persist(PointBalance(userId = userId, available = availableDelta, pending = pendingDelta, updatedAt = now))
        } else {
            balance.available += availableDelta
            balance.pending += pendingDelta
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
        val started = System.nanoTime()
        val processed = when (name) {
            "bid-expiry" -> expireBids(now)
            "payment-due" -> expireUnpaidTrades(now)
            "pg-inquiry" -> inquireUnknownOrders(now)
            else -> badRequest("알 수 없는 배치: $name")
        }
        log.info("배치 {} (현재 {}): processed={}, {}ms", name, now, processed, (System.nanoTime() - started) / 1_000_000)
        return processed
    }

    /** bid-expiry: ACTIVE이고 expires_at ≤ 현재인 입찰을 id 순으로 EXPIRED. 입찰마다 그 사이즈를 잠근 트랜잭션 하나. */
    private fun expireBids(now: Instant): Int {
        val targets = em.createNativeQuery(
            "SELECT id FROM bids WHERE status = 'ACTIVE' AND expires_at <= :now ORDER BY id",
        ).setParameter("now", now).resultList.map { (it as Number).toLong() }
        var processed = 0
        for (bidId in targets) {
            val changed = tx.execute {
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
            if (changed) processed++
        }
        return processed
    }

    /**
     * payment-due: AWAITING_PAYMENT이고 payment_due_at ≤ 현재인 체결 중 결과가 정해지지 않은 주문(UNKNOWN·PAYING)이 없는 것을
     * id 순으로 만료한다. 체결 1건 = 그 사이즈를 잠근 트랜잭션 하나(§3.3).
     */
    private fun expireUnpaidTrades(now: Instant): Int {
        val targets = em.createNativeQuery(
            "SELECT t.id FROM trades t" +
                " WHERE t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= :now" +
                " AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id" +
                "                 WHERE i.trade_id = t.id AND o.status IN ('UNKNOWN', 'PAYING'))" +
                " ORDER BY t.id",
        ).setParameter("now", now).resultList.map { (it as Number).toLong() }
        var processed = 0
        for (tradeId in targets) {
            if (tx.execute { expireUnpaidTrade(tradeId, now) }!!) processed++
        }
        return processed
    }

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
                " WHERE i.trade_id = :trade AND o.status IN ('UNKNOWN', 'PAYING')",
        ).setParameter("trade", tradeId).singleResult as Number
        if (trade.status != "AWAITING_PAYMENT" || trade.paymentDueAt.isAfter(now) || undecidedOrders.toLong() > 0) return false

        trade.status = "PAYMENT_EXPIRED"

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
    private fun inquireUnknownOrders(now: Instant): Int {
        val targets = em.createQuery("SELECT o FROM PurchaseOrder o WHERE o.status = 'UNKNOWN' ORDER BY o.id", PurchaseOrder::class.java)
            .resultList
        var processed = 0
        for (target in targets) {
            val key = target.idempotencyKey
            val reply = pg.inquire(key)
            val status = when (reply?.status) {
                "APPROVED" -> "APPROVED"
                "DECLINED", "NOT_FOUND" -> "DECLINED"
                null -> null
                else -> {
                    log.warn("PG 재조회가 알 수 없는 상태를 돌려줌: {} — 응답 없음으로 본다", reply)
                    null
                }
            }
            if (status == null) continue
            val changed = tx.execute {
                lockUser(target.buyerId)
                val order = em.find(PurchaseOrder::class.java, target.id)
                em.refresh(order)
                // 다른 재조회가 먼저 확정했으면 건너뛴다
                if (order.status != "UNKNOWN") return@execute false
                em.persist(
                    PgTransaction(
                        orderId = order.id!!,
                        kind = "INQUIRY",
                        amount = order.cardAmount,
                        status = status,
                        pgTxId = if (status == "APPROVED") reply!!.pgTxId else null,
                        idempotencyKey = key,
                        requestedAt = now,
                        respondedAt = now,
                    ),
                )
                if (status == "APPROVED") {
                    val requestedAt = em.createQuery(
                        "SELECT p.requestedAt FROM PgTransaction p WHERE p.orderId = :order AND p.kind = 'APPROVE'",
                        Instant::class.java,
                    ).setParameter("order", order.id).singleResult
                    approveOrder(order.id!!, paidAt = requestedAt, now = now)
                } else {
                    declineOrder(order.id!!, now)
                }
                true
            }!!
            if (changed) processed++
        }
        return processed
    }
}
