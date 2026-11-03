package hidden

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3 숨은 테스트: 발송 기한 = 결제 + 3일(그 정각부터 지난 것)을 쓰는 다섯 곳이 모두 같은 기한을 쓴다.
 * 발송 등록(현재 < 기한) · shipment-deadline(기한 ≤ 현재) · 마감 임박 알림 SHIPMENT_DUE_SOON(기한 − 1일 ≤ 현재 < 기한)
 * · 지연 기록 SHIPMENT_OVERDUE(기한 ≤ 현재).
 * 테스트끼리 같은 DB·시계를 쓴다: 사용자·사이즈는 테스트마다 새로 만들고, 시각은 그 테스트에서 결제한 시각에서 잰다(시계는 앞으로만).
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class D3HiddenTest {
    private val day = Duration.ofDays(1)
    private fun days(n: Long) = Duration.ofDays(n)

    @Test
    fun `A 발송 등록 - 결제 + 3일 1초 전은 된다, 정각은 409 DEADLINE_PASSED`() {
        val x = D3Fx.paid()
        val y = D3Fx.paid()
        assertTrue(!y.paidAt.isBefore(x.paidAt), "준비")

        D3Fx.moveTo(x.paidAt.plus(days(3)).minusSeconds(1))
        val ok = D3Fx.ship(x)
        assertEquals(200, ok.status, "결제 + 3일 1초 전 발송 등록: $ok")
        assertEquals("SHIPPED_TO_CENTER", D3Fx.status(x.item))

        D3Fx.moveTo(y.paidAt.plus(days(3)))
        val late = D3Fx.ship(y)
        assertEquals(409, late.status, "결제 + 3일 정각 발송 등록: $late")
        assertEquals("DEADLINE_PASSED", late.json["code"].asText(), "결제 + 3일 정각 발송 등록: $late")
        assertEquals("PAID", D3Fx.status(y.item))
    }

    @Test
    fun `B shipment-deadline - 결제 + 3일 1초 전은 그대로, 정각에 판매자 귀책 취소`() {
        val x = D3Fx.paid()

        D3Fx.moveTo(x.paidAt.plus(days(3)).minusSeconds(1))
        D3Fx.runBatch("shipment-deadline")
        assertEquals("PAID", D3Fx.status(x.item), "결제 + 3일 1초 전 shipment-deadline 뒤 상태")

        D3Fx.moveTo(x.paidAt.plus(days(3)))
        D3Fx.runBatch("shipment-deadline")
        assertEquals("CANCELLED_SELLER_FAULT", D3Fx.status(x.item), "결제 + 3일 정각 shipment-deadline 뒤 상태")
    }

    @Test
    fun `C 마감 임박 알림 - 결제 + 2일 1초 전은 없고, 결제 + 2일 정각에 판매자에게 1건`() {
        val x = D3Fx.paid()

        D3Fx.moveTo(x.paidAt.plus(days(2)).minusSeconds(1))
        D3Fx.runBatch("deadline-reminder")
        assertEquals(0L, D3Fx.dueSoon(x), "결제 + 2일 1초 전 마감 임박 알림 수")

        D3Fx.moveTo(x.paidAt.plus(days(2)))
        D3Fx.runBatch("deadline-reminder")
        assertEquals(1L, D3Fx.dueSoon(x), "결제 + 2일 정각 마감 임박 알림 수")
    }

    @Test
    fun `D 마감 임박 알림 - 결제 + 3일 정각은 없고, 결제 + 3일 1초 전은 1건`() {
        val y = D3Fx.paid()
        D3Fx.advance(1)
        val z = D3Fx.paid()
        assertEquals(y.paidAt.plusSeconds(1), z.paidAt, "준비: z 결제 = y 결제 + 1초")

        D3Fx.moveTo(y.paidAt.plus(days(3)))   // y: 결제 + 3일 정각, z: 결제 + 3일 1초 전
        D3Fx.runBatch("deadline-reminder")
        assertEquals(0L, D3Fx.dueSoon(y), "결제 + 3일 정각 마감 임박 알림 수")
        assertEquals(1L, D3Fx.dueSoon(z), "결제 + 3일 1초 전 마감 임박 알림 수")
    }

    @Test
    fun `E 지연 기록 - 결제 + 3일 정각에 1행, 1초 전은 없음, 항목 PAID 그대로`() {
        val x = D3Fx.paid()
        D3Fx.advance(1)
        val y = D3Fx.paid()
        assertEquals(x.paidAt.plusSeconds(1), y.paidAt, "준비: y 결제 = x 결제 + 1초")

        D3Fx.moveTo(x.paidAt.plus(days(3)))   // x: 결제 + 3일 정각, y: 결제 + 3일 1초 전
        D3Fx.runBatch("delivery-delay-scan")
        assertEquals(1L, D3Fx.overdue(x), "결제 + 3일 정각 지연 기록 수")
        assertEquals(0L, D3Fx.overdue(y), "결제 + 3일 1초 전 지연 기록 수")
        assertEquals("PAID", D3Fx.status(x.item), "지연 기록은 항목 상태를 바꾸지 않는다")

        D3Fx.moveTo(x.paidAt.plus(days(3)).plus(day))
        D3Fx.runBatch("delivery-delay-scan")
        assertEquals(1L, D3Fx.overdue(x), "재실행 뒤 지연 기록 수(중복 없음)")
    }
}
