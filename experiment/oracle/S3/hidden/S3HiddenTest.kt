package hidden

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import support.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S3 숨은 테스트: 판매 제한 중(sale_restricted_until > 현재)인 판매자의 판매 입찰은 시세 최저 판매가·위시리스트 최저 판매가·
 * 가격 알림 발동·새 구매 입찰/즉시 구매의 상대에서 빠지고, 제한이 끝나면(현재 = 제한 끝 포함) 다시 포함된다. 입찰 자체·구매 쪽은 그대로.
 * 기존 계약 이전 2개: contract RestrictionAndConcurrencyTest "제한은 새 입찰에만 - 이미 걸린 입찰의 체결과 결제…"(판매자 기대값만 새 규칙),
 * contract2 PenaltyTest "제한을 건 패널티 뒤로 다시 센다"(준비를 첫 제한 전에 끝낸다).
 * 테스트끼리 같은 DB·시계를 쓴다: 사용자·사이즈는 테스트마다 새로 만들고 시계는 앞으로만 상대 이동한다.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class S3HiddenTest {
    private val day = Duration.ofDays(1)

    @Test
    fun `B1 시세 - 제한 중 판매자의 판매 입찰은 최저 판매가에서 빠지고 최고 구매가는 그대로, 제한 끝 시각에 다시 포함`() {
        val size = S3Fx.createProductSize()
        val restricted = S3Fx.createUser()
        val other = S3Fx.createUser()
        val buyer = S3Fx.createUser()
        val hidden = S3Fx.activeBid(restricted, size, "SELL", 90_000)
        S3Fx.activeBid(other, size, "SELL", 100_000)
        val buy = S3Fx.activeBid(buyer, size, "BUY", 80_000)
        val mark = S3Fx.mark()
        val until = Clock.now().plus(Duration.ofDays(10))
        S3Fx.setSaleRestrictedUntil(restricted, until)
        S3Fx.setSaleRestrictedUntil(buyer, until) // 판매 제한 중인 사용자의 구매 입찰은 그대로 센다

        assertEquals(80_000L to 100_000L, S3Fx.book(size), "제한 중: 최고 구매가 그대로, 최저 판매가는 다른 판매자의 것")
        S3Fx.moveTo(until.minusSeconds(1))
        assertEquals(80_000L to 100_000L, S3Fx.book(size), "제한 끝 1초 전")
        S3Fx.moveTo(until)
        assertEquals(80_000L to 90_000L, S3Fx.book(size), "제한 끝 = 현재: 다시 포함")
        S3Fx.assertUntouched(hidden, mark, "시세")
        S3Fx.assertUntouched(buy, mark, "시세")
    }

    @Test
    fun `B2 위시리스트 - 제한 중 판매자의 판매 입찰은 각 항목의 최저 판매가에서 빠지고 제한이 끝나면 다시 포함`() {
        val mixed = S3Fx.createProductSize()
        val onlyRestricted = S3Fx.createProductSize()
        val restricted = S3Fx.createUser()
        val other = S3Fx.createUser()
        val hidden1 = S3Fx.activeBid(restricted, mixed, "SELL", 90_000)
        S3Fx.activeBid(other, mixed, "SELL", 100_000)
        val hidden2 = S3Fx.activeBid(restricted, onlyRestricted, "SELL", 70_000)
        val viewer = S3Fx.createUser()
        S3Fx.addWish(viewer, mixed)
        S3Fx.addWish(viewer, onlyRestricted)
        val mark = S3Fx.mark()
        val until = Clock.now().plus(Duration.ofDays(10))
        S3Fx.setSaleRestrictedUntil(restricted, until)

        assertEquals(mapOf(mixed to 100_000L, onlyRestricted to null), S3Fx.wishlistBestSell(viewer), "제한 중")
        S3Fx.moveTo(until)
        assertEquals(mapOf(mixed to 90_000L, onlyRestricted to 70_000L), S3Fx.wishlistBestSell(viewer), "제한 끝 = 현재: 다시 포함")
        S3Fx.assertUntouched(hidden1, mark, "위시리스트")
        S3Fx.assertUntouched(hidden2, mark, "위시리스트")
    }

    @Test
    fun `B3 가격 알림 - 제한 중 판매자의 판매 입찰로는 발동하지 않고 다른 판매자의 것을 고르며, 제한이 끝나면 발동`() {
        val a = S3Fx.createProductSize()
        val b = S3Fx.createProductSize()
        val restricted = S3Fx.createUser()
        val other = S3Fx.createUser()
        val hiddenA = S3Fx.activeBid(restricted, a, "SELL", 90_000)
        S3Fx.activeBid(other, a, "SELL", 100_000)
        S3Fx.activeBid(restricted, b, "SELL", 90_000)
        val openB = S3Fx.activeBid(other, b, "SELL", 93_000)
        val owner = S3Fx.createUser()
        val alertA = S3Fx.addAlert(owner, a, 95_000)
        val alertB = S3Fx.addAlert(owner, b, 95_000)
        val mark = S3Fx.mark()
        val until = Clock.now().plus(Duration.ofDays(10))
        S3Fx.setSaleRestrictedUntil(restricted, until)

        S3Fx.scan()
        assertEquals("ACTIVE" to null, S3Fx.alert(alertA), "제한 중 판매자의 90,000만 목표가 이하 → 발동 없음")
        assertEquals("TRIGGERED" to openB, S3Fx.alert(alertB), "더 싼 제한 판매자의 입찰이 아니라 다른 판매자의 93,000")
        S3Fx.moveTo(until)
        S3Fx.scan()
        assertEquals("TRIGGERED" to hiddenA, S3Fx.alert(alertA), "제한 끝 = 현재: 다시 포함되어 발동")
        S3Fx.assertUntouched(hiddenA, mark, "가격 알림")
    }

    @Test
    fun `B4 상대 - 새 구매 입찰·즉시 구매는 제한 중 판매자의 판매 입찰을 건너뛰고, 제한이 끝나면 그것과 거래한다`() {
        val size = S3Fx.createProductSize()
        val restricted = S3Fx.createUser()
        val other = S3Fx.createUser()
        val hidden = S3Fx.activeBid(restricted, size, "SELL", 90_000)
        val open = S3Fx.activeBid(other, size, "SELL", 100_000)
        val mark = S3Fx.mark()
        val until = Clock.now().plus(Duration.ofDays(10))
        S3Fx.setSaleRestrictedUntil(restricted, until)

        val r1 = S3Fx.ok("구매 입찰", S3Fx.bid(S3Fx.createUser(), size, "BUY", 120_000, 30), 201)
        assertEquals("MATCHED", r1.json["status"].asText(), "$r1")
        assertEquals(open, S3Fx.trade(r1.json["tradeId"].asLong()).second, "다른 판매자의 100,000과 거래: $r1")
        assertEquals(100_000L, S3Fx.trade(r1.json["tradeId"].asLong()).third)

        val r2 = S3Fx.ok("교차하는 구매 입찰", S3Fx.bid(S3Fx.createUser(), size, "BUY", 95_000, 30), 201)
        assertEquals("ACTIVE", r2.json["status"].asText(), "남은 상대는 제한 중 판매자의 90,000뿐 → 거래 없음: $r2")
        val r3 = S3Fx.instant(S3Fx.createUser(), size, "BUY")
        assertEquals(409, r3.status, "즉시 구매 상대 없음: $r3")
        assertEquals("NO_COUNTER_BID", r3.json["code"].asText(), "$r3")
        S3Fx.assertUntouched(hidden, mark, "상대")

        S3Fx.moveTo(until)
        val r4 = S3Fx.ok("즉시 구매", S3Fx.instant(S3Fx.createUser(), size, "BUY"), 201)
        assertEquals("MATCHED", r4.json["status"].asText(), "$r4")
        assertEquals(hidden to 90_000L, S3Fx.trade(r4.json["tradeId"].asLong()).let { it.second to it.third }, "제한 끝 = 현재: 그 입찰과 거래")
    }

    @Test
    fun `B5 판매 쪽 그대로 - 판매 제한 중인 사용자가 건 구매 입찰은 새 판매 입찰·즉시 판매의 상대가 된다`() {
        val size = S3Fx.createProductSize()
        val user = S3Fx.createUser()
        val buy1 = S3Fx.activeBid(user, size, "BUY", 100_000)
        S3Fx.setSaleRestrictedUntil(user, Clock.now().plus(Duration.ofDays(10)))
        val r1 = S3Fx.ok("판매 입찰", S3Fx.bid(S3Fx.createUser(), size, "SELL", 90_000), 201)
        assertEquals("MATCHED", r1.json["status"].asText(), "$r1")
        assertEquals(buy1 to 100_000L, S3Fx.trade(r1.json["tradeId"].asLong()).let { it.first to it.third }, "제한 중인 사용자의 구매 입찰과 거래")

        val buy2 = S3Fx.activeBid(user, size, "BUY", 95_000) // 판매 제한 중에도 구매 입찰은 걸 수 있다
        val r2 = S3Fx.ok("즉시 판매", S3Fx.instant(S3Fx.createUser(), size, "SELL"), 201)
        assertEquals("MATCHED", r2.json["status"].asText(), "$r2")
        assertEquals(buy2, S3Fx.trade(r2.json["tradeId"].asLong()).first, "즉시 판매 상대")
    }

    @Test
    fun `C1 이미 걸린 입찰 - 제한된 구매자의 구매 입찰 체결·결제는 그대로, 제한된 판매자의 판매 입찰은 새 구매 입찰과 거래되지 않는다`() {
        // contract RestrictionAndConcurrencyTest "제한은 새 입찰에만 - 이미 걸린 입찰의 체결과 결제는 막지 않는다"를 새 규칙으로 옮긴 것
        val now = Clock.now()
        val size = S3Fx.createProductSize()
        val buyer = S3Fx.createUser()
        val seller = S3Fx.createUser()
        val buy = S3Fx.activeBid(buyer, size, "BUY", 100_000, 30)
        val sell = S3Fx.activeBid(seller, size, "SELL", 130_000, 30)
        S3Fx.setPurchaseRestrictedUntil(buyer, now.plus(Duration.ofDays(7)))
        S3Fx.setSaleRestrictedUntil(seller, now.plus(Duration.ofDays(7)))
        val mark = S3Fx.mark()

        // 제한된 구매자의 기존 구매 입찰이 체결된다
        val r = S3Fx.ok("판매 입찰", S3Fx.bid(S3Fx.createUser(), size, "SELL", 100_000, 30), 201)
        assertEquals("MATCHED", r.json["status"].asText(), "$r")
        val tradeId = r.json["tradeId"].asLong()
        assertEquals(buy, S3Fx.trade(tradeId).first)
        // 제한된 판매자의 기존 판매 입찰은 새 구매 입찰과 거래되지 않는다
        val r2 = S3Fx.ok("구매 입찰", S3Fx.bid(S3Fx.createUser(), size, "BUY", 130_000, 30), 201)
        assertEquals("ACTIVE", r2.json["status"].asText(), "$r2")
        S3Fx.assertUntouched(sell, mark, "제한된 판매자의 판매 입찰")
        // 제한된 구매자가 결제한다
        val pay = S3Fx.ok("결제", S3Fx.pay(buyer, tradeId), 201)
        assertEquals("PAID", pay.json["status"].asText(), "$pay")
    }

    @Test
    fun `C2 재계수 - 판매 제한은 제한을 건 패널티 뒤로 다시 센다(뒤 2건은 그대로, 3건째에 다시 제한)`() {
        // contract2 PenaltyTest 의 같은 계약. 추가 귀책 항목은 첫 제한 전에 결제·발송·입고까지 해 둔다
        val seller = S3Fx.createUser()
        val items = (1..6).map { S3Fx.soldItem(seller, 50_000) }
        items.forEach { S3Fx.shipAndReceive(it, seller) }

        items.take(3).forEach { S3Fx.inspectFail(it) }
        val t1 = Clock.now()
        val firstUntil = t1.plus(Duration.ofDays(30))
        assertEquals(firstUntil, S3Fx.saleRestrictedUntil(seller))

        S3Fx.advance(day)
        items.slice(3..4).forEach { S3Fx.inspectFail(it) }
        assertEquals(5, S3Fx.penaltyCount(seller))
        assertEquals(firstUntil, S3Fx.saleRestrictedUntil(seller), "마지막 제한 뒤 패널티 2건 → 그대로")

        val t3 = S3Fx.advance(day)
        S3Fx.inspectFail(items[5])
        assertEquals(6, S3Fx.penaltyCount(seller))
        assertEquals(t3.plus(Duration.ofDays(30)), S3Fx.saleRestrictedUntil(seller))
    }
}
