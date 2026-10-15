package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg

/** 실행 설정. 앱은 PG_URL=http://127.0.0.1:PG_PORT 로 떠 있다고 가정한다. */
object Env {
    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    val baseUrl: String = env("BASE_URL", "http://127.0.0.1:8081").trimEnd('/')
    val dbUrl: String = env("DB_URL", "jdbc:postgresql://127.0.0.1:15434/ccle_seed")
    val dbUser: String = env("DB_USER", "ccle")
    val dbPassword: String = env("DB_PASSWORD", "ccle")
    val pgPort: Int = env("PG_PORT", FakePg.DEFAULT_PORT.toString()).toInt()
    /** TIMEOUT 계열 지연. 앱의 PG_TIMEOUT_MS(기본 2000)보다 커야 한다. */
    val pgDelayMs: Long = env("PG_DELAY_MS", FakePg.DEFAULT_DELAY_MS.toString()).toLong()
    /** 앱 HTTP 요청 타임아웃(PG 타임아웃 경로가 2초 이상 걸리므로 넉넉히). */
    val httpTimeoutMs: Long = env("HTTP_TIMEOUT_MS", "15000").toLong()
}
