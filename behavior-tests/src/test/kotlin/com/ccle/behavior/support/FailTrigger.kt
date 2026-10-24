package com.ccle.behavior.support

import java.util.UUID
import kotlin.test.assertEquals

/**
 * 실패 주입용 임시 DB 트리거(명세 batch-isolation §0: 특정 행에서만 예외를 내고 끝나면 지운다, 스키마 파일 무변경).
 * `BEFORE <event> ON <table> FOR EACH ROW WHEN (<condition>)`에서 RAISE EXCEPTION. 이름은 매번 새로 만들고,
 * block이 끝나면(예외여도) 트리거·함수를 지운 뒤 pg_trigger에 남지 않았는지 확인한다.
 */
object FailTrigger {
    /** 트리거가 내는 예외 문구의 접두. 응답 message에 이 문구가 섞이면 안 된다(ADR-0003 §12의 1). */
    const val RAISE_MARK = "bt-injected-failure"

    /**
     * @param event `UPDATE` 또는 `INSERT`
     * @param condition 행 조건(UPDATE면 OLD.…, INSERT면 NEW.…). 그 행에서만 실패하게 한다.
     * @return block 결과. block에는 트리거 이름(= RAISE 문구에 들어가는 값)을 넘긴다.
     */
    fun <T> on(table: String, event: String, condition: String, block: (name: String) -> T): T {
        require(table.matches(Regex("[a-z_]+")))
        require(event == "UPDATE" || event == "INSERT")
        val name = "bt_fail_" + UUID.randomUUID().toString().replace("-", "").take(12)
        Db.update(
            "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "RAISE EXCEPTION '$RAISE_MARK $name on $table'; END $$",
        )
        val result = try {
            Db.update("CREATE TRIGGER $name BEFORE $event ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
            block(name)
        } finally {
            Db.update("DROP TRIGGER IF EXISTS $name ON $table")
            Db.update("DROP FUNCTION IF EXISTS $name()")
        }
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_trigger WHERE tgname = ?", name), "트리거 제거")
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_proc WHERE proname = ?", name), "함수 제거")
        return result
    }
}

/** 배치 응답의 `failed`(ADR-0003 §7·§12의 2): 정수 ≥ 0. */
fun Resp.failed(): Long {
    val n = long("failed")
    kotlin.test.assertTrue(n >= 0, "failed ≥ 0: $this")
    return n
}

/** 계약 밖 서버 오류 응답(ADR-0003 §12의 1): 400 UNEXPECTED_ERROR, message = 고정 문구, 예외·트리거 문구 미포함. */
const val UNEXPECTED_MESSAGE = "예상하지 못한 서버 오류"

fun Resp.assertUnexpectedError(triggerName: String? = null): Resp = apply {
    assertError(400, "UNEXPECTED_ERROR")
    assertEquals(UNEXPECTED_MESSAGE, text("message"), "message = 고정 문구: $this")
    kotlin.test.assertFalse(body.contains(FailTrigger.RAISE_MARK), "본문에 예외 문구가 없어야 한다: $this")
    if (triggerName != null) kotlin.test.assertFalse(body.contains(triggerName), "본문에 트리거 이름이 없어야 한다: $this")
}
