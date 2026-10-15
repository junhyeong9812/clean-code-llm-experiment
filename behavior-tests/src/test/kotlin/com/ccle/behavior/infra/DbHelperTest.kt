package com.ccle.behavior.infra

import com.ccle.behavior.support.Db
import com.ccle.behavior.support.InfraTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB 도우미 자체 테스트: 만든 행이 스키마 제약(CHECK·FK·UNIQUE)을 통과하고 verify 규칙에 맞는지. */
@InfraTest
class DbHelperTest {
    private val now = Db.SEED_NOW.toInstant()

    @Test
    fun `사용자 - 등급·제한 시각 지정, 이메일 겹치지 않음`() {
        val until = now.plus(Duration.ofDays(7))
        val a = Db.createUser(grade = "GOLD", purchaseRestrictedUntil = until)
        val b = Db.createUser(saleRestrictedUntil = until)
        assertTrue(a != b)
        val ua = Db.user(a)
        assertEquals("GOLD", ua.grade)
        assertEquals(until, ua.purchaseRestrictedUntil)
        assertNull(ua.saleRestrictedUntil)
        val ub = Db.user(b)
        assertEquals("BRONZE", ub.grade)
        assertEquals(until, ub.saleRestrictedUntil)
        Db.setRestrictions(b, null, null)
        assertNull(Db.user(b).saleRestrictedUntil)
    }

    @Test
    fun `상품 사이즈 - 기존 카테고리, 매번 새 사이즈`() {
        val s1 = Db.createProductSize()
        val s2 = Db.createProductSize("TECH")
        assertTrue(s1 != s2)
        assertEquals(1L, Db.long("SELECT count(*) FROM product_sizes ps JOIN products p ON p.id = ps.product_id JOIN categories c ON c.id = p.category_id WHERE ps.id = ? AND c.name = 'TECH'", s2))
        assertTrue(Db.bidsOfSize(s1).isEmpty())
    }

    @Test
    fun `포인트 묶음 - EARN AVAILABLE, 잔액 캐시, 출처 거래`() {
        val user = Db.createUser()
        val c1 = now.minus(Duration.ofDays(1))
        val c2 = now.minus(Duration.ofDays(2))
        val b1 = Db.createPointBundle(user, 1000, c1)
        val b2 = Db.createPointBundle(user, 500, c2)
        val e1 = Db.ledgerEntry(b1.earnId)
        assertEquals("EARN", e1.kind)
        assertEquals("AVAILABLE", e1.status)
        assertEquals(1000L, e1.amount)
        assertEquals(1000L, e1.remaining)
        assertEquals(c1.plus(Duration.ofDays(365)), e1.expiresAt)
        assertEquals(c2.plus(Duration.ofDays(365)), Db.ledgerEntry(b2.earnId).expiresAt)
        val bal = Db.balance(user)!!
        assertEquals(1500L, bal.available)
        assertEquals(0L, bal.pending)
        // verify point_balance_cache: available = AVAILABLE remaining 합
        assertEquals(bal.available, Db.long("SELECT coalesce(sum(remaining),0) FROM point_ledger WHERE user_id = ? AND kind='EARN' AND status='AVAILABLE'", user))
        // verify earn_amount_rate: 적립 = floor(floor(카드 몫 × 체결가 / b) / 100)
        val base = Db.long(
            "SELECT (oi.card_share * oi.price) / (oi.price + oi.buyer_fee + oi.shipping_share) FROM order_items oi JOIN point_ledger l ON l.order_item_id = oi.id WHERE l.id = ?",
            b2.earnId,
        )
        assertEquals(500L, base / 100)
        // 출처 거래: 체결 PAID, 주문 PAID, 항목 CONFIRMED, PG 승인 1건
        assertEquals(2L, Db.long("SELECT count(*) FROM trades WHERE buyer_id = ? AND status = 'PAID'", user))
        assertEquals(2L, Db.long("SELECT count(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.buyer_id = ? AND oi.status = 'CONFIRMED'", user))
        assertEquals(2L, Db.long("SELECT count(*) FROM pg_transactions p JOIN orders o ON o.id = p.order_id WHERE o.buyer_id = ? AND p.kind='APPROVE' AND p.status='APPROVED' AND p.amount = o.card_amount", user))
    }

    @Test
    fun `포인트 묶음 - 마지막 정산 마감 전 확정 시각은 거부(verify settlement_inv8)`() {
        val cutoff = Db.lastSettlementCutoff()
        assertNotNull(cutoff, "시드에 주간 정산이 있어야 한다(이 검사의 전제)")
        val user = Db.createUser()
        val err = runCatching { Db.createPointBundle(user, 1000, cutoff.minusSeconds(1)) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "거부되어야 한다: $err")
        assertNull(Db.balance(user))
    }

    @Test
    fun `조회 함수 - 없는 id`() {
        val missing = Db.missingId("bids")
        assertEquals(0L, Db.long("SELECT count(*) FROM bids WHERE id = ?", missing))
    }
}
