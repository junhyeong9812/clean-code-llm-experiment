package com.ccle.behavior.support

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith

/** 단계 A 계약 테스트(ADR-0007, 패키지 contract3): 태그 contract3 + 가짜 PG. Gradle contract3Test가 돌린다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("contract3")
@ExtendWith(FakePgExtension::class)
annotation class Contract3Test
