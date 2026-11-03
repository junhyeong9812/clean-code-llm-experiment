package hidden

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 숨은 테스트: 결제에 쓰는 포인트는 만료가 이른 적립분부터(만료가 같으면 id 순) 빠지고(ADR-0003 §5.2),
 * 그래서 먼저 끝나는 적립분이 point-expiry에 남지 않는다. 결제 취소의 복구는 그대로 만료가 늦은 적립분부터(§7.3).
 * 테스트끼리 같은 DB·시계를 쓴다: 사용자·사이즈는 테스트마다 새로 만들고, 시각은 그 테스트 시작 시점의 현재에서 잰다(시계는 앞으로만).
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class D2HiddenTest {
    private val day = Duration.ofDays(1)

    @Test
    fun `A 배분 - 만료가 이른 적립분부터 빠진다(id가 뒤여도)`() {
        val now = D2Fx.now()
        val buyer = D2Fx.newUser()
        val late = D2Fx.bundle(buyer, 1_000, now.minus(day))                  // id 앞, 만료 늦음
        val early = D2Fx.bundle(buyer, 600, now.minus(day.multipliedBy(2)))   // id 뒤, 만료 이름
        assertTrue(early.id > late.id && early.expiresAt.isBefore(late.expiresAt), "준비")

        val order = D2Fx.pay(buyer, 1, 1_000).json["orderId"].asLong()
        assertEquals(mapOf(early.id to 600L, late.id to 400L), D2Fx.useAllocations(order), "결제 1,000P의 배분")
        assertEquals(0L, D2Fx.remaining(early.id), "만료 이른 적립분 remaining")
        assertEquals(600L, D2Fx.remaining(late.id), "만료 늦은 적립분 remaining")
    }

    @Test
    fun `B 소멸 - 먼저 끝나는 적립분을 다 쓴 뒤 그 만료가 지나 point-expiry를 돌려도 소멸 0, 사용 가능액 유지`() {
        val now = D2Fx.now()
        val buyer = D2Fx.newUser()
        val early = D2Fx.bundle(buyer, 1_000, now.minus(day.multipliedBy(2)))
        val late = D2Fx.bundle(buyer, 2_000, now.minus(day))
        D2Fx.pay(buyer, 1, 1_000)

        D2Fx.moveTo(early.expiresAt.plus(Duration.ofHours(1)))   // 이른 쪽 만료 1시간 뒤(늦은 쪽은 아직 23시간 남음)
        D2Fx.runBatch("point-expiry")
        assertEquals(0L, D2Fx.expiredSum(buyer), "point-expiry가 소멸한 포인트")
        assertEquals(2_000L to 2_000L, D2Fx.points(buyer), "point-expiry 뒤 (usable, available)")
        assertEquals(0L, D2Fx.remaining(early.id), "만료 이른 적립분 remaining")
        assertEquals(2_000L, D2Fx.remaining(late.id), "만료 늦은 적립분 remaining")
    }

    @Test
    fun `C 배분 - 만료가 같으면 id 순`() {
        val at = D2Fx.now().minus(day)
        val buyer = D2Fx.newUser()
        val first = D2Fx.bundle(buyer, 600, at)
        val second = D2Fx.bundle(buyer, 1_000, at, earnShift = Duration.ofSeconds(1)) // created_at 1초 뒤 — id 순 = created_at 순
        assertEquals(first.expiresAt, second.expiresAt, "준비")
        assertEquals(D2Fx.createdAt(first.id).plusSeconds(1), D2Fx.createdAt(second.id), "준비: created_at 순 = id 순")

        val order = D2Fx.pay(buyer, 1, 1_000).json["orderId"].asLong()
        assertEquals(mapOf(first.id to 600L, second.id to 400L), D2Fx.useAllocations(order), "결제 1,000P의 배분")
    }

    @Test
    fun `D 복구 - 결제 취소로 돌아오는 포인트는 만료가 늦은 적립분부터`() {
        val now = D2Fx.now()
        val buyer = D2Fx.newUser()
        val early = D2Fx.bundle(buyer, 1_000, now.minus(day.multipliedBy(2)))
        val late = D2Fx.bundle(buyer, 2_000, now.minus(day))
        val pay = D2Fx.pay(buyer, 2, 2_000)
        val order = pay.json["orderId"].asLong()
        assertEquals(mapOf(early.id to 1_000L, late.id to 1_000L), D2Fx.useAllocations(order), "결제 2,000P의 배분")

        // 두 번째 항목(배송비가 붙지 않은 쪽)만 구매자 취소 → 그 항목 포인트 몫만 복구
        val item = pay.json["items"].maxOf { it["orderItemId"].asLong() }
        D2Fx.cancel(item, buyer)
        val restore = D2Fx.restoreOf(item)
        assertTrue(restore.second in 1..1_000, "준비: 복구 양 ${restore.second}은 늦은 적립분이 가져간 1,000 이하")
        assertEquals(mapOf(late.id to restore.second), D2Fx.allocations(restore.first), "복구 배분")
        assertEquals(0L, D2Fx.remaining(early.id), "만료 이른 적립분 remaining")
        assertEquals(1_000L + restore.second, D2Fx.remaining(late.id), "만료 늦은 적립분 remaining")
    }
}
