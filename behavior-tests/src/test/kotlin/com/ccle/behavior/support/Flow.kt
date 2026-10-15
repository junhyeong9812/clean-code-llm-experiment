package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 엔드포인트를 부르는 짧은 도우미. 단언은 테스트 쪽에서 한다(여기서는 준비 단계의 성공만 확인). */
object Flow {
    private val KST = ZoneOffset.ofHours(9)

    // ---- 시계 (§7) ----
    fun now(): Instant = Api.get("/admin/clock").assertStatus(200).json.instantAt("now")

    fun setClock(t: Instant): Resp = Api.put("/admin/clock", mapOf("now" to iso(t)))

    /** 시계를 d만큼 앞으로 옮기고 새 현재 시각을 돌려준다. */
    fun advance(d: Duration): Instant {
        val target = now().plus(d)
        val r = setClock(target)
        assertTrue(r.status in 200..299, "시계 이동 실패: $r")
        assertEquals(target, now(), "시계가 옮긴 값이 아니다")
        return target
    }

    /** 시계를 정확히 t로 옮긴다(t ≥ 현재여야 한다). */
    fun moveTo(t: Instant): Instant {
        val r = setClock(t)
        assertTrue(r.status in 200..299, "시계 이동 실패: $r")
        return t
    }

    /** 초를 항상 출력한다(OffsetDateTime.toString()은 초가 0이면 초를 뺀다). */
    fun iso(t: Instant): String = OffsetDateTime.ofInstant(t, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    // ---- 입찰 (§3) ----
    fun bid(user: Long, size: Long, side: String, price: Long, validDays: Int = 30): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to validDays), user)

    fun instant(user: Long, size: Long, side: String): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "instant" to true), user)

    /** 일반 입찰을 ACTIVE로 등록하고 bidId를 돌려준다. */
    fun activeBid(user: Long, size: Long, side: String, price: Long, validDays: Int = 30): Long {
        val r = bid(user, size, side, price, validDays).assertStatus(201)
        assertEquals("ACTIVE", r.text("status"), "준비: ACTIVE 입찰이어야 한다 $r")
        return r.long("bidId")
    }

    /** 판매 입찰 → 구매 입찰로 체결 1건을 만든다(가격 = 판매가 = 구매가). */
    fun trade(buyer: Long, seller: Long, size: Long, price: Long, validDays: Int = 30): Long {
        activeBid(seller, size, "SELL", price, validDays)
        val r = bid(buyer, size, "BUY", price, validDays).assertStatus(201)
        assertEquals("MATCHED", r.text("status"), "준비: 체결되어야 한다 $r")
        return r.long("tradeId")
    }

    fun cancel(user: Any?, bidId: Long): Resp = Api.delete("/bids/$bidId", user)

    // ---- 결제 (§5) ----
    fun newKey(): String = "bt-" + UUID.randomUUID().toString()

    fun pay(user: Any?, tradeIds: List<Long>, pointToUse: Long? = null, key: String? = newKey()): Resp {
        val body = buildMap<String, Any> {
            put("tradeIds", tradeIds)
            if (pointToUse != null) put("pointToUse", pointToUse)
        }
        val r = Api.post("/orders", body, user, key)
        // 새 주문(201)이면 PG는 그 키로 승인 요청 1건을 card_amount로 받았어야 한다(§5.1·§6).
        // 모든 결제 경로에 같은 단언을 건다. 200(멱등 재요청)·오류 응답은 PG를 부르지 않으므로 여기서 보지 않는다.
        if (r.status == 201 && key != null) {
            assertEquals(listOf(r.long("cardAmount")), PgClient.suite.approvalAmounts(key), "PG가 받은 amount = card_amount: $r")
        }
        return r
    }

    /** 가짜 PG에 그 멱등 키의 결과를 정한다(HTTP 제어, §6). */
    fun pgOutcome(key: String, outcome: FakePg.Outcome) {
        val r = Api.request("POST", "/_control/outcomes", mapOf("idempotencyKey" to key, "outcome" to outcome.name), base = pgUrl())
        assertEquals(200, r.status, "가짜 PG 제어 실패: $r")
    }

    /** 그 키의 재조회(GET)가 응답하지 않게 한다(ADR 밖 보조 제어). */
    fun pgInquiryTimeout(key: String) {
        val r = Api.request("POST", "/_control/inquiry-outcomes", mapOf("idempotencyKey" to key, "outcome" to "TIMEOUT"), base = pgUrl())
        assertEquals(200, r.status, "가짜 PG 제어 실패: $r")
    }

    fun pgUrl() = "http://127.0.0.1:${Env.pgPort}"

    // ---- 배치 (§7) ----
    fun batch(name: String): Resp = Api.post("/admin/batches/$name", null)

    fun runBatch(name: String): Resp {
        val r = batch(name).assertStatus(200)
        assertTrue(r.json.get("processed")?.isIntegralNumber == true, "processed 정수 아님: $r")
        return r
    }
}
