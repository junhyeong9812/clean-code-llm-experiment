package com.ccle.behavior.support

import java.util.UUID
import kotlin.test.assertEquals

/**
 * 경합 보장용 임시 DB 트리거(FailTrigger와 같은 방식 — 끝나면 지운다, 스키마 파일 무변경).
 * `BEFORE <event> ON <table> FOR EACH ROW WHEN (<condition>)`에서 pg_sleep(seconds)만 하고 행은 그대로 쓴다.
 * 먼저 잠금을 잡은 요청이 쓰기 직전에 잠금을 쥔 채 멈추므로, 동시에 보낸 다른 요청은 그동안 같은 잠금을 기다리게 된다
 * — 요청 시작만 맞추는 래치로는 두 요청이 순서대로 처리돼도 통과하는 문제를 막는다(ADR-0007 §2.3 경합 검증).
 */
object SlowTrigger {
    fun <T> on(table: String, event: String, seconds: Double = 0.5, condition: String = "TRUE", block: () -> T): T {
        require(table.matches(Regex("[a-z_]+")))
        require(event == "UPDATE" || event == "INSERT")
        require(seconds > 0 && seconds <= 5)
        val name = "bt_slow_" + UUID.randomUUID().toString().replace("-", "").take(12)
        Db.update(
            "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "PERFORM pg_sleep($seconds); RETURN NEW; END $$",
        )
        val result = try {
            Db.update("CREATE TRIGGER $name BEFORE $event ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
            block()
        } finally {
            Db.update("DROP TRIGGER IF EXISTS $name ON $table")
            Db.update("DROP FUNCTION IF EXISTS $name()")
        }
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_trigger WHERE tgname = ?", name), "트리거 제거")
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_proc WHERE proname = ?", name), "함수 제거")
        return result
    }
}
