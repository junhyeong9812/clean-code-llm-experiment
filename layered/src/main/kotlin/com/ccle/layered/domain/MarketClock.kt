package com.ccle.layered.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 멈춘 시계(ADR-0003 §7). 앱의 모든 시각은 여기서 읽는다. 관리 엔드포인트로만 앞으로 옮긴다.
 * 요청 하나는 처음에 읽은 시각 하나로 처리한다.
 */
interface MarketClock {
    fun now(): Instant

    /** 같은 시각·뒤 시각만 허용한다. 앞 시각이면 CLOCK_BACKWARD. */
    fun moveTo(requested: Instant)

    companion object {
        /** 시계 값은 마이크로초로 자른다: DB(timestamptz)가 마이크로초까지라 저장한 시각과 시계 값이 어긋나지 않게. */
        fun normalize(t: Instant): Instant = t.truncatedTo(ChronoUnit.MICROS)

        /** 시계 이동 판정: 자른 요청 시각이 현재보다 앞이면 CLOCK_BACKWARD, 아니면 그것이 새 현재 시각. */
        fun next(current: Instant, requested: Instant): Instant {
            val target = normalize(requested)
            if (target.isBefore(current)) {
                throw MarketException(Failure.CLOCK_BACKWARD, "시계를 뒤로 옮길 수 없다: 현재 $current, 요청 $target")
            }
            return target
        }
    }
}
