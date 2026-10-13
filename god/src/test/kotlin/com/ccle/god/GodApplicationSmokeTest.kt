package com.ccle.god

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import kotlin.test.assertEquals

// 도커 PostgreSQL(docker/compose.yml)에 실제로 접속해 앱이 뜨고 DB 헬스가 UP인지 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GodApplicationSmokeTest(@Autowired private val rest: TestRestTemplate) {

    @Test
    fun healthIsUpWithDatabase() {
        val body = rest.getForObject("/actuator/health", Map::class.java)
        assertEquals("UP", body["status"])
    }
}
