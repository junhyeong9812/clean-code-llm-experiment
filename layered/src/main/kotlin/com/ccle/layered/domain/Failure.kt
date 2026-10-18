package com.ccle.layered.domain

// 계약에 정해진 실패(ADR-0003 §2의 code). 어떤 실패를 낼지는 도메인·서비스가 정하고, HTTP 상태로 바꾸는 것은 api가 한다.

enum class Failure {
    UNKNOWN_USER,
    CLOCK_BACKWARD,
}

class MarketException(val failure: Failure, message: String) : RuntimeException(message)
