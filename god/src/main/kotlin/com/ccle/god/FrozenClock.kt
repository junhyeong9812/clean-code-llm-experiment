package com.ccle.god

import com.ccle.god.web.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

// 멈춘 시계(ADR-0003 §7). 앱의 모든 시각은 여기서 읽는다. 관리 엔드포인트로만 앞으로 옮긴다.
// 시작 값: CLOCK_START 환경변수, 없으면 시드의 현재 시각.
@Component
class FrozenClock(@Value("\${CLOCK_START:2026-10-01T00:00:00+09:00}") start: String) {
    private var current: Instant = OffsetDateTime.parse(start).toInstant()

    @Synchronized
    fun now(): Instant = current

    // 같은 시각은 허용, 앞 시각이면 409
    @Synchronized
    fun moveTo(target: Instant) {
        if (target.isBefore(current)) {
            throw ApiException(HttpStatus.CONFLICT, "CLOCK_BACKWARD", "시계를 뒤로 옮길 수 없다: 현재 $current, 요청 $target")
        }
        current = target
    }
}
