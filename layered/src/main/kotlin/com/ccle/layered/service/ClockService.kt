package com.ccle.layered.service

import com.ccle.layered.domain.MarketClock
import org.springframework.stereotype.Service
import java.time.Instant

/** 관리 엔드포인트의 시계 조회·이동(ADR-0003 §7). */
@Service
class ClockService(private val clock: MarketClock) {

    fun now(): Instant = clock.now()

    /** 같은 시각·뒤 시각만 허용한다. 앞 시각이면 시계가 CLOCK_BACKWARD. */
    fun moveTo(target: Instant): Instant = clock.moveTo(target)
}
