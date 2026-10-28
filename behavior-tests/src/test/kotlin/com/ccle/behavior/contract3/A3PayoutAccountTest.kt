package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract3.SA.textOrNullAt
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §6.1 지급 계좌 변경 요청 · §6.2 관리자 판정(+ §2.7 감사 PAYOUT_ACCOUNT_DECISION) · §6.3 GET /me/payout-account.
 * 은행 코드 6개(004·011·020·081·088·090), 계좌 숫자 10~14자리, 저장은 "****" + 끝 4자리.
 */
@Contract3Test
class A3PayoutAccountTest {

    private val banks = listOf("004", "011", "020", "081", "088", "090")

    private fun pending(user: Long, bank: String = "004", acct: String = "1234567890"): Long =
        SA.requestPayout(user, bank, acct).assertStatus(201).long("requestId")

    // ---- §6.1 ----

    @Test
    fun `§6_1 요청 - 201 요청 모양(PENDING), requested_at = 현재, 계좌는 마스킹만 저장(전체 번호 없음)`() {
        val user = Db.createUser()
        val now = Flow.advance(HOUR)
        val r = SA.requestPayout(user, "088", "1234567890").assertStatus(201)
        val row = SA.payoutRequests(user).single()
        SA.assertPayoutRequestBody(r.json, row)
        assertEquals("088", row.text("bank_code"))
        assertEquals("****7890", row.text("account_no_masked"))
        assertEquals("****7890", r.text("accountNoMasked"))
        assertEquals("PENDING", row.text("status"))
        assertNull(row.textOrNull("reject_reason"))
        assertEquals(now, row.instant("requested_at"))
        assertNull(row.instantOrNull("decided_at"))
        assertTrue(r.isNull("rejectReason") && r.isNull("decidedAt"), "null 필드: $r")
        assertTrue(!r.body.contains("1234567890"), "응답에 전체 번호가 없다: $r")
        val allValues = Db.query("SELECT row_to_json(r)::text FROM payout_account_requests r WHERE id = ?", row.id) { it.getString(1) }.single()
        assertTrue(!allValues.contains("1234567890"), "전체 번호는 저장하지 않는다: $allValues")
    }

    @Test
    fun `§6_1 경계 - 은행 코드 6개 모두·계좌 10자리·14자리는 201`() {
        for ((i, bank) in banks.withIndex()) {
            val user = Db.createUser()
            val acct = if (i % 2 == 0) "0000000001" else "12345678901234"
            SA.requestPayout(user, bank, acct).assertStatus(201)
            val row = SA.payoutRequests(user).single()
            assertEquals(bank, row.text("bank_code"))
            assertEquals("****" + acct.takeLast(4), row.text("account_no_masked"))
        }
    }

    @Test
    fun `§6_1·§19의 2 400 INVALID_REQUEST - 알 수 없는 은행·계좌 9자리·15자리·숫자 아닌 문자·JSON 숫자·앞뒤 공백·누락·null, 행 없음`() {
        val user = Db.createUser()
        val bodies = listOf(
            """{"bankCode":"999","accountNo":"1234567890"}""",
            """{"bankCode":"","accountNo":"1234567890"}""",
            """{"accountNo":"1234567890"}""",
            """{"bankCode":null,"accountNo":"1234567890"}""",
            """{"bankCode":"004","accountNo":"123456789"}""",
            """{"bankCode":"004","accountNo":"123456789012345"}""",
            """{"bankCode":"004","accountNo":"12345abcde"}""",
            """{"bankCode":"004","accountNo":"12345-67890"}""",
            """{"bankCode":"004","accountNo":1234567890}""",
            """{"bankCode":"004","accountNo":" 1234567890"}""",
            """{"bankCode":"004","accountNo":"1234567890 "}""",
            """{"bankCode":4,"accountNo":"1234567890"}""",
            """{"bankCode":"004","accountNo":""}""",
            """{"bankCode":"004"}""",
            """{"bankCode":"004","accountNo":null}""",
            """not-json""",
        )
        for (b in bodies) SA.requestPayoutRaw(user, b).assertError(400, "INVALID_REQUEST")
        assertTrue(SA.payoutRequests(user).isEmpty())
    }

    @Test
    fun `§6_1 401 - 헤더 없음·숫자 아님·없는 사용자, 401이 400보다 먼저`() {
        SA.requestPayout(null, "004", "1234567890").assertError(401, "UNKNOWN_USER")
        SA.requestPayout("abc", "004", "1234567890").assertError(401, "UNKNOWN_USER")
        SA.requestPayout(Db.missingId("users"), "004", "1234567890").assertError(401, "UNKNOWN_USER")
        SA.requestPayout(null, "999", "1").assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§6_1 409 - PENDING이 있으면 ITEM_STATE_CONFLICT, 판정(승인·반려) 뒤에는 다시 요청할 수 있다, 400이 409보다 먼저`() {
        val user = Db.createUser()
        val first = pending(user)
        SA.requestPayout(user, "011", "9999999999").assertError(409, "ITEM_STATE_CONFLICT")
        SA.requestPayout(user, "999", "9999999999").assertError(400, "INVALID_REQUEST")
        assertEquals(1, SA.payoutRequests(user).size)

        SA.decide(first, true, null).assertStatus(200)
        val second = pending(user, "011", "9999999999")
        SA.decide(second, false, "불일치").assertStatus(200)
        pending(user, "020", "8888888888")
        assertEquals(listOf("APPROVED", "REJECTED", "PENDING"), SA.payoutRequests(user).map { it.text("status") })
    }

    @Test
    fun `§6_1·§2_3 동시 요청 - 같은 사용자 6건이면 201 1건·나머지 409, PENDING 1행`() {
        val user = Db.createUser()
        val rs = SlowTrigger.on("payout_account_requests", "INSERT") { SA.concurrently(6) { i -> SA.requestPayout(user, banks[i], "123456789$i") } }
        val win = SA.assertOneWins(rs, 201)
        val row = SA.payoutRequests(user).single()
        assertEquals(win.long("requestId"), row.id)
        assertEquals("PENDING", row.text("status"))
    }

    // ---- §6.2 ----

    @Test
    fun `§6_2·§2_7 승인 - APPROVED·decided_at = 현재·reason 무시(NULL), 200 요청 모양, 감사 1행 detail APPROVED`() {
        val user = Db.createUser()
        val id = pending(user)
        val now = Flow.advance(HOUR)
        val r = SA.decide(id, true, "무시되는 사유").assertStatus(200)
        val row = Db3.row("payout_account_requests", id)
        SA.assertPayoutRequestBody(r.json, row)
        assertEquals("APPROVED", row.text("status"))
        assertNull(row.textOrNull("reject_reason"), "승인 시 reason은 무시")
        assertEquals(now, row.instant("decided_at"))
        SA.assertOneAudit("PAYOUT_ACCOUNT_DECISION", id, "APPROVED", now)
    }

    @Test
    fun `§6_2·§2_7 반려 - REJECTED·reject_reason = 받은 그대로·decided_at = 현재, 감사 detail REJECTED`() {
        val user = Db.createUser()
        val id = pending(user)
        val now = Flow.advance(HOUR)
        val r = SA.decide(id, false, "  서류 불일치 ").assertStatus(200)
        val row = Db3.row("payout_account_requests", id)
        SA.assertPayoutRequestBody(r.json, row)
        assertEquals("REJECTED", row.text("status"))
        assertEquals("  서류 불일치 ", row.text("reject_reason"))
        assertEquals("  서류 불일치 ", r.json.textOrNullAt("rejectReason"))
        assertEquals(now, row.instant("decided_at"))
        SA.assertOneAudit("PAYOUT_ACCOUNT_DECISION", id, "REJECTED", now)
    }

    @Test
    fun `§6_2·§19의 9 오류 - 반려 reason null·빈 값·키 누락 400, 승인이어도 reason 5면 400, approve 누락·형식 오류 400, 없는 요청 404, PENDING 아님 409, 실패는 감사 없음`() {
        val user = Db.createUser()
        val id = pending(user)
        SA.decide(id, false, null).assertError(400, "INVALID_REQUEST")
        SA.decide(id, false, "   ").assertError(400, "INVALID_REQUEST")
        SA.decide(id, false, "").assertError(400, "INVALID_REQUEST")
        SA.decideRaw(id, """{"approve":false}""").assertError(400, "INVALID_REQUEST")    // 키 누락 = null
        // §19의 9: 형식 검사는 approve와 무관하게 먼저 — 승인이어도 reason이 문자열·null이 아니면 400
        SA.decideRaw(id, """{"approve":true,"reason":5}""").assertError(400, "INVALID_REQUEST")
        SA.decideRaw(id, """{"reason":"x"}""").assertError(400, "INVALID_REQUEST")
        SA.decideRaw(id, """{"approve":"yes","reason":"x"}""").assertError(400, "INVALID_REQUEST")
        SA.decideRaw(id, """{"approve":null,"reason":"x"}""").assertError(400, "INVALID_REQUEST")
        assertEquals("PENDING", Db3.row("payout_account_requests", id).text("status"))
        SA.assertNoAudit("PAYOUT_ACCOUNT_DECISION", id)

        val missing = Db.missingId("payout_account_requests")
        SA.decide(missing, true, null).assertError(404, "NOT_FOUND")
        SA.decide(missing, false, null).assertError(400, "INVALID_REQUEST")             // 400이 404보다
        SA.assertNoAudit("PAYOUT_ACCOUNT_DECISION", missing)

        val decidedAt = Flow.advance(HOUR)
        SA.decide(id, true, null).assertStatus(200)
        Flow.advance(HOUR)
        SA.decide(id, true, null).assertError(409, "ITEM_STATE_CONFLICT")
        SA.decide(id, false, "다시").assertError(409, "ITEM_STATE_CONFLICT")
        SA.decide(id, false, null).assertError(400, "INVALID_REQUEST")                  // 400이 409보다
        val row = Db3.row("payout_account_requests", id)
        assertEquals("APPROVED", row.text("status"))
        assertEquals(decidedAt, row.instant("decided_at"))
        SA.assertOneAudit("PAYOUT_ACCOUNT_DECISION", id, "APPROVED", decidedAt)
    }

    @Test
    fun `§6_2·§2_3 동시 판정 - 같은 요청에 5건이면 200 1건·나머지 409, 감사 1행`() {
        val user = Db.createUser()
        val id = pending(user)
        Flow.advance(HOUR)
        val win = SA.assertOneWins(SlowTrigger.on("payout_account_requests", "UPDATE") { SA.concurrently(5) { i -> SA.decide(id, i % 2 == 0, "사유 $i") } }, 200)
        val row = Db3.row("payout_account_requests", id)
        assertEquals(win.text("status"), row.text("status"))
        assertEquals(1, SA.auditCount("PAYOUT_ACCOUNT_DECISION", id))
    }

    // ---- §6.3 ----

    @Test
    fun `§6_3 조회 - 승인·기존 계좌 모두 없으면 세 필드 null, PENDING은 pending에만`() {
        val user = Db.createUser()
        val r0 = SA.payoutAccount(user).assertStatus(200)
        for (f in listOf("bankCode", "accountNoMasked", "source", "pending")) SA.assertNullField(r0.json, f)

        val id = pending(user, "081", "5555566666")
        val r1 = SA.payoutAccount(user).assertStatus(200)
        for (f in listOf("bankCode", "accountNoMasked", "source")) SA.assertNullField(r1.json, f)
        SA.assertPayoutRequestBody(r1.json.get("pending"), Db3.row("payout_account_requests", id))

        SA.decide(id, false, "x").assertStatus(200)
        val r2 = SA.payoutAccount(user).assertStatus(200)
        for (f in listOf("bankCode", "accountNoMasked", "source", "pending")) SA.assertNullField(r2.json, f)
    }

    @Test
    fun `§6_3 조회 - 기존 계좌 INITIAL → 승인 요청 REQUEST(decided_at 최신), 뒤의 반려는 무시, decided_at 같으면 id 큰 것`() {
        // 기존 계좌(시드와 같은 모양)를 가진 새 사용자 — 시드 사용자에 기대지 않는다
        val user = Db.createUser()
        val bank = "081"
        val masked = "****4321"
        Db.update("INSERT INTO seller_payout_accounts(user_id, bank_code, account_no_masked) VALUES (?, ?, ?)", user, bank, masked)
        val r0 = SA.payoutAccount(user).assertStatus(200)
        assertEquals(bank, r0.text("bankCode"))
        assertEquals(masked, r0.text("accountNoMasked"))
        assertEquals("INITIAL", r0.text("source"))
        SA.assertNullField(r0.json, "pending")

        val a = pending(user, "004", "1111111111")
        assertEquals("INITIAL", SA.payoutAccount(user).assertStatus(200).text("source"), "PENDING은 유효 계좌가 아니다")
        Flow.advance(HOUR)
        SA.decide(a, true, null).assertStatus(200)
        val r1 = SA.payoutAccount(user).assertStatus(200)
        assertEquals("004", r1.text("bankCode"))
        assertEquals("****1111", r1.text("accountNoMasked"))
        assertEquals("REQUEST", r1.text("source"))
        SA.assertNullField(r1.json, "pending")

        val rej = pending(user, "011", "2222222222")
        Flow.advance(HOUR)
        SA.decide(rej, false, "x").assertStatus(200)
        assertEquals("****1111", SA.payoutAccount(user).assertStatus(200).text("accountNoMasked"), "반려는 유효 계좌를 바꾸지 않는다")

        // 같은 시각(멈춘 시계)에 승인 두 건 → decided_at 같음 → id 큰 것
        Flow.advance(HOUR)
        val b = pending(user, "020", "3333333333")
        SA.decide(b, true, null).assertStatus(200)
        val c = pending(user, "090", "4444444444")
        SA.decide(c, true, null).assertStatus(200)
        assertEquals(Db3.row("payout_account_requests", b).instant("decided_at"), Db3.row("payout_account_requests", c).instant("decided_at"))
        assertTrue(c > b)
        val r2 = SA.payoutAccount(user).assertStatus(200)
        assertEquals("090", r2.text("bankCode"))
        assertEquals("****4444", r2.text("accountNoMasked"))
        assertEquals("REQUEST", r2.text("source"))

        val p = pending(user, "088", "5555555555")
        val r3 = SA.payoutAccount(user).assertStatus(200)
        assertEquals("****4444", r3.text("accountNoMasked"))
        SA.assertPayoutRequestBody(r3.json.get("pending"), Db3.row("payout_account_requests", p))
        assertEquals("PENDING", r3.json.get("pending").textAt("status"))
        // 승인해도 seller_payout_accounts는 그대로(§6.2 불변식 2)
        assertEquals(bank to masked, Db.query("SELECT bank_code, account_no_masked FROM seller_payout_accounts WHERE user_id = ?", user) {
            it.getString(1) to it.getString(2)
        }.single())
    }

    @Test
    fun `§6_1·§2_2 판매 제한 사용자도 요청할 수 있다 - 201(SALE_RESTRICTED를 내지 않는다)`() {
        val user = Db.createUser()                                   // ACTIVE 입찰 없음
        Db.setRestrictions(user, Flow.now().plus(java.time.Duration.ofDays(7)), Flow.now().plus(java.time.Duration.ofDays(7)))
        SA.requestPayout(user, "004", "1234567890").assertStatus(201)
        assertEquals("PENDING", SA.payoutRequests(user).single().text("status"))
    }

    @Test
    fun `§6_3 401`() {
        SA.payoutAccount(null).assertError(401, "UNKNOWN_USER")
        SA.payoutAccount("abc").assertError(401, "UNKNOWN_USER")
        SA.payoutAccount(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
