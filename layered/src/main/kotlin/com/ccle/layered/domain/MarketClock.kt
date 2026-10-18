package com.ccle.layered.domain

import java.time.Instant

/**
 * 멈춘 시계(ADR-0003 §7). 앱의 모든 시각은 여기서 읽는다. 관리 엔드포인트로만 앞으로 옮긴다.
 * 요청 하나는 처음에 읽은 시각 하나로 처리한다.
 */
interface MarketClock {
    fun now(): Instant

    /** 같은 시각·뒤 시각만 허용하고 옮긴 뒤의 시각을 돌려준다. 앞 시각이면 CLOCK_BACKWARD. */
    fun moveTo(requested: Instant): Instant
}
