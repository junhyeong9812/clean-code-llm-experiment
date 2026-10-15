package com.ccle.behavior.infra

import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Env
import com.ccle.behavior.support.InfraTest
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertTrue

/**
 * tools/seedgen/verify.sql을 JDBC로 1회 실행한다(psql 메타 명령 줄은 뺀다). 위반이 있으면 마지막 DO 블록이 예외를 던진다.
 * DB 도우미가 만든 행(사용자·상품·포인트 묶음과 그 출처 거래)이 시드와 함께 불변식을 통과하는지 본다.
 * 앱이 행을 쓴 뒤에 돌리면 앱이 만든 행도 함께 판정한다(ADR-0003 §11).
 */
@InfraTest
class VerifySqlTest {
    @Test
    fun `verify_sql 통과 - 도우미가 만든 행 포함`() {
        // 이 실행에서도 도우미 행을 하나 만들어 둔다(빈 판정 방지)
        val user = Db.createUser()
        Db.createPointBundle(user, 1230, Db.SEED_NOW.toInstant().minus(Duration.ofHours(12)))

        val file = listOf(File("../tools/seedgen/verify.sql"), File("tools/seedgen/verify.sql")).first { it.exists() }
        val sql = file.readLines().filterNot { it.trimStart().startsWith("\\") }.joinToString("\n")
        DriverManager.getConnection(Env.dbUrl, Env.dbUser, Env.dbPassword).use { c ->
            var lastWarnings: java.sql.SQLWarning? = null
            c.createStatement().use { st ->
                st.execute(sql)
                // 결과 집합을 끝까지 소비한다(DO 블록 예외는 execute에서 이미 던져진다)
                var more = true
                while (more) { more = st.moreResults || st.updateCount != -1 }
                lastWarnings = st.warnings
            }
            val warnings = generateSequence(lastWarnings) { it.nextWarning }.map { it.message }.toList()
            assertTrue(warnings.any { it?.contains("모두 통과") == true }, "verify 통과 NOTICE가 없다: $warnings")
        }
    }
}
