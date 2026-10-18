package com.ccle.layered.domain

import java.math.BigInteger

// 금액 계산(ADR-0002 §5.1·§9.2). 금액은 모두 0 이상의 원 단위 정수다.
// 곱셈의 중간값은 mulDiv로 넘치지 않게 계산하고, 합계는 Math.addExact로 넘치면 오류가 드러나게 한다.

/** floor(a × b ÷ c). 중간 곱은 BigInteger로 계산하고, 결과가 Long을 넘으면 ArithmeticException. */
fun mulDiv(a: Long, b: Long, c: Long): Long =
    BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).longValueExact()
