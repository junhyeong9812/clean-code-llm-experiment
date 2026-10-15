package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg

/** 실행 설정. 앱은 PG_URL=http://127.0.0.1:PG_PORT 로 떠 있다고 가정한다. */
object Env {
    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    val baseUrl: String = env("BASE_URL", "http://127.0.0.1:8081").trimEnd('/')
    val dbUrl: String = env("DB_URL", "jdbc:postgresql://127.0.0.1:15434/ccle_seed")
    val dbUser: String = env("DB_USER", "ccle")
    val dbPassword: String = env("DB_PASSWORD", "ccle")
    /** PG_EXTERNAL=1이면 가짜 PG를 띄우지 않고 이미 떠 있는 127.0.0.1:PG_PORT에 붙는다(run.sh가 앱보다 먼저 띄운다). */
    val pgExternal: Boolean = env("PG_EXTERNAL", "0") == "1"
    val pgPort: Int = env("PG_PORT", FakePg.DEFAULT_PORT.toString()).toInt()
    /** TIMEOUT 계열 지연. 앱의 PG_TIMEOUT_MS(기본 2000)보다 커야 한다. */
    val pgDelayMs: Long = env("PG_DELAY_MS", FakePg.DEFAULT_DELAY_MS.toString()).toLong()
    /** 앱 HTTP 요청 타임아웃(PG 타임아웃 경로가 2초 이상 걸리므로 넉넉히). */
    val httpTimeoutMs: Long = env("HTTP_TIMEOUT_MS", "15000").toLong()
    /** 배치 호출(POST /admin/batches/...) 전용 타임아웃. 시계를 크게 옮긴 뒤 첫 배치는 시드 전체를 처리할 수 있다. */
    val batchTimeoutMs: Long = env("BATCH_TIMEOUT_MS", "120000").toLong()
    /** 가짜 PG 주소. PG_URL이 있으면 그대로, 없으면 http://127.0.0.1:PG_PORT. JVM 안에서 띄우는 PG는 PG_PORT로 뜬다. */
    val pgUrl: String = System.getenv("PG_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: "http://127.0.0.1:$pgPort"
}
