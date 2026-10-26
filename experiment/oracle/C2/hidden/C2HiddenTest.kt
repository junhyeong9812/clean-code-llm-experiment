package hidden

import support.Clock
import support.Db
import java.time.Duration
import kotlin.test.Test
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C2 숨은 테스트: GET /me/penalties 계약 — 본인 패널티만, 항목·금액·생성 시각·정산 반영 여부, 생성 시각 내림차순(같으면 id 내림차순),
 * 빈 목록, 401, 주간 정산 전후의 반영 여부, 시드 패널티와 섞여도 DB와 같은 결과.
 * 테스트끼리 같은 DB·시계를 쓴다: 사용자는 테스트마다 새로 만들고 시계는 앞으로만 상대 이동한다. 주간 정산은 한 테스트만 돌린다.
 */
// 실행 순서 고정(이름순): '시드'(시드 판매자의 미정산 패널티 단언)가 '정산 반영 여부'(주간 정산 배치)보다 먼저 돌아야 한다
@TestMethodOrder(MethodOrderer.MethodName::class)
class C2HiddenTest {

    @Test
    fun `401 - 헤더 없음, 숫자 아님, 없는 사용자`() {
        for (user in listOf(null, "abc", "999999999")) {
            val r = Fx.penalties(user)
            assertEquals(401, r.status, "X-User-Id=$user: $r")
            assertEquals("UNKNOWN_USER", r.json["code"]?.asText(), "X-User-Id=$user: $r")
            assertTrue(r.json["message"]?.isTextual == true, "오류 본문 message: $r")
        }
    }

    @Test
    fun `빈 목록 - 패널티가 없는 사용자는 200 빈 배열`() {
        assertEquals(emptyList(), Pen.parse(Fx.penalties(Fx.createUser())))
    }

    @Test
    fun `목록 - 항목·금액·생성 시각, 생성 시각 내림차순이고 같은 시각이면 id 내림차순, 구매자에게는 보이지 않음`() {
        val seller = Fx.createUser()
        val prices = listOf(33_333L, 50_000L, 12_345L)
        val sizes = prices.map { Fx.sellBid(seller, it) }
        val (buyerA, a) = Fx.buyAndPay(sizes[0], prices[0])
        val t0 = now()
        Fx.sellerFault(a, seller)
        val t1 = Clock.advance(Duration.ofHours(1))
        val (_, b) = Fx.buyAndPay(sizes[1], prices[1])
        val (_, c) = Fx.buyAndPay(sizes[2], prices[2])
        Fx.sellerFault(b, seller)
        Fx.sellerFault(c, seller)

        val got = Pen.parse(Fx.penalties(seller))
        assertEquals(listOf(c, b, a), got.map { it.orderItemId }, "생성 시각 내림차순, 같은 시각(b·c)이면 id 내림차순: $got")
        assertEquals(listOf(1_234L, 5_000L, 3_333L), got.map { it.amount }, "금액 = 패널티 금액: $got")
        assertEquals(listOf(t1, t1, t0), got.map { it.createdAt }, "생성 시각: $got")
        assertEquals(listOf(false, false, false), got.map { it.settled }, "정산 전: $got")
        assertEquals(listOf(c, b, a).map { Pen.rowOfItem(it).id }, got.map { it.penaltyId }, "penaltyId = 패널티 행: $got")
        assertTrue(got[0].penaltyId > got[1].penaltyId, "같은 시각이면 id 내림차순: $got")
        assertEquals(Pen.expected(Pen.rowsOfSeller(seller)), got, "DB의 그 판매자 패널티와 같다")

        assertEquals(emptyList(), Pen.parse(Fx.penalties(buyerA)), "구매자는 판매자 패널티를 보지 않는다")
    }

    @Test
    fun `분리 - 다른 판매자의 패널티와 시드 패널티는 보이지 않는다`() {
        val x = Fx.createUser()
        val y = Fx.createUser()
        val sx = Fx.sellBid(x, 40_000)
        val sy = Fx.sellBid(y, 70_000)
        val (_, ix) = Fx.buyAndPay(sx, 40_000)
        val (_, iy) = Fx.buyAndPay(sy, 70_000)
        Fx.sellerFault(ix, x)
        Fx.sellerFault(iy, y)
        val t = now()

        val gx = Pen.parse(Fx.penalties(x))
        assertEquals(listOf(ix), gx.map { it.orderItemId }, "x는 자기 패널티 1건만: $gx")
        assertEquals(4_000L, gx.single().amount)
        assertEquals(t, gx.single().createdAt)
        val gy = Pen.parse(Fx.penalties(y))
        assertEquals(listOf(iy), gy.map { it.orderItemId }, "y는 자기 패널티 1건만: $gy")
        assertEquals(7_000L, gy.single().amount)
    }

    @Test
    fun `시드 - 시드 판매자의 목록은 DB의 그 판매자 패널티 전부와 같다(정렬·금액·반영 여부)`() {
        val most = Db.long("SELECT seller_id FROM seller_penalties GROUP BY seller_id ORDER BY count(*) DESC, seller_id LIMIT 1")
        val exp = Pen.expected(Pen.rowsOfSeller(most))
        assertTrue(exp.size >= 3 && exp.any { it.settled }, "준비: 시드에 정산 반영된 패널티가 여러 건인 판매자: $exp")
        assertEquals(exp, Pen.parse(Fx.penalties(most)), "시드 판매자 $most")

        val mixed = Db.query(
            "SELECT seller_id FROM seller_penalties GROUP BY seller_id" +
                " HAVING count(settlement_id) > 0 AND count(*) > count(settlement_id) ORDER BY seller_id",
        ).map { (it["seller_id"] as Number).toLong() }
        for (s in mixed) assertEquals(Pen.expected(Pen.rowsOfSeller(s)), Pen.parse(Fx.penalties(s)), "시드 판매자 $s")
    }

    @Test
    fun `정산 반영 여부 - 주간 정산 전 false, 마감 전 패널티는 정산 뒤 true, 마감 뒤 패널티는 false`() {
        // 시드: 아직 정산되지 않은 패널티가 있는 판매자(이번 정산에서 반영된다)
        val seedSeller = Db.long(
            "SELECT min(seller_id) FROM seller_penalties WHERE settlement_id IS NULL AND seller_id IN" +
                " (SELECT seller_id FROM seller_penalties WHERE settlement_id IS NOT NULL)",
        )
        val seedBefore = Pen.parse(Fx.penalties(seedSeller))
        assertEquals(Pen.expected(Pen.rowsOfSeller(seedSeller)), seedBefore, "정산 전 시드 판매자 $seedSeller")
        assertTrue(seedBefore.any { !it.settled } && seedBefore.any { it.settled }, "준비: 반영·미반영이 섞인 시드 판매자: $seedBefore")

        val seller = Fx.createUser()
        val s1 = Fx.sellBid(seller, 60_000)
        val s2 = Fx.sellBid(seller, 20_000)
        val (_, before) = Fx.buyAndPay(s1, 60_000)
        Fx.sellerFault(before, seller)
        val cutoff = Fx.nextCutoff(now())
        Clock.moveTo(cutoff.plus(Duration.ofHours(1)))
        val (_, after) = Fx.buyAndPay(s2, 20_000)
        Fx.sellerFault(after, seller)

        val pre = Pen.parse(Fx.penalties(seller))
        assertEquals(listOf(after to false, before to false), pre.map { it.orderItemId to it.settled }, "정산 전: $pre")

        Fx.weeklySettlement()

        val post = Pen.parse(Fx.penalties(seller))
        assertEquals(listOf(after to false, before to true), post.map { it.orderItemId to it.settled },
            "마감 전 패널티만 정산에 반영: $post")
        assertEquals(listOf(2_000L, 6_000L), post.map { it.amount })
        assertEquals(pre.map { it.penaltyId to it.createdAt }, post.map { it.penaltyId to it.createdAt }, "정산은 다른 필드를 바꾸지 않는다")
        assertEquals(Pen.expected(Pen.rowsOfSeller(seller)), post, "DB와 같다")

        val seedAfter = Pen.parse(Fx.penalties(seedSeller))
        assertEquals(Pen.expected(Pen.rowsOfSeller(seedSeller)), seedAfter, "정산 뒤 시드 판매자 $seedSeller")
        assertTrue(seedAfter.all { it.settled }, "시드의 미정산 패널티도 이번 정산에 반영: $seedAfter")
    }
}
