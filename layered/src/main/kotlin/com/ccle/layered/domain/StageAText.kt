package com.ccle.layered.domain

/** 글자 수(ADR-0007 §2.1·§19의 6): 앞뒤 공백(isWhitespace)을 뺀 값의 유니코드 코드 포인트 수. 중간 공백은 센다. */
internal fun textLength(value: String): Int {
    val trimmed = value.trim()
    return trimmed.codePointCount(0, trimmed.length)
}
