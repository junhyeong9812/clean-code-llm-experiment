package com.ccle.god

import com.ccle.god.entity.AdminAuditLog
import com.ccle.god.entity.Bid
import com.ccle.god.entity.BidEvent
import com.ccle.god.entity.BuyerWarning
import com.ccle.god.entity.Dispute
import com.ccle.god.entity.FeeRebate
import com.ccle.god.entity.InboundReservation
import com.ccle.god.entity.InboundSlot
import com.ccle.god.entity.Inquiry
import com.ccle.god.entity.InquiryMessage
import com.ccle.god.entity.Inspection
import com.ccle.god.entity.MonthlyStatement
import com.ccle.god.entity.Notification
import com.ccle.god.entity.OrderItem
import com.ccle.god.entity.PayoutAccountRequest
import com.ccle.god.entity.PgTransaction
import com.ccle.god.entity.PointAllocation
import com.ccle.god.entity.PointBalance
import com.ccle.god.entity.PointLedgerEntry
import com.ccle.god.entity.PriceAlert
import com.ccle.god.entity.PriceStatDaily
import com.ccle.god.entity.ProductSize
import com.ccle.god.entity.PurchaseOrder
import com.ccle.god.entity.Refund
import com.ccle.god.entity.Review
import com.ccle.god.entity.SellerGrade
import com.ccle.god.entity.SellerPenalty
import com.ccle.god.entity.SellerProfile
import com.ccle.god.entity.SellerRating
import com.ccle.god.entity.Settlement
import com.ccle.god.entity.SettlementItem
import com.ccle.god.entity.ShipmentDelay
import com.ccle.god.entity.ShipmentTrackingEvent
import com.ccle.god.entity.TaxInvoice
import com.ccle.god.entity.Trade
import com.ccle.god.entity.User
import com.ccle.god.entity.Wishlist
import com.ccle.god.web.AdminDisputeView
import com.ccle.god.web.AdminSettlementView
import com.ccle.god.web.ApiException
import com.ccle.god.web.AuditLogView
import com.ccle.god.web.BatchResult
import com.ccle.god.web.BidCancelled
import com.ccle.god.web.BidPlaced
import com.ccle.god.web.BidView
import com.ccle.god.web.BookView
import com.ccle.god.web.DisputeOpened
import com.ccle.god.web.DisputeView
import com.ccle.god.web.FeeRebateView
import com.ccle.god.web.InboundReservationView
import com.ccle.god.web.InboundSlotView
import com.ccle.god.web.InquiryDetailView
import com.ccle.god.web.InquiryMessageView
import com.ccle.god.web.InquiryOrderItemView
import com.ccle.god.web.InquiryView
import com.ccle.god.web.ItemView
import com.ccle.god.web.MonthlyStatementView
import com.ccle.god.web.NotificationView
import com.ccle.god.web.OrderItemView
import com.ccle.god.web.OrderView
import com.ccle.god.web.PageView
import com.ccle.god.web.PaymentResult
import com.ccle.god.web.PayoutAccountView
import com.ccle.god.web.PayoutRequestView
import com.ccle.god.web.PenaltyView
import com.ccle.god.web.PointsView
import com.ccle.god.web.PriceAlertView
import com.ccle.god.web.PriceStatView
import com.ccle.god.web.RecentTradeView
import com.ccle.god.web.RefundView
import com.ccle.god.web.RestrictedUserView
import com.ccle.god.web.ReviewView
import com.ccle.god.web.SellerGradeView
import com.ccle.god.web.SellerGradesView
import com.ccle.god.web.SellerProfileView
import com.ccle.god.web.SellerReviewsView
import com.ccle.god.web.SettlementItemView
import com.ccle.god.web.SettlementView
import com.ccle.god.web.ShipmentDelayView
import com.ccle.god.web.TaxInvoiceCorrected
import com.ccle.god.web.TaxInvoiceView
import com.ccle.god.web.TrackingEventView
import com.ccle.god.web.TrackingView
import com.ccle.god.web.TradeView
import com.ccle.god.web.UserSummaryView
import com.ccle.god.web.WarningView
import com.ccle.god.web.WishlistEntryView
import com.ccle.god.web.WishlistItemView
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 거래소의 절차·규칙·저장 전부(ADR-0003 1단계: 입찰·체결·결제·포인트·배치, ADR-0004 2단계: 주문 항목 이후 절차·주간 정산).
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

    // ---- 배치: bid-expiry·payment-due·pg-inquiry (ADR-0003 §7), shipment-deadline·auto-confirm·point-expiry·grade-recalc·weekly-settlement (ADR-0004 §5) ----

    /**
     * `POST /admin/batches/{name}`: 현재 시각 기준 1회. processed = 상태가 바뀐 주 대상 수(grade-recalc는 쓴 이력 행 수, weekly-settlement는 만든 정산 행 수),
     * failed = 커밋되지 못한 대상 수(ADR-0003 §12의 2). 알 수 없는 이름은 400. 대상 1건 = 트랜잭션 하나(weekly-settlement는 한 주 = 트랜잭션 하나).
     * weekly-settlement만 격리하지 않는다: 예외가 나면 그 자리에서 멈추고 요청은 오류 응답으로 끝난다(ADR-0004 §15의 1).
     */
    fun runBatch(name: String): BatchResult {
        val now = clock.now()
        val started = System.nanoTime() // 소요 시간 로그용(업무 시각 아님)
        val result = when (name) {
            "bid-expiry" -> expireBids(now)
            "payment-due" -> expireUnpaidTrades(now)
            "pg-inquiry" -> inquireUnknownOrders(now)
            "shipment-deadline" -> cancelUnshippedItems(now)
            "auto-confirm" -> autoConfirmItems(now, isolatedAs = name)
            "point-expiry" -> expirePoints(now)
            "grade-recalc" -> recalcGrades(now)
            "weekly-settlement" -> BatchResult(settleWeeks(now), failed = 0)
            "rating-recalc" -> recalcRatings(now)
            "seller-grade-recalc" -> recalcSellerGrades(now)
            "rebate-settlement" -> settleRebates(now)
            "monthly-statement" -> writeMonthlyStatements(now)
            "tax-invoice-issue" -> issueTaxInvoices(now)
            "deadline-reminder" -> remindDeadlines(now)
            "price-alert-scan" -> scanPriceAlerts(now)
            "delivery-delay-scan" -> scanDeliveryDelays(now)
            "inquiry-auto-close" -> autoCloseInquiries(now)
            "price-stats" -> writePriceStats(now)
            "inbound-no-show" -> markInboundNoShows(now)
            else -> badRequest("알 수 없는 배치: $name")
        }
        log.info(
            "배치 {} (현재 {}): processed={}, failed={}, {}ms",
            name, now, result.processed, result.failed, (System.nanoTime() - started) / 1_000_000,
        )
        return result
    }

    /**
     * 배치 대상 id를 id 순 키셋 페이지(500개씩)로 읽어 하나씩 처리한다. processed = handle이 true를 돌려준 수.
     * 배치를 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다.
     * condition은 별칭 t로 쓴 WHERE 조건, params는 그 조건의 이름 붙은 파라미터.
     * isolatedAs(배치 이름)가 있으면 대상 1건의 예외는 그 대상의 실패로 세고 다음 대상을 계속한다(ADR-0003 §12의 2) — 대상의 트랜잭션은
     * 롤백됐고(커밋 단계 예외면 커밋 여부 불명) 대상 조건이 그대로 남으므로 다음 실행의 대상이다. 카드 취소 실패(409 PG_UNAVAILABLE)는 WARN, 그 밖은 ERROR로 남긴다.
     * 없으면(기동 시 전환, weekly-settlement 안의 auto-confirm) 예외가 그대로 전파된다.
     */
    private fun forEachTarget(
        table: String,
        condition: String,
        params: Map<String, Any>,
        isolatedAs: String? = null,
        handle: (Long) -> Boolean,
    ): BatchResult {
        val upTo = (em.createNativeQuery("SELECT coalesce(max(id), 0) FROM $table").singleResult as Number).toLong()
        var after = 0L
        var processed = 0
        var failed = 0
        while (true) {
            val query = em.createNativeQuery(
                "SELECT t.id FROM $table t WHERE ($condition) AND t.id > :after AND t.id <= :upTo ORDER BY t.id LIMIT 500",
            ).setParameter("after", after).setParameter("upTo", upTo)
            params.forEach { (name, value) -> query.setParameter(name, value) }
            val ids = query.resultList.map { (it as Number).toLong() }
            if (ids.isEmpty()) return BatchResult(processed, failed)
            for (id in ids) {
                val changed = if (isolatedAs == null) {
                    handle(id)
                } else {
                    try {
                        handle(id)
                    } catch (e: Exception) {
                        failed++
                        if (e is ApiException && e.code == "PG_UNAVAILABLE") {
                            log.warn("{}: 대상 {} 건너뜀(카드 취소 실패)", isolatedAs, id)
                        } else {
                            log.error("{}: 대상 {} 실패(반영 안 됨 — 커밋 단계 예외면 커밋 여부 불명, 다음 실행이 다시 판정)", isolatedAs, id, e)
                        }
                        false
                    }
                }
                if (changed) processed++
            }
            after = ids.last()
        }
    }

    /** bid-expiry: ACTIVE이고 expires_at ≤ 현재인 입찰을 id 순으로 EXPIRED. 입찰마다 그 사이즈를 잠근 트랜잭션 하나. */
    private fun expireBids(now: Instant): BatchResult =
        forEachTarget("bids", "t.status = 'ACTIVE' AND t.expires_at <= :now", mapOf("now" to now), isolatedAs = "bid-expiry") { bidId ->
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
    private fun expireUnpaidTrades(now: Instant): BatchResult =
        forEachTarget(
            "trades",
            "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= :now" +
                " AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id" +
                "                 WHERE i.trade_id = t.id AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING'))",
            mapOf("now" to now),
            isolatedAs = "payment-due",
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
    private fun inquireUnknownOrders(now: Instant): BatchResult =
        forEachTarget("orders", "t.status = 'UNKNOWN'", emptyMap(), isolatedAs = "pg-inquiry") { orderId ->
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

    /**
     * shipment-deadline(ADR-0004 §5): PAID이고 paid_at + 3일 ≤ 현재인 항목을 id 순으로 §6 판매자 귀책(사유 `발송 기한 초과`).
     * 항목 1건 = 주문 → 항목을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다(그 사이 구매자 취소·발송됐으면 건너뜀).
     * 카드 취소 실패(409 PG_UNAVAILABLE)면 그 트랜잭션을 롤백하고 건너뛴다(§7.2 — 격리 순회가 실패로 센다). 다음 실행의 대상으로 남는다.
     */
    private fun cancelUnshippedItems(now: Instant): BatchResult =
        forEachTarget(
            "order_items",
            "t.status = 'PAID' AND t.paid_at <= :due",
            mapOf("due" to now.minus(Duration.ofDays(3))),
            isolatedAs = "shipment-deadline",
        ) { itemId ->
            tx.execute {
                val item = lockOrderOf(itemId)
                if (item.status != "PAID" || item.paidAt!!.plus(Duration.ofDays(3)).isAfter(now)) {
                    false
                } else {
                    sellerFault(item, reason = "발송 기한 초과", now = now)
                    true
                }
            }!!
        }

    /**
     * auto-confirm(ADR-0004 §5): DELIVERED이고 delivered_at + 7일 ≤ 현재인 항목을 id 순으로 CONFIRMED, confirmed_at = delivered_at + 7일(소급) → §8.2.
     * 항목 1건 = 주문 → 항목을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다(그 사이 구매자가 확정했으면 건너뜀).
     * 단독 배치는 격리하고(isolatedAs), weekly-settlement 안에서는 격리하지 않는다(ADR-0004 §15의 1 — 실패하면 정산하지 않는다).
     */
    private fun autoConfirmItems(now: Instant, isolatedAs: String?): BatchResult =
        forEachTarget(
            "order_items",
            "t.status = 'DELIVERED' AND t.delivered_at <= :due",
            mapOf("due" to now.minus(Duration.ofDays(7))),
            isolatedAs,
        ) { itemId ->
            tx.execute {
                val item = lockOrderOf(itemId)
                val due = item.deliveredAt?.plus(Duration.ofDays(7))
                if (item.status != "DELIVERED" || due!!.isAfter(now)) {
                    false
                } else {
                    confirmItem(item, confirmedAt = due, now = now)
                    true
                }
            }!!
        }

    /**
     * point-expiry(ADR-0004 §8.3): EARN AVAILABLE, remaining > 0, expires_at ≤ 현재인 묶음을 id 순으로 소멸.
     * 묶음마다 EXPIRE 1행(amount = remaining, 주문 NULL, created_at = 현재) + 배분 1행, remaining 0(EARN은 AVAILABLE), available 감소.
     * 묶음 1개 = 그 사용자 행을 잠근 트랜잭션 하나(묶음 remaining을 바꾸는 처리는 모두 이 잠금 안에서 한다). 다른 잠금은 잡지 않는다.
     */
    private fun expirePoints(now: Instant): BatchResult =
        forEachTarget(
            "point_ledger",
            "t.kind = 'EARN' AND t.status = 'AVAILABLE' AND t.remaining > 0 AND t.expires_at <= :now",
            mapOf("now" to now),
            isolatedAs = "point-expiry",
        ) { earnId ->
            tx.execute {
                val earn = em.find(PointLedgerEntry::class.java, earnId)
                lockUser(earn.userId)
                em.refresh(earn)
                val left = earn.remaining!!
                if (earn.status != "AVAILABLE" || left == 0L || earn.expiresAt!!.isAfter(now)) {
                    false
                } else {
                    val expire = PointLedgerEntry(userId = earn.userId, kind = "EXPIRE", status = "DONE", amount = left, createdAt = now)
                    em.persist(expire)
                    em.persist(PointAllocation(entryId = expire.id!!, earnEntryId = earnId, amount = left))
                    earn.remaining = 0
                    changeBalance(earn.userId, availableDelta = -left, pendingDelta = 0, now = now)
                    true
                }
            }!!
        }

    /**
     * 등급 재산정의 기준 금액(ADR-0004 §8.5): 사용자 t가 구매자인 항목 중 CONFIRMED·DISPUTED이고 confirmed_at ∈ [:from, :to)의 체결가 합.
     * 등급 경계: VIP ≥ 5,000,000 · GOLD ≥ 2,000,000 · SILVER ≥ 500,000 · 그 밖 BRONZE.
     */
    private val gradeBasisSql =
        "(SELECT coalesce(sum(i.price), 0) FROM order_items i JOIN orders o ON o.id = i.order_id" +
            " WHERE o.buyer_id = t.id AND i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at >= :from AND i.confirmed_at < :to)"
    private val gradeSql =
        "(SELECT CASE WHEN b.v >= 5000000 THEN 'VIP' WHEN b.v >= 2000000 THEN 'GOLD' WHEN b.v >= 500000 THEN 'SILVER' ELSE 'BRONZE' END" +
            " FROM (SELECT $gradeBasisSql AS v) b)"

    /**
     * grade-recalc(ADR-0004 §8.5): 기준일 D = 현재가 속한 달 1일(KST), 기간 = [D − 6개월, D)(KST 00:00).
     * 대상 = effective_from = D 이력이 없고 계산한 등급이 users.grade와 다른 사용자(한 번의 집계 조건으로 거른다), id 순.
     * 사용자 1명 = 그 사용자 행을 잠근 트랜잭션 하나. 잠근 뒤 이력 유무·기준 금액·users.grade를 다시 읽어 판정한다
     * (기준 금액을 바꾸는 확정·분쟁 인정은 구매자 행을 잠그므로 잠근 뒤의 값이 최신이다). 반환 = 쓴 이력 행 수.
     */
    private fun recalcGrades(now: Instant): BatchResult {
        val kst = ZoneOffset.ofHours(9)
        val d = LocalDate.ofInstant(now, kst).withDayOfMonth(1)
        val params = mapOf<String, Any>(
            "d" to d,
            "from" to d.minusMonths(6).atStartOfDay(kst).toInstant(),
            "to" to d.atStartOfDay(kst).toInstant(),
        )
        val noHistoryAtD = "NOT EXISTS (SELECT 1 FROM user_grade_history h WHERE h.user_id = t.id AND h.effective_from = :d)"
        return forEachTarget("users", "$noHistoryAtD AND t.grade <> $gradeSql", params, isolatedAs = "grade-recalc") { userId ->
            tx.execute {
                lockUser(userId)
                val query = em.createNativeQuery(
                    "SELECT $gradeSql, $gradeBasisSql, t.grade, $noHistoryAtD FROM users t WHERE t.id = :user",
                ).setParameter("user", userId)
                params.forEach { (name, value) -> query.setParameter(name, value) }
                val row = query.singleResult as Array<*>
                val grade = row[0] as String
                val basis = (row[1] as Number).toLong()
                if (row[3] != true || grade == row[2]) return@execute false
                em.createNativeQuery(
                    "INSERT INTO user_grade_history (user_id, grade, effective_from, basis_amount) VALUES (:user, :grade, :d, :basis)",
                ).setParameter("user", userId).setParameter("grade", grade).setParameter("d", d).setParameter("basis", basis)
                    .executeUpdate()
                em.find(User::class.java, userId).grade = grade
                true
            }!!
        }
    }

    // ---- 주간 정산 (ADR-0004 §10) ----

    private val kst: ZoneOffset = ZoneOffset.ofHours(9)

    /**
     * 정산 주(마감 :cutoff)의 대상 항목 조건(§10.2, 별칭 i). 경계는 모두 cutoff 배타.
     * INCLUDED·HELD: CONFIRMED·DISPUTED이고 confirmed_at < cutoff, 아직 INCLUDED가 없는 것(HELD는 주마다 다시 쓴다).
     * CLAWBACK: REFUNDED_AFTER_CONFIRM이고 cancelled_at < cutoff, INCLUDED 수 > CLAWBACK 수.
     */
    private val settlementTargetSql =
        "(i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at < :cutoff" +
            " AND NOT EXISTS (SELECT 1 FROM settlement_items s WHERE s.order_item_id = i.id AND s.status = 'INCLUDED'))" +
            " OR (i.status = 'REFUNDED_AFTER_CONFIRM' AND i.cancelled_at < :cutoff" +
            " AND (SELECT count(*) FILTER (WHERE s.status = 'INCLUDED') - count(*) FILTER (WHERE s.status = 'CLAWBACK')" +
            "      FROM settlement_items s WHERE s.order_item_id = i.id) > 0)"

    /**
     * weekly-settlement(§10.1): 먼저 auto-confirm을 수행하고(소급 확정 시각이 이미 마감된 주에 들어가지 않게), 정산할 주를 오래된 주부터 정산한다.
     * 마감 = 월요일 00:00 KST. 정산할 주 = 마감 ≤ 현재이고 마감이 지금까지 만든 정산의 마지막 마감(max(period_start) + 7일)보다 뒤인 주.
     * 정산 행이 하나도 없으면 현재 직전 마감 1주만. 한 주 = 모든 판매자를 트랜잭션 하나로(§9.2 — 실패하면 그 주 전체가 커밋되지 않는다).
     */
    private fun settleWeeks(now: Instant): Int {
        autoConfirmItems(now, isolatedAs = null)
        val latest = LocalDate.ofInstant(now, kst).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastPeriodStart = em.createQuery("SELECT max(s.periodStart) FROM Settlement s", LocalDate::class.java).singleResult
        var cutoff = lastPeriodStart?.plusDays(14) ?: latest
        var processed = 0
        while (!cutoff.isAfter(latest)) {
            val week = cutoff
            processed += tx.execute { settleWeek(week) }!!
            cutoff = cutoff.plusDays(7)
        }
        return processed
    }

    /**
     * 한 주(마감일 cutoffDate)의 정산(§10.2). 반환 = 만든 정산 행 수.
     * 잠금(§9.1): 대상 항목 행을 id 순으로 잠근 뒤(order_items만 — 다른 테이블 행은 잠그지 않는다) 새 문장으로 대상 조건을 다시 평가한다.
     * 정산 행 INSERT는 판매자 users 행에 FK 잠금을 거는데, 그 전에 항목을 모두 잠갔으므로 주문 → 항목 → 사용자 순서의 항목 처리와 교착하지 않는다.
     * 판매자 = 대상·미정산 패널티(created_at < cutoff)·음수 이월(직전 정산 carry_out < 0) 중 하나라도 있는 판매자, id 순.
     * 금액: INCLUDED (체결가, floor(체결가 × 카테고리 수수료율)), HELD (0, 0), CLAWBACK (−체결가, −수수료).
     * net = gross − fee_total − penalty_total + carry_in, payout = max(net, 0), carry_out = min(net, 0),
     * payout > 0이면 PAID·paid_at = period_start + 9일 00:00 KST(그 주 다음 수요일), 아니면 CARRIED·paid_at NULL.
     */
    private fun settleWeek(cutoffDate: LocalDate): Int {
        val cutoff = cutoffDate.atStartOfDay(kst).toInstant()
        val periodStart = cutoffDate.minusDays(7)
        em.createNativeQuery("SELECT i.id FROM order_items i WHERE $settlementTargetSql ORDER BY i.id FOR UPDATE")
            .setParameter("cutoff", cutoff).resultList

        // 수수료는 DB에서 계산한다: sale_fee_rate가 numeric이라 체결가 × 율이 정확하고 floor가 verify와 같은 식이다
        val targets = em.createNativeQuery(
            "SELECT i.id, t.seller_id, i.status, i.price, CAST(floor(i.price * c.sale_fee_rate) AS bigint)" +
                " FROM order_items i JOIN trades t ON t.id = i.trade_id JOIN product_sizes ps ON ps.id = t.product_size_id" +
                " JOIN products p ON p.id = ps.product_id JOIN categories c ON c.id = p.category_id" +
                " WHERE $settlementTargetSql ORDER BY i.id",
        ).setParameter("cutoff", cutoff).resultList.map { row ->
            row as Array<*>
            val price = (row[3] as Number).toLong()
            val fee = (row[4] as Number).toLong()
            val (status, amount, itemFee) = when (row[2]) {
                "CONFIRMED" -> Triple("INCLUDED", price, fee)
                "DISPUTED" -> Triple("HELD", 0L, 0L)
                else -> Triple("CLAWBACK", -price, -fee)
            }
            (row[1] as Number).toLong() to
                SettlementItem(settlementId = 0, orderItemId = (row[0] as Number).toLong(), amount = amount, fee = itemFee, status = status)
        }.groupBy({ it.first }, { it.second })
        val penalties = em.createQuery(
            "SELECT p FROM SellerPenalty p WHERE p.settlementId IS NULL AND p.createdAt < :cutoff ORDER BY p.id",
            SellerPenalty::class.java,
        ).setParameter("cutoff", cutoff).resultList.groupBy { it.sellerId }
        // 판매자의 직전 정산 = period_start가 가장 늦은 정산. carry_out이 0이면 carry_in 0이므로 음수만 읽는다
        val carries = em.createNativeQuery(
            "SELECT s.seller_id, s.carry_out FROM settlements s WHERE s.carry_out < 0" +
                " AND s.period_start = (SELECT max(s2.period_start) FROM settlements s2 WHERE s2.seller_id = s.seller_id)",
        ).resultList.associate { row ->
            row as Array<*>
            (row[0] as Number).toLong() to (row[1] as Number).toLong()
        }

        val sellers = (targets.keys + penalties.keys + carries.keys).sorted()
        for (sellerId in sellers) {
            val items = targets[sellerId].orEmpty()
            val sellerPenalties = penalties[sellerId].orEmpty()
            val gross = items.sumOf { it.amount }
            val feeTotal = items.sumOf { it.fee }
            val penaltyTotal = sellerPenalties.sumOf { it.amount }
            val carryIn = carries[sellerId] ?: 0L
            val net = gross - feeTotal - penaltyTotal + carryIn
            val payout = maxOf(net, 0L)
            val settlement = Settlement(
                sellerId = sellerId,
                periodStart = periodStart,
                periodEnd = periodStart.plusDays(6),
                gross = gross,
                feeTotal = feeTotal,
                penaltyTotal = penaltyTotal,
                carryIn = carryIn,
                net = net,
                payout = payout,
                carryOut = minOf(net, 0L),
                status = if (payout > 0) "PAID" else "CARRIED",
                paidAt = if (payout > 0) periodStart.plusDays(9).atStartOfDay(kst).toInstant() else null,
            )
            em.persist(settlement)
            for (item in items) {
                item.settlementId = settlement.id!!
                em.persist(item)
            }
            sellerPenalties.forEach { it.settlementId = settlement.id }
        }
        return sellers.size
    }

    /** `GET /me/settlements`(§10.3): 본인 정산, period_start 오름차순, 각 정산의 항목은 orderItemId 오름차순. 한 스냅샷에서 읽는다. */
    fun listSettlements(userId: Long): List<SettlementView> = snapshotTx.execute {
        val settlements = em.createQuery(
            "SELECT s FROM Settlement s WHERE s.sellerId = :seller ORDER BY s.periodStart",
            Settlement::class.java,
        ).setParameter("seller", userId).resultList
        val items = if (settlements.isEmpty()) {
            emptyMap()
        } else {
            em.createQuery(
                "SELECT i FROM SettlementItem i WHERE i.settlementId IN :ids ORDER BY i.orderItemId, i.id",
                SettlementItem::class.java,
            ).setParameter("ids", settlements.map { it.id!! }).resultList.groupBy { it.settlementId }
        }
        settlements.map { s ->
            SettlementView(
                settlementId = s.id!!,
                periodStart = s.periodStart,
                periodEnd = s.periodEnd,
                gross = s.gross,
                feeTotal = s.feeTotal,
                penaltyTotal = s.penaltyTotal,
                carryIn = s.carryIn,
                net = s.net,
                payout = s.payout,
                carryOut = s.carryOut,
                status = s.status,
                paidAt = s.paidAt,
                items = items[s.id].orEmpty().map { SettlementItemView(it.orderItemId, it.amount, it.fee, it.status) },
            )
        }
    }!!

    // ---- 단계 A 공통 (ADR-0007 §2) ----

    /** 글자 수(§2.1·§19의 6): 앞뒤 공백(isWhitespace)을 뺀 값의 유니코드 코드 포인트 수. 중간 공백은 센다. */
    private fun textLength(value: String): Int {
        val trimmed = value.trim()
        return trimmed.codePointCount(0, trimmed.length)
    }

    /** 새 테이블 행의 상태 충돌(§2.2 — 기존 code ITEM_STATE_CONFLICT를 쓴다). */
    private fun stateConflict(message: String): Nothing =
        throw ApiException(HttpStatus.CONFLICT, "ITEM_STATE_CONFLICT", message)

    /** 새 테이블 행 하나를 `FOR UPDATE`로 잠근다(§2.3). 잠근 뒤 엔티티를 refresh해 최신 상태로 판정한다. */
    private fun lockRow(table: String, id: Long) {
        em.createNativeQuery("SELECT id FROM $table WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    /**
     * 관리자 감사 로그 1행(§2.7·§11.3). 새 `/admin` 쓰기 요청이 성공 경로의 같은 트랜잭션 안에서 부른다(실패하면 함께 롤백된다).
     * target_id = 바뀐 행 id, detail은 action별 값(§11.3), created_at = 요청 시각.
     */
    private fun audit(action: String, targetId: Long, detail: String?, now: Instant) {
        em.persist(AdminAuditLog(action = action, targetId = targetId, detail = detail, createdAt = now))
    }

    /**
     * 지난달 M(현재 달 1일 − 1개월)이 닫혔으면 M, 아니면 null(§5.3·§6.4). 닫힘 = M에 속하는 마지막 월요일 L에 대해
     * 지금까지 만든 정산의 마지막 마감(max(period_start) + 7일, ADR-0004 §10.1)이 L + 7일 이상.
     */
    private fun closedLastMonth(now: Instant): LocalDate? {
        val m = LocalDate.ofInstant(now, kst).withDayOfMonth(1).minusMonths(1)
        val lastMonday = m.plusMonths(1).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastPeriodStart = em.createQuery("SELECT max(s.periodStart) FROM Settlement s", LocalDate::class.java).singleResult
            ?: return null
        return if (lastPeriodStart.plusDays(7).isBefore(lastMonday.plusDays(7))) null else m
    }

    // ---- A1 리뷰·평점 (ADR-0007 §4) ----

    /**
     * 리뷰 작성(§4.1). 구매자만, CONFIRMED만, 항목당 1건(지운 뒤에도 다시 쓸 수 없다), 현재 ≤ confirmed_at + 30일.
     * 순서: 400 → 404 → 403 → 주문 행 FOR UPDATE → 409(상태 → 중복 → 기한). 주문 행 잠금으로 같은 항목의 동시 작성이 줄을 선다.
     */
    fun writeReview(userId: Long, itemId: Long, rating: Long?, content: String?): ReviewView {
        if (rating == null) badRequest("rating이 필요하다")
        if (rating !in 1L..5L) badRequest("rating은 1~5: $rating")
        if (content == null) badRequest("content가 필요하다")
        if (textLength(content) !in 1..1_000) badRequest("content는 1~1,000자")

        val now = clock.now()
        return tx.execute {
            val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
            requireBuyer(item, userId)
            em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE").setParameter("id", item.orderId).resultList
            em.refresh(item)
            requireItemStatus(item, "CONFIRMED")
            val exists = em.createQuery("SELECT count(r) FROM Review r WHERE r.orderItemId = :item", Long::class.javaObjectType)
                .setParameter("item", itemId).singleResult
            if (exists > 0) stateConflict("리뷰가 이미 있는 주문 항목: $itemId")
            val deadline = item.confirmedAt!!.plus(Duration.ofDays(30))
            if (now.isAfter(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "리뷰 작성 기간($deadline)이 지났다: $itemId")
            }
            val review = Review(
                orderItemId = itemId,
                buyerId = userId,
                sellerId = em.find(Trade::class.java, item.tradeId).sellerId,
                rating = rating.toShort(),
                content = content,
                status = "VISIBLE",
                createdAt = now,
                updatedAt = now,
            )
            em.persist(review)
            reviewView(review)
        }!!
    }

    /** 리뷰 삭제(§4.2). 작성자만. VISIBLE·HIDDEN → DELETED(hidden_reason NULL), DELETED면 409. */
    fun deleteReview(userId: Long, reviewId: Long): ReviewView {
        val now = clock.now()
        return tx.execute {
            val review = em.find(Review::class.java, reviewId) ?: notFound("없는 리뷰: $reviewId")
            if (review.buyerId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "작성자가 아닌 리뷰: $reviewId")
            lockRow("reviews", reviewId)
            em.refresh(review)
            if (review.status == "DELETED") stateConflict("이미 지운 리뷰: $reviewId")
            review.status = "DELETED"
            review.hiddenReason = null
            review.updatedAt = now
            reviewView(review)
        }!!
    }

    /** 관리자 숨김(§4.3). VISIBLE만 → HIDDEN·ADMIN + 감사 REVIEW_HIDE(detail null). */
    fun hideReview(reviewId: Long): ReviewView {
        val now = clock.now()
        return tx.execute {
            val review = em.find(Review::class.java, reviewId) ?: notFound("없는 리뷰: $reviewId")
            lockRow("reviews", reviewId)
            em.refresh(review)
            if (review.status != "VISIBLE") stateConflict("VISIBLE이 아닌 리뷰: $reviewId (${review.status})")
            review.status = "HIDDEN"
            review.hiddenReason = "ADMIN"
            review.updatedAt = now
            audit("REVIEW_HIDE", reviewId, null, now)
            reviewView(review)
        }!!
    }

    /**
     * 판매자 리뷰 조회(§4.4, 헤더 불필요). 평점 = seller_ratings 행(마지막 rating-recalc 결과, 없으면 0·0),
     * averageX100 = floor(ratingSum × 100 / reviewCount)(0건이면 null), reviews = 지금의 VISIBLE 리뷰 reviewId 오름차순.
     */
    fun sellerReviews(sellerId: Long): SellerReviewsView = snapshotTx.execute {
        em.find(User::class.java, sellerId) ?: notFound("없는 사용자: $sellerId")
        val rating = em.find(SellerRating::class.java, sellerId)
        val count = rating?.reviewCount?.toLong() ?: 0L
        val sum = rating?.ratingSum?.toLong() ?: 0L
        val reviews = em.createQuery(
            "SELECT r FROM Review r WHERE r.sellerId = :seller AND r.status = 'VISIBLE' ORDER BY r.id",
            Review::class.java,
        ).setParameter("seller", sellerId).resultList
        SellerReviewsView(
            sellerId = sellerId,
            reviewCount = count,
            ratingSum = sum,
            averageX100 = if (count == 0L) null else Math.floorDiv(sum * 100, count),
            reviews = reviews.map { reviewView(it) },
        )
    }!!

    private fun reviewView(r: Review) =
        ReviewView(r.id!!, r.orderItemId, r.sellerId, r.rating.toInt(), r.content, r.status, r.createdAt)

    /**
     * rating-recalc(§4.5): 리뷰가 있는 판매자(users id 순). 판매자 1명 = 그 판매자의 리뷰 행을 id 순으로 잠근 트랜잭션 하나.
     * 1 분쟁 숨김(VISIBLE이고 항목 DISPUTED·REFUNDED_AFTER_CONFIRM → HIDDEN·DISPUTE) 2 분쟁 해제(HIDDEN·DISPUTE이고 항목 CONFIRMED → VISIBLE)
     * 3 평점(VISIBLE 수·합 — 행이 없고 1건 이상이면 INSERT, 행이 있고 값이 다르면 UPDATE). 반환 = 1~3 중 하나라도 쓴 판매자 수.
     */
    private fun recalcRatings(now: Instant): BatchResult =
        forEachTarget(
            "users",
            "EXISTS (SELECT 1 FROM reviews r WHERE r.seller_id = t.id)",
            emptyMap(),
            isolatedAs = "rating-recalc",
        ) { sellerId ->
            tx.execute {
                val locked = em.createNativeQuery("SELECT id FROM reviews WHERE seller_id = :seller ORDER BY id FOR UPDATE")
                    .setParameter("seller", sellerId).resultList.map { (it as Number).toLong() }.toSet()
                // 잠근 행만 처리한다 — 두 문장 사이에 커밋된 새 리뷰(잠기지 않음)는 다음 실행의 대상이다
                val reviews = em.createQuery("SELECT r FROM Review r WHERE r.sellerId = :seller ORDER BY r.id", Review::class.java)
                    .setParameter("seller", sellerId).resultList.filter { it.id in locked }
                var wrote = false
                for (review in reviews) {
                    val itemStatus = em.find(OrderItem::class.java, review.orderItemId).status
                    if (review.status == "VISIBLE" && (itemStatus == "DISPUTED" || itemStatus == "REFUNDED_AFTER_CONFIRM")) {
                        review.status = "HIDDEN"
                        review.hiddenReason = "DISPUTE"
                        review.updatedAt = now
                        wrote = true
                    } else if (review.status == "HIDDEN" && review.hiddenReason == "DISPUTE" && itemStatus == "CONFIRMED") {
                        review.status = "VISIBLE"
                        review.hiddenReason = null
                        review.updatedAt = now
                        wrote = true
                    }
                }
                val visible = reviews.filter { it.status == "VISIBLE" }
                val count = visible.size
                val sum = visible.sumOf { it.rating.toInt() }
                val rating = em.find(SellerRating::class.java, sellerId)
                if (rating == null) {
                    if (count > 0) {
                        em.persist(SellerRating(sellerId = sellerId, reviewCount = count, ratingSum = sum, recalculatedAt = now))
                        wrote = true
                    }
                } else if (rating.reviewCount != count || rating.ratingSum != sum) {
                    rating.reviewCount = count
                    rating.ratingSum = sum
                    rating.recalculatedAt = now
                    wrote = true
                }
                wrote
            }!!
        }

    // ---- A2 판매자 등급·수수료 리베이트 (ADR-0007 §5) ----

    /** 판매자 등급(§5.1): MASTER ≥ 8,000,000 · PRO ≥ 3,000,000 · PLUS ≥ 1,000,000 · 그 밖 STANDARD. */
    private fun sellerGradeOf(basis: Long): String = when {
        basis >= 8_000_000 -> "MASTER"
        basis >= 3_000_000 -> "PRO"
        basis >= 1_000_000 -> "PLUS"
        else -> "STANDARD"
    }

    /** 리베이트율(§5.3, 천분율): STANDARD 0 · PLUS 50 · PRO 100 · MASTER 150. */
    private fun rebatePermille(grade: String): Int = when (grade) {
        "STANDARD" -> 0
        "PLUS" -> 50
        "PRO" -> 100
        "MASTER" -> 150
        else -> throw IllegalStateException("알 수 없는 판매자 등급: $grade")
    }

    /**
     * seller-grade-recalc(§5.2): 기준월 D = 현재가 속한 달 1일(KST). 대상 = 정산 행이 있고 effective_from = D 행이 없는 사용자(users id 순).
     * 사용자 1명 = 그 사용자 행을 잠근 트랜잭션 하나, 잠근 뒤 D 행이 없는지 다시 본다(있으면 건너뜀 — 첫 결과 유지).
     * 기준 금액 = INCLUDED amount 합(소속 정산 period_start ∈ [D − 3개월, D)). 판매 제한 중(sale_restricted_until > 현재)이면 restricted·STANDARD.
     */
    private fun recalcSellerGrades(now: Instant): BatchResult {
        val d = LocalDate.ofInstant(now, kst).withDayOfMonth(1)
        return forEachTarget(
            "users",
            "EXISTS (SELECT 1 FROM settlements s WHERE s.seller_id = t.id)" +
                " AND NOT EXISTS (SELECT 1 FROM seller_grades g WHERE g.seller_id = t.id AND g.effective_from = :d)",
            mapOf("d" to d),
            isolatedAs = "seller-grade-recalc",
        ) { userId ->
            tx.execute {
                lockUser(userId)
                val existing = em.createQuery(
                    "SELECT count(g) FROM SellerGrade g WHERE g.sellerId = :seller AND g.effectiveFrom = :d",
                    Long::class.javaObjectType,
                ).setParameter("seller", userId).setParameter("d", d).singleResult
                if (existing > 0) return@execute false
                val basis = (
                    em.createNativeQuery(
                        "SELECT coalesce(sum(si.amount), 0) FROM settlement_items si JOIN settlements s ON s.id = si.settlement_id" +
                            " WHERE s.seller_id = :seller AND si.status = 'INCLUDED' AND s.period_start >= :from AND s.period_start < :d",
                    ).setParameter("seller", userId).setParameter("from", d.minusMonths(3)).setParameter("d", d).singleResult as Number
                    ).toLong()
                val restricted = em.find(User::class.java, userId).saleRestrictedUntil?.isAfter(now) == true
                em.persist(
                    SellerGrade(
                        sellerId = userId,
                        grade = if (restricted) "STANDARD" else sellerGradeOf(basis),
                        effectiveFrom = d,
                        basisAmount = basis,
                        restricted = restricted,
                        createdAt = now,
                    ),
                )
                true
            }!!
        }
    }

    /**
     * rebate-settlement(§5.3): 대상 달 M = 지난달, 닫혔을 때만(§6.4와 같은 판정 — 아니면 대상 0건).
     * 대상 = effective_from = M 등급 행이 있고 month = M 리베이트가 없는 판매자(users id 순). 사용자 행을 잠근 뒤 다시 확인한다.
     * fee_base = period_start가 M에 속하는 정산의 fee_total 합, amount = floor(max(fee_base, 0) × 율 / 1000). amount > 0이면 PAID(paid_at = 현재), 아니면 ZERO.
     */
    private fun settleRebates(now: Instant): BatchResult {
        val m = closedLastMonth(now) ?: return BatchResult(0, 0)
        val params = mapOf<String, Any>("m" to m)
        return forEachTarget(
            "users",
            "EXISTS (SELECT 1 FROM seller_grades g WHERE g.seller_id = t.id AND g.effective_from = :m)" +
                " AND NOT EXISTS (SELECT 1 FROM fee_rebates f WHERE f.seller_id = t.id AND f.month = :m)",
            params,
            isolatedAs = "rebate-settlement",
        ) { userId ->
            tx.execute {
                lockUser(userId)
                val grade = em.createQuery(
                    "SELECT g FROM SellerGrade g WHERE g.sellerId = :seller AND g.effectiveFrom = :m",
                    SellerGrade::class.java,
                ).setParameter("seller", userId).setParameter("m", m).resultList.firstOrNull() ?: return@execute false
                val existing = em.createQuery(
                    "SELECT count(f) FROM FeeRebate f WHERE f.sellerId = :seller AND f.month = :m",
                    Long::class.javaObjectType,
                ).setParameter("seller", userId).setParameter("m", m).singleResult
                if (existing > 0) return@execute false
                val feeBase = (
                    em.createNativeQuery(
                        "SELECT coalesce(sum(s.fee_total), 0) FROM settlements s" +
                            " WHERE s.seller_id = :seller AND s.period_start >= :m AND s.period_start < :next",
                    ).setParameter("seller", userId).setParameter("m", m).setParameter("next", m.plusMonths(1)).singleResult as Number
                    ).toLong()
                val rate = rebatePermille(grade.grade)
                val amount = mulDiv(maxOf(feeBase, 0L), rate.toLong(), 1000)
                em.persist(
                    FeeRebate(
                        sellerId = userId,
                        month = m,
                        grade = grade.grade,
                        feeBase = feeBase,
                        ratePermille = rate,
                        amount = amount,
                        status = if (amount > 0) "PAID" else "ZERO",
                        paidAt = if (amount > 0) now else null,
                        createdAt = now,
                    ),
                )
                true
            }!!
        }
    }

    /** `GET /me/seller-grade`(§5.4): current = effective_from이 가장 늦은 행, history = effective_from 오름차순. */
    fun sellerGrades(userId: Long): SellerGradesView {
        val history = em.createQuery(
            "SELECT g FROM SellerGrade g WHERE g.sellerId = :seller ORDER BY g.effectiveFrom",
            SellerGrade::class.java,
        ).setParameter("seller", userId).resultList.map { SellerGradeView(it.grade, it.effectiveFrom, it.basisAmount, it.restricted) }
        return SellerGradesView(current = history.lastOrNull(), history = history)
    }

    /** `GET /me/fee-rebates`(§5.4): month 오름차순. */
    fun feeRebates(userId: Long): List<FeeRebateView> =
        em.createQuery("SELECT f FROM FeeRebate f WHERE f.sellerId = :seller ORDER BY f.month", FeeRebate::class.java)
            .setParameter("seller", userId).resultList
            .map { FeeRebateView(it.month, it.grade, it.feeBase, it.ratePermille, it.amount, it.status, it.paidAt) }

    // ---- A3 지급 계좌 변경·월간 정산서 (ADR-0007 §6) ----

    /** 시드 은행 코드(§6.1). */
    private val bankCodes = setOf("004", "011", "020", "081", "088", "090")

    private val accountNoPattern = Regex("[0-9]{10,14}")

    /**
     * 지급 계좌 변경 요청(§6.1). bankCode는 시드 은행 코드 6개 중 하나, accountNo는 숫자 10~14자리(앞뒤 공백 불가 — §19의 2).
     * 사용자 행 FOR UPDATE → PENDING 요청이 있으면 409. 저장은 "****" + 끝 4자리(전체 번호는 저장하지 않는다).
     */
    fun requestPayoutAccount(userId: Long, bankCode: String?, accountNo: String?): PayoutRequestView {
        if (bankCode == null) badRequest("bankCode가 필요하다")
        if (bankCode !in bankCodes) badRequest("알 수 없는 은행 코드: $bankCode")
        if (accountNo == null) badRequest("accountNo가 필요하다")
        if (!accountNoPattern.matches(accountNo)) badRequest("accountNo는 숫자 10~14자리")

        val now = clock.now()
        return tx.execute {
            lockUser(userId)
            val pending = em.createQuery(
                "SELECT count(p) FROM PayoutAccountRequest p WHERE p.userId = :user AND p.status = 'PENDING'",
                Long::class.javaObjectType,
            ).setParameter("user", userId).singleResult
            if (pending > 0) stateConflict("판정 대기 중인 요청이 있다: 사용자 $userId")
            val request = PayoutAccountRequest(
                userId = userId,
                bankCode = bankCode,
                accountNoMasked = "****" + accountNo.takeLast(4),
                status = "PENDING",
                requestedAt = now,
            )
            em.persist(request)
            payoutRequestView(request)
        }!!
    }

    /**
     * 지급 계좌 판정(§6.2·§19의 9). approve 필수, 반려면 reason이 비지 않아야 한다(받은 그대로 저장), 승인이면 reason은 저장하지 않는다.
     * PENDING만(요청 행 FOR UPDATE 뒤 확인) → APPROVED·REJECTED, decided_at = 현재 + 감사 PAYOUT_ACCOUNT_DECISION(detail = 결과 상태).
     * seller_payout_accounts는 바꾸지 않는다.
     */
    fun decidePayoutAccount(requestId: Long, approve: Boolean?, reason: String?): PayoutRequestView {
        if (approve == null) badRequest("approve가 필요하다")
        if (!approve && reason.isNullOrBlank()) badRequest("반려에는 reason이 필요하다")

        val now = clock.now()
        return tx.execute {
            val request = em.find(PayoutAccountRequest::class.java, requestId) ?: notFound("없는 요청: $requestId")
            lockRow("payout_account_requests", requestId)
            em.refresh(request)
            if (request.status != "PENDING") stateConflict("PENDING이 아닌 요청: $requestId (${request.status})")
            request.status = if (approve) "APPROVED" else "REJECTED"
            request.rejectReason = if (approve) null else reason
            request.decidedAt = now
            audit("PAYOUT_ACCOUNT_DECISION", requestId, request.status, now)
            payoutRequestView(request)
        }!!
    }

    /**
     * `GET /me/payout-account`(§6.3): 유효 계좌 = APPROVED 요청 중 decided_at이 가장 늦은 것(같으면 id 큰 것) → REQUEST,
     * 없으면 seller_payout_accounts 행 → INITIAL, 둘 다 없으면 null. pending = PENDING 요청.
     */
    fun payoutAccount(userId: Long): PayoutAccountView = snapshotTx.execute {
        val requests = em.createQuery(
            "SELECT p FROM PayoutAccountRequest p WHERE p.userId = :user AND p.status IN ('APPROVED', 'PENDING') ORDER BY p.decidedAt DESC, p.id DESC",
            PayoutAccountRequest::class.java,
        ).setParameter("user", userId).resultList
        val approved = requests.firstOrNull { it.status == "APPROVED" }
        val pending = requests.firstOrNull { it.status == "PENDING" }?.let { payoutRequestView(it) }
        if (approved != null) {
            PayoutAccountView(approved.bankCode, approved.accountNoMasked, "REQUEST", pending)
        } else {
            val initial = em.createNativeQuery("SELECT bank_code, account_no_masked FROM seller_payout_accounts WHERE user_id = :user")
                .setParameter("user", userId).resultList.firstOrNull() as Array<*>?
            if (initial != null) {
                PayoutAccountView(initial[0] as String, initial[1] as String, "INITIAL", pending)
            } else {
                PayoutAccountView(null, null, null, pending)
            }
        }
    }!!

    private fun payoutRequestView(p: PayoutAccountRequest) =
        PayoutRequestView(p.id!!, p.bankCode, p.accountNoMasked, p.status, p.rejectReason, p.requestedAt, p.decidedAt)

    /**
     * monthly-statement(§6.4): 대상 달 M = 지난달, 닫혔을 때만(아니면 대상 0건). 대상 = period_start가 M에 속하는 정산이 있고
     * month = M 정산서가 없는 판매자(users id 순). 사용자 행을 잠근 뒤 다시 확인하고 그 달 정산들로 1행:
     * 건수·각 합·CARRIED 수·HELD 행 수(주마다 센다)·period_start가 가장 늦은 정산의 carry_out.
     */
    private fun writeMonthlyStatements(now: Instant): BatchResult {
        val m = closedLastMonth(now) ?: return BatchResult(0, 0)
        val next = m.plusMonths(1)
        return forEachTarget(
            "users",
            "EXISTS (SELECT 1 FROM settlements s WHERE s.seller_id = t.id AND s.period_start >= :m AND s.period_start < :next)" +
                " AND NOT EXISTS (SELECT 1 FROM monthly_statements ms WHERE ms.seller_id = t.id AND ms.month = :m)",
            mapOf("m" to m, "next" to next),
            isolatedAs = "monthly-statement",
        ) { userId ->
            tx.execute {
                lockUser(userId)
                val existing = em.createQuery(
                    "SELECT count(ms) FROM MonthlyStatement ms WHERE ms.sellerId = :seller AND ms.month = :m",
                    Long::class.javaObjectType,
                ).setParameter("seller", userId).setParameter("m", m).singleResult
                if (existing > 0) return@execute false
                val settlements = em.createQuery(
                    "SELECT s FROM Settlement s WHERE s.sellerId = :seller AND s.periodStart >= :m AND s.periodStart < :next ORDER BY s.periodStart",
                    Settlement::class.java,
                ).setParameter("seller", userId).setParameter("m", m).setParameter("next", next).resultList
                if (settlements.isEmpty()) return@execute false
                val held = em.createQuery(
                    "SELECT count(i) FROM SettlementItem i WHERE i.settlementId IN :ids AND i.status = 'HELD'",
                    Long::class.javaObjectType,
                ).setParameter("ids", settlements.map { it.id!! }).singleResult
                em.persist(
                    MonthlyStatement(
                        sellerId = userId,
                        month = m,
                        settlementCount = settlements.size,
                        gross = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.gross) },
                        feeTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.feeTotal) },
                        penaltyTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.penaltyTotal) },
                        payoutTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.payout) },
                        carriedCount = settlements.count { it.status == "CARRIED" },
                        heldCount = held.toInt(),
                        lastCarryOut = settlements.last().carryOut,
                        createdAt = now,
                    ),
                )
                true
            }!!
        }
    }

    /** `GET /me/monthly-statements`(§6.4): month 오름차순. */
    fun monthlyStatements(userId: Long): List<MonthlyStatementView> =
        em.createQuery("SELECT ms FROM MonthlyStatement ms WHERE ms.sellerId = :seller ORDER BY ms.month", MonthlyStatement::class.java)
            .setParameter("seller", userId).resultList
            .map {
                MonthlyStatementView(
                    it.month, it.settlementCount, it.gross, it.feeTotal, it.penaltyTotal, it.payoutTotal,
                    it.carriedCount, it.heldCount, it.lastCarryOut,
                )
            }

    // ---- A7 세금계산서 (ADR-0007 §10) ----

    /** 부가세(§10.1) = floor(공급가액 × 10 / 100). 공급가액은 0 이상이다. */
    private fun vatOf(supply: Long): Long = mulDiv(supply, 10, 100)

    /**
     * tax-invoice-issue(§10.2): 계산서가 하나도 없는 정산 중 (PAID이고 paid_at ≤ 현재) 또는 CARRIED(settlements id 순).
     * 정산 1건 = 그 정산 행을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다. PAID → ISSUE/ISSUED(공급가액 = max(fee_total, 0)),
     * CARRIED → HOLD/DONE(0·0·0). issued_at = 현재.
     */
    private fun issueTaxInvoices(now: Instant): BatchResult =
        forEachTarget(
            "settlements",
            "NOT EXISTS (SELECT 1 FROM tax_invoices v WHERE v.settlement_id = t.id)" +
                " AND ((t.status = 'PAID' AND t.paid_at <= :now) OR t.status = 'CARRIED')",
            mapOf("now" to now),
            isolatedAs = "tax-invoice-issue",
        ) { settlementId ->
            tx.execute {
                lockRow("settlements", settlementId)
                val s = em.find(Settlement::class.java, settlementId)
                val existing = em.createQuery("SELECT count(v) FROM TaxInvoice v WHERE v.settlementId = :s", Long::class.javaObjectType)
                    .setParameter("s", settlementId).singleResult
                val invoice = when {
                    existing > 0 -> null
                    s.status == "PAID" && !s.paidAt!!.isAfter(now) -> {
                        val supply = maxOf(s.feeTotal, 0L)
                        val vat = vatOf(supply)
                        TaxInvoice(
                            settlementId = settlementId, sellerId = s.sellerId, kind = "ISSUE", status = "ISSUED",
                            supplyAmount = supply, vatAmount = vat, totalAmount = supply + vat, issuedAt = now,
                        )
                    }
                    s.status == "CARRIED" -> TaxInvoice(
                        settlementId = settlementId, sellerId = s.sellerId, kind = "HOLD", status = "DONE",
                        supplyAmount = 0, vatAmount = 0, totalAmount = 0, issuedAt = now,
                    )
                    else -> null
                }
                if (invoice != null) em.persist(invoice)
                invoice != null
            }!!
        }

    /**
     * 세금계산서 정정(§10.3). supplyAmount ≥ 0. 대상이 ISSUE·ISSUED가 아니면 409(원본 행 FOR UPDATE 뒤 확인).
     * 한 트랜잭션, 이 순서로: 원본 → CANCELLED(부분 UNIQUE 때문에 INSERT보다 먼저 DB에 보낸다) · CANCEL 1행(원본 금액 × −1)
     * · 새 ISSUE/ISSUED 1행(같은 정산, ref = 원본) · 감사 TAX_INVOICE_CORRECTION(detail = 새 ISSUE id). 원본은 status만 바뀐다(§19의 12).
     */
    fun correctTaxInvoice(invoiceId: Long, supplyAmount: Long?): TaxInvoiceCorrected {
        if (supplyAmount == null) badRequest("supplyAmount가 필요하다")
        if (supplyAmount < 0) badRequest("supplyAmount는 0 이상: $supplyAmount")

        val now = clock.now()
        return tx.execute {
            val original = em.find(TaxInvoice::class.java, invoiceId) ?: notFound("없는 세금계산서: $invoiceId")
            lockRow("tax_invoices", invoiceId)
            em.refresh(original)
            if (original.kind != "ISSUE" || original.status != "ISSUED") {
                stateConflict("정정할 수 없는 세금계산서: $invoiceId (${original.kind}·${original.status})")
            }
            original.status = "CANCELLED"
            em.flush()
            val cancel = TaxInvoice(
                settlementId = original.settlementId, sellerId = original.sellerId, kind = "CANCEL", status = "DONE",
                supplyAmount = -original.supplyAmount, vatAmount = -original.vatAmount, totalAmount = -original.totalAmount,
                refInvoiceId = invoiceId, issuedAt = now,
            )
            em.persist(cancel)
            val vat = vatOf(supplyAmount)
            val reissue = TaxInvoice(
                settlementId = original.settlementId, sellerId = original.sellerId, kind = "ISSUE", status = "ISSUED",
                supplyAmount = supplyAmount, vatAmount = vat, totalAmount = Math.addExact(supplyAmount, vat),
                refInvoiceId = invoiceId, issuedAt = now,
            )
            em.persist(reissue)
            audit("TAX_INVOICE_CORRECTION", invoiceId, reissue.id!!.toString(), now)
            TaxInvoiceCorrected(cancelled = taxInvoiceView(cancel), reissued = taxInvoiceView(reissue))
        }!!
    }

    /** `GET /me/tax-invoices`(§10.4): 판매자 본인 계산서 전부(세 kind), invoiceId 오름차순. */
    fun taxInvoices(userId: Long): List<TaxInvoiceView> =
        em.createQuery("SELECT v FROM TaxInvoice v WHERE v.sellerId = :seller ORDER BY v.id", TaxInvoice::class.java)
            .setParameter("seller", userId).resultList.map { taxInvoiceView(it) }

    private fun taxInvoiceView(v: TaxInvoice) =
        TaxInvoiceView(v.id!!, v.settlementId, v.kind, v.status, v.supplyAmount, v.vatAmount, v.totalAmount, v.refInvoiceId, v.issuedAt)

    // ---- A4 알림 (ADR-0007 §7) ----

    /**
     * deadline-reminder 한 종류(§7.1 표의 한 행). 별칭 t = 훑는 표. condition = 대상 조건(dedupe 제외), recipient = 받는 사람 식,
     * dedupeKey = dedupe_key 식(대상 조건의 NOT EXISTS와 INSERT가 같은 식을 쓴다).
     */
    private class Reminder(
        val kind: String,
        val table: String,
        val condition: String,
        val params: Map<String, Any>,
        val recipient: String,
        val dedupeKey: String,
    )

    /**
     * deadline-reminder(§7.1): 여섯 종류를 표의 행 순서대로 하나씩 끝까지 훑는다(종류마다 그 표의 id 순). 대상 1건 = 알림 1행 INSERT(잠금 없음).
     * 기한·제한은 저장된 열을 비교만 한다. 반환 = 만든 알림 수.
     */
    private fun remindDeadlines(now: Instant): BatchResult {
        val restrictionKey = { kind: String, column: String ->
            "'$kind:' || t.id || ':' || CAST(floor(extract(epoch FROM t.$column)) AS bigint)"
        }
        val reminders = listOf(
            Reminder(
                "PAYMENT_DUE_SOON", "trades",
                "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at > :now AND t.payment_due_at <= :after10m",
                mapOf("now" to now, "after10m" to now.plus(Duration.ofMinutes(10))),
                "t.buyer_id", "'PAYMENT_DUE_SOON:' || t.id",
            ),
            Reminder(
                "SHIPMENT_DUE_SOON", "order_items",
                "t.status = 'PAID' AND t.paid_at <= :before2d AND t.paid_at > :before3d",
                mapOf("before2d" to now.minus(Duration.ofDays(2)), "before3d" to now.minus(Duration.ofDays(3))),
                "(SELECT tr.seller_id FROM trades tr WHERE tr.id = t.trade_id)", "'SHIPMENT_DUE_SOON:' || t.id",
            ),
            Reminder(
                "DISPUTE_WINDOW_CLOSING", "order_items",
                "t.status = 'CONFIRMED' AND t.confirmed_at <= :before12d AND t.confirmed_at >= :before14d",
                mapOf("before12d" to now.minus(Duration.ofDays(12)), "before14d" to now.minus(Duration.ofDays(14))),
                "(SELECT o.buyer_id FROM orders o WHERE o.id = t.order_id)", "'DISPUTE_WINDOW_CLOSING:' || t.id",
            ),
            Reminder(
                "SETTLEMENT_PAID", "settlements",
                "t.status = 'PAID' AND t.paid_at > :before7d AND t.paid_at <= :now",
                mapOf("before7d" to now.minus(Duration.ofDays(7)), "now" to now),
                "t.seller_id", "'SETTLEMENT_PAID:' || t.id",
            ),
            Reminder(
                "PURCHASE_RESTRICTED", "users", "t.purchase_restricted_until > :now", mapOf("now" to now),
                "t.id", restrictionKey("PURCHASE_RESTRICTED", "purchase_restricted_until"),
            ),
            Reminder(
                "SALE_RESTRICTED", "users", "t.sale_restricted_until > :now", mapOf("now" to now),
                "t.id", restrictionKey("SALE_RESTRICTED", "sale_restricted_until"),
            ),
        )
        var processed = 0
        var failed = 0
        for (r in reminders) {
            val result = forEachTarget(
                r.table,
                "${r.condition} AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.dedupe_key = ${r.dedupeKey})",
                r.params,
                isolatedAs = "deadline-reminder",
            ) { id ->
                tx.execute {
                    em.createNativeQuery(
                        "INSERT INTO notifications (user_id, kind, ref_id, dedupe_key, created_at)" +
                            " SELECT ${r.recipient}, '${r.kind}', t.id, ${r.dedupeKey}, :now FROM ${r.table} t WHERE t.id = :id",
                    ).setParameter("now", now).setParameter("id", id).executeUpdate() == 1
                }!!
            }
            processed += result.processed
            failed += result.failed
        }
        return BatchResult(processed, failed)
    }

    /** `GET /me/notifications`(§7.2): 본인 알림 notificationId 오름차순. unread는 "true"·"false"만(§19의 3), true면 read_at NULL만. */
    fun notifications(userId: Long, unread: String?): List<NotificationView> {
        if (unread != null && unread != "true" && unread != "false") badRequest("unread는 true 또는 false: $unread")
        val jpql = "SELECT n FROM Notification n WHERE n.userId = :user" +
            (if (unread == "true") " AND n.readAt IS NULL" else "") + " ORDER BY n.id"
        return em.createQuery(jpql, Notification::class.java).setParameter("user", userId).resultList.map { notificationView(it) }
    }

    /** 알림 읽음(§7.2). 본인 것만(403), 알림 행 FOR UPDATE 뒤 이미 읽었으면 409(멱등 아님). read_at = 현재. */
    fun readNotification(userId: Long, notificationId: Long): NotificationView {
        val now = clock.now()
        return tx.execute {
            val notification = em.find(Notification::class.java, notificationId) ?: notFound("없는 알림: $notificationId")
            if (notification.userId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 알림: $notificationId")
            lockRow("notifications", notificationId)
            em.refresh(notification)
            if (notification.readAt != null) stateConflict("이미 읽은 알림: $notificationId")
            notification.readAt = now
            notificationView(notification)
        }!!
    }

    private fun notificationView(n: Notification) = NotificationView(n.id!!, n.kind, n.refId, n.createdAt, n.readAt)

    // ---- A5 위시리스트·가격 알림 (ADR-0007 §8) ----

    /** 위시리스트 등록(§8.1). 없는 사이즈 404, 사용자 행 FOR UPDATE 뒤 이미 있으면 409. */
    fun addWish(userId: Long, productSizeId: Long?): WishlistEntryView {
        if (productSizeId == null) badRequest("productSizeId가 필요하다")
        val now = clock.now()
        return tx.execute {
            keyShareSize(productSizeId)
            lockUser(userId)
            if (wishOf(userId, productSizeId) != null) stateConflict("이미 위시리스트에 있는 사이즈: $productSizeId")
            val wish = Wishlist(userId = userId, productSizeId = productSizeId, createdAt = now)
            em.persist(wish)
            WishlistEntryView(wish.productSizeId, wish.createdAt)
        }!!
    }

    /**
     * 사이즈 존재 확인 + KEY SHARE(§19의 10-2). 사용자 행 잠금보다 먼저 잡아 잠금 순서를 기존 입찰·결제 만료 경로와 같은
     * "사이즈 → 사용자"로 둔다(뒤의 INSERT가 FK 검사로 사이즈에 KEY SHARE를 걸 때 순서가 거꾸로 되면 교착). 없으면 404.
     */
    private fun keyShareSize(productSizeId: Long) {
        em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR KEY SHARE").setParameter("id", productSizeId)
            .resultList.ifEmpty { notFound("없는 사이즈: $productSizeId") }
    }

    /** 위시리스트 삭제(§8.1·§19의 10-1). 사용자 행 FOR UPDATE 뒤 다시 찾는다 — 내 위시리스트에 없으면 404(동시 삭제는 하나만 200). 행을 지운다. */
    fun removeWish(userId: Long, productSizeId: Long): WishlistEntryView =
        tx.execute {
            lockUser(userId)
            val wish = wishOf(userId, productSizeId) ?: notFound("위시리스트에 없는 사이즈: $productSizeId")
            em.remove(wish)
            WishlistEntryView(wish.productSizeId, wish.createdAt)
        }!!

    private fun wishOf(userId: Long, productSizeId: Long): Wishlist? =
        em.createQuery("SELECT w FROM Wishlist w WHERE w.userId = :user AND w.productSizeId = :size", Wishlist::class.java)
            .setParameter("user", userId).setParameter("size", productSizeId).resultList.firstOrNull()

    /**
     * `GET /me/wishlist`(§8.1): productSizeId 오름차순. bestSell = ACTIVE이고 expires_at > 현재인 SELL 입찰 최저가(호가와 같은 기준),
     * activeAlert = 그 사이즈의 내 ACTIVE 가격 알림(§19의 10).
     */
    fun wishlist(userId: Long): List<WishlistItemView> = snapshotTx.execute {
        val now = clock.now()
        em.createQuery("SELECT w FROM Wishlist w WHERE w.userId = :user ORDER BY w.productSizeId", Wishlist::class.java)
            .setParameter("user", userId).resultList.map { w ->
                val bestSell = em.createNativeQuery(
                    "SELECT min(price) FROM bids WHERE product_size_id = :size AND side = 'SELL' AND status = 'ACTIVE' AND expires_at > :now",
                ).setParameter("size", w.productSizeId).setParameter("now", now).singleResult as Number?
                val alert = em.createQuery(
                    "SELECT a FROM PriceAlert a WHERE a.userId = :user AND a.productSizeId = :size AND a.status = 'ACTIVE'",
                    PriceAlert::class.java,
                ).setParameter("user", userId).setParameter("size", w.productSizeId).resultList.firstOrNull()
                WishlistItemView(w.productSizeId, w.createdAt, bestSell?.toLong(), alert?.let { priceAlertView(it) })
            }
    }!!

    /** 가격 알림 등록(§8.2). targetPrice > 0, 없는 사이즈 404, 사용자 행 FOR UPDATE 뒤 같은 사이즈 ACTIVE가 있으면 409. expires_at = 현재 + 60일. */
    fun addPriceAlert(userId: Long, productSizeId: Long?, targetPrice: Long?): PriceAlertView {
        if (productSizeId == null) badRequest("productSizeId가 필요하다")
        if (targetPrice == null) badRequest("targetPrice가 필요하다")
        if (targetPrice <= 0) badRequest("targetPrice는 0보다 커야 한다: $targetPrice")
        val now = clock.now()
        return tx.execute {
            keyShareSize(productSizeId)
            lockUser(userId)
            val active = em.createQuery(
                "SELECT count(a) FROM PriceAlert a WHERE a.userId = :user AND a.productSizeId = :size AND a.status = 'ACTIVE'",
                Long::class.javaObjectType,
            ).setParameter("user", userId).setParameter("size", productSizeId).singleResult
            if (active > 0) stateConflict("같은 사이즈의 ACTIVE 가격 알림이 있다: $productSizeId")
            val alert = PriceAlert(
                userId = userId,
                productSizeId = productSizeId,
                targetPrice = targetPrice,
                status = "ACTIVE",
                expiresAt = now.plus(Duration.ofDays(60)),
                createdAt = now,
            )
            em.persist(alert)
            priceAlertView(alert)
        }!!
    }

    /** 가격 알림 취소(§8.2). 본인 것만, 알림 행 FOR UPDATE 뒤 ACTIVE가 아니면 409. → CANCELLED, closed_at = 현재. */
    fun cancelPriceAlert(userId: Long, alertId: Long): PriceAlertView {
        val now = clock.now()
        return tx.execute {
            val alert = em.find(PriceAlert::class.java, alertId) ?: notFound("없는 가격 알림: $alertId")
            if (alert.userId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 가격 알림: $alertId")
            lockRow("price_alerts", alertId)
            em.refresh(alert)
            if (alert.status != "ACTIVE") stateConflict("ACTIVE가 아닌 가격 알림: $alertId (${alert.status})")
            alert.status = "CANCELLED"
            alert.closedAt = now
            priceAlertView(alert)
        }!!
    }

    /** `GET /me/price-alerts`(§8.2): alertId 오름차순. */
    fun priceAlerts(userId: Long): List<PriceAlertView> =
        em.createQuery("SELECT a FROM PriceAlert a WHERE a.userId = :user ORDER BY a.id", PriceAlert::class.java)
            .setParameter("user", userId).resultList.map { priceAlertView(it) }

    private fun priceAlertView(a: PriceAlert) =
        PriceAlertView(a.id!!, a.productSizeId, a.targetPrice, a.status, a.expiresAt, a.triggeredBidId, a.closedAt, a.createdAt)

    /**
     * price-alert-scan(§8.3): ACTIVE 가격 알림(id 순). 알림 1건 = 그 행을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다.
     * 1 expires_at ≤ 현재 → EXPIRED. 2 주인이 구매 제한 중이면 그대로(세지 않음). 3 그 사이즈의 체결 가능한 SELL 중 price ≤ target인
     * price, created_at, id 순 첫 번째 → TRIGGERED + 알림 PRICE_ALERT. 입찰은 읽기만 한다. 반환 = 1 또는 3으로 바뀐 알림 수.
     */
    private fun scanPriceAlerts(now: Instant): BatchResult =
        forEachTarget("price_alerts", "t.status = 'ACTIVE'", emptyMap(), isolatedAs = "price-alert-scan") { alertId ->
            tx.execute {
                lockRow("price_alerts", alertId)
                val alert = em.find(PriceAlert::class.java, alertId)
                if (alert.status != "ACTIVE") return@execute false
                if (!alert.expiresAt.isAfter(now)) {
                    alert.status = "EXPIRED"
                    alert.closedAt = now
                    return@execute true
                }
                if (em.find(User::class.java, alert.userId).purchaseRestrictedUntil?.isAfter(now) == true) return@execute false
                val bidId = em.createNativeQuery(
                    "SELECT id FROM bids WHERE product_size_id = :size AND side = 'SELL' AND status = 'ACTIVE' AND expires_at > :now" +
                        " AND price <= :target ORDER BY price, created_at, id LIMIT 1",
                ).setParameter("size", alert.productSizeId).setParameter("now", now).setParameter("target", alert.targetPrice)
                    .resultList.firstOrNull() as Number? ?: return@execute false
                alert.status = "TRIGGERED"
                alert.triggeredBidId = bidId.toLong()
                alert.closedAt = now
                em.persist(
                    Notification(userId = alert.userId, kind = "PRICE_ALERT", refId = alertId, dedupeKey = "PRICE_ALERT:$alertId", createdAt = now),
                )
                true
            }!!
        }

    // ---- A11 배송 추적 (ADR-0007 §12.2) ----

    private val carriers = setOf("CJ", "HANJIN", "LOTTE", "POST")
    private val trackingEventTypes = setOf("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")
    private val trackableItemStatuses = setOf("SHIPPED_TO_CENTER", "INSPECTING", "DELIVERING", "DELIVERED")

    /**
     * 배송 추적 이벤트 기록(§12.2). enum은 문서 글자 그대로, trackingNo 1~30자(받은 그대로 저장), occurredAt ≤ 현재(아니면 400).
     * 없는 항목 404, 항목 상태가 SHIPPED_TO_CENTER·INSPECTING·DELIVERING·DELIVERED가 아니면 409. 1행(received_at = 현재)
     * + 감사 TRACKING_EVENT(target = 새 이벤트 id, detail = event_type). 항목 상태는 바꾸지 않는다.
     */
    fun recordTrackingEvent(itemId: Long, carrier: String?, trackingNo: String?, eventType: String?, occurredAt: Instant?): TrackingEventView {
        if (carrier == null) badRequest("carrier가 필요하다")
        if (carrier !in carriers) badRequest("알 수 없는 carrier: $carrier")
        if (trackingNo == null) badRequest("trackingNo가 필요하다")
        if (textLength(trackingNo) !in 1..30) badRequest("trackingNo는 1~30자")
        if (eventType == null) badRequest("eventType이 필요하다")
        if (eventType !in trackingEventTypes) badRequest("알 수 없는 eventType: $eventType")
        if (occurredAt == null) badRequest("occurredAt이 필요하다")
        val now = clock.now()
        if (occurredAt.isAfter(now)) badRequest("occurredAt은 현재 이후일 수 없다: $occurredAt")

        return tx.execute {
            val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
            if (item.status !in trackableItemStatuses) stateConflict("배송 추적을 기록할 수 없는 주문 항목: $itemId (${item.status})")
            val event = ShipmentTrackingEvent(
                orderItemId = itemId,
                carrier = carrier,
                trackingNo = trackingNo,
                eventType = eventType,
                occurredAt = occurredAt,
                receivedAt = now,
            )
            em.persist(event)
            audit("TRACKING_EVENT", event.id!!, eventType, now)
            trackingEventView(event)
        }!!
    }

    /** `GET /order-items/{id}/tracking`(§12.2): 구매자 또는 판매자만. events는 occurredAt·id 오름차순, delays는 id 오름차순. */
    fun tracking(userId: Long, itemId: Long): TrackingView = snapshotTx.execute {
        val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
        val trade = em.find(Trade::class.java, item.tradeId)
        if (userId != trade.buyerId && userId != trade.sellerId) {
            throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "구매자도 판매자도 아닌 주문 항목: $itemId")
        }
        val events = em.createQuery(
            "SELECT e FROM ShipmentTrackingEvent e WHERE e.orderItemId = :item ORDER BY e.occurredAt, e.id",
            ShipmentTrackingEvent::class.java,
        ).setParameter("item", itemId).resultList.map { trackingEventView(it) }
        val delays = em.createQuery("SELECT d FROM ShipmentDelay d WHERE d.orderItemId = :item ORDER BY d.id", ShipmentDelay::class.java)
            .setParameter("item", itemId).resultList.map { ShipmentDelayView(it.kind, it.detectedAt) }
        TrackingView(events, delays)
    }!!

    private fun trackingEventView(e: ShipmentTrackingEvent) =
        TrackingEventView(e.id!!, e.orderItemId, e.carrier, e.trackingNo, e.eventType, e.occurredAt, e.receivedAt)

    /**
     * delivery-delay-scan(§12.2): 두 종류를 차례로(order_items id 순). SHIPMENT_OVERDUE = PAID이고 paid_at + 3일 ≤ 현재,
     * DELIVERY_LATE = DELIVERING이고 inspected_at + 5일 ≤ 현재. 같은 (항목, kind) 행이 없을 때만 1행(detected_at = 현재).
     * 잠금 없음, 항목 상태는 바꾸지 않는다. 반환 = 쓴 행 수.
     */
    private fun scanDeliveryDelays(now: Instant): BatchResult {
        val kinds = listOf(
            Triple("SHIPMENT_OVERDUE", "t.status = 'PAID' AND t.paid_at <= :due", now.minus(Duration.ofDays(3))),
            Triple("DELIVERY_LATE", "t.status = 'DELIVERING' AND t.inspected_at <= :due", now.minus(Duration.ofDays(5))),
        )
        var processed = 0
        var failed = 0
        for ((kind, condition, due) in kinds) {
            val result = forEachTarget(
                "order_items",
                "$condition AND NOT EXISTS (SELECT 1 FROM shipment_delays d WHERE d.order_item_id = t.id AND d.kind = '$kind')",
                mapOf("due" to due),
                isolatedAs = "delivery-delay-scan",
            ) { itemId ->
                tx.execute {
                    em.persist(ShipmentDelay(orderItemId = itemId, kind = kind, detectedAt = now))
                    true
                }!!
            }
            processed += result.processed
            failed += result.failed
        }
        return BatchResult(processed, failed)
    }

    // ---- A6 고객 문의 (ADR-0007 §9) ----

    private val inquiryCategories = setOf("ORDER", "PAYMENT", "SETTLEMENT", "ACCOUNT", "OTHER")

    /** 문의 본문·추가 메시지·답변(§9.1): 앞뒤 공백을 뺀 값이 1~2,000자. */
    private fun messageBody(body: String?): String {
        if (body == null) badRequest("body가 필요하다")
        if (textLength(body) !in 1..2_000) badRequest("body는 1~2,000자")
        return body
    }

    /**
     * 문의 작성(§9.1). category는 다섯 값 중 하나, title 1~100자, body 1~2,000자. orderItemId가 있으면 없는 항목 404,
     * 그 체결의 구매자·판매자가 아니면 403. 문의 1행(OPEN, sla_due_at = 현재 + 48시간) + USER 메시지 1행.
     */
    fun createInquiry(userId: Long, category: String?, title: String?, body: String?, orderItemId: Long?): InquiryView {
        if (category == null) badRequest("category가 필요하다")
        if (category !in inquiryCategories) badRequest("알 수 없는 category: $category")
        if (title == null) badRequest("title이 필요하다")
        if (textLength(title) !in 1..100) badRequest("title은 1~100자")
        val text = messageBody(body)

        val now = clock.now()
        return tx.execute {
            if (orderItemId != null) {
                val item = em.find(OrderItem::class.java, orderItemId) ?: notFound("없는 주문 항목: $orderItemId")
                val trade = em.find(Trade::class.java, item.tradeId)
                if (userId != trade.buyerId && userId != trade.sellerId) {
                    throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "구매자도 판매자도 아닌 주문 항목: $orderItemId")
                }
            }
            val inquiry = Inquiry(
                userId = userId,
                orderItemId = orderItemId,
                category = category,
                title = title,
                status = "OPEN",
                createdAt = now,
                slaDueAt = now.plus(Duration.ofHours(48)),
            )
            em.persist(inquiry)
            em.persist(InquiryMessage(inquiryId = inquiry.id!!, author = "USER", body = text, createdAt = now))
            inquiryView(inquiry, now)
        }!!
    }

    /**
     * 상태를 바꾸는 문의 요청의 공통 단계(§9.1): 없는 문의 404 → (작성자 요청이면) 작성자가 아니면 403 → 문의 행 FOR UPDATE 뒤 CLOSED면 409.
     * authorId = null이면 운영자 요청이다.
     */
    private fun lockOpenInquiry(inquiryId: Long, authorId: Long?): Inquiry {
        val inquiry = em.find(Inquiry::class.java, inquiryId) ?: notFound("없는 문의: $inquiryId")
        if (authorId != null && inquiry.userId != authorId) {
            throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "작성자가 아닌 문의: $inquiryId")
        }
        lockRow("inquiries", inquiryId)
        em.refresh(inquiry)
        if (inquiry.status == "CLOSED") stateConflict("종결된 문의: $inquiryId")
        return inquiry
    }

    /** 추가 메시지(§9.1·§19의 11). 작성자만, OPEN·ANSWERED → USER 메시지 1행, 상태 OPEN(answered_at·first_answered_at은 그대로). */
    fun addInquiryMessage(userId: Long, inquiryId: Long, body: String?): InquiryView {
        val text = messageBody(body)
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpenInquiry(inquiryId, userId)
            em.persist(InquiryMessage(inquiryId = inquiryId, author = "USER", body = text, createdAt = now))
            inquiry.status = "OPEN"
            inquiryView(inquiry, now)
        }!!
    }

    /**
     * 운영자 답변(§9.1). OPEN·ANSWERED → ADMIN 메시지 1행, ANSWERED, answered_at = 현재, first_answered_at이 NULL이면 현재
     * + 감사 INQUIRY_ANSWER(detail null).
     */
    fun answerInquiry(inquiryId: Long, body: String?): InquiryView {
        val text = messageBody(body)
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpenInquiry(inquiryId, authorId = null)
            em.persist(InquiryMessage(inquiryId = inquiryId, author = "ADMIN", body = text, createdAt = now))
            inquiry.status = "ANSWERED"
            inquiry.answeredAt = now
            if (inquiry.firstAnsweredAt == null) inquiry.firstAnsweredAt = now
            audit("INQUIRY_ANSWER", inquiryId, null, now)
            inquiryView(inquiry, now)
        }!!
    }

    /** 종결(§9.1). 작성자만, OPEN·ANSWERED → CLOSED, close_reason = USER, closed_at = 현재. */
    fun closeInquiry(userId: Long, inquiryId: Long): InquiryView {
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpenInquiry(inquiryId, userId)
            inquiry.status = "CLOSED"
            inquiry.closeReason = "USER"
            inquiry.closedAt = now
            inquiryView(inquiry, now)
        }!!
    }

    /** `GET /me/inquiries`(§9.2): 내 문의 inquiryId 오름차순. */
    fun inquiries(userId: Long): List<InquiryView> {
        val now = clock.now()
        return em.createQuery("SELECT i FROM Inquiry i WHERE i.userId = :user ORDER BY i.id", Inquiry::class.java)
            .setParameter("user", userId).resultList.map { inquiryView(it, now) }
    }

    /** `GET /me/inquiries/{id}`(§9.2): 작성자만. 메시지는 id 순, orderItem은 지금의 항목 상태와 OPEN 분쟁 유무(읽기만). 한 스냅샷에서 읽는다. */
    fun inquiryDetail(userId: Long, inquiryId: Long): InquiryDetailView = snapshotTx.execute {
        val now = clock.now()
        val inquiry = em.find(Inquiry::class.java, inquiryId) ?: notFound("없는 문의: $inquiryId")
        if (inquiry.userId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "작성자가 아닌 문의: $inquiryId")
        val messages = em.createQuery(
            "SELECT m FROM InquiryMessage m WHERE m.inquiryId = :inquiry ORDER BY m.id",
            InquiryMessage::class.java,
        ).setParameter("inquiry", inquiryId).resultList.map { InquiryMessageView(it.author, it.body, it.createdAt) }
        val orderItem = inquiry.orderItemId?.let { itemId ->
            val openDisputes = em.createQuery(
                "SELECT count(d) FROM Dispute d WHERE d.orderItemId = :item AND d.status = 'OPEN'",
                Long::class.javaObjectType,
            ).setParameter("item", itemId).singleResult
            InquiryOrderItemView(itemId, em.find(OrderItem::class.java, itemId).status, openDisputes > 0)
        }
        InquiryDetailView(inquiryView(inquiry, now), messages, orderItem)
    }!!

    /** 문의 모양(§9.1). slaBreached = (미답변이고 현재 > sla_due_at) 또는 first_answered_at > sla_due_at — 계산 값. */
    private fun inquiryView(i: Inquiry, now: Instant): InquiryView {
        val first = i.firstAnsweredAt
        val breached = if (first == null) now.isAfter(i.slaDueAt) else first.isAfter(i.slaDueAt)
        return InquiryView(
            i.id!!, i.category, i.title, i.status, i.closeReason, i.orderItemId, i.createdAt, i.slaDueAt, breached,
            i.firstAnsweredAt, i.answeredAt, i.closedAt,
        )
    }

    /**
     * inquiry-auto-close(§9.3): ANSWERED이고 answered_at + 7일 ≤ 현재(id 순). 문의 1건 = 그 행을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다.
     * → CLOSED, close_reason = AUTO, closed_at = 현재(배치 시각). 반환 = 종결한 문의 수.
     */
    private fun autoCloseInquiries(now: Instant): BatchResult {
        val due = now.minus(Duration.ofDays(7))
        return forEachTarget(
            "inquiries",
            "t.status = 'ANSWERED' AND t.answered_at <= :due",
            mapOf("due" to due),
            isolatedAs = "inquiry-auto-close",
        ) { inquiryId ->
            tx.execute {
                lockRow("inquiries", inquiryId)
                val inquiry = em.find(Inquiry::class.java, inquiryId)
                if (inquiry.status != "ANSWERED" || inquiry.answeredAt!!.isAfter(due)) return@execute false
                inquiry.status = "CLOSED"
                inquiry.closeReason = "AUTO"
                inquiry.closedAt = now
                true
            }!!
        }
    }

    // ---- A8 시세 (ADR-0007 §11.1·§11.2) ----

    /** 쿼리의 날짜(§2.1 "YYYY-MM-DD"). 없으면 null, 형식이 틀리거나 없는 날짜면 400. */
    private val ymd = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    private fun dateParam(name: String, raw: String?): LocalDate? {
        if (raw == null) return null
        if (!ymd.matches(raw)) badRequest("$name 는 YYYY-MM-DD 날짜여야 한다: $raw")   // ISO 확장 연도(+10000-…·-0001-…) 거부
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            badRequest("$name 는 YYYY-MM-DD 날짜여야 한다: $raw")
        }
    }

    /**
     * price-stats(§11.1): 대상 날짜 d = 오늘(KST) − 1일. 대상 = 상태 PAID이고 matched_at의 KST 날짜가 d인 체결이 있고 (사이즈, d) 행이 없는 사이즈
     * (product_sizes id 순). 잠금 없음. 사이즈 1개: 건수·최저·최고·중앙값(오름차순 floor((n − 1) / 2)번째 — 짝수면 아래 중앙값). 반환 = 쓴 행 수.
     */
    private fun writePriceStats(now: Instant): BatchResult {
        val d = LocalDate.ofInstant(now, kst).minusDays(1)
        val params = mapOf<String, Any>(
            "from" to d.atStartOfDay(kst).toInstant(),
            "to" to d.plusDays(1).atStartOfDay(kst).toInstant(),
            "d" to d,
        )
        return forEachTarget(
            "product_sizes",
            "EXISTS (SELECT 1 FROM trades tr WHERE tr.product_size_id = t.id AND tr.status = 'PAID'" +
                " AND tr.matched_at >= :from AND tr.matched_at < :to)" +
                " AND NOT EXISTS (SELECT 1 FROM price_stats_daily p WHERE p.product_size_id = t.id AND p.stat_date = :d)",
            params,
            isolatedAs = "price-stats",
        ) { sizeId ->
            tx.execute {
                val prices = em.createQuery(
                    "SELECT tr.price FROM Trade tr WHERE tr.productSizeId = :size AND tr.status = 'PAID'" +
                        " AND tr.matchedAt >= :from AND tr.matchedAt < :to ORDER BY tr.price",
                    Long::class.javaObjectType,
                ).setParameter("size", sizeId).setParameter("from", params.getValue("from")).setParameter("to", params.getValue("to"))
                    .resultList
                if (prices.isEmpty()) return@execute false
                em.persist(
                    PriceStatDaily(
                        productSizeId = sizeId,
                        statDate = d,
                        tradeCount = prices.size,
                        minPrice = prices.first(),
                        maxPrice = prices.last(),
                        medianPrice = prices[(prices.size - 1) / 2],
                        createdAt = now,
                    ),
                )
                true
            }!!
        }
    }

    /** `GET /product-sizes/{id}/price-stats`(§11.2, 헤더 불필요): from·to 필수(양끝 포함), from ≤ to, 일수 ≤ 92(아니면 400) → 없는 사이즈 404. date 오름차순. */
    fun priceStats(productSizeId: Long, from: String?, to: String?): List<PriceStatView> {
        val fromDate = dateParam("from", from) ?: badRequest("from이 필요하다")
        val toDate = dateParam("to", to) ?: badRequest("to가 필요하다")
        if (fromDate.isAfter(toDate)) badRequest("from이 to보다 늦다: $fromDate > $toDate")
        if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > 92) badRequest("기간은 92일 이하: $fromDate ~ $toDate")
        em.find(ProductSize::class.java, productSizeId) ?: notFound("없는 사이즈: $productSizeId")
        return em.createQuery(
            "SELECT p FROM PriceStatDaily p WHERE p.productSizeId = :size AND p.statDate >= :from AND p.statDate <= :to ORDER BY p.statDate",
            PriceStatDaily::class.java,
        ).setParameter("size", productSizeId).setParameter("from", fromDate).setParameter("to", toDate).resultList
            .map { PriceStatView(it.statDate, it.tradeCount, it.minPrice, it.maxPrice, it.medianPrice) }
    }

    /** `GET /product-sizes/{id}/recent-trades`(§11.2, 헤더 불필요): 그 사이즈의 PAID 체결 최근 20건, matched_at 내림차순·같으면 id 내림차순. */
    fun recentTrades(productSizeId: Long): List<RecentTradeView> {
        em.find(ProductSize::class.java, productSizeId) ?: notFound("없는 사이즈: $productSizeId")
        return em.createQuery(
            "SELECT t FROM Trade t WHERE t.productSizeId = :size AND t.status = 'PAID' ORDER BY t.matchedAt DESC, t.id DESC",
            Trade::class.java,
        ).setParameter("size", productSizeId).setMaxResults(20).resultList.map { RecentTradeView(it.id!!, it.price, it.matchedAt) }
    }

    // ---- A9 관리자 조회 (ADR-0007 §11.3 · §2.5 페이지) ----

    /** 키셋 페이지(§2.5): limit 1~100(기본 50), after 0 이상(기본 0 = 처음부터). 정수가 아니거나 범위 밖이면 400. */
    private class Page(val limit: Int, val after: Long)

    private fun page(limit: String?, after: String?): Page {
        val size = if (limit == null) 50 else limit.toIntOrNull() ?: badRequest("limit은 정수: $limit")
        if (size !in 1..100) badRequest("limit은 1~100: $size")
        val from = if (after == null) 0L else after.toLongOrNull() ?: badRequest("after는 정수: $after")
        if (from < 0) badRequest("after는 0 이상: $from")
        return Page(size, from)
    }

    /** 페이지 응답(§2.5): 받은 수가 limit과 같으면 마지막 id, 적으면 null. */
    private fun <T> pageView(items: List<T>, page: Page, idOf: (T) -> Long): PageView<T> =
        PageView(items, if (items.size == page.limit) idOf(items.last()) else null)

    /** 날짜 범위 필터(§2.5): 양끝 포함, 둘 다 있을 때 from > to면 400. */
    private fun dateRange(from: String?, to: String?): Pair<LocalDate?, LocalDate?> {
        val fromDate = dateParam("from", from)
        val toDate = dateParam("to", to)
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) badRequest("from이 to보다 늦다: $fromDate > $toDate")
        return fromDate to toDate
    }

    private fun requireUserRow(userId: Long): User = em.find(User::class.java, userId) ?: notFound("없는 사용자: $userId")

    /** `GET /admin/users/restricted`(§11.3): type = purchase|sale(필수), 그 제한 끝 > 현재인 사용자, id 순 페이지. */
    fun restrictedUsers(type: String?, limit: String?, after: String?): PageView<RestrictedUserView> {
        if (type == null) badRequest("type이 필요하다")
        val column = when (type) {
            "purchase" -> "purchaseRestrictedUntil"
            "sale" -> "saleRestrictedUntil"
            else -> badRequest("type은 purchase 또는 sale: $type")
        }
        val page = page(limit, after)
        val users = em.createQuery(
            "SELECT u FROM User u WHERE u.$column > :now AND u.id > :after ORDER BY u.id",
            User::class.java,
        ).setParameter("now", clock.now()).setParameter("after", page.after).setMaxResults(page.limit).resultList
        val items = users.map {
            RestrictedUserView(it.id!!, it.email, (if (type == "purchase") it.purchaseRestrictedUntil else it.saleRestrictedUntil)!!)
        }
        return pageView(items, page) { it.userId }
    }

    /** `GET /admin/users/{id}/summary`(§11.3·§19의 13): 경고·패널티 수는 전체 기간, sellerGrade = effective_from이 가장 늦은 행. 한 스냅샷에서 읽는다. */
    fun userSummary(userId: Long): UserSummaryView = snapshotTx.execute {
        val user = requireUserRow(userId)
        val warnings = em.createQuery("SELECT count(w) FROM BuyerWarning w WHERE w.userId = :user", Long::class.javaObjectType)
            .setParameter("user", userId).singleResult
        val penalties = em.createQuery("SELECT count(p) FROM SellerPenalty p WHERE p.sellerId = :user", Long::class.javaObjectType)
            .setParameter("user", userId).singleResult
        val grade = em.createQuery(
            "SELECT g FROM SellerGrade g WHERE g.sellerId = :seller ORDER BY g.effectiveFrom DESC",
            SellerGrade::class.java,
        ).setParameter("seller", userId).setMaxResults(1).resultList.firstOrNull()
        UserSummaryView(
            user.id!!, user.email, user.grade, user.purchaseRestrictedUntil, user.saleRestrictedUntil, warnings, penalties,
            grade?.let { SellerGradeView(it.grade, it.effectiveFrom, it.basisAmount, it.restricted) },
        )
    }!!

    /** `GET /admin/users/{id}/penalties`(§11.3): 그 판매자의 패널티, id 순 페이지. 없는 사용자 404. */
    fun userPenalties(userId: Long, limit: String?, after: String?): PageView<PenaltyView> {
        val page = page(limit, after)
        requireUserRow(userId)
        val items = em.createQuery(
            "SELECT p FROM SellerPenalty p WHERE p.sellerId = :user AND p.id > :after ORDER BY p.id",
            SellerPenalty::class.java,
        ).setParameter("user", userId).setParameter("after", page.after).setMaxResults(page.limit).resultList
            .map { PenaltyView(it.id!!, it.orderItemId, it.amount, it.settlementId, it.createdAt) }
        return pageView(items, page) { it.penaltyId }
    }

    /** `GET /admin/users/{id}/warnings`(§11.3): 그 사용자의 경고, id 순 페이지. 없는 사용자 404. */
    fun userWarnings(userId: Long, limit: String?, after: String?): PageView<WarningView> {
        val page = page(limit, after)
        requireUserRow(userId)
        val items = em.createQuery(
            "SELECT w FROM BuyerWarning w WHERE w.userId = :user AND w.id > :after ORDER BY w.id",
            BuyerWarning::class.java,
        ).setParameter("user", userId).setParameter("after", page.after).setMaxResults(page.limit).resultList
            .map { WarningView(it.id!!, it.tradeId, it.createdAt) }
        return pageView(items, page) { it.warningId }
    }

    /**
     * `GET /admin/settlements`(§11.3·§19의 13): 선택 필터 status(PAID·CARRIED)·sellerId·from~to(period_start, 양끝 포함), id 순 페이지.
     * 없는 sellerId는 빈 목록이다(필터).
     */
    fun adminSettlements(status: String?, sellerId: String?, from: String?, to: String?, limit: String?, after: String?): PageView<AdminSettlementView> {
        if (status != null && status != "PAID" && status != "CARRIED") badRequest("알 수 없는 정산 상태: $status")
        val seller = if (sellerId == null) null else sellerId.toLongOrNull() ?: badRequest("sellerId는 정수: $sellerId")
        val (fromDate, toDate) = dateRange(from, to)
        val page = page(limit, after)
        val jpql = "SELECT s FROM Settlement s WHERE s.id > :after" +
            (if (status != null) " AND s.status = :status" else "") +
            (if (seller != null) " AND s.sellerId = :seller" else "") +
            (if (fromDate != null) " AND s.periodStart >= :from" else "") +
            (if (toDate != null) " AND s.periodStart <= :to" else "") +
            " ORDER BY s.id"
        val query = em.createQuery(jpql, Settlement::class.java).setParameter("after", page.after)
        if (status != null) query.setParameter("status", status)
        if (seller != null) query.setParameter("seller", seller)
        if (fromDate != null) query.setParameter("from", fromDate)
        if (toDate != null) query.setParameter("to", toDate)
        val items = query.setMaxResults(page.limit).resultList
            .map { AdminSettlementView(it.id!!, it.sellerId, it.periodStart, it.status, it.net, it.payout, it.paidAt) }
        return pageView(items, page) { it.settlementId }
    }

    /** `GET /admin/disputes`(§11.3): 선택 필터 status(OPEN·ACCEPTED·REJECTED), id 순 페이지. */
    fun adminDisputes(status: String?, limit: String?, after: String?): PageView<AdminDisputeView> {
        if (status != null && status != "OPEN" && status != "ACCEPTED" && status != "REJECTED") badRequest("알 수 없는 분쟁 상태: $status")
        val page = page(limit, after)
        val query = em.createQuery(
            "SELECT d FROM Dispute d WHERE d.id > :after" + (if (status != null) " AND d.status = :status" else "") + " ORDER BY d.id",
            Dispute::class.java,
        ).setParameter("after", page.after)
        if (status != null) query.setParameter("status", status)
        val items = query.setMaxResults(page.limit).resultList
            .map { AdminDisputeView(it.id!!, it.orderItemId, it.status, it.openedAt, it.resolvedAt) }
        return pageView(items, page) { it.disputeId }
    }

    private val auditActions = setOf(
        "REVIEW_HIDE", "PAYOUT_ACCOUNT_DECISION", "INQUIRY_ANSWER", "TAX_INVOICE_CORRECTION", "TRACKING_EVENT",
        "INBOUND_SLOT_CREATE", "INBOUND_CHECK_IN",
    )

    /** `GET /admin/audit-logs`(§11.3): 선택 필터 action(일곱 값), id 순 페이지. */
    fun auditLogs(action: String?, limit: String?, after: String?): PageView<AuditLogView> {
        if (action != null && action !in auditActions) badRequest("알 수 없는 action: $action")
        val page = page(limit, after)
        val query = em.createQuery(
            "SELECT a FROM AdminAuditLog a WHERE a.id > :after" + (if (action != null) " AND a.action = :action" else "") + " ORDER BY a.id",
            AdminAuditLog::class.java,
        ).setParameter("after", page.after)
        if (action != null) query.setParameter("action", action)
        val items = query.setMaxResults(page.limit).resultList
            .map { AuditLogView(it.id!!, it.action, it.targetId, it.detail, it.createdAt) }
        return pageView(items, page) { it.auditId }
    }

    // ---- A10 판매자 프로필 (ADR-0007 §12.1) ----

    /**
     * 프로필 저장(§12.1·§19의 14). shopName 1~40자, intro 0~500자(필수 문자열), 휴가는 둘 다 null이거나 둘 다 있고 from ≤ to.
     * 사용자 행 FOR UPDATE 뒤 없으면 INSERT(created_at), 있으면 UPDATE. updated_at = 현재. 휴가는 표시만 한다.
     */
    fun putSellerProfile(userId: Long, shopName: String?, intro: String?, vacationFrom: LocalDate?, vacationTo: LocalDate?): SellerProfileView {
        if (shopName == null) badRequest("shopName이 필요하다")
        if (textLength(shopName) !in 1..40) badRequest("shopName은 1~40자")
        if (intro == null) badRequest("intro가 필요하다")
        if (textLength(intro) > 500) badRequest("intro는 0~500자")
        if ((vacationFrom == null) != (vacationTo == null)) badRequest("vacationFrom·vacationTo는 둘 다 있거나 둘 다 null")
        if (vacationFrom != null && vacationFrom.isAfter(vacationTo)) badRequest("vacationFrom이 vacationTo보다 늦다")

        val now = clock.now()
        return tx.execute {
            lockUser(userId)
            val profile = em.find(SellerProfile::class.java, userId)
            if (profile == null) {
                val created = SellerProfile(
                    userId = userId,
                    shopName = shopName,
                    intro = intro,
                    vacationFrom = vacationFrom,
                    vacationTo = vacationTo,
                    createdAt = now,
                    updatedAt = now,
                )
                em.persist(created)
                sellerProfileView(created, now)
            } else {
                profile.shopName = shopName
                profile.intro = intro
                profile.vacationFrom = vacationFrom
                profile.vacationTo = vacationTo
                profile.updatedAt = now
                sellerProfileView(profile, now)
            }
        }!!
    }

    /** `GET /sellers/{userId}/profile`(§12.1, 헤더 불필요): 사용자나 프로필이 없으면 404. */
    fun sellerProfile(userId: Long): SellerProfileView {
        requireUserRow(userId)
        val profile = em.find(SellerProfile::class.java, userId) ?: notFound("프로필이 없는 사용자: $userId")
        return sellerProfileView(profile, clock.now())
    }

    /** onVacation = 오늘(KST)이 [vacationFrom, vacationTo] 안(계산 값). */
    private fun sellerProfileView(p: SellerProfile, now: Instant): SellerProfileView {
        val today = LocalDate.ofInstant(now, kst)
        val from = p.vacationFrom
        val to = p.vacationTo
        val onVacation = from != null && to != null && !today.isBefore(from) && !today.isAfter(to)
        return SellerProfileView(p.userId, p.shopName, p.intro, from, to, onVacation)
    }

    // ---- A12 입고 예약 (ADR-0007 §12.3) ----

    /**
     * 슬롯 생성(§12.3). slotStart는 정시(분·초 0)이고 > 현재, capacity 1~100(아니면 400). 잠글 부모 행이 없으므로
     * INSERT … ON CONFLICT (slot_start) DO NOTHING으로 쓰고 들어간 행이 0이면 409(같은 시각 — 동시 생성도). + 감사 INBOUND_SLOT_CREATE(detail null).
     */
    fun createInboundSlot(slotStart: Instant?, capacity: Long?): InboundSlotView {
        if (slotStart == null) badRequest("slotStart가 필요하다")
        if (capacity == null) badRequest("capacity가 필요하다")
        if (capacity !in 1L..100L) badRequest("capacity는 1~100: $capacity")
        if (slotStart.epochSecond % 3600 != 0L || slotStart.nano != 0) badRequest("slotStart는 정시여야 한다: $slotStart")
        val now = clock.now()
        if (!slotStart.isAfter(now)) badRequest("slotStart는 현재 뒤여야 한다: $slotStart")

        return tx.execute {
            val inserted = em.createNativeQuery(
                "INSERT INTO inbound_slots (slot_start, capacity, created_at) VALUES (:start, :capacity, :now)" +
                    " ON CONFLICT (slot_start) DO NOTHING",
            ).setParameter("start", slotStart).setParameter("capacity", capacity.toInt()).setParameter("now", now).executeUpdate()
            if (inserted == 0) stateConflict("같은 시각의 슬롯이 있다: $slotStart")
            val slotId = (
                em.createNativeQuery("SELECT id FROM inbound_slots WHERE slot_start = :start")
                    .setParameter("start", slotStart).singleResult as Number
                ).toLong()
            audit("INBOUND_SLOT_CREATE", slotId, null, now)
            InboundSlotView(slotId, slotStart, capacity.toInt(), 0)
        }!!
    }

    /** `GET /inbound-slots?date=`(§12.3, 헤더 불필요): date 필수, 그 KST 날짜의 슬롯 slotStart 오름차순. */
    fun inboundSlots(date: String?): List<InboundSlotView> {
        val d = dateParam("date", date) ?: badRequest("date가 필요하다")
        return snapshotTx.execute {
            em.createQuery(
                "SELECT s FROM InboundSlot s WHERE s.slotStart >= :from AND s.slotStart < :to ORDER BY s.slotStart",
                InboundSlot::class.java,
            ).setParameter("from", d.atStartOfDay(kst).toInstant()).setParameter("to", d.plusDays(1).atStartOfDay(kst).toInstant())
                .resultList.map { InboundSlotView(it.id!!, it.slotStart, it.capacity, liveReservationsOfSlot(it.id!!)) }
        }!!
    }

    private fun liveReservationsOfSlot(slotId: Long): Long =
        em.createQuery(
            "SELECT count(r) FROM InboundReservation r WHERE r.slotId = :slot AND r.status IN ('RESERVED', 'CHECKED_IN')",
            Long::class.javaObjectType,
        ).setParameter("slot", slotId).singleResult

    /**
     * 입고 예약(§12.3). 없는 슬롯·항목 404, 그 체결의 판매자가 아니면 403. 주문 행 → 슬롯 행 순서로 FOR UPDATE 한 뒤
     * 1 항목이 PAID가 아니거나 그 항목의 살아 있는 예약(RESERVED·CHECKED_IN)이 있으면 409 2 살아 있는 예약 수 ≥ 정원이면 409
     * 3 현재 > slot_start − 24시간이면 409 DEADLINE_PASSED → 1행(RESERVED, created_at = 현재).
     */
    fun reserveInbound(userId: Long, slotId: Long?, orderItemId: Long?): InboundReservationView {
        if (slotId == null) badRequest("slotId가 필요하다")
        if (orderItemId == null) badRequest("orderItemId가 필요하다")
        val now = clock.now()
        return tx.execute {
            val slot = em.find(InboundSlot::class.java, slotId) ?: notFound("없는 슬롯: $slotId")
            val item = em.find(OrderItem::class.java, orderItemId) ?: notFound("없는 주문 항목: $orderItemId")
            if (em.find(Trade::class.java, item.tradeId).sellerId != userId) {
                throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "판매자가 아닌 주문 항목: $orderItemId")
            }
            em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE").setParameter("id", item.orderId).resultList
            em.refresh(item)
            lockRow("inbound_slots", slotId)
            requireItemStatus(item, "PAID")
            val liveOfItem = em.createQuery(
                "SELECT count(r) FROM InboundReservation r WHERE r.orderItemId = :item AND r.status IN ('RESERVED', 'CHECKED_IN')",
                Long::class.javaObjectType,
            ).setParameter("item", orderItemId).singleResult
            if (liveOfItem > 0) stateConflict("살아 있는 입고 예약이 있는 주문 항목: $orderItemId")
            if (liveReservationsOfSlot(slotId) >= slot.capacity) stateConflict("정원이 찬 슬롯: $slotId")
            val deadline = slot.slotStart.minus(Duration.ofHours(24))
            if (now.isAfter(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "입고 예약 기한($deadline)이 지났다: 슬롯 $slotId")
            }
            val reservation = InboundReservation(
                slotId = slotId,
                sellerId = userId,
                orderItemId = orderItemId,
                status = "RESERVED",
                createdAt = now,
            )
            em.persist(reservation)
            reservationView(reservation)
        }!!
    }

    /** 예약 취소(§12.3). 본인 것만, 예약 행 FOR UPDATE 뒤 RESERVED가 아니면 409, 현재 > slot_start − 2시간이면 409 DEADLINE_PASSED. */
    fun cancelInboundReservation(userId: Long, reservationId: Long): InboundReservationView {
        val now = clock.now()
        return tx.execute {
            val reservation = em.find(InboundReservation::class.java, reservationId) ?: notFound("없는 예약: $reservationId")
            if (reservation.sellerId != userId) throw ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "남의 예약: $reservationId")
            lockRow("inbound_reservations", reservationId)
            em.refresh(reservation)
            if (reservation.status != "RESERVED") stateConflict("RESERVED가 아닌 예약: $reservationId (${reservation.status})")
            val deadline = em.find(InboundSlot::class.java, reservation.slotId).slotStart.minus(Duration.ofHours(2))
            if (now.isAfter(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "입고 예약 취소 기한($deadline)이 지났다: $reservationId")
            }
            reservation.status = "CANCELLED"
            reservation.closedAt = now
            reservationView(reservation)
        }!!
    }

    /**
     * 체크인(§12.3). 예약 행 FOR UPDATE 뒤 RESERVED가 아니면 409, 현재 < slot_start면 409, 현재 ≥ slot_start + 1시간이면 409 DEADLINE_PASSED.
     * → CHECKED_IN, closed_at = 현재 + 감사 INBOUND_CHECK_IN(detail null). 항목 상태는 바꾸지 않는다.
     */
    fun checkInInbound(reservationId: Long): InboundReservationView {
        val now = clock.now()
        return tx.execute {
            val reservation = em.find(InboundReservation::class.java, reservationId) ?: notFound("없는 예약: $reservationId")
            lockRow("inbound_reservations", reservationId)
            em.refresh(reservation)
            if (reservation.status != "RESERVED") stateConflict("RESERVED가 아닌 예약: $reservationId (${reservation.status})")
            val slotStart = em.find(InboundSlot::class.java, reservation.slotId).slotStart
            if (now.isBefore(slotStart)) stateConflict("슬롯 시작($slotStart) 전이다: $reservationId")
            val deadline = slotStart.plus(Duration.ofHours(1))
            if (!now.isBefore(deadline)) {
                throw ApiException(HttpStatus.CONFLICT, "DEADLINE_PASSED", "체크인 기한($deadline)이 지났다: $reservationId")
            }
            reservation.status = "CHECKED_IN"
            reservation.closedAt = now
            audit("INBOUND_CHECK_IN", reservationId, null, now)
            reservationView(reservation)
        }!!
    }

    /** `GET /me/inbound-reservations`(§12.3): 내 예약 reservationId 오름차순. */
    fun inboundReservations(userId: Long): List<InboundReservationView> =
        em.createQuery("SELECT r FROM InboundReservation r WHERE r.sellerId = :seller ORDER BY r.id", InboundReservation::class.java)
            .setParameter("seller", userId).resultList.map { reservationView(it) }

    private fun reservationView(r: InboundReservation) =
        InboundReservationView(
            r.id!!, r.slotId, em.find(InboundSlot::class.java, r.slotId).slotStart, r.orderItemId, r.status, r.createdAt, r.closedAt,
        )

    /**
     * inbound-no-show(§12.3): RESERVED이고 slot_start + 1시간 ≤ 현재(id 순). 예약 1건 = 그 행을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다.
     * → NO_SHOW, closed_at = 현재. 패널티·판매 제한과 무관하다. 반환 = 바꾼 예약 수.
     */
    private fun markInboundNoShows(now: Instant): BatchResult {
        val due = now.minus(Duration.ofHours(1))
        return forEachTarget(
            "inbound_reservations",
            "t.status = 'RESERVED' AND EXISTS (SELECT 1 FROM inbound_slots s WHERE s.id = t.slot_id AND s.slot_start <= :due)",
            mapOf("due" to due),
            isolatedAs = "inbound-no-show",
        ) { reservationId ->
            tx.execute {
                lockRow("inbound_reservations", reservationId)
                val reservation = em.find(InboundReservation::class.java, reservationId)
                if (reservation.status != "RESERVED") return@execute false
                reservation.status = "NO_SHOW"
                reservation.closedAt = now
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
        }.processed
        if (converted > 0) log.warn("기동 시 PAYING 주문 {}건을 UNKNOWN으로 바꿨다(확정은 pg-inquiry)", converted)
    }
}
