package hidden

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import support.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 숨은 테스트: 구매·판매 제한은 끝 시각 정각까지 제한이다(끝 ≥ 현재). 끝 < 현재(1초 전)는 제한 아님, 끝 > 현재는 그대로 제한.
 * 영역마다 한 메서드: 새 입찰(BUY·SELL) · 판매자 등급 · 제한 알림(구매·판매) · 가격 알림 발동 · 가격 알림 재무장 · 관리자 목록(purchase·sale)
 * + 정답이 깨뜨리는 기존 계약 이전(메서드 이름에 '이전'): 제한 경계(contract RestrictionAndConcurrencyTest) → B1,
 * A2·A4·A5·A9·A14 경계 단언 → 각 영역 메서드, 구매 경고 재계수 2건(contract BatchAndClockTest) → R1·R2(제한이 풀린 시각 = 끝 + 1초로 준비).
 * 판매 패널티 재계수(제한을 건 시각과 같은 시각의 패널티는 다시 세지 않는다)는 R3(contract2 PenaltyTest 방식 — 판매 입찰을 미리 걸어 둔다).
 * 멈춘 시계라 Clock.now()를 끝 시각으로 넣으면 끝 = 현재가 정확히 성립한다. 테스트끼리 같은 DB·시계를 쓴다:
 * 사용자·사이즈는 테스트마다 새로 만들고 시계는 앞으로만 상대 이동한다(실행 순서는 이름순, 순서에 기대는 단언 없음).
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class D1HiddenTest {
    private val sec = Duration.ofSeconds(1)

    @Test
    fun `B1 구매 입찰 - 구매 제한 끝 = 현재면 구매 입찰·즉시 구매 403, 판매는 허용, 끝이 1초 전이면 허용 (제한 경계 이전)`() {
        val now = Clock.now()
        val size = D1Fx.createProductSize()
        val edge = D1Fx.createUser(purchaseUntil = now, saleUntil = now.minus(Duration.ofDays(1)))
        val r1 = D1Fx.bid(edge, size, "BUY", 90_000)
        assertEquals(403, r1.status, "끝 = 현재: $r1")
        assertEquals("PURCHASE_RESTRICTED", r1.json["code"].asText(), "$r1")
        val r2 = D1Fx.instant(edge, size, "BUY")
        assertEquals(403, r2.status, "즉시 구매도 403(상대 없음보다 먼저): $r2")
        assertEquals("PURCHASE_RESTRICTED", r2.json["code"].asText(), "$r2")
        D1Fx.activeBid(edge, size, "SELL", 120_000) // 판매 제한 끝이 하루 전 → 판매 허용

        val released = D1Fx.createUser(purchaseUntil = now.minus(sec))
        D1Fx.activeBid(released, size, "BUY", 80_000)
        val later = D1Fx.createUser(purchaseUntil = now.plus(sec))
        assertEquals(403, D1Fx.bid(later, size, "BUY", 70_000).status, "끝 = 현재 + 1초: 제한")
    }

    @Test
    fun `B2 판매 입찰 - 판매 제한 끝 = 현재면 판매 입찰·즉시 판매 403, 구매는 허용, 끝이 1초 전이면 허용`() {
        val now = Clock.now()
        val size = D1Fx.createProductSize()
        val edge = D1Fx.createUser(saleUntil = now)
        val r1 = D1Fx.bid(edge, size, "SELL", 120_000)
        assertEquals(403, r1.status, "끝 = 현재: $r1")
        assertEquals("SALE_RESTRICTED", r1.json["code"].asText(), "$r1")
        val r2 = D1Fx.instant(edge, size, "SELL")
        assertEquals(403, r2.status, "즉시 판매도 403(상대 없음보다 먼저): $r2")
        assertEquals("SALE_RESTRICTED", r2.json["code"].asText(), "$r2")
        D1Fx.activeBid(edge, size, "BUY", 80_000) // 구매 제한 없음 → 구매 허용

        val released = D1Fx.createUser(saleUntil = now.minus(sec))
        D1Fx.activeBid(released, size, "SELL", 130_000)
        val later = D1Fx.createUser(saleUntil = now.plus(sec))
        assertEquals(403, D1Fx.bid(later, size, "SELL", 140_000).status, "끝 = 현재 + 1초: 제한")
    }

    @Test
    fun `G1 판매자 등급 - 기준 금액 MASTER여도 판매 제한 끝 = 현재면 restricted·STANDARD, 끝이 1초 전이면 제한 아님·MASTER (A2 §5_1 판매 제한 이전)`() {
        val now = Clock.now()
        val restricted = D1Fx.createUser(saleUntil = now.plus(sec))
        val edge = D1Fx.createUser(saleUntil = now)
        val released = D1Fx.createUser(saleUntil = now.minus(sec))
        for (u in listOf(restricted, edge, released)) D1Fx.addSettlement(u, 8_000_000)

        D1Fx.batch("seller-grade-recalc")
        assertEquals(Triple(true, "STANDARD", 8_000_000L), D1Fx.latestGrade(restricted), "끝 = 현재 + 1초: 제한(basis는 그대로)")
        assertEquals(Triple(true, "STANDARD", 8_000_000L), D1Fx.latestGrade(edge), "끝 = 현재: 제한(basis는 그대로)")
        assertEquals(Triple(false, "MASTER", 8_000_000L), D1Fx.latestGrade(released), "끝 = 현재 − 1초: 제한이 풀려 기준 금액대로 MASTER")
    }

    @Test
    fun `N1 구매 제한 알림 - 끝 = 현재인 사용자도 대상(키의 E = 끝 시각 epoch 초), 끝이 1초 전이면 대상 아님 (A4 §7_1 경계 이전)`() {
        val now = Clock.now()
        val edge = D1Fx.createUser(purchaseUntil = now)
        val later = D1Fx.createUser(purchaseUntil = now.plus(sec))
        val released = D1Fx.createUser(purchaseUntil = now.minus(sec))
        // 경계와 무관한 A4 단언: 소수 초 끝 시각 → 키의 E는 epoch 초 내림, 알림 필드(받는 사람·kind·대상·created_at = 배치의 현재·read_at 없음)
        val fracP = now.plus(Duration.ofDays(1)).plusMillis(700)
        val fracS = now.plus(Duration.ofDays(2)).plusMillis(300)
        val frac = D1Fx.createUser(purchaseUntil = fracP, saleUntil = fracS)

        D1Fx.batch("deadline-reminder")
        assertEquals(listOf("PURCHASE_RESTRICTED:$edge:${now.epochSecond}"), D1Fx.restrictionNotifs(edge, "PURCHASE_RESTRICTED"), "끝 = 현재: 대상")
        assertEquals(listOf<Any?>(edge, "PURCHASE_RESTRICTED", edge, now, null), D1Fx.notif("PURCHASE_RESTRICTED:$edge:${now.epochSecond}"))
        val kP = "PURCHASE_RESTRICTED:$frac:${Math.floorDiv(fracP.toEpochMilli(), 1000L)}"
        val kS = "SALE_RESTRICTED:$frac:${Math.floorDiv(fracS.toEpochMilli(), 1000L)}"
        assertEquals(listOf(kP), D1Fx.restrictionNotifs(frac, "PURCHASE_RESTRICTED"), "소수 초 끝(+700ms): E 내림")
        assertEquals(listOf(kS), D1Fx.restrictionNotifs(frac, "SALE_RESTRICTED"), "소수 초 끝(+300ms): E 내림")
        assertEquals(listOf<Any?>(frac, "PURCHASE_RESTRICTED", frac, now, null), D1Fx.notif(kP))
        assertEquals(listOf<Any?>(frac, "SALE_RESTRICTED", frac, now, null), D1Fx.notif(kS))
        assertEquals(listOf("PURCHASE_RESTRICTED:$later:${now.plus(sec).epochSecond}"), D1Fx.restrictionNotifs(later, "PURCHASE_RESTRICTED"))
        assertTrue(D1Fx.restrictionNotifs(released, "PURCHASE_RESTRICTED").isEmpty(), "끝 = 현재 − 1초: 대상 아님")
        for (u in listOf(edge, later, released)) assertTrue(D1Fx.restrictionNotifs(u, "SALE_RESTRICTED").isEmpty(), "판매 제한 없음: $u")

        D1Fx.batch("deadline-reminder")
        assertEquals(1, D1Fx.restrictionNotifs(edge, "PURCHASE_RESTRICTED").size, "다시 돌려도 같은 키는 한 번")

        // 제한이 연장되면(끝 시각이 바뀌면) 새 키로 1행, 앞 구간 알림은 그대로
        val extended = now.plus(Duration.ofDays(5))
        D1Fx.setPurchaseUntil(edge, extended)
        D1Fx.advance(sec)
        D1Fx.batch("deadline-reminder")
        assertEquals(
            listOf("PURCHASE_RESTRICTED:$edge:${now.epochSecond}", "PURCHASE_RESTRICTED:$edge:${extended.epochSecond}"),
            D1Fx.restrictionNotifs(edge, "PURCHASE_RESTRICTED"),
        )
        assertEquals(extended, D1Fx.purchaseUntil(edge), "제한은 읽기만 한다")
        assertEquals(fracP to fracS, D1Fx.purchaseUntil(frac) to D1Fx.saleUntil(frac), "제한은 읽기만 한다(소수 초 그대로)")
        assertEquals(listOf(kP) to listOf(kS), D1Fx.restrictionNotifs(frac, "PURCHASE_RESTRICTED") to D1Fx.restrictionNotifs(frac, "SALE_RESTRICTED"), "같은 구간은 다시 만들지 않는다")
    }

    @Test
    fun `N2 판매 제한 알림 - 끝 = 현재인 사용자도 대상(키의 E = 끝 시각 epoch 초), 끝이 1초 전이면 대상 아님 (A4 §7_1 경계 이전)`() {
        val now = Clock.now()
        val edge = D1Fx.createUser(saleUntil = now)
        val later = D1Fx.createUser(saleUntil = now.plus(sec))
        val released = D1Fx.createUser(saleUntil = now.minus(sec))

        D1Fx.batch("deadline-reminder")
        assertEquals(listOf("SALE_RESTRICTED:$edge:${now.epochSecond}"), D1Fx.restrictionNotifs(edge, "SALE_RESTRICTED"), "끝 = 현재: 대상")
        assertEquals(listOf<Any?>(edge, "SALE_RESTRICTED", edge, now, null), D1Fx.notif("SALE_RESTRICTED:$edge:${now.epochSecond}"))
        assertEquals(listOf("SALE_RESTRICTED:$later:${now.plus(sec).epochSecond}"), D1Fx.restrictionNotifs(later, "SALE_RESTRICTED"))
        assertTrue(D1Fx.restrictionNotifs(released, "SALE_RESTRICTED").isEmpty(), "끝 = 현재 − 1초: 대상 아님")
    }

    @Test
    fun `P1 가격 알림 발동 - 주인의 구매 제한 끝 = 현재면 맞는 입찰이 있어도 ACTIVE, 1초 지나면 TRIGGERED (A5 §8_3의 2 이전)`() {
        val size = D1Fx.createProductSize()
        val owner = D1Fx.createUser()
        val alertId = D1Fx.addAlert(owner, size, 100_000)
        D1Fx.activeBid(D1Fx.createUser(), size, "SELL", 100_000)
        val now = D1Fx.advance(sec)
        D1Fx.setPurchaseUntil(owner, now.plus(sec))

        D1Fx.batch("price-alert-scan")
        assertEquals("ACTIVE", D1Fx.alertStatus(alertId), "끝 = 현재 + 1초: 제한")
        D1Fx.advance(sec)
        D1Fx.batch("price-alert-scan")
        assertEquals("ACTIVE", D1Fx.alertStatus(alertId), "끝 = 현재: 아직 제한")
        D1Fx.advance(sec)
        D1Fx.batch("price-alert-scan")
        assertEquals("TRIGGERED", D1Fx.alertStatus(alertId), "끝 = 현재 − 1초: 발동")
        assertEquals(now.plus(sec), D1Fx.purchaseUntil(owner), "제한은 읽기만 한다")
    }

    @Test
    fun `P2 가격 알림 재무장 - 주인의 구매 제한 끝 = 현재면 건너뛰고, 1초 지나면 재무장 (A14 §20_3_3 구매 제한 이전)`() {
        val size = D1Fx.createProductSize()
        val owner = D1Fx.createUser()
        D1Fx.addWish(owner, size)
        val alertId = D1Fx.addAlert(owner, size, 100_000)
        val seller = D1Fx.createUser()
        val bidId = D1Fx.activeBid(seller, size, "SELL", 100_000)
        D1Fx.advance(sec)
        D1Fx.batch("price-alert-scan")
        assertEquals("TRIGGERED", D1Fx.alertStatus(alertId), "준비 발동")
        D1Fx.ok("입찰 취소", support.Api.delete("/bids/$bidId", seller))
        val now = D1Fx.advance(Duration.ofHours(1))
        D1Fx.setPurchaseUntil(owner, now.plus(sec))

        D1Fx.batch("price-alert-rearm")
        assertEquals(1, D1Fx.alertsOf(owner, size).size, "끝 = 현재 + 1초: 제한 → 새 알림 없음")
        D1Fx.advance(sec)
        D1Fx.batch("price-alert-rearm")
        assertEquals(1, D1Fx.alertsOf(owner, size).size, "끝 = 현재: 아직 제한 → 새 알림 없음")
        D1Fx.advance(sec)
        D1Fx.batch("price-alert-rearm")
        val rows = D1Fx.alertsOf(owner, size)
        assertEquals(2, rows.size, "끝 = 현재 − 1초: 재무장: $rows")
        assertEquals("ACTIVE", rows.last().second)
        assertEquals(now.plus(sec), D1Fx.purchaseUntil(owner), "제한은 읽기만 한다")
    }

    @Test
    fun `Q1 관리자 목록 purchase - 끝 ≥ 현재인 사용자(끝 = 현재 포함), 1초 전은 제외 (A9 §11_3 이전)`() {
        val now = Clock.now()
        val before = D1Fx.maxUserId()
        val day = D1Fx.createUser(purchaseUntil = now.plus(Duration.ofDays(1)))
        val edge = D1Fx.createUser(purchaseUntil = now)
        D1Fx.createUser(purchaseUntil = now.minus(sec))
        D1Fx.createUser(saleUntil = now)
        assertEquals(listOf(day, edge), D1Fx.restrictedIds("purchase", before))
    }

    @Test
    fun `Q2 관리자 목록 sale - 끝 ≥ 현재인 사용자(끝 = 현재 포함), 1초 전은 제외 (A9 §11_3 이전)`() {
        val now = Clock.now()
        val before = D1Fx.maxUserId()
        val plusSec = D1Fx.createUser(saleUntil = now.plus(sec))
        D1Fx.createUser(saleUntil = now.minus(sec))
        val edge = D1Fx.createUser(saleUntil = now)
        D1Fx.createUser(purchaseUntil = now)
        assertEquals(listOf(plusSec, edge), D1Fx.restrictedIds("sale", before))

        // 항목 모양·페이지·type 오류(경계와 무관한 A9 단언도 함께 옮긴다)
        val r = D1Fx.ok("목록", support.Api.get("/admin/users/restricted?type=sale&after=$before"))
        val first = r.json["items"][0]
        assertEquals(plusSec, first["userId"].asLong())
        assertTrue(first["email"].asText().endsWith("@d1.test"), "email: $first")
        assertEquals(now.plus(sec), java.time.OffsetDateTime.parse(first["restrictedUntil"].asText()).toInstant(), "restrictedUntil: $first")
        val page = D1Fx.ok("페이지", support.Api.get("/admin/users/restricted?type=sale&after=$before&limit=1"))
        assertEquals(listOf(plusSec), page.json["items"].map { it["userId"].asLong() })
        assertEquals(plusSec, page.json["nextAfter"].asLong(), "nextAfter: $page")
        support.Api.get("/admin/users/restricted").let { assertEquals(400 to "INVALID_REQUEST", it.status to it.json["code"].asText(), "type 없음: $it") }
        support.Api.get("/admin/users/restricted?type=both").let { assertEquals(400 to "INVALID_REQUEST", it.status to it.json["code"].asText(), "알 수 없는 type: $it") }
    }

    @Test
    fun `R1 구매 경고 - 제한을 건 경고는 다음 계산에서 빠지고 다시 센다 (BatchAndClockTest 이전, 제한이 풀린 끝 + 1초에 준비)`() {
        val buyer = D1Fx.createUser()
        val t1 = D1Fx.expireAll(D1Fx.unpaidTrades(buyer, 3))
        val firstUntil = t1.plus(Duration.ofDays(7))
        assertEquals(firstUntil, D1Fx.purchaseUntil(buyer))

        D1Fx.moveTo(firstUntil.plus(sec))
        val more = D1Fx.unpaidTrades(buyer, 3)
        val t2 = D1Fx.moveTo(D1Fx.paymentDueAt(more[0]))
        D1Fx.batch("payment-due")
        assertTrue(more.all { D1Fx.tradeStatus(it) == "PAYMENT_EXPIRED" })
        assertEquals(6, D1Fx.warningCount(buyer))
        assertEquals(t2.plus(Duration.ofDays(7)), D1Fx.purchaseUntil(buyer))
    }

    @Test
    fun `R2 구매 경고 - 제한 뒤 경고 2건은 제한을 다시 걸지 않는다(제한 전 경고는 세지 않음) (BatchAndClockTest 이전, 끝 + 1초에 준비)`() {
        val buyer = D1Fx.createUser()
        val t1 = D1Fx.expireAll(D1Fx.unpaidTrades(buyer, 3))
        val until = t1.plus(Duration.ofDays(7))
        assertEquals(until, D1Fx.purchaseUntil(buyer))
        D1Fx.moveTo(until.plus(sec))
        D1Fx.expireAll(D1Fx.unpaidTrades(buyer, 2))
        assertEquals(5, D1Fx.warningCount(buyer))
        assertEquals(until, D1Fx.purchaseUntil(buyer), "마지막 제한 뒤 경고 2건 → 그대로")
    }

    @Test
    fun `R3 판매 패널티 - 제한을 건 패널티(제한 시각과 같은 시각)는 다시 세지 않는다, 뒤 2건은 그대로·3건째에 다시 제한 (PenaltyTest 방식 — 판매 입찰을 미리 걸어 둠)`() {
        val seller = D1Fx.createUser()
        val sizes = D1Fx.sellBids(seller, 6)
        val t1 = D1Fx.faultAll(sizes.take(3).map { D1Fx.buyAndPay(it) })
        val until = t1.plus(Duration.ofDays(30))
        assertEquals(3, D1Fx.penaltyCount(seller))
        assertEquals(until, D1Fx.saleUntil(seller), "준비: 패널티 3건 → 현재 + 30일")

        // 마지막 제한을 건 시각 = until − 30일 = t1 = 앞 3건의 created_at → 그 3건은 다시 세지 않는다
        D1Fx.faultAll(listOf(D1Fx.buyAndPay(sizes[3]), D1Fx.buyAndPay(sizes[4])))
        assertEquals(5, D1Fx.penaltyCount(seller))
        assertEquals(until, D1Fx.saleUntil(seller), "마지막 제한 뒤 패널티 2건 → 그대로")

        val t3 = D1Fx.faultAll(listOf(D1Fx.buyAndPay(sizes[5])))
        assertEquals(6, D1Fx.penaltyCount(seller))
        assertEquals(t3.plus(Duration.ofDays(30)), D1Fx.saleUntil(seller), "제한 뒤 3건째 → 다시 제한")
    }
}
