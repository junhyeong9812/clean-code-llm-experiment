package com.ccle.behavior.support

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 경합을 **관측해서** 보장하는 임시 DB 트리거(FailTrigger와 같은 방식 — 끝나면 지운다, 스키마 파일 무변경).
 *
 * 테스트 연결이 advisory 잠금 K를 쥔 채, `BEFORE <event> ON <table> FOR EACH ROW WHEN (<condition>)` 트리거가
 * 그 잠금을 기다리게 한다. 그래서 먼저 쓰기에 닿은 요청은 자기 행 잠금(ADR-0007 §2.3)을 쥔 채 트리거에서 멈춘다.
 * block(동시 요청)은 별도 스레드에서 돌리고, 이 함수는 **이 트리거 때문에 함께 멈춘 요청 세션이 2개 이상**인 순간을
 * 본 뒤에 K를 푼다. 셈은 K 자체를 기다리는 세션(advisory 대기) + 그 세션들에게 막힌 세션(pg_blocking_pids — 행 잠금
 * 경로의 두 번째 요청)이다. 다른 세션의 무관한 잠금 대기는 세지 않는다. 그 순간을 못 보면 실패한다 — 요청 시작만
 * 맞추는 래치·고정 지연으로는 두 요청이 순서대로 처리돼도 통과하는 문제를 막는다.
 *
 * 관측하는 것은 "동시에 쓰기 경로에 들어왔다"까지다. 잠금이 맞는지(1건만 성공·나머지 409)는 호출한 테스트의 단언이 본다
 * — 부모 잠금 없는 `INSERT … ON CONFLICT` 경로(슬롯 생성)는 두 요청이 모두 K를 기다리는 것이 정상이다.
 */
object SlowTrigger {
    /** 대기 관측 상한. HTTP 타임아웃보다 짧아야 멈춘 요청이 끊기지 않는다(아래 require). */
    private const val OBSERVE_MS = 8_000L

    /** K를 기다리는 세션 + 그 세션에게 막힌 세션 수. K < 2^31이라 pg_locks에서 classid 0·objid K·objsubid 1이다. */
    private fun contended(key: Long): Long = Db.long(
        "WITH a AS (SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid = 0 AND objid::bigint = ? " +
            "AND objsubid = 1 AND NOT granted) " +
            "SELECT (SELECT count(*) FROM a) + (SELECT count(*) FROM pg_stat_activity s " +
            "WHERE s.pid NOT IN (SELECT pid FROM a) AND pg_blocking_pids(s.pid) && ARRAY(SELECT pid FROM a))",
        key,
    )

    fun <T> on(table: String, event: String, condition: String = "TRUE", block: () -> T): T {
        require(table.matches(Regex("[a-z_]+")))
        require(event == "UPDATE" || event == "INSERT")
        require(Env.httpTimeoutMs > OBSERVE_MS + 2_000) { "HTTP_TIMEOUT_MS(${Env.httpTimeoutMs})는 관측 상한 ${OBSERVE_MS}ms보다 2초 이상 길어야 한다" }
        val name = "bt_slow_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val key = Random.nextLong(1L shl 20, 1L shl 31)
        Db.long("SELECT count(*) FROM (SELECT pg_advisory_lock(?)) x", key)
        var released = false
        fun release() {
            if (!released) {
                released = true
                Db.long("SELECT count(*) FROM (SELECT pg_advisory_unlock(?)) x", key)
            }
        }
        val pool = runCatching { Executors.newSingleThreadExecutor() }.getOrElse { release(); throw it }
        var failure: Throwable? = null
        try {
            Db.update(
                "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "PERFORM pg_advisory_xact_lock_shared($key); RETURN NEW; END $$",
            )
            Db.update("CREATE TRIGGER $name BEFORE $event ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
            val future = pool.submit<T> { block() }
            val deadline = System.currentTimeMillis() + OBSERVE_MS
            var maxSeen = 0L
            while (System.currentTimeMillis() < deadline && !future.isDone) {
                maxSeen = maxOf(maxSeen, contended(key))
                if (maxSeen >= 2) break
                Thread.sleep(20)
            }
            release()
            val result = future.get(60, TimeUnit.SECONDS)
            assertTrue(
                maxSeen >= 2,
                "경합 미관측: 이 트리거에서 함께 멈춘 요청이 2개 미만(최대 $maxSeen) — 요청이 순서대로 처리됐거나 쓰기 전에 끝났다",
            )
            return result
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // 정리는 단계마다 따로 시도한다(한 단계 실패가 나머지를 건너뛰지 않게). 원래 오류가 있으면 정리 오류는 거기에 붙인다.
            val errors = listOf(
                runCatching { release() },
                runCatching { pool.shutdownNow(); check(pool.awaitTermination(20, TimeUnit.SECONDS)) { "요청 스레드가 끝나지 않음" } },
                runCatching { Db.update("DROP TRIGGER IF EXISTS $name ON $table") },
                runCatching { Db.update("DROP FUNCTION IF EXISTS $name()") },
                runCatching { assertEquals(0L, Db.long("SELECT count(*) FROM pg_trigger WHERE tgname = ?", name), "트리거 제거") },
                runCatching { assertEquals(0L, Db.long("SELECT count(*) FROM pg_proc WHERE proname = ?", name), "함수 제거") },
            ).mapNotNull { it.exceptionOrNull() }
            val original = failure
            if (errors.isNotEmpty()) {
                if (original != null) errors.forEach { original.addSuppressed(it) }
                else throw errors.first().also { first -> errors.drop(1).forEach { first.addSuppressed(it) } }
            }
        }
    }
}
