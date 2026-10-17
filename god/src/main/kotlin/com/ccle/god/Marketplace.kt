package com.ccle.god

import com.ccle.god.entity.Bid
import com.ccle.god.entity.BidEvent
import com.ccle.god.entity.ProductSize
import com.ccle.god.entity.Trade
import com.ccle.god.entity.User
import com.ccle.god.web.ApiException
import com.ccle.god.web.BidCancelled
import com.ccle.god.web.BidPlaced
import com.ccle.god.web.BidView
import com.ccle.god.web.BookView
import com.ccle.god.web.TradeView
import jakarta.persistence.EntityManager
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
) {

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

    // ---- 포인트 (§5.2) ----

    // ---- 배치: bid-expiry·payment-due·pg-inquiry (§7) ----
}
