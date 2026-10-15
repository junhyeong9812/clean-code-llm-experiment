package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * 스위트 전체에서 가짜 PG를 1번만 띄운다(127.0.0.1:PG_PORT). 루트 저장소에 두어 스위트가 끝날 때 닫힌다.
 */
class FakePgExtension : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        context.root.getStore(NS).getOrComputeIfAbsent(KEY, { Holder(FakePg(Env.pgPort, Env.pgDelayMs).start()) }, Holder::class.java)
    }

    private class Holder(val pg: FakePg) : ExtensionContext.Store.CloseableResource {
        init { instance = pg }
        override fun close() { pg.close(); instance = null }
    }

    companion object {
        private val NS = ExtensionContext.Namespace.create(FakePgExtension::class.java)
        private const val KEY = "fake-pg"
        @Volatile private var instance: FakePg? = null
        val pg: FakePg get() = checkNotNull(instance) { "가짜 PG가 아직 뜨지 않았다(@ContractTest 확장 필요)" }
    }
}

/** 계약 테스트: 태그 contract + 가짜 PG. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("contract")
@ExtendWith(FakePgExtension::class)
annotation class ContractTest

/** 인프라 자체 테스트: 태그 infra. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("infra")
annotation class InfraTest
