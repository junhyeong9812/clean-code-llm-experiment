package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §11.1 배치 price-stats(어제 KST의 PAID 체결 집계, 아래 중앙값, 배치 시점 상태, 소급 없음)와
 * §11.2 시세 조회(price-stats 날짜 범위 양끝 포함·≤ 92일, recent-trades 최근 PAID 20건).
 */
@Contract3Test
class A8PriceStatsTest {

    private fun statRow(size: Long, d: LocalDate): Row3? =
        Db3.rows("price_stats_daily", "product_size_id = ? AND stat_date = ?", size, d).singleOrNull()

    /** 같은 사이즈에 prices대로 체결을 만들고 한 주문으로 결제한다(체결 시각 = 현재). 반환: tradeIds. */
    private fun paidTrades(size: Long, prices: List<Long>): List<Long> {
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val trades = prices.map { Flow.trade(buyer, seller, size, it) }
        Flow.pay(buyer, trades).assertStatus(201)
        return trades
    }

    /** 결제하지 않은 체결 1건(AWAITING_PAYMENT). 반환: (구매자, tradeId). */
    private fun unpaidTrade(size: Long, price: Long): Pair<Long, Long> {
        val buyer = Db.createUser()
        return buyer to Flow.trade(buyer, Db.createUser(), size, price)
    }

    /** 다음 KST 자정 10분 전(현재 이후)으로 시계를 옮긴다. 반환: 그 KST 날짜. */
    private fun toLateEvening(): LocalDate {
        val now = Flow.now()
        var t = Kst.at(Kst.date(now).plusDays(1)).minus(Duration.ofMinutes(10))
        if (t.isBefore(now)) t = t.plus(DAY)
        Flow.moveTo(t)
        return Kst.date(t)
    }

    @Test
    fun `§11_1 price-stats - 어제(KST) PAID 체결만 집계, median = 정렬 후 floor((n−1)÷2)번째(짝수는 아래 중앙값), created_at = 현재 - 배치 뒤 PAID가 된 체결로 다시 쓰지 않는다`() {
        val d = toLateEvening()
        val even = Db.createProductSize()
        val odd = Db.createProductSize()
        val unpaidOnly = Db.createProductSize()
        paidTrades(even, listOf(300_000, 100_000, 400_000, 200_000))
        val (lateBuyer, lateTrade) = unpaidTrade(even, 50_000)
        paidTrades(odd, listOf(10_000, 30_000, 20_000))
        unpaidTrade(unpaidOnly, 70_000)

        val at = Flow.moveTo(Kst.at(d.plusDays(1)))
        S2.runBatch("price-stats")
        val e = statRow(even, d)!!
        assertEquals(4L, e.long("trade_count"))
        assertEquals(100_000L, e.long("min_price"))
        assertEquals(400_000L, e.long("max_price"))
        assertEquals(200_000L, e.long("median_price"), "짝수 개는 아래 중앙값")
        assertEquals(at, e.instant("created_at"))
        val o = statRow(odd, d)!!
        assertEquals(listOf(3L, 10_000L, 30_000L, 20_000L), listOf("trade_count", "min_price", "max_price", "median_price").map { o.long(it) })
        assertNull(statRow(unpaidOnly, d), "PAID 체결이 없는 사이즈는 행이 없다")

        // 그 날의 체결이 배치 뒤에 PAID가 되어도 이미 쓴 행은 고치지 않는다(재실행도 새 행을 만들지 않는다)
        Flow.pay(lateBuyer, listOf(lateTrade)).assertStatus(201)
        Flow.advance(SEC)
        S2.runBatch("price-stats")
        val e2 = statRow(even, d)!!
        assertEquals(e.id, e2.id)
        assertEquals(4L, e2.long("trade_count"))
        assertEquals(100_000L, e2.long("min_price"))
        assertEquals(at, e2.instant("created_at"))
        assertEquals(1L, Db3.count("price_stats_daily", "product_size_id = ?", even))
        assertNull(statRow(unpaidOnly, d))
    }

    @Test
    fun `§11_1 price-stats 격리 - A 사이즈 쓰기가 실패해도 failed ≥ 1·A 행 없음, B는 씀, 다시 돌리면 A도 쓴다`() {
        val d = toLateEvening()
        val a = Db.createProductSize()
        val b = Db.createProductSize()
        assertTrue(a < b)
        paidTrades(a, listOf(10_000))
        paidTrades(b, listOf(20_000))
        val at = Flow.moveTo(Kst.at(d.plusDays(1)))
        FailTrigger.on("price_stats_daily", "INSERT", "NEW.product_size_id = $a") {
            val r = S2.runBatch("price-stats")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertNull(statRow(a, d))
        assertEquals(at, statRow(b, d)!!.instant("created_at"))
        val later = Flow.advance(SEC)
        S2.runBatch("price-stats")
        val ra = statRow(a, d)!!
        assertEquals(listOf(1L, 10_000L, 10_000L, 10_000L), listOf("trade_count", "min_price", "max_price", "median_price").map { ra.long(it) })
        assertEquals(later, ra.instant("created_at"))
        assertEquals(at, statRow(b, d)!!.instant("created_at"))
    }

    @Test
    fun `§11_1 price-stats는 지난 날짜를 소급하지 않는다 - 그제 체결은 오늘 실행에서 행이 생기지 않는다`() {
        val now = Flow.now()
        val size = Db.createProductSize()
        paidTrades(size, listOf(15_000))
        val day = Kst.date(now)
        Flow.moveTo(Kst.at(day.plusDays(2)))
        S2.runBatch("price-stats")
        assertNull(statRow(size, day), "대상 날짜는 오늘 − 1일뿐")
        assertEquals(0L, Db3.count("price_stats_daily", "product_size_id = ?", size))
    }

    // ---- §11.2 시세 조회 ----

    @Test
    fun `§11_2 price-stats 조회 - from·to 양끝 포함, date 오름차순, 행 모양, 잘못된 X-User-Id도 200(헤더 불필요)`() {
        val size = Db.createProductSize()
        val d1 = LocalDate.parse("2026-09-10")
        val created = Db.SEED_NOW.toInstant()
        val stats = listOf(
            Triple(d1.plusDays(2), 1L, listOf(5_000L, 5_000L, 5_000L)),
            Triple(d1, 3L, listOf(1_000L, 3_000L, 2_000L)),
            Triple(d1.plusDays(1), 2L, listOf(4_000L, 6_000L, 4_000L)),
        )
        for ((d, n, p) in stats) {
            Db3.insert("price_stats_daily", mapOf(
                "product_size_id" to size, "stat_date" to d, "trade_count" to n,
                "min_price" to p[0], "max_price" to p[1], "median_price" to p[2], "created_at" to created,
            ))
        }
        fun dates(from: LocalDate, to: LocalDate): List<String> {
            val r = Api.get("/product-sizes/$size/price-stats?from=$from&to=$to").assertStatus(200)
            return SC.array(r).map { it.textAt("date") }
        }
        val all = SC.array(Api.get("/product-sizes/$size/price-stats?from=$d1&to=${d1.plusDays(2)}").assertStatus(200))
        assertEquals(listOf(d1, d1.plusDays(1), d1.plusDays(2)).map { it.toString() }, all.map { it.textAt("date") })
        val first = all[0]
        assertEquals(3L, first.longAt("tradeCount"))
        assertEquals(1_000L, first.longAt("minPrice"))
        assertEquals(3_000L, first.longAt("maxPrice"))
        assertEquals(2_000L, first.longAt("medianPrice"))
        assertEquals(listOf(d1.plusDays(1).toString()), dates(d1.plusDays(1), d1.plusDays(1)))
        assertEquals(listOf(d1, d1.plusDays(1)).map { it.toString() }, dates(d1, d1.plusDays(1)))
        assertEquals(listOf(d1.plusDays(2).toString()), dates(d1.plusDays(2), d1.plusDays(30)))
        assertEquals(emptyList(), dates(d1.minusDays(10), d1.minusDays(1)))
        // §19의 7: 헤더 불필요 — 잘못된 X-User-Id(숫자 아님·없는 사용자)도 읽지 않는다
        val q = "/product-sizes/$size/price-stats?from=$d1&to=$d1"
        assertEquals(1, SC.array(Api.get(q, "abc").assertStatus(200)).size)
        assertEquals(1, SC.array(Api.get(q, Db.missingId("users")).assertStatus(200)).size)
        Api.get("/product-sizes/$size/recent-trades", "abc").assertStatus(200)
    }

    @Test
    fun `§11_2 price-stats 조회 400·404 - from·to 필수, 형식 오류, from ＞ to, 일수 93(92는 허용), 없는 사이즈 404(400이 먼저)`() {
        val size = Db.createProductSize()
        val base = "/product-sizes/$size/price-stats"
        val from = LocalDate.parse("2026-06-01")
        Api.get("$base?from=$from&to=${from.plusDays(91)}").assertStatus(200)        // 92일
        Api.get("$base?from=$from&to=${from.plusDays(92)}").assertError(400, "INVALID_REQUEST") // 93일
        Api.get("$base?from=$from&to=$from").assertStatus(200)                       // 1일
        Api.get("$base?from=$from&to=${from.minusDays(1)}").assertError(400, "INVALID_REQUEST")
        Api.get("$base?to=$from").assertError(400, "INVALID_REQUEST")
        Api.get("$base?from=$from").assertError(400, "INVALID_REQUEST")
        Api.get(base).assertError(400, "INVALID_REQUEST")
        Api.get("$base?from=2026-6-1&to=2026-06-02").assertError(400, "INVALID_REQUEST")
        Api.get("$base?from=2026-06-01&to=2026-02-30").assertError(400, "INVALID_REQUEST")
        val missing = Db.missingId("product_sizes")
        Api.get("/product-sizes/$missing/price-stats?from=$from&to=$from").assertError(404, "NOT_FOUND")
        Api.get("/product-sizes/$missing/price-stats?from=$from").assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `§11_2 recent-trades - 그 사이즈 PAID 체결 최근 20건, matched_at 내림차순·같으면 id 내림차순, 미결제 제외, 없는 사이즈 404`() {
        val size = Db.createProductSize()
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val t0 = Flow.now()
        val early = (0 until 11).map { Flow.trade(buyer, seller, size, 10_000L + it * 100) }
        val t1 = Flow.advance(Duration.ofMinutes(1))
        val late = (0 until 10).map { Flow.trade(buyer, seller, size, 20_000L + it * 100) }
        Flow.pay(buyer, early + late).assertStatus(201)
        Flow.advance(Duration.ofMinutes(1))
        unpaidTrade(size, 5_000)

        val r = Api.get("/product-sizes/$size/recent-trades").assertStatus(200)
        val list = SC.array(r)
        val expected = late.sortedDescending() + early.sortedDescending().take(10)
        assertEquals(expected, list.map { it.longAt("tradeId") }, "최근 20건: $r")
        for (j in list) {
            val t = Db.trade(j.longAt("tradeId"))
            assertEquals("PAID", t.status)
            assertEquals(t.price, j.longAt("price"))
            assertEquals(t.matchedAt, j.instantAt("matchedAt"))
        }
        assertEquals(t1, list.first().instantAt("matchedAt"))
        assertEquals(t0, list.last().instantAt("matchedAt"))
        assertEquals(emptyList(), SC.array(Api.get("/product-sizes/${Db.createProductSize()}/recent-trades").assertStatus(200)))
        Api.get("/product-sizes/${Db.missingId("product_sizes")}/recent-trades").assertError(404, "NOT_FOUND")
    }
}
