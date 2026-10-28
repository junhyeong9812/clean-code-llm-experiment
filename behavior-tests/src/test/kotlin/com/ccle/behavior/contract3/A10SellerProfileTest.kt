package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §12.1 A10 판매자 프로필: PUT(없으면 INSERT·있으면 UPDATE, 상점명 1~40자·소개 0~500자·휴가 쌍), GET(onVacation 계산),
 * 글자 수 규칙(§2.1 — 앞뒤 공백을 뺀 코드 포인트 수, 저장은 받은 그대로).
 */
@Contract3Test
class A10SellerProfileTest {

    private fun profileRow(user: Long): Row3 = Db3.rowBy("seller_profiles", "user_id", user) ?: error("seller_profiles user_id=$user 없음")

    /** 프로필 모양 = DB 행 + onVacation(계산 값). */
    private fun assertProfile(r: Resp, user: Long, onVacation: Boolean) {
        val row = profileRow(user)
        val j = r.json
        for (f in listOf("userId", "shopName", "intro", "vacationFrom", "vacationTo", "onVacation")) assertTrue(j.has(f), "프로필 필드 '$f' 없음: $r")
        assertEquals(user, j.longAt("userId"), "userId: $r")
        assertEquals(row.text("shop_name"), j.textAt("shopName"), "shopName: $r")
        assertEquals(row.text("intro"), j.textAt("intro"), "intro: $r")
        assertEquals(row["vacation_from"]?.toString(), j.get("vacationFrom").takeUnless { it.isNull }?.asText(), "vacationFrom: $r")
        assertEquals(row["vacation_to"]?.toString(), j.get("vacationTo").takeUnless { it.isNull }?.asText(), "vacationTo: $r")
        assertTrue(j.get("onVacation").isBoolean, "onVacation boolean: $r")
        assertEquals(onVacation, j.get("onVacation").asBoolean(), "onVacation: $r")
    }

    @Test
    fun `§12_1 PUT 첫 저장은 INSERT(created_at = updated_at = 현재), 다시 PUT은 UPDATE(created_at 유지·updated_at = 현재) - 200 프로필 모양`() {
        val user = Db.createUser()
        val t1 = Flow.now()
        val today = Kst.date(t1)
        val r1 = SC.putProfile(user, "  bt 상점  ", "  소개  ", today.toString(), today.plusDays(3).toString()).assertStatus(200)
        assertProfile(r1, user, onVacation = true)
        val row1 = profileRow(user)
        assertEquals("  bt 상점  ", row1.text("shop_name"), "§2.1 저장은 받은 그대로")
        assertEquals("  소개  ", row1.text("intro"))
        assertEquals(today, row1.date("vacation_from"))
        assertEquals(today.plusDays(3), row1.date("vacation_to"))
        assertEquals(t1, row1.instant("created_at"))
        assertEquals(t1, row1.instant("updated_at"))

        val t2 = Flow.advance(HOUR)
        val r2 = SC.putProfile(user, "새 이름", "", null, null).assertStatus(200)
        assertProfile(r2, user, onVacation = false)
        val row2 = profileRow(user)
        assertEquals("새 이름", row2.text("shop_name"))
        assertEquals("", row2.text("intro"), "소개 0자 허용")
        assertNull(row2["vacation_from"])
        assertNull(row2["vacation_to"])
        assertEquals(t1, row2.instant("created_at"), "UPDATE는 created_at을 바꾸지 않는다")
        assertEquals(t2, row2.instant("updated_at"))
        assertEquals(1L, Db3.count("seller_profiles", "user_id = ?", user))
        assertProfile(SC.profile(user).assertStatus(200), user, onVacation = false)
    }

    @Test
    fun `§12_1·§2_1 상점명 1~40자·소개 0~500자 경계 - 코드 포인트로 세고 앞뒤 공백은 빼고 중간 공백은 센다`() {
        val user = Db.createUser()
        SC.putProfile(user, shopName = SC.cps(40)).assertStatus(200)
        assertEquals(SC.cps(40), profileRow(user).text("shop_name"))
        SC.putProfile(user, shopName = SC.cps(41)).assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "  " + "a".repeat(40) + "  ").assertStatus(200)
        SC.putProfile(user, shopName = "a" + " ".repeat(38) + "b").assertStatus(200)
        SC.putProfile(user, shopName = "a" + " ".repeat(39) + "b").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "x").assertStatus(200)
        SC.putProfile(user, shopName = "   ").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = null).assertError(400, "INVALID_REQUEST")

        // intro 경계 — PUT은 본문 전체로 UPDATE하므로(§12.1) shopName을 마지막 성공 값 "x"로 함께 보낸다
        SC.putProfile(user, shopName = "x", intro = SC.cps(500)).assertStatus(200)
        SC.putProfile(user, shopName = "x", intro = SC.cps(501)).assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "x", intro = " " + "가".repeat(500) + " ").assertStatus(200)
        SC.putProfile(user, shopName = "x", intro = "   ").assertStatus(200)            // 앞뒤 공백을 빼면 0자 — 허용, 받은 그대로 저장
        assertEquals("   ", profileRow(user).text("intro"))
        assertEquals("x", profileRow(user).text("shop_name"), "400은 행을 바꾸지 않는다(마지막 성공 값)")

        // §19의 6: 앞뒤 NBSP(U+00A0)·전각 공백(U+3000)도 깎고 센다
        SC.putProfile(user, shopName = "\u00A0\u3000" + "a".repeat(40) + "\u3000\u00A0").assertStatus(200)
        SC.putProfile(user, shopName = "\u00A0" + "a".repeat(41) + "\u3000").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "\u3000\u00A0").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, shopName = "\u00A0\u3000" + "a".repeat(40) + "\u3000\u00A0", intro = "\u3000" + "가".repeat(500) + "\u00A0").assertStatus(200)
        assertEquals("\u00A0\u3000" + "a".repeat(40) + "\u3000\u00A0", profileRow(user).text("shop_name"), "받은 그대로 저장")
    }

    @Test
    fun `§12_1 휴가 - 둘 다 null 또는 둘 다 있고 from ≤ to(같은 날 허용), 하나만·from ＞ to·형식 오류는 400, 401`() {
        val user = Db.createUser()
        val d = Kst.date(Flow.now()).plusDays(10)
        SC.putProfile(user, from = d.toString(), to = d.toString()).assertStatus(200)
        SC.putProfile(user, from = d.toString(), to = null).assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, from = null, to = d.toString()).assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, from = d.plusDays(1).toString(), to = d.toString()).assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, from = "2026/11/01", to = "2026/11/02").assertError(400, "INVALID_REQUEST")
        SC.putProfile(user, from = d.toString(), to = "nope").assertError(400, "INVALID_REQUEST")
        assertEquals(d, profileRow(user).date("vacation_from"))
        assertEquals(d, profileRow(user).date("vacation_to"))

        SC.putProfile(null).assertError(401, "UNKNOWN_USER")
        SC.putProfile("abc").assertError(401, "UNKNOWN_USER")
        SC.putProfile(Db.missingId("users"), shopName = "").assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§19의 14 intro 필수(null·키 누락 400, 빈 문자열 허용), vacationFrom·vacationTo 키 누락 = null(200)`() {
        val user = Db.createUser()
        SC.putProfile(user, intro = null).assertError(400, "INVALID_REQUEST")
        Api.request("PUT", "/me/seller-profile", mapOf("shopName" to "상점"), user).assertError(400, "INVALID_REQUEST")
        assertEquals(0L, Db3.count("seller_profiles", "user_id = ?", user))
        val r = Api.request("PUT", "/me/seller-profile", mapOf("shopName" to "상점", "intro" to ""), user).assertStatus(200)
        assertProfile(r, user, onVacation = false)
        val row = profileRow(user)
        assertEquals("", row.text("intro"))
        assertNull(row["vacation_from"])
        assertNull(row["vacation_to"])
        val d = Kst.date(Flow.now())
        Api.request("PUT", "/me/seller-profile", mapOf("shopName" to "상점", "intro" to "", "vacationFrom" to d.toString()), user)
            .assertError(400, "INVALID_REQUEST")      // to 키 누락 = null → 하나만 있음
    }

    @Test
    fun `§19의 7 헤더 불필요 GET 프로필은 잘못된 X-User-Id(숫자 아님·없는 사용자)여도 200`() {
        val user = Db.createUser()
        SC.putProfile(user).assertStatus(200)
        Api.get("/sellers/$user/profile", "abc").assertStatus(200)
        Api.get("/sellers/$user/profile", Db.missingId("users")).assertStatus(200)
    }

    @Test
    fun `§12_1 GET onVacation = 오늘(KST)이 from~to 안(양끝 포함), 휴가 없음은 false`() {
        val today = Kst.date(Flow.now())
        fun onVacation(from: LocalDate?, to: LocalDate?): Boolean {
            val u = Db.createUser()
            SC.putProfile(u, from = from?.toString(), to = to?.toString()).assertStatus(200)
            val r = SC.profile(u).assertStatus(200)
            return r.json.get("onVacation").asBoolean().also { assertProfile(r, u, it) }
        }
        assertEquals(true, onVacation(today, today))
        assertEquals(true, onVacation(today.minusDays(2), today))
        assertEquals(true, onVacation(today, today.plusDays(2)))
        assertEquals(false, onVacation(today.plusDays(1), today.plusDays(5)))
        assertEquals(false, onVacation(today.minusDays(5), today.minusDays(1)))
        assertEquals(false, onVacation(null, null))
    }

    @Test
    fun `§12_1 GET - 없는 사용자 404, 프로필 없는 사용자 404 NOT_FOUND(헤더 불필요)`() {
        SC.profile(Db.missingId("users")).assertError(404, "NOT_FOUND")
        SC.profile(Db.createUser()).assertError(404, "NOT_FOUND")
    }

    @Test
    fun `§12_1·§2_3 같은 사용자의 첫 PUT 2건 동시 - 둘 다 200, 프로필 행은 1개`() {
        val user = Db.createUser()
        // 먼저 잠근 요청이 INSERT 직전 0.5초 멈춘다 → 다른 요청은 사용자 행 잠금을 기다린 뒤 UPDATE로 간다(경합 보장)
        val rs = SlowTrigger.on("seller_profiles", "INSERT", condition = "NEW.user_id = $user") {
            SC.concurrently(2) { i -> SC.putProfile(user, shopName = "동시 $i") }
        }
        rs.forEach { it.assertStatus(200) }
        assertEquals(1L, Db3.count("seller_profiles", "user_id = ?", user))
        assertTrue(profileRow(user).text("shop_name") in setOf("동시 0", "동시 1"))
    }
}
