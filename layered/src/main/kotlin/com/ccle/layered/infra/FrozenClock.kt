package com.ccle.layered.infra

import com.ccle.layered.domain.MarketClock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

// 시계 구현(ADR-0003 §7): 현재 값 보관과 동기화만 한다(판정·절삭은 MarketClock.next·normalize).
// 시작 값: CLOCK_START 환경변수, 없으면 시드의 현재 시각. 확인과 변경은 같은 잠금 안에서 한다.
@Component
class FrozenClock(@Value("\${CLOCK_START:2026-10-01T00:00:00+09:00}") start: String) : MarketClock {
    private var current: Instant = MarketClock.normalize(OffsetDateTime.parse(start).toInstant())

    @Synchronized
    override fun now(): Instant = current

    @Synchronized
    override fun moveTo(requested: Instant) {
        current = MarketClock.next(current, requested)
    }
}
