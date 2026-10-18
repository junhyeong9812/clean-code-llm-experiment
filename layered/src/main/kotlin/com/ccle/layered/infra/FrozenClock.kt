package com.ccle.layered.infra

import com.ccle.layered.domain.Failure
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.MarketException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// 시계 구현(ADR-0003 §7). 시작 값: CLOCK_START 환경변수, 없으면 시드의 현재 시각.
// 값은 마이크로초로 자른다: DB(timestamptz)가 마이크로초까지라 저장한 시각과 시계 값이 어긋나지 않게.
@Component
class FrozenClock(@Value("\${CLOCK_START:2026-10-01T00:00:00+09:00}") start: String) : MarketClock {
    private var current: Instant = OffsetDateTime.parse(start).toInstant().truncatedTo(ChronoUnit.MICROS)

    @Synchronized
    override fun now(): Instant = current

    @Synchronized
    override fun moveTo(requested: Instant) {
        val target = requested.truncatedTo(ChronoUnit.MICROS)
        if (target.isBefore(current)) {
            throw MarketException(Failure.CLOCK_BACKWARD, "시계를 뒤로 옮길 수 없다: 현재 $current, 요청 $target")
        }
        current = target
    }
}
