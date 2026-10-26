package hidden

import com.fasterxml.jackson.databind.JsonNode
import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C2 숨은 테스트 준비 도우미. 패널티는 자연 흐름(판매 입찰 → 구매 체결 → 결제 → 발송 → 입고 → 검수 불합격)으로만 만든다. */
object Fx {
    private val KST = ZoneId.of("Asia/Seoul")
    private val n = AtomicLong(System.nanoTime() % 1_000_000_000)

    private fun uniq() = "c2h-${n.incrementAndGet()}"

    fun createUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@c2.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    fun createProductSize(): Long {
        val u = uniq()
        val category = Db.long("SELECT min(id) FROM categories")
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES (?, 'c2', ?, ?, 100000) RETURNING id",
            category, "c2-$u", "C2-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    fun ok(step: String, r: Resp, expected: Int = 200): Resp {
        assertEquals(expected, r.status, "준비 $step: $r")
        return r
    }

    /** 판매자가 사이즈 하나에 판매 입찰(90일)을 건다(제한은 새 입찰에만 걸리므로 패널티 전에 미리 건다). 반환: 사이즈 id. */
    fun sellBid(seller: Long, price: Long): Long {
        val size = createProductSize()
        val r = ok("판매 입찰", Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 90), seller), 201)
        assertEquals("ACTIVE", r.json["status"].asText(), "준비 판매 입찰 ACTIVE: $r")
        return size
    }

    /** 새 구매자가 그 사이즈를 사서 결제한다. 반환: (구매자, 주문 항목 id). */
    fun buyAndPay(size: Long, price: Long): Pair<Long, Long> {
        val buyer = createUser()
        val b = ok("구매 입찰", Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer), 201)
        assertEquals("MATCHED", b.json["status"].asText(), "준비 체결: $b")
        val pay = ok("결제", Api.post("/orders", mapOf("tradeIds" to listOf(b.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "c2h-" + UUID.randomUUID()), 201)
        assertEquals("PAID", pay.json["status"].asText(), "준비 결제 승인: $pay")
        return buyer to pay.json["items"].single()["orderItemId"].asLong()
    }

    /** 결제된 항목을 발송 → 입고 → 검수 불합격(판매자 귀책 — 패널티 1행)으로. */
    fun sellerFault(item: Long, seller: Long) {
        ok("발송", Api.post("/order-items/$item/shipment", null, seller))
        ok("입고", Api.post("/admin/order-items/$item/receipt", null))
        val r = ok("검수 불합격", Api.post("/admin/order-items/$item/inspection", mapOf("result" to "FAIL", "reason" to "오염")))
        assertEquals("CANCELLED_SELLER_FAULT", r.json["status"].asText(), "준비 판매자 귀책: $r")
    }

    fun penalties(user: Any?): Resp = Api.get("/me/penalties", user)

    fun weeklySettlement() = ok("주간 정산", Batches.run("weekly-settlement"))

    /** t 뒤 첫 정산 마감(다음 월요일 00:00 KST). */
    fun nextCutoff(t: Instant): Instant =
        t.atZone(KST).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(KST).toInstant()
}

/** DB의 패널티 1행(기대값 계산용). */
data class PenRow(val id: Long, val orderItemId: Long, val amount: Long, val createdAt: Instant, val settled: Boolean)

/** 응답의 패널티 1행. */
data class PenOut(val penaltyId: Long, val orderItemId: Long, val amount: Long, val createdAt: Instant, val settled: Boolean)

object Pen {
    fun rowsOfSeller(seller: Long): List<PenRow> =
        Db.query("SELECT id, order_item_id, amount, created_at, settlement_id FROM seller_penalties WHERE seller_id = ?", seller).map {
            PenRow(
                (it["id"] as Number).toLong(), (it["order_item_id"] as Number).toLong(), (it["amount"] as Number).toLong(),
                (it["created_at"] as java.sql.Timestamp).toInstant(), it["settlement_id"] != null,
            )
        }

    fun rowOfItem(item: Long): PenRow =
        Db.query("SELECT seller_id FROM seller_penalties WHERE order_item_id = ?", item).single().let { r ->
            rowsOfSeller((r["seller_id"] as Number).toLong()).single { it.orderItemId == item }
        }

    /** 계약의 정렬: 생성 시각 내림차순, 같으면 패널티 id 내림차순. */
    fun expected(rows: List<PenRow>): List<PenOut> =
        rows.sortedWith(compareByDescending<PenRow> { it.createdAt }.thenByDescending { it.id })
            .map { PenOut(it.id, it.orderItemId, it.amount, it.createdAt, it.settled) }

    /** 200 + JSON 배열, 원소마다 필드·타입 확인(정수·정수·정수·KST 오프셋 ISO-8601·불리언). */
    fun parse(r: Resp): List<PenOut> {
        assertEquals(200, r.status, "패널티 조회: $r")
        assertTrue(r.json.isArray, "응답은 JSON 배열: $r")
        return r.json.map { e -> one(e, r) }
    }

    private fun one(e: JsonNode, r: Resp): PenOut {
        for (f in listOf("penaltyId", "orderItemId", "amount", "createdAt", "settled")) assertTrue(e.has(f), "필드 '$f' 없음: $e ($r)")
        for (f in listOf("penaltyId", "orderItemId", "amount")) assertTrue(e[f].isIntegralNumber, "'$f'는 정수: $e")
        assertTrue(e["createdAt"].isTextual, "createdAt은 문자열: $e")
        assertTrue(e["settled"].isBoolean, "settled는 불리언: $e")
        val t = OffsetDateTime.parse(e["createdAt"].asText())
        assertEquals("+09:00", t.offset.id, "createdAt은 KST 오프셋: $e")
        return PenOut(e["penaltyId"].asLong(), e["orderItemId"].asLong(), e["amount"].asLong(), t.toInstant(), e["settled"].asBoolean())
    }
}

fun now(): Instant = Clock.now()
